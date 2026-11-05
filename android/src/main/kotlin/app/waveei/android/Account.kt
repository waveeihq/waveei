package app.waveei.android

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import app.waveei.core.api.Api
import app.waveei.core.api.FreeTier
import app.waveei.core.api.Plan
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.AppCheckToken
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The Waveei account: email and password, confirmed by a link in an email, for Tide. A person who never signed in
 * gets an anonymous account on the first dictation, for the free tier. Our own accounts, kept by Firebase Auth in
 * our project; it renews the short-lived token on its own, so the keyboard never needs a screen to stay signed in.
 * Actions return a short reason when they fail, null when they worked.
 */
object Account {
    private var auth: FirebaseAuth? = null
    private var appCheck: FirebaseAppCheck? = null
    private lateinit var store: SharedPreferences

    /** The last App Check token, reused while it lasts: free minutes need one, Tide does not. */
    @Volatile private var check: AppCheckToken? = null
    @Volatile private var checkAskedAt = 0L

    /** The last ask for a token, still on its way or done: a dictation in the first seconds waits for the start's ask. */
    @Volatile private var asking: Task<AppCheckToken>? = null

    /**
     * Firebase is set up from the build's values; a build without them, or where Firebase cannot start
     * (unit tests on Robolectric), simply has no accounts.
     */
    fun init(context: Context) {
        store = context.getSharedPreferences("account", Context.MODE_PRIVATE)
        resolver = context.applicationContext.contentResolver
        if (BuildConfig.FIREBASE_API_KEY.isEmpty()) return
        val options = FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.GCP_PROJECT)
            // The project number, second in the app id: App Check's Play Integrity provider reads it from here.
            .setGcmSenderId(BuildConfig.FIREBASE_APP_ID.split(':')[1])
            .build()
        val app = runCatching { FirebaseApp.initializeApp(context, options) }
            .onFailure { DebugLog.add("account: Firebase did not start: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return
        auth = runCatching { FirebaseAuth.getInstance(app) }
            .onFailure { DebugLog.add("account: Firebase Auth did not start: ${it.javaClass.simpleName}") }
            .getOrNull()
        appCheck = runCatching {
            FirebaseAppCheck.getInstance(app).apply {
                installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
                setTokenAutoRefreshEnabled(true)
                addAppCheckListener { check = it }
            }
        }.onFailure { DebugLog.add("account: App Check did not start: ${it.javaClass.simpleName}") }.getOrNull()
        refreshAppCheck()
    }

    private var resolver: android.content.ContentResolver? = null

    /**
     * The phone, for the free tier's count only: a hash of its Android id, the same after a reinstall or cleared data,
     * new after a factory reset; null when Android gives none. Read only when a free dictation first needs it, never
     * at the app's start or for Tide; only its hash leaves the phone.
     */
    val deviceId: String? by lazy {
        resolver?.let { Settings.Secure.getString(it, Settings.Secure.ANDROID_ID) }?.takeIf { it.isNotEmpty() }?.let { id ->
            MessageDigest.getInstance("SHA-256").digest("waveei:$id".toByteArray()).copyOf(16).toHexString()
        }
    }

    /** Dictation can go through the server: signed in to Tide, or, while the free tier is on, without an account. */
    val ready: Boolean get() = if (FreeTier.ON) auth != null else signedIn

    /** Signed in with a confirmed email. */
    val signedIn: Boolean get() = auth?.currentUser?.isEmailVerified == true

    /** The plan the server last told ([Plan.TIDE], [Plan.FREE] or [Plan.NONE]); null before it told. */
    val plan: String? get() = if (::store.isInitialized) store.getString(PLAN, null) else null

    /** Signed in, and the server has not said the account is without Tide (free or none): until it tells, it counts as Tide. */
    val tide: Boolean get() = signedIn && plan != Plan.NONE && plan != Plan.FREE

    /** The Firebase user id, which a Tide purchase is linked to. */
    val uid: String get() = auth?.currentUser?.uid.orEmpty()

    /** Signed in with an email that is not confirmed yet. */
    val waiting: Boolean get() = auth?.currentUser?.let { !it.isAnonymous && !it.isEmailVerified } == true

    val email: String get() = auth?.currentUser?.email.orEmpty()

    /** An account is on this phone, signed in or anonymous: shared diagnostics wait for one, never make one. */
    val exists: Boolean get() = auth?.currentUser != null

    /**
     * The sign-in token for the server, renewed when close to expiry; with no account yet and the free tier on, an
     * anonymous one is made first. Blocks: call off the main thread.
     */
    fun token(): String? {
        val auth = auth ?: return null
        val user = auth.currentUser ?: (if (FreeTier.ON) anonymous(auth) else null) ?: return null
        return idToken(user).getOrElse { error ->
            // Firebase deletes an anonymous account 30 days after it was made (automatic clean-up): a new one replaces it.
            val gone = (error.cause as? FirebaseAuthInvalidUserException)?.errorCode == USER_NOT_FOUND
            if (!gone || !user.isAnonymous || !FreeTier.ON) return null
            DebugLog.add("account: the anonymous account was deleted, a new one replaces it")
            replace(auth, user)?.let { idToken(it).getOrNull() }
        }
    }

    private fun idToken(user: FirebaseUser): Result<String?> =
        runCatching { Tasks.await(user.getIdToken(false), TOKEN_WAIT_S, TimeUnit.SECONDS).token }
            .onFailure { DebugLog.add("account: no token: ${it.javaClass.simpleName}") }

    /**
     * One at a time, as [anonymous]: the account is replaced once, whoever finds it gone first. Firebase signs a
     * deleted user out itself when its token fails to renew; here too, in case that has not happened yet.
     */
    @Synchronized
    private fun replace(auth: FirebaseAuth, gone: FirebaseUser): FirebaseUser? {
        if (auth.currentUser?.uid == gone.uid) auth.signOut()
        return anonymous(auth)
    }

    /** One at a time: the warm-up and the first piece both ask, and two would make two accounts. */
    @Synchronized
    private fun anonymous(auth: FirebaseAuth): FirebaseUser? = auth.currentUser ?: runCatching { await(auth.signInAnonymously()).user }
        .onSuccess { DebugLog.add("account: anonymous, for the free tier") }
        .onFailure { DebugLog.add("account: no anonymous account: ${it.javaClass.simpleName}") }
        .getOrNull()

    /**
     * The App Check token: the one at hand while fresh, else the one App Check keeps or fetches, waited for a few
     * seconds, since the free tier needs it and a cold start has none at hand yet: a new ask, or within a minute of the
     * last one, that ask, as the start's own is still on its way when a dictation comes right after it. Blocks: call
     * off the main thread.
     */
    fun appCheckToken(): String? {
        check?.takeIf { it.expireTimeMillis > System.currentTimeMillis() + APP_CHECK_MARGIN_MS }?.let { return it.token }
        // Taken from the task itself: its listeners run on the main thread, maybe after this returns.
        (refreshAppCheck() ?: asking)?.let { task -> runCatching { Tasks.await(task, APP_CHECK_WAIT_S, TimeUnit.SECONDS) }.getOrNull()?.let { check = it } }
        return check?.takeIf { it.expireTimeMillis > System.currentTimeMillis() }?.token
    }

    /** At most once a minute: every attempt costs a Play Integrity request, and they are counted per day. */
    private fun refreshAppCheck(): Task<AppCheckToken>? {
        val now = System.currentTimeMillis()
        if (now - checkAskedAt < APP_CHECK_RETRY_MS) return null
        checkAskedAt = now
        return appCheck?.getAppCheckToken(false)
            ?.addOnSuccessListener { check = it }
            ?.addOnFailureListener { DebugLog.add("account: no App Check token: ${it.javaClass.simpleName}") }
            ?.also { asking = it }
    }

    /** The free minutes left this week, as the server last told; null when not known or on Tide. */
    val freeLeftMs: Long?
        get() {
            if (!::store.isInitialized || !store.contains(FREE_LEFT)) return null
            // A new week starts with the full minutes again.
            if (FreeTier.week(store.getLong(FREE_AT, 0)) != FreeTier.week(System.currentTimeMillis())) return null
            return store.getLong(FREE_LEFT, 0)
        }

    /** The week's free minutes are used up, as far as the app knows: a dictation would only fail at its stop. */
    val freeUsedUp: Boolean get() = FreeTier.ON && !tide && freeLeftMs == 0L

    /** Reads the plan and the free minutes left off a server answer; Tide's answers carry no minutes. */
    fun note(response: Response) {
        if (!::store.isInitialized) return
        response.header(Plan.HEADER)?.let { store.edit().putString(PLAN, it).apply() }
        val left = response.header(FreeTier.LEFT_MS)?.toLongOrNull() ?: return
        store.edit().putLong(FREE_LEFT, left).putLong(FREE_AT, System.currentTimeMillis()).apply()
    }

    /** Tide's last payment failed (Stripe's past_due or unpaid): the card needs updating at Link. */
    val paymentFailed: Boolean get() = signedIn && ::store.isInitialized && store.getString(BILLING, null) != null

    /** Tide is cancelled and runs to the end of the month paid for: Resubscribe is offered. */
    val tideEnding: Boolean get() = tide && ::store.isInitialized && store.getString(RENEWS, null) == "no"

    /** Reads the failed payment, a cancelled Tide and Tide's payment page off the plan's answer, the only one that tells them. */
    fun noteBilling(response: Response) {
        if (!::store.isInitialized || response.header(Plan.HEADER) == null) return
        store.edit().putString(BILLING, response.header(Plan.BILLING)).putString(RENEWS, response.header(Plan.RENEWS)).apply()
        response.header(Plan.LINK)?.takeIf { it.startsWith(PAYMENT_PAGES) }?.let { store.edit().putString(LINK, it).apply() }
    }

    /** Tide's payment page as the server last named it; only ever one of Stripe's. */
    val tideLink: String? get() = if (::store.isInitialized) store.getString(LINK, null) else null

    /** Another account: the plan and minutes known belong to the last one. */
    private fun forget() {
        if (::store.isInitialized) store.edit().remove(FREE_LEFT).remove(FREE_AT).remove(PLAN).remove(BILLING).remove(RENEWS).apply()
    }

    suspend fun signIn(context: Context, email: String, password: String): String? = act(context, "signed in") { auth ->
        await(auth.signInWithEmailAndPassword(email, password))
        forget()
    }

    /**
     * A new account; the confirmation email goes out at once. Made from the anonymous account when there is one,
     * so the person stays the same user.
     */
    suspend fun create(context: Context, email: String, password: String): String? = act(context, "account created") { auth ->
        val anonymous = auth.currentUser?.takeIf { it.isAnonymous }
        if (anonymous != null) await(anonymous.linkWithCredential(EmailAuthProvider.getCredential(email, password)))
        else await(auth.createUserWithEmailAndPassword(email, password))
        await(auth.currentUser!!.sendEmailVerification())
    }

    suspend fun sendConfirmation(context: Context): String? = act(context, "confirmation sent") { auth ->
        auth.currentUser?.let { await(it.sendEmailVerification()) }
    }

    suspend fun resetPassword(context: Context, email: String): String? = act(context, "password reset sent") { auth ->
        await(auth.sendPasswordResetEmail(email))
    }

    /**
     * After the user tapped the link in the email: the account is read again, and the token renewed
     * so that it carries the confirmation the server checks.
     */
    suspend fun checkConfirmed() {
        val user = auth?.currentUser ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                await(user.reload())
                if (user.isEmailVerified) await(user.getIdToken(true))
            }
        }
        if (user.isEmailVerified) DebugLog.add("account: email confirmed")
    }

    /** Back to the free tier: the next dictation gets a new anonymous account. */
    fun signOut() {
        auth?.signOut()
        forget()
        DebugLog.add("account: signed out")
    }

    /** What the server did with a request to end Tide or the account. */
    enum class Outcome { DONE, LATE, ELSEWHERE, FAILED }

    /** Ends Tide at the end of the month paid for; [Outcome.ELSEWHERE] when only Link can (no subscription known here). */
    suspend fun cancelTide(): Outcome = tide(Api.TIDE_CANCEL, "Tide cancelled at period end")

    /** A cancelled Tide renews again; [Outcome.ELSEWHERE] when only Link can. */
    suspend fun resubscribeTide(): Outcome = tide(Api.TIDE_RESUME, "Tide resubscribed")

    /**
     * Within 14 days of subscribing: the unused days refunded and Tide ended at once. [Outcome.LATE]: the payment is
     * older; [Outcome.ELSEWHERE]: only Link's support can refund (no subscription known here).
     */
    suspend fun refundTide(): Outcome = tide(Api.TIDE_REFUND, "Tide refunded and ended", late = true)

    /** A Tide request's outcome, [done] logged when it went through; a 409 is [Outcome.LATE] only where [late]. */
    private suspend fun tide(path: String, done: String, late: Boolean = false): Outcome = when (call(path).first) {
        204 -> Outcome.DONE.also { DebugLog.add("account: $done") }
        409 -> if (late) Outcome.LATE else Outcome.FAILED
        404, 501 -> Outcome.ELSEWHERE
        else -> Outcome.FAILED
    }

    /**
     * Deletes the account and all the server keeps of it, then signs out here. It never ends Tide: within its 14 days
     * ([Outcome.LATE]) the person refunds it first, and while it would renew ([Outcome.ELSEWHERE]) they cancel it first;
     * either way nothing is deleted.
     */
    suspend fun delete(): Outcome {
        val (code, kind) = call(Api.ACCOUNT_DELETE)
        return when {
            code == 204 -> {
                auth?.signOut()
                forget()
                DebugLog.add("account: deleted")
                Outcome.DONE
            }
            code == 409 && kind == Api.REFUND_FIRST -> Outcome.LATE
            code == 409 -> Outcome.ELSEWHERE
            else -> Outcome.FAILED
        }
    }

    /** Tide's billing page as Stripe has it now; null without a subscription or a network. */
    class Billing(val renews: Boolean, val periodEnd: Long?, val card: String?, val cardExpires: String?, val invoices: List<Invoice>)

    class Invoice(val date: Long, val total: Long, val currency: String, val paid: Boolean, val pdf: String?)

    suspend fun billing(): Billing? = withContext(Dispatchers.IO) {
        if (!signedIn) return@withContext null
        runCatching {
            Server.get(Api.TIDE_BILLING)?.let { body ->
                val json = JSONObject(body)
                // An ended subscription has nothing to renew or resubscribe: no billing page for it.
                if (json.optString("status") !in setOf("active", "trialing", "past_due")) return@let null
                val card = json.optJSONObject("card")
                val invoices = json.optJSONArray("invoices")
                Billing(
                    renews = json.optBoolean("renews", true),
                    periodEnd = json.optLong("periodEnd").takeIf { it > 0 },
                    card = card?.let { "${it.optString("brand").replaceFirstChar(Char::uppercase)} •••• ${it.optString("last4")}" },
                    cardExpires = card?.let { "%02d/%d".format(it.optInt("expMonth"), it.optInt("expYear")) },
                    invoices = (0 until (invoices?.length() ?: 0)).map { invoices!!.getJSONObject(it) }.map {
                        Invoice(it.optLong("date"), it.optLong("total"), it.optString("currency"), it.optString("status") == "paid", it.optString("pdf").takeIf(String::isNotBlank))
                    },
                )
            }
        }.onFailure { DebugLog.add("account: billing failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /** An empty POST to the server; the HTTP status, 0 when it never got there. */
    private suspend fun call(path: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        runCatching { Server.post(path) }
            .onFailure { DebugLog.add("account: $path failed: ${it.javaClass.simpleName}") }
            .getOrDefault(0 to null)
            .also { DebugLog.add("account: $path, HTTP ${it.first}${it.second?.let { kind -> " $kind" }.orEmpty()}") }
    }

    private suspend fun act(context: Context, done: String, block: (FirebaseAuth) -> Unit): String? {
        val auth = auth ?: return context.getString(R.string.accounts_unavailable)
        return try {
            withContext(Dispatchers.IO) { block(auth) }
            DebugLog.add("account: $done")
            null
        } catch (e: Exception) {
            val error = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            DebugLog.add("account: failed: ${error.javaClass.simpleName}")
            context.getString(
                when (error) {
                    is FirebaseAuthWeakPasswordException -> R.string.weak_password
                    is FirebaseAuthUserCollisionException -> R.string.email_taken
                    is FirebaseAuthInvalidCredentialsException, is FirebaseAuthInvalidUserException -> R.string.wrong_login
                    is FirebaseNetworkException -> R.string.no_network
                    else -> R.string.account_failed
                },
            )
        }
    }

    private fun <T> await(task: Task<T>): T = Tasks.await(task, TOKEN_WAIT_S, TimeUnit.SECONDS)

    private const val TOKEN_WAIT_S = 15L
    private const val USER_NOT_FOUND = "ERROR_USER_NOT_FOUND"
    private const val APP_CHECK_MARGIN_MS = 5 * 60_000L
    private const val APP_CHECK_RETRY_MS = 60_000L
    private const val APP_CHECK_WAIT_S = 3L
    private const val FREE_LEFT = "free_left_ms"
    private const val FREE_AT = "free_at"
    private const val PLAN = "plan"
    private const val BILLING = "billing"
    private const val RENEWS = "renews"
    private const val LINK = "tide_link"
    private const val PAYMENT_PAGES = "https://buy.stripe.com/"
}
