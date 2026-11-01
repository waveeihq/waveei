package app.waveei.android

import android.Manifest
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.window.SplashScreen
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.waveei.android.ui.AppRoot
import app.waveei.android.ui.AppTheme
import app.waveei.android.ui.EmojiPanel
import app.waveei.core.api.LogBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app: onboarding, home with the setup status, and the screens under it. */
class MainActivity : ComponentActivity() {
    lateinit var prefs: Prefs
        private set

    /** Bumped whenever the setup status may have changed, so the UI re-reads it. */
    var refresh by mutableIntStateOf(0)
        private set
    var theme by mutableStateOf(Prefs.Theme.SYSTEM)

    /** A payment page was opened over the app, so coming back asks for the plan a few more times. */
    private var paymentOpened = false

    /** About's update check: what it last found, and the newer build, if any. */
    var updateStatus by mutableStateOf<String?>(null)
        private set
    var available by mutableStateOf<Updater.Release?>(null)
        private set
    private var checkedOnOpen = false

    /** The first update's guide is up; and the download waits for the install permission, to start on coming back. */
    var updateGuide by mutableStateOf(false)
    private var pendingInstall = false

    /** A screen asked for from outside (the dot's menu, the recording notification); taken by [AppRoot]. */
    var openScreen by mutableStateOf<String?>(null)

    /**
     * The screen on top of Home came from outside (the keyboard, the dot): Back there closes the app, off Recents too,
     * for the one the person was in, as Android's Back is by time, not Home first. Cleared once they leave or go back
     * to Home.
     */
    var fromOutside by mutableStateOf(false)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    private val photosPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        theme = prefs.theme
        fadeSplash()
        openScreen = intent.getStringExtra(EXTRA_SCREEN)
        setContent {
            AppTheme(theme) { AppRoot(this) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openScreen = intent.getStringExtra(EXTRA_SCREEN)
    }

    override fun onResume() {
        super.onResume()
        refresh++
        // Back from a purchase too: the plan is asked again, and a few times more while Stripe's word may be on its way.
        refreshPlan()
        if (paymentOpened) {
            paymentOpened = false
            lifecycleScope.launch {
                for (wait in PLAN_RECHECKS_MS) {
                    delay(wait)
                    if (Account.tide && !Account.paymentFailed) break
                    refreshPlan()
                }
            }
        }
        // Back from the confirmation email: the account is read again.
        if (Account.waiting) {
            lifecycleScope.launch {
                Account.checkConfirmed()
                refresh++
                refreshPlan()
            }
        }
        resumed = true
        // Back from "Install unknown apps" with it allowed: the download goes on by itself.
        if (pendingInstall && packageManager.canRequestPackageInstalls()) {
            pendingInstall = false
            startDownload()
        }
        // An install that needed a confirmation while the app was in the background asks now.
        Updater.confirm?.let {
            Updater.confirm = null
            startActivity(it)
        }
        // By itself only now and then; the Check button in About looks at once.
        val now = System.currentTimeMillis()
        if (!checkedOnOpen && prefs.autoUpdateCheck && now - prefs.lastUpdateCheck >= UPDATE_CHECK_MS) {
            checkedOnOpen = true
            prefs.lastUpdateCheck = now
            checkForUpdate()
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onStop() {
        super.onStop()
        keepSplashTheme()
        // Come back to later from the launcher, the screen is the app's own again: Back goes to Home.
        fromOutside = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker is a dialog; the selection is known when focus returns.
        if (hasFocus) refresh++
    }

    // Launch screen (the theme's animated mark; Android shows it until the first frame, no longer)

    /**
     * Leaves into the app in two steps, never the mark over the content: first the mark grows a touch and fades on the
     * whole page colour, then the bare page fades into the app. A launch with no mark (opened by the keyboard or a dot,
     * see [splashless]) only fades the page. At once with animations off.
     */
    private fun fadeSplash() = splashScreen.setOnExitAnimationListener { view ->
        if (!ValueAnimator.areAnimatorsEnabled()) return@setOnExitAnimationListener view.remove()
        val page = view.background
        val icon = view.iconView
        val pageFade = if (page != null) {
            ValueAnimator.ofInt(255, 0).apply { addUpdateListener { page.alpha = it.animatedValue as Int } }
        } else {
            ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f)
        }.setDuration(SPLASH_PAGE_FADE_MS)
        val parts = mutableListOf<Animator>()
        if (icon != null) {
            parts += ObjectAnimator.ofPropertyValuesHolder(
                icon,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, SPLASH_EXIT_SCALE),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, SPLASH_EXIT_SCALE),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
            ).setDuration(SPLASH_ICON_EXIT_MS)
        }
        parts += pageFade
        AnimatorSet().apply {
            playSequentially(parts)
            interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = view.remove()
            })
        }.start()
    }

    /** Settings › Theme Light or Dark shows the launch screen in that theme next time; System follows the system's. */
    private fun keepSplashTheme() = splashScreen.setSplashScreenTheme(
        when (prefs.theme) {
            Prefs.Theme.LIGHT -> R.style.SplashDay
            Prefs.Theme.DARK -> R.style.SplashNight
            Prefs.Theme.SYSTEM -> Resources.ID_NULL
        },
    )

    // Setup status (the home screen shows every permission and fixes it in one tap)

    fun micGranted(): Boolean = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun keyboardEnabled(): Boolean = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.id == KeyboardService.id(this) }

    fun signedIn(): Boolean = Account.signedIn

    fun accountWaiting(): Boolean = Account.waiting

    fun accountChanged() {
        refresh++
        refreshPlan()
    }

    /** Signed in with Tide, as far as the server last told. */
    fun tide(): Boolean = Account.tide

    fun paymentFailed(): Boolean = Account.paymentFailed

    fun tideEnding(): Boolean = Account.tideEnding

    fun refreshPlan() {
        if (Account.signedIn) Dictation.refreshPlan { runOnUiThread { refresh++ } }
    }

    /** Tide's payment page, linked to this account so the purchase finds it; signed in only. */
    fun subscribeLink(): String? {
        if (!Account.signedIn) return null
        return Uri.parse(Account.tideLink ?: TIDE_LINK).buildUpon()
            .appendQueryParameter("client_reference_id", Account.uid)
            .appendQueryParameter("prefilled_email", Account.email)
            .build().toString()
    }

    fun sendConfirmation() {
        lifecycleScope.launch {
            val error = Account.sendConfirmation(this@MainActivity)
            toast(error ?: getString(R.string.confirmation_sent, Account.email))
        }
    }

    fun signOut() {
        Account.signOut()
        refresh++
    }

    fun cancelTide() {
        lifecycleScope.launch {
            when (Account.cancelTide()) {
                Account.Outcome.DONE, Account.Outcome.LATE -> toast(getString(R.string.tide_cancelled)).also { refreshPlan() }
                Account.Outcome.ELSEWHERE -> openPayment(LINK)
                Account.Outcome.FAILED -> toast(getString(R.string.account_failed))
            }
        }
    }

    fun resubscribeTide() {
        lifecycleScope.launch {
            when (Account.resubscribeTide()) {
                Account.Outcome.DONE, Account.Outcome.LATE -> toast(getString(R.string.tide_resubscribed)).also { refreshPlan() }
                Account.Outcome.ELSEWHERE -> openPayment(LINK)
                Account.Outcome.FAILED -> toast(getString(R.string.account_failed))
            }
        }
    }

    fun refundTide() {
        lifecycleScope.launch {
            when (Account.refundTide()) {
                Account.Outcome.DONE -> toast(getString(R.string.tide_refunded)).also { refreshPlan() }
                Account.Outcome.LATE -> toast(getString(R.string.refund_late))
                Account.Outcome.ELSEWHERE -> openPayment(LINK_REFUNDS)
                Account.Outcome.FAILED -> toast(getString(R.string.account_failed))
            }
        }
    }

    /** Deletes the account; with Tide still refundable or renewing, says what to do first, and [refundFirst] opens Tide's page. */
    fun deleteAccount(refundFirst: () -> Unit) {
        lifecycleScope.launch {
            when (Account.delete()) {
                Account.Outcome.DONE -> toast(getString(R.string.account_deleted)).also { refresh++ }
                Account.Outcome.LATE -> toast(getString(R.string.refund_tide_first)).also { refundFirst() }
                Account.Outcome.ELSEWHERE -> toast(getString(R.string.cancel_tide_first)).also { refundFirst() }
                Account.Outcome.FAILED -> toast(getString(R.string.account_failed))
            }
        }
    }

    /** Sends a problem report or a word, with the event log only when [withLog]; [done] says whether it went. */
    fun sendFeedback(message: String, withLog: Boolean, done: (Boolean) -> Unit) {
        lifecycleScope.launch {
            val lines = if (withLog) DebugLog.newest(LogBatch.MAX_LINES) else null
            val code = withContext(Dispatchers.IO) { runCatching { Server.log(message, lines) }.getOrDefault(0) }
            DebugLog.add("feedback: sent${if (withLog) " with the log" else ""}, HTTP $code")
            toast(getString(if (code == 204) R.string.feedback_thanks else R.string.feedback_failed))
            done(code == 204)
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    fun dotServiceOn(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == ComponentName(this, DotService::class.java) }

    enum class Floating { MIC, SWITCH, RECORDING }

    fun floating(kind: Floating): Boolean = when (kind) {
        Floating.MIC -> prefs.dotEnabled
        Floating.SWITCH -> prefs.switchIcon
        Floating.RECORDING -> prefs.recordingIcon
    }

    /** Turns a floating icon on or off; they need the Accessibility service, turned on after the disclosure. */
    /** Turned on with the Accessibility service off, a dot opens its settings, unless [openSettings] is false. */
    fun setFloating(kind: Floating, on: Boolean, openSettings: Boolean = true) {
        when (kind) {
            Floating.MIC -> prefs.dotEnabled = on
            Floating.SWITCH -> prefs.switchIcon = on
            Floating.RECORDING -> prefs.recordingIcon = on
        }
        DotService.instance?.refresh()
        if (on && openSettings && !dotServiceOn()) openAccessibilitySettings()
        refresh++
    }


    fun requestMic() {
        if (micGranted()) return
        if (prefs.micAsked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            openAppSettings()
        } else {
            prefs.micAsked = true
            micPermission.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    /** All photos: access to a few chosen ones never includes a screenshot taken later. */
    fun photosGranted(): Boolean = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    /** Only a few chosen photos allowed (Android 14), which never include a screenshot taken later. */
    fun photosPartial(): Boolean = !photosGranted() &&
        checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

    fun requestPhotos() {
        if (photosGranted()) return
        if (prefs.photosAsked && !shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES)) {
            openAppSettings()
        } else {
            prefs.photosAsked = true
            photosPermission.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        }
    }

    /** Asked before and refused for good: Android no longer shows its prompt, only the app's settings can allow it. */
    fun micBlocked(): Boolean =
        !micGranted() && prefs.micAsked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

    /**
     * Whether Android keeps Waveei's Accessibility switch restricted (Android 13+, an app from outside a store, until App
     * info allows restricted settings); null where that cannot be read. Anything but allowed counts when Waveei came
     * from a file or a browser, or (Android 15+) was put in by an app that is not part of the phone, as a messenger or
     * a file manager: newer Android leaves the setting at its default and decides by the install's source, trusting
     * only installers the phone ships with or names (AOSP EnhancedConfirmationService.isPackageEcmGuarded). Wrongly
     * "restricted" costs a guide whose first stop already finds the switch working; wrongly "not" leaves a greyed switch
     * unexplained.
     */
    fun restrictedSettings(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val read = restrictedReading()
            val fromFile = read.source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || read.source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
            val fromApp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && !read.systemInstaller
            read.mode == AppOpsManager.MODE_ERRORED || (fromFile || fromApp) && read.mode != AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }

    /** What [restrictedSettings] reads, for the event log: the setting's mode, the install's source and its installer. */
    fun restrictedReason(): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) "before Android 13"
        else runCatching {
            val read = restrictedReading()
            "mode ${read.mode}, source ${read.source}, installer ${DebugLog.name(read.installer, if (read.systemInstaller) "a system app" else "an app")}"
        }.getOrElse { "unread: ${it.javaClass.simpleName}" }

    private class RestrictedReading(val mode: Int, val source: Int, val installer: String?, val systemInstaller: Boolean)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun restrictedReading(): RestrictedReading {
        val mode = getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(RESTRICTED_SETTINGS_OP, Process.myUid(), packageName)
        val info = packageManager.getInstallSourceInfo(packageName)
        val installer = info.installingPackageName
        // The app that installed Waveei is always visible to it; one the phone ships with is trusted.
        val system = installer != null && runCatching { packageManager.getApplicationInfo(installer, PackageManager.ApplicationInfoFlags.of(0)).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false)
        return RestrictedReading(mode, info.packageSource, installer, system)
    }

    fun openAppSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

    fun openKeyboardSettings() = startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))

    fun openAccessibilitySettings() = startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openUrl(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    /** A payment page in a Custom Tab over the app, so closing it comes back here; a plain browser if no browser offers tabs. */
    fun openPayment(url: String) = runCatching {
        paymentOpened = true
        DebugLog.add("tide: payment page opened")
        CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
    }.onFailure { openUrl(url) }

    /** Other enabled keyboards, for "switch-back keyboard". */
    fun otherKeyboards(): List<Pair<String, String>> =
        getSystemService(InputMethodManager::class.java).enabledInputMethodList
            .filter { it.id != KeyboardService.id(this) }
            .map { it.id to it.loadLabel(packageManager).toString() }

    fun deleteAllData() {
        BufferStore.clear()
        ClipboardHistory.clear()
        DebugLog.clear()
        Stats.clear()
        deleteSharedPreferences(EmojiPanel.PREFS)
        prefs.clearAll()
        Sync.forget()
        theme = prefs.theme
        refresh++
        Toast.makeText(this, R.string.data_deleted, Toast.LENGTH_SHORT).show()
    }

    // Updates

    fun checkForUpdate() {
        updateStatus = getString(R.string.checking)
        Thread {
            val result = runCatching { Updater(this).latest() }
            runOnUiThread {
                result.onSuccess { release ->
                    available = release?.takeIf { it.versionCode > versionCode() }
                    updateStatus = available?.let { getString(R.string.version, it.name) } ?: getString(R.string.up_to_date)
                }.onFailure {
                    // The network's own error text ("Unable to resolve host…") said in plain words, as the install does.
                    updateStatus = when (it) {
                        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.SocketTimeoutException -> getString(R.string.update_no_network)
                        else -> it.message
                    }
                    DebugLog.add("update: check failed: ${it.javaClass.simpleName}")
                }
            }
        }.start()
    }

    /**
     * Downloads the update and installs it, on the person's tap. Progress and errors go to [Updater.install],
     * which About shows. [UpdateService] does the work, so it goes on after they leave the app.
     */
    fun installUpdate() {
        if (available == null) return checkForUpdate()
        val install = Updater.install
        if (install is Updater.Install.Downloading || install == Updater.Install.Installing) return
        // The first time, the guide shows the two checks Android makes, and goes on from there.
        if (!prefs.updateGuideSeen) {
            updateGuide = true
            return
        }
        continueUpdate()
    }

    /** The install permission, asked before the download so the install right after it is not stopped; then the download. */
    fun continueUpdate() {
        if (!packageManager.canRequestPackageInstalls()) {
            updateStatus = getString(R.string.allow_installs)
            pendingInstall = true
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        startDownload()
    }

    fun canInstall(): Boolean = packageManager.canRequestPackageInstalls()

    private fun startDownload() {
        val release = available ?: return
        val install = Updater.install
        if (install is Updater.Install.Downloading || install == Updater.Install.Installing) return
        prefs.updateGuideSeen = true
        updateStatus = getString(R.string.downloading)
        Updater.install = Updater.Install.Downloading(0, -1)
        UpdateService.start(this, release)
        refresh++
    }

    private fun versionCode(): Long = packageManager.getPackageInfo(packageName, 0).longVersionCode

    fun versionName(): String = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    companion object {
        const val EXTRA_SCREEN = "screen"

        /**
         * Start options for opening the app from the keyboard, a dot or the recording notice: if Android shows the launch
         * screen (the app's screen not running yet), only the page colour, without the mark, as such a launch is quick.
         */
        fun splashless(): Bundle =
            ActivityOptions.makeBasic().setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR).toBundle()

        /** The app op behind Android's "Restricted setting" for sideloaded apps (AppOpsManager's hidden OPSTR). */
        const val RESTRICTED_SETTINGS_OP = "android:access_restricted_settings"

        /** The launch screen's exit (Material 3 emphasized accelerate): the mark goes in 200 ms, then the page in 150. */
        const val SPLASH_ICON_EXIT_MS = 200L
        const val SPLASH_PAGE_FADE_MS = 150L
        const val SPLASH_EXIT_SCALE = 1.06f

        val PRIVACY_POLICY = "${BuildConfig.SOURCE_URL}/blob/main/PRIVACY.md"
        val TERMS = "${BuildConfig.SOURCE_URL}/blob/main/TERMS.md"

        /** Raised when the Terms change so that people must agree again: Home asks once, before anything else. */
        const val TERMS_VERSION = 1

        /** Tide's payment page (Stripe sells it through Link) until the server names one ([Account.tideLink]). */
        const val TIDE_LINK = "https://buy.stripe.com/9B614n5M6cZ62T21AMb7y01"

        /** Stripe sells Tide through Link, where the buyer manages the subscription. */
        const val LINK = "https://app.link.com"

        /** Link's own refunds, when the server cannot make one. */
        const val LINK_REFUNDS = "https://support.link.com/questions/sold-through-link-refunds"

        /** After a payment page: Stripe tells the server within seconds, so the plan is asked again over about 20 s. */
        val PLAN_RECHECKS_MS = listOf(3_000L, 7_000L, 10_000L)

        /** How often the app looks for an update by itself, when opened. */
        const val UPDATE_CHECK_MS = 6 * 3_600_000L

        /** Whether the app is on screen, so an install confirmation can open now instead of waiting. */
        @Volatile
        var resumed = false
    }
}
