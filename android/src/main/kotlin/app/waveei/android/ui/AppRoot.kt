package app.waveei.android.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.waveei.android.Account
import app.waveei.android.BufferStore
import app.waveei.android.DebugLog
import app.waveei.android.MainActivity
import app.waveei.android.R
import app.waveei.core.buffer.BufferEntry
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class Screen(@StringRes val title: Int) {
    HOME(0),
    ONBOARDING(0),
    ACCOUNT(R.string.group_account),
    BUFFER(R.string.buffer_title),
    WORDS(R.string.words),
    SETTINGS(R.string.settings),
    STYLES(R.string.styles_translation),
    SPEECH_LANGUAGES(R.string.speech_language),
    TRANSLATION_LANGUAGES(R.string.translation_languages),
    VOCABULARY(R.string.vocabulary),
    SNIPPETS(R.string.snippets),
    PRIVACY(R.string.privacy),
    FEEDBACK(R.string.feedback),
    ABOUT(R.string.about),
    LICENSES(R.string.licenses),
    PLAN(R.string.pro),
}

/** What a screen is opened for, besides the screen itself: Settings at the Dots group. */
sealed interface Extra {
    data class Group(val group: Int) : Extra
}

/**
 * A simple back stack; the system back gesture pops it. Each screen on the stack keeps its state, such as the scroll
 * position, until it is popped, so going back returns to where the user was. It also plays the screens' motion;
 * without a [scope] (a screen drawn alone in a test) it jumps.
 */
class Navigator(private val stack: SnapshotStateList<Screen>, private val states: SaveableStateHolder, private val scope: CoroutineScope? = null) {
    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1
    internal val screens: List<Screen> get() = stack

    /** Forward: 0 → 1 as the new screen comes in. */
    internal val forward = Animatable(1f)

    /** Back: the gesture's progress (eased), then the commit 0 → 1 that takes the screen off. */
    internal val drag = Animatable(0f)
    internal val commit = Animatable(0f)
    private var leaving = false

    private var extra: Extra? = null

    /** Takes what the current screen was opened for, once. */
    fun takeExtra(): Extra? = extra.also { extra = null }

    fun go(screen: Screen, extra: Extra? = null) {
        if (leaving) return
        this.extra = extra
        stack += screen
        DebugLog.add("ui: open ${screen.name.lowercase()}")
        scope?.launch {
            forward.snapTo(0f)
            // The new screen's first frame composes it, which can take longer than the motion itself; start the clock
            // after it, so forward plays out in full instead of landing at once.
            withFrameNanos {}
            withFrameNanos {}
            forward.animateTo(1f, tween(FORWARD_IN_MS, easing = Motion.standard))
        }
    }

    /** Back from the bar's arrow: the forward motion's path played in reverse, a little quicker than forward. */
    fun back() {
        if (!canGoBack || leaving) return
        if (scope == null) return pop()
        leaving = true
        scope.launch {
            try {
                forward.animateTo(0f, tween(FORWARD_MS, easing = Motion.standard))
                pop()
            } finally {
                // In the same frame as the pop: the screen under is the top one now, in place.
                forward.snapTo(1f)
                leaving = false
            }
        }
    }

    /** Takes the top screen off to the right, then pops it. */
    internal suspend fun leave() {
        leaving = true
        try {
            commit.animateTo(1f, tween(COMMIT_MS, easing = Motion.emphasizedAccelerate))
            pop()
        } finally {
            commit.snapTo(0f)
            drag.snapTo(0f)
            leaving = false
        }
    }

    /** A back gesture let go of: the screen settles back. */
    internal suspend fun stay() = drag.animateTo(0f, tween(CANCEL_MS, easing = Motion.standard))

    private fun pop() {
        if (!canGoBack) return
        states.removeState(key(stack.lastIndex, stack.last()))
        stack.removeAt(stack.lastIndex)
    }

    /** Replaces the whole stack, e.g. when onboarding is finished. */
    fun reset(screen: Screen) {
        stack.forEachIndexed { i, it -> states.removeState(key(i, it)) }
        stack.clear()
        stack += screen
        extra = null
        scope?.launch { forward.snapTo(1f) }
    }
}

@Composable
fun AppRoot(activity: MainActivity) {
    val stack = rememberSaveable(
        saver = listSaver(save = { list -> list.map { it.name } }, restore = { names -> names.map(Screen::valueOf).toMutableStateList() }),
    ) { mutableListOf(if (activity.prefs.onboardingDone) Screen.HOME else Screen.ONBOARDING).toMutableStateList() }
    val states = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val nav = remember(stack) { Navigator(stack, states, scope) }
    // Opened from outside, Back on that screen closes the app and takes it off Recents: a screen opened from the
    // keyboard goes when done, and the app the person was in shows again.
    LaunchedEffect(nav.canGoBack) { if (!nav.canGoBack) activity.fromOutside = false }
    val outside = activity.fromOutside && nav.screens.size == 2
    PredictiveBackHandler(enabled = nav.canGoBack && !outside) { events ->
        try {
            events.collect { nav.drag.snapTo(it.progress.pow(0.9f)) }
            nav.leave()
        } catch (e: CancellationException) {
            scope.launch { nav.stay() }
            throw e
        }
    }
    BackHandler(enabled = outside) { activity.finishAndRemoveTask() }
    LaunchedEffect(activity.openScreen) {
        val name = activity.openScreen ?: return@LaunchedEffect
        activity.openScreen = null
        if (!activity.prefs.onboardingDone) return@LaunchedEffect
        val (screen, extra) = when (name) {
            SETTINGS_DOTS -> Screen.SETTINGS to Extra.Group(R.string.group_dots)
            else -> (Screen.entries.firstOrNull { it.name == name } ?: return@LaunchedEffect) to null
        }
        nav.reset(Screen.HOME)
        if (screen != Screen.HOME) nav.go(screen, extra)
        activity.fromOutside = screen != Screen.HOME
    }
    OverlayHost {
        Screens(activity, nav, states)
        if (activity.updateGuide) UpdateGuide(activity)
    }
}

/** The top screen, and while it moves the one under it. */
@Composable
private fun Screens(activity: MainActivity, nav: Navigator, states: SaveableStateHolder) {
    val screens = nav.screens
    val top = screens.lastIndex
    // Read here, with the stack: the layers below place the screens this composition shows, so the frame that pops
    // the screen (and snaps forward back to 1) never draws it at rest over the one under it.
    val p = nav.forward.value
    val g = nav.drag.value
    val c = nav.commit.value
    val moving = p < 1f || g > 0f || c > 0f
    Box(Modifier.fillMaxSize().background(appColors.page)) {
        for (depth in (if (moving && top > 0) top - 1 else top)..top) {
            val screen = screens[depth]
            key(key(depth, screen)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { place(p, g, c, depth == top) }
                        .background(appColors.page),
                ) {
                    states.SaveableStateProvider(key(depth, screen)) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
                        ) { ScreenContent(screen, activity, nav) }
                    }
                }
            }
        }
    }
}

/** Where a screen is drawn while the screens move. */
private fun androidx.compose.ui.graphics.GraphicsLayerScope.place(p: Float, g: Float, c: Float, top: Boolean) {
    if (top) {
        if (p < 1f) {
            translationX = (48.dp.toPx()) * (1f - p)
            alpha = ((p - 0.2f) / 0.6f).coerceIn(0f, 1f)
        } else {
            val scale = lerp(1f, 0.9f, g)
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.5f, 0.5f)
            translationX = lerp(18.dp.toPx() * g, 430.dp.toPx(), c)
            alpha = 1f - 0.4f * c
            val radius = 40.dp.toPx() * g
            if (radius > 0f) {
                shape = RoundedCornerShape(radius)
                clip = true
            } else {
                shape = RectangleShape
                clip = false
            }
        }
    } else if (p < 1f) {
        translationX = -48.dp.toPx() * p
        alpha = (1f - 2f * p).coerceIn(0f, 1f)
    } else {
        // From full size, so nothing jumps as the gesture starts.
        val scale = lerp(lerp(1f, 0.97f, g), 1f, c)
        scaleX = scale
        scaleY = scale
    }
}

@Composable
private fun ScreenContent(screen: Screen, activity: MainActivity, nav: Navigator) {
    when (screen) {
        Screen.HOME -> HomeScreen(activity, nav)
        Screen.ONBOARDING -> OnboardingScreen(activity, nav)
        Screen.ACCOUNT -> AccountScreen(activity, nav)
        Screen.BUFFER -> BufferScreen(activity, nav)
        Screen.WORDS -> WordsScreen(activity, nav)
        Screen.SETTINGS -> SettingsScreen(activity, nav)
        Screen.STYLES -> StylesScreen(activity, nav)
        Screen.SPEECH_LANGUAGES -> SpeechLanguagesScreen(activity, nav)
        Screen.TRANSLATION_LANGUAGES -> TranslationLanguagesScreen(activity, nav)
        Screen.VOCABULARY -> VocabularyScreen(activity, nav)
        Screen.SNIPPETS -> SnippetsScreen(activity, nav)
        Screen.PRIVACY -> PrivacyScreen(activity, nav)
        Screen.FEEDBACK -> FeedbackScreen(activity, nav)
        Screen.ABOUT -> AboutScreen(activity, nav)
        Screen.LICENSES -> LicensesScreen(nav)
        Screen.PLAN -> PlanScreen(activity, nav)
    }
}

/** A screen under Home: the bar with its title and back arrow, then [content], scrolling unless [scroll] is off. */
@Composable
fun Page(screen: Screen, nav: Navigator, scroll: Boolean = true, action: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(screen.title), nav::back, action)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        ) {
            content()
            if (scroll) BottomSpace()
        }
    }
}

/** The saved state of a screen at a depth of the back stack. */
private fun key(depth: Int, screen: Screen) = "$depth:${screen.name}"

/** The screen name other parts open (EXTRA_SCREEN) besides [Screen]'s own: Settings at the Dots. */
const val SETTINGS_DOTS = "SETTINGS_DOTS"

private const val FORWARD_MS = 300
// Into a screen: Material 3's long2 (500 ms), slower than back out of it, so forward does not read as rushed.
private const val FORWARD_IN_MS = 500
private const val COMMIT_MS = 250
private const val CANCEL_MS = 200

/** A state backed by a stored setting: reading is cheap, writing stores it at once. */
@Composable
fun <T> rememberPref(read: () -> T, write: (T) -> Unit): MutableState<T> {
    val state = remember { mutableStateOf(read()) }
    return remember {
        object : MutableState<T> {
            override var value: T
                get() = state.value
                set(value) {
                    write(value)
                    state.value = value
                }

            override fun component1(): T = value

            override fun component2(): (T) -> Unit = { value = it }
        }
    }
}

/** The history rows, newest first, kept up to date while on screen. */
@Composable
fun rememberBufferEntries(): List<BufferEntry> {
    var entries by remember { mutableStateOf(shownEntries()) }
    DisposableEffect(Unit) {
        val listener = { entries = shownEntries() }
        BufferStore.addListener(listener)
        onDispose { BufferStore.removeListener(listener) }
    }
    return entries
}

/** A dictation still being recorded or transcribed shows once it has its text. */
private fun shownEntries() = BufferStore.all().filter { !it.status.active }.sortedByDescending { it.id }

/**
 * Sign in, or create the Waveei account: Create account turns the dialog into its own form, with the same two fields;
 * a new account gets a confirmation link by email.
 */
@Composable
fun AccountDialog(activity: MainActivity, onDismiss: () -> Unit) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A new account's password typed twice, the same.
    val ready = !busy && '@' in email && password.isNotEmpty() && (!creating || repeat == password)

    fun run(action: suspend () -> String?, onDone: () -> Unit) {
        busy = true
        error = null
        scope.launch {
            val failure = action()
            busy = false
            if (failure == null) onDone() else error = failure
        }
    }

    fun signedIn() {
        activity.accountChanged()
        onDismiss()
    }

    DialogCard(onDismiss) {
        DialogTitle(stringResource(if (creating) R.string.create_account else R.string.sign_in))
        val emailError = error?.takeIf { '@' !in email }
        Field(
            email,
            { email = it.trim() },
            placeholder = "",
            label = stringResource(R.string.email),
            error = emailError,
            inDialog = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Field(
            password,
            { password = it },
            placeholder = "",
            label = stringResource(R.string.password),
            error = error?.takeIf { emailError == null && password.isEmpty() },
            inDialog = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        // Said once the second is typed out in full and still not the same, not at every letter.
        val mismatch = stringResource(R.string.passwords_differ).takeIf { creating && repeat.isNotEmpty() && repeat.length >= password.length && repeat != password }
        if (creating) {
            Field(
                repeat,
                { repeat = it },
                placeholder = "",
                label = stringResource(R.string.repeat_password),
                error = mismatch,
                inDialog = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
        // Read out by TalkBack as it appears.
        (error ?: mismatch)?.let { Text(it, style = type(14f, 20f, Fonts.MEDIUM), color = appColors.red, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        // The links stay in the accent, never greyed as if broken: tapped before the fields are filled, they say what
        // they need.
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Each form links to the other; what is typed stays.
            LinkText(stringResource(if (creating) R.string.sign_in else R.string.create_account), !busy) {
                creating = !creating
                error = null
            }
            if (!creating) LinkText(stringResource(R.string.forgot_password), !busy) {
                if ('@' !in email) {
                    error = activity.getString(R.string.enter_email_first)
                    return@LinkText
                }
                run({ Account.resetPassword(activity, email) }) {
                    Toast.makeText(activity, activity.getString(R.string.reset_sent, email), Toast.LENGTH_LONG).show()
                }
            }
        }
        DialogButtons(stringResource(R.string.cancel), stringResource(if (creating) R.string.create else R.string.sign_in), confirmEnabled = ready, onDismiss = onDismiss) {
            if (creating) {
                run({ Account.create(activity, email, password) }) {
                    Toast.makeText(activity, activity.getString(R.string.confirmation_sent, email), Toast.LENGTH_LONG).show()
                    signedIn()
                }
            } else {
                run({ Account.signIn(activity, email, password) }, ::signedIn)
            }
        }
    }
}

/** A link in a dialog: accent text as the text buttons (15 sp), grey only while busy; a 48 dp touch area. */
@Composable
private fun LinkText(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = type(16f, 24f, Fonts.SEMIBOLD),
        color = if (enabled) appColors.acc else appColors.ink3,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/** The five things the Accessibility service does, each with its glyph (disclosure_line_*). */
val DISCLOSURE_LINES = listOf(
    "cursor-text" to R.string.disclosure_line_field,
    "keyboard" to R.string.disclosure_line_keyboard,
    "paper-plane-tilt" to R.string.disclosure_line_send,
    "clipboard" to R.string.disclosure_line_paste,
    "eye-slash" to R.string.disclosure_line_nothing,
)

/** The disclosure's five lines, for the dialog and the setup step. */
@Composable
fun DisclosureLines(size: Float = 14.5f, line: Float = 19f) {
    val c = appColors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DISCLOSURE_LINES.forEach { (glyph, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(c.blueBox),
                    contentAlignment = Alignment.Center,
                ) { Glyph(glyph, 18.dp, c.blueInk) }
                Text(stringResource(text), style = type(size, line), color = c.ink, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * The prominent disclosure before the Accessibility service: what it reads, why, and that nothing is collected.
 * Consent turns the dots on and opens the system screen where the user enables it.
 */
@Composable
fun DotDisclosure(onAgree: () -> Unit, onDismiss: () -> Unit) {
    DialogCard(onDismiss) {
        DialogTitle(stringResource(R.string.use_floating_icons_question))
        Column(
            Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DialogText(stringResource(R.string.disclosure_what))
            DisclosureLines()
            DialogText(stringResource(R.string.disclosure_next), small = true)
        }
        DialogButtons(stringResource(R.string.no_thanks), stringResource(R.string.agree), onDismiss = onDismiss) {
            onDismiss()
            onAgree()
        }
    }
}
