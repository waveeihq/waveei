package app.waveei.android.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.waveei.android.Account
import app.waveei.android.DebugLog
import app.waveei.android.Diagnostics
import app.waveei.android.MainActivity
import app.waveei.android.R
import app.waveei.core.api.FreeTier
import kotlin.math.PI
import kotlin.math.sin

/** What setup asks for, read once for the whole screen and passed down to the steps. */
private data class SetupState(
    val mic: Boolean,
    val micBlocked: Boolean,
    val keyboard: Boolean,
    val dots: Boolean,
    val restricted: Boolean,
    val restrictedSeen: Boolean,
    val signedIn: Boolean,
    val tide: Boolean,
    val waiting: Boolean,
)

/** Turns every dot on; with the Accessibility service off and [open], this opens its settings, once. */
private fun enableFloating(activity: MainActivity, open: Boolean = true) {
    MainActivity.Floating.entries.forEach { activity.setFloating(it, true, openSettings = false) }
    if (open && !activity.dotServiceOn()) activity.openAccessibilitySettings()
}

/**
 * Setup: Start → Microphone → Keyboard → Floating dots (+ consent) → Account → App reports. [at] is the screen it
 * opens on, by its place: step × 2, plus 1 for the consent.
 */
@Composable
fun OnboardingScreen(activity: MainActivity, nav: Navigator, at: Int = 0) {
    // Read here, again at every return to the app (ON_RESUME) and after the permission prompt, and passed down:
    // the steps sit in AnimatedContent, which skips them while their arguments stay the same.
    @Suppress("UNUSED_VARIABLE") val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value
    @Suppress("UNUSED_VARIABLE") val refresh = activity.refresh
    val prefs = activity.prefs
    val state = SetupState(
        activity.micGranted(), activity.micBlocked(), activity.keyboardEnabled(), activity.dotServiceOn(),
        // Where Android's word cannot be read: turned on here once (Agree) and still off on Android 13+.
        activity.restrictedSettings() ?: (prefs.dotEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
        prefs.restrictedSeen,
        activity.signedIn(), activity.tide(), activity.accountWaiting(),
    )
    var step by rememberSaveable { mutableIntStateOf(at / 2) }
    var consent by rememberSaveable { mutableStateOf(at % 2 == 1) }
    var accountDialog by remember { mutableStateOf(false) }
    // Each step reached, in the event log: where setup is left.
    LaunchedEffect(step, consent) { DebugLog.add("setup: ${if (consent) "dots consent" else SETUP_STEPS[step]}") }
    val finish = {
        prefs.onboardingDone = true
        prefs.guidePending = true
        nav.reset(Screen.HOME)
    }
    // The system Back does what the on-screen Back does: the consent closes, a later step goes to the one before. On
    // Start it leaves the app, as there is nothing before it.
    BackHandler(enabled = consent || step > 0) { if (consent) consent = false else step-- }
    val slide = with(LocalDensity.current) { STEP_SHIFT.roundToPx() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = maxHeight
        Column(Modifier.fillMaxSize()) {
            Progress(if (consent) 3 else step)
            AnimatedContent(
                // Each screen of setup by its place: the consent sits between the dots and the account.
                step * 2 + if (consent) 1 else 0,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(tween(STEP_MS, easing = Motion.standard)) { if (forward) slide else -slide } + fadeIn(tween(STEP_MS * 6 / 10, delayMillis = STEP_MS / 5))) togetherWith
                        (slideOutHorizontally(tween(STEP_MS, easing = Motion.standard)) { if (forward) -slide else slide } + fadeOut(tween(STEP_MS / 2)))
                },
                modifier = Modifier.weight(1f),
                label = "setup",
            ) { screen ->
                val place = screen / 2
                // Under a later step only the room the on-screen Back needs, so a step that fits does not scroll.
                val bottom = if (place == 0) (height * 0.17f).coerceIn(24.dp, 160.dp) else BACK_CLEARANCE
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = GUTTER),
                ) {
                    // A step's picture keeps the gutter's distance from the progress bar, the gap under the bar included.
                    val top = if (place == 0) 0.dp else STEP_TOP
                    Spacer(Modifier.height(top))
                    Column(
                        Modifier.heightIn(min = height - PROGRESS_HEIGHT - top),
                        // Every step from the top, so its title stays put from step to step.
                        verticalArrangement = if (place == 0) Arrangement.SpaceBetween else Arrangement.Top,
                    ) {
                        when {
                            place == 0 -> StartStep(activity) { step = 1 }
                            // The prompt's picture takes what the step leaves, so the step fits the screen.
                            place == 1 -> MicStep(activity, state, (height - PROGRESS_HEIGHT - STEP_TOP - BACK_CLEARANCE - MIC_STEP_TEXT).coerceIn(MIC_PICTURE_MIN, MIC_PICTURE_MAX)) { step = 2 }
                            place == 2 -> KeyboardStep(activity, state.keyboard) { step = 3 }
                            place == 3 && screen % 2 == 1 -> ConsentStep(
                                onAgree = {
                                    consent = false
                                    // Restricted, back to the guide, whose own button opens Accessibility: opened
                                    // here, the guide would only flash on the way out.
                                    enableFloating(activity, open = !state.restricted)
                                },
                                onNo = { consent = false },
                            )
                            place == 3 -> DotsStep(activity, state, onTurnOn = { consent = true }) { step = 4 }
                            place == 4 -> AccountStep(activity, state, onSignIn = { accountDialog = true }, onPlan = { nav.go(Screen.PLAN) }) { step = 5 }
                            else -> ReportsStep(activity) { share ->
                                prefs.shareEvents = share
                                DebugLog.share = share
                                DebugLog.add("setup: app reports ${if (share) "shared" else "not shared"}")
                                if (share) Thread { Diagnostics.logPhone(activity) }.start()
                                finish()
                            }
                        }
                        Spacer(Modifier.height(bottom))
                    }
                }
            }
        }
        if (step > 0) {
            // The page under Back, so a step scrolling under it never runs into the label.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(BACK_BAR)
                    .background(appColors.page),
            )
            Text(
                stringResource(R.string.back),
                style = type(16f, 24f, Fonts.SEMIBOLD),
                color = appColors.ink2,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    // The label on the content's edge, its touch area around it.
                    .padding(start = 12.dp, bottom = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { if (consent) consent = false else step-- }
                    .padding(horizontal = 12.dp, vertical = 13.dp),
            )
        }
    }
    if (accountDialog) AccountDialog(activity) { accountDialog = false }
}

/** A segment per step after Start; the next fills as it comes. */
@Composable
private fun Progress(step: Int) {
    val c = appColors
    val label = stringResource(R.string.step_of, step, STEPS)
    Row(
        Modifier
            .fillMaxWidth()
            .height(PROGRESS_HEIGHT)
            .padding(start = GUTTER, end = GUTTER, top = 10.dp)
            .alpha(if (step == 0) 0f else 1f)
            // For TalkBack, as a progress bar: "Step 2 of 4".
            .semantics {
                if (step > 0) {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(step.toFloat() / STEPS, 0f..1f, STEPS - 1)
                }
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(STEPS) { i ->
            val fill by animateFloatAsState(if (step > i) 1f else 0f, tween(PROGRESS_MS, delayMillis = 60, easing = Motion.emphasizedDecelerate), label = "progress")
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(c.track),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(c.blue),
                )
            }
        }
    }
}

@Composable
private fun StartStep(activity: MainActivity, onStart: () -> Unit) {
    val c = appColors
    StartCard()
    Column {
        StepTitle(stringResource(R.string.talk_it_types))
        StepBody(stringResource(R.string.onboarding_intro))
        // The only place the free tier's limit is said before it is used up.
        if (FreeTier.ON) {
            val free = stringResource(R.string.onboarding_free)
            val cut = free.indexOf(',') + 1
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight(Fonts.SEMIBOLD))) { append(free.take(cut)) }
                    append(free.drop(cut))
                },
                style = type(14f, 20f),
                color = c.ink2,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        InfoLine(stringResource(R.string.leaves_phone), modifier = Modifier.padding(top = 18.dp))
        // The Terms right over the button that accepts them: the person agrees with a tap of their own, nothing ticked.
        TermsLine(activity, Modifier.padding(top = 14.dp))
        Cta(stringResource(R.string.agree_and_start), "arrow-right") {
            activity.prefs.termsAccepted = MainActivity.TERMS_VERSION
            DebugLog.add("setup: terms ${MainActivity.TERMS_VERSION} accepted")
            onStart()
        }
    }
}

/** The Start card: a message being typed by voice, "Listening…" and the keyboard's mic ball in its water. */
@Composable
private fun StartCard() {
    val c = appColors
    Box(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(200.dp)
            .dropShadow(RoundedCornerShape(28.dp), Shadow(radius = 14.dp, color = Color(0x12121821), offset = DpOffset(0.dp, 4.dp)))
            .clip(RoundedCornerShape(28.dp))
            .background(c.box),
    ) {
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 22.dp, end = 18.dp)
                .widthIn(max = 270.dp)
                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                .background(c.blue)
                .padding(horizontal = 15.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.start_sample), style = type(16f, 24f, Fonts.MEDIUM), color = Color.White, modifier = Modifier.weight(1f, fill = false))
            val blink by rememberInfiniteTransition(label = "caret").animateFloat(1f, 0f, infiniteRepeatable(tween(CARET_MS, easing = LinearEasing)), label = "blink")
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .size(2.dp, 19.dp)
                    .alpha(if (blink > 0.5f) 1f else 0f)
                    .background(Color.White),
            )
        }
        Text(
            stringResource(R.string.start_listening),
            style = type(12f, 16f, Fonts.SEMIBOLD),
            color = c.ink3,
            modifier = Modifier.padding(start = 18.dp, top = 96.dp),
        )
        // At the bottom centre, cut 6 dp by the card's edge.
        MicBall(
            80.dp,
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 6.dp),
        )
    }
}

@Composable
private fun MicStep(activity: MainActivity, state: SetupState, picture: Dp, onNext: () -> Unit) {
    val granted = state.mic
    val blocked = state.micBlocked
    StepTile("microphone", granted)
    StepTitle(stringResource(R.string.allow_the_microphone))
    StepBody(stringResource(R.string.onboarding_mic))
    if (blocked) InfoLine(stringResource(R.string.mic_blocked), glyph = "info", grey = true, modifier = Modifier.padding(top = 18.dp))
    // Android's prompt drawn ahead of it, with the answer that keeps the mic for good.
    if (!granted && !blocked) {
        Box(Modifier.fillMaxWidth().padding(top = 24.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) { MicPromptPicture(picture) }
        InfoLine(stringResource(R.string.mic_pick_while_using), glyph = "info", modifier = Modifier.padding(top = 18.dp))
    }
    Granted(granted, stringResource(R.string.microphone_on), onNext) {
        if (blocked) {
            Cta(stringResource(R.string.open_settings), "gear-six", onClick = activity::openAppSettings)
        } else {
            Cta(stringResource(R.string.allow_microphone), "microphone", onClick = activity::requestMic)
        }
    }
    NextLink(granted, onNext)
}

@Composable
private fun KeyboardStep(activity: MainActivity, granted: Boolean, onNext: () -> Unit) {
    StepTile("keyboard", granted)
    StepTitle(stringResource(R.string.turn_on_keyboard))
    StepBody(stringResource(R.string.onboarding_keyboard))
    Granted(granted, stringResource(R.string.keyboard_on), onNext) {
        Cta(stringResource(R.string.enable_in_settings), "keyboard", onClick = activity::openKeyboardSettings)
    }
    NextLink(granted, onNext)
}

@Composable
private fun DotsStep(activity: MainActivity, state: SetupState, onTurnOn: () -> Unit, onNext: () -> Unit) {
    // [granted] is the service on its own: Android turns it on only by hand, and without it nothing floats.
    val granted = state.dots
    val prefs = activity.prefs
    // Why the guide shows or not, in the event log: Android's answer is read, not seen.
    LaunchedEffect(state.restricted) { DebugLog.add("setup: dots restricted ${state.restricted}, ${activity.restrictedReason()}") }
    StepTile("hand", granted)
    StepTitle(stringResource(R.string.floating_icons))
    // An app from outside a store finds its switch greyed out on Android 13+: three stops, drawn, each a tap away.
    if (!granted && (state.restricted || state.restrictedSeen)) {
        val found = state.restrictedSeen || !state.restricted
        val allowed = !state.restricted
        StepBody(stringResource(R.string.dots_restricted_body))
        Column(Modifier.padding(top = 18.dp)) {
            StepItem(1, if (found) StepState.DONE else StepState.NOW, stringResource(R.string.dots_find_title), stringResource(R.string.dots_find_detail)) {
                PhonePair({ GreyInAccessibilityPicture() }, { RestrictedDialogPicture() })
            }
            StepItem(
                2,
                when {
                    allowed -> StepState.DONE
                    found -> StepState.NOW
                    else -> StepState.TODO
                },
                stringResource(R.string.dots_allow_title),
                stringResource(R.string.dots_allow_detail),
            ) { PhonePair({ AppInfoMenuPicture(false) }, { AppInfoMenuPicture(true) }) }
            StepItem(3, if (allowed) StepState.NOW else StepState.TODO, stringResource(R.string.dots_on_title), stringResource(R.string.dots_on_detail)) {
                PhonePair({ UseServicePicture() }, { FullControlPicture() })
            }
        }
        when {
            !found -> Cta(stringResource(R.string.open_accessibility), "hand") {
                // The disclosure comes first; agreed to before, straight to Accessibility.
                if (prefs.dotEnabled) {
                    prefs.restrictedSeen = true
                    activity.openAccessibilitySettings()
                } else {
                    onTurnOn()
                }
            }
            !allowed -> Cta(stringResource(R.string.open_app_info), "gear-six", onClick = activity::openAppSettings)
            else -> Cta(stringResource(R.string.open_accessibility), "hand") { if (prefs.dotEnabled) activity.openAccessibilitySettings() else onTurnOn() }
        }
        NextLink(false, onNext)
        return
    }
    StepBody(stringResource(R.string.onboarding_floating))
    Granted(granted, stringResource(R.string.access_dots_on), onNext) {
        Cta(stringResource(R.string.turn_on_accessibility_cta), "hand", onClick = onTurnOn)
    }
    NextLink(granted, onNext)
}

/** The prominent disclosure as a step of its own: the five lines, where Android goes next, Agree or No thanks. */
@Composable
private fun ConsentStep(onAgree: () -> Unit, onNo: () -> Unit) {
    StepTitle(stringResource(R.string.use_floating_icons_question))
    StepBody(stringResource(R.string.disclosure_what))
    Box(Modifier.padding(top = 14.dp)) { DisclosureLines() }
    StepBody(stringResource(R.string.disclosure_next))
    Cta(stringResource(R.string.agree), "check", onClick = onAgree)
    Secondary(stringResource(R.string.no_thanks), appColors.ink2, onClick = onNo)
}

@Composable
private fun AccountStep(activity: MainActivity, state: SetupState, onSignIn: () -> Unit, onPlan: () -> Unit, onDone: () -> Unit) {
    val signedIn = state.signedIn
    val tide = state.tide
    val waiting = state.waiting
    StepTile("user-circle", signedIn)
    StepTitle(stringResource(R.string.your_account))
    StepBody(stringResource(if (FreeTier.ON) R.string.onboarding_account else R.string.sign_in_description))
    if (!tide) TidePromo()
    if (waiting) InfoLine("${Account.email} · ${stringResource(R.string.waiting_description)}", glyph = "envelope", grey = true, modifier = Modifier.padding(top = 18.dp))
    when {
        tide -> DoneLine(stringResource(R.string.waveei_tide_on))
        // Without the free tier, dictation needs Tide: the plan is one tap away.
        signedIn && !FreeTier.ON -> Cta(stringResource(R.string.subscribe), "arrow-right", onClick = onPlan)
        signedIn -> DoneLine(stringResource(R.string.signed_in))
        waiting -> Cta(stringResource(R.string.send_again), "envelope", onClick = activity::sendConfirmation)
        else -> Cta(stringResource(R.string.sign_in), "user-circle", onClick = onSignIn)
    }
    when {
        // Done is then the one thing left: the filled action, where the step's action was.
        tide || (signedIn && FreeTier.ON) -> Cta(stringResource(R.string.done), "arrow-right", top = 16.dp, onClick = onDone)
        signedIn -> Secondary(stringResource(R.string.done), appColors.tonalInk, onClick = onDone)
        else -> Secondary(stringResource(R.string.not_now), appColors.ink2, onClick = onDone)
    }
}

/** What Waveei Tide gives and costs, before the account it needs: the plan's name and price, then its three gifts. */
@Composable
private fun TidePromo() {
    val c = appColors
    Column(
        Modifier
            .padding(top = 18.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.box)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("${stringResource(R.string.pro)} · ${stringResource(R.string.tide_price)}", style = type(16f, 24f, Fonts.SEMIBOLD), color = c.ink)
        listOf("microphone" to R.string.tide_no_limit, "waveform" to R.string.tide_live, "device-mobile" to R.string.tide_sync).forEach { (glyph, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(glyph, 20.dp, c.blueInk)
                Text(stringResource(text), style = type(16f, 24f), color = c.ink2)
            }
        }
    }
}

/**
 * Whether app reports go to Waveei: asked once, as a choice of two buttons that look the same, nothing picked for the
 * person (no dark pattern: neither answer is the "primary" one). What reports hold is the body; what they never hold
 * is the lock line, as on the first step, so it reads at a glance. Privacy changes it later.
 */
@Composable
private fun ReportsStep(activity: MainActivity, onChoice: (Boolean) -> Unit) {
    StepTile("shield-check", false)
    StepTitle(stringResource(R.string.reports_title))
    StepBody(stringResource(R.string.reports_body))
    InfoLine(stringResource(R.string.reports_never), modifier = Modifier.padding(top = 18.dp))
    PolicyLink(activity, Modifier.padding(top = 14.dp))
    ChoiceButton(stringResource(R.string.share_reports), Modifier.padding(top = 28.dp)) { onChoice(true) }
    ChoiceButton(stringResource(R.string.dont_share), Modifier.padding(top = 10.dp)) { onChoice(false) }
}

/** "More in the Privacy policy", its link set as the Terms line's. */
@Composable
private fun PolicyLink(activity: MainActivity, modifier: Modifier = Modifier) {
    val c = appColors
    val policy = stringResource(R.string.privacy_policy)
    val line = stringResource(R.string.more_in_policy, policy)
    val link = TextLinkStyles(SpanStyle(color = c.tonalInk, fontWeight = FontWeight(Fonts.SEMIBOLD)))
    Text(
        buildAnnotatedString {
            append(line)
            val at = line.indexOf(policy)
            if (at >= 0) addLink(LinkAnnotation.Clickable(policy, link) { activity.openUrl(MainActivity.PRIVACY_POLICY) }, at, at + policy.length)
        },
        style = type(14f, 20f),
        color = c.ink2,
        modifier = modifier,
    )
}

/** One of two answers given the same weight: white, a hairline ring, at least 48 dp (taller for a larger font). */
@Composable
private fun ChoiceButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = appColors
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(c.box)
            .border(1.dp, c.line2, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = type(16f, 24f, Fonts.SEMIBOLD), color = c.ink, textAlign = TextAlign.Center) }
}

/** The 64 dp tile of a step: blue, green once the step is done. */
@Composable
private fun StepTile(glyph: String, done: Boolean) {
    val c = appColors
    val fill by animateColorAsState(if (done) c.okTile else c.blueBox, tween(GRANT_MS), label = "tile")
    val ink by animateColorAsState(if (done) c.green else c.blueInk, tween(GRANT_MS), label = "glyph")
    Box(
        Modifier
            .padding(bottom = 22.dp)
            .size(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(fill),
        contentAlignment = Alignment.Center,
    ) { Glyph(glyph, 30.dp, ink) }
}

@Composable
private fun StepTitle(text: String) = Text(text, style = type(28f, 36f, Fonts.TITLE, letterSpacing = -0.3f), color = appColors.ink)

@Composable
private fun StepBody(text: String) = Text(text, style = type(16f, 24f), color = appColors.ink2, modifier = Modifier.padding(top = 12.dp))

/**
 * The step's 48 dp action, flat blue (no shadow: the design keeps shadows for pop-ups, the mic ball and the dots). An
 * arrow that says "on" follows the label, as the guide's Next; any other glyph leads it.
 */
@Composable
private fun Cta(text: String, glyph: String, top: Dp = 28.dp, onClick: () -> Unit) {
    val c = appColors
    val after = glyph == "arrow-right"
    Row(
        Modifier
            .padding(top = top)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(c.blue)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!after) Glyph(glyph, 19.dp, Color.White)
        Text(text, style = type(16f, 24f, Fonts.SEMIBOLD), color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
        if (after) Glyph(glyph, 19.dp, Color.White)
    }
}

/** What a done step says where its action was: the tick and the words, a line, not a button. */
@Composable
private fun DoneLine(text: String) {
    val c = appColors
    Row(
        Modifier
            .padding(top = 28.dp)
            .fillMaxWidth()
            .heightIn(min = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tick(true)
        Text(text, style = type(16f, 24f, Fonts.SEMIBOLD), color = c.ink, modifier = Modifier.padding(start = 10.dp))
    }
}

/** The action until the step is granted; then the done line and Next, filled, in the action's place. */
@Composable
private fun Granted(granted: Boolean, done: String, onNext: () -> Unit, action: @Composable () -> Unit) {
    AnimatedContent(
        granted,
        transitionSpec = { fadeIn(tween(GRANTED_MS, delayMillis = GRANTED_MS / 4, easing = Motion.standard)) togetherWith fadeOut(tween(GRANTED_MS * 35 / 100)) },
        label = "granted",
    ) {
        if (it) {
            Column {
                DoneLine(done)
                Cta(stringResource(R.string.next), "arrow-right", top = 16.dp, onClick = onNext)
            }
        } else {
            action()
        }
    }
}

/** Under the action: Not now until the step is granted, which goes on too (never blocked); once granted, Next is the action itself. */
@Composable
private fun NextLink(granted: Boolean, onNext: () -> Unit) {
    if (!granted) Secondary(stringResource(R.string.not_now), appColors.ink2, onClick = onNext)
}

/** The text button under a step's action, 14 dp below it. */
@Composable
private fun Secondary(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = type(16f, 24f, Fonts.SEMIBOLD),
            color = color,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 13.dp),
        )
    }
}

/**
 * The keyboard's mic ball in its listening water: white air, a back wave and the blue water at
 * [level], a white surface line, the glyph white under the water and blue above it, a white rim.
 */
@Composable
fun MicBall(size: Dp, modifier: Modifier = Modifier, level: Float = 0.49f) {
    val c = appColors
    val night = c.dark
    val sway by rememberInfiniteTransition(label = "water").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(SWAY_MS, easing = LinearEasing)), label = "sway")
    val glyph = rememberVectorPainter(Icons.vector("microphone"))
    Canvas(
        modifier
            .size(size)
            .dropShadow(CircleShape, Shadow(radius = 12.dp, color = Color(0xFF0E70F4).copy(alpha = 0.22f), offset = DpOffset(0.dp, 6.dp))),
    ) {
        val w = this.size.width
        val h = this.size.height
        val surface = h * (1f - level)
        clipPath(Path().apply { addOval(Rect(Offset.Zero, this@Canvas.size)) }) {
            drawRect(Brush.verticalGradient(if (night) listOf(Color(0xFF2A374C), Color(0xFF1E2B42)) else listOf(Color.White, Color(0xFFEAF3FF))))
            drawPath(ballWave(w, h, surface, 0.95f, sway + 1.3f), if (night) Color(0xFF4E9BFF).copy(alpha = 0.38f) else Color(0xFFBFE2FF))
            val front = ballWave(w, h, surface, 0.95f, sway)
            drawPath(front, Brush.verticalGradient(listOf(Color(0xFF6CC2FF), Color(0xFF2F86F0), Color(0xFF0E70F4)), startY = surface, endY = h))
            drawPath(ballWave(w, h, surface, 0.95f, sway, open = true), Color.White.copy(alpha = 0.75f), style = Stroke(1.5.dp.toPx()))
            // The glyph: blue above the water, white in it.
            val g = w * 0.36f
            translate((w - g) / 2, (h - g) / 2) {
                clipRect(top = -g, bottom = surface - (h - g) / 2) { with(glyph) { draw(Size(g, g), colorFilter = ColorFilter.tint(if (night) Color(0xFF8EC3FF) else Color(0xFF0E70F4))) } }
                clipRect(top = surface - (h - g) / 2, bottom = g * 2) { with(glyph) { draw(Size(g, g), colorFilter = ColorFilter.tint(Color.White)) } }
            }
        }
        // The rim and the hairline: white and ink by day, faint white and blue at night.
        drawCircle(if (night) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.95f), radius = w / 2 - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
        drawCircle(if (night) Color(120, 170, 255).copy(alpha = 0.22f) else Color(0xFF121821).copy(alpha = 0.14f), radius = w / 2 - 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
    }
}

/** The water's edge across the ball, filled to its bottom unless [open]. */
private fun ballWave(width: Float, height: Float, surface: Float, amp: Float, phase: Float, open: Boolean = false) = Path().apply {
    val swing = height * 0.045f * amp
    val steps = 32
    for (i in 0..steps) {
        val x = width * i / steps
        val y = surface + swing * sin(2 * PI * i / steps + phase).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    if (!open) {
        lineTo(width, height)
        lineTo(0f, height)
        close()
    }
}

/** The steps after Start, each a segment of the progress. */
private const val STEPS = 5
private val SETUP_STEPS = listOf("start", "microphone", "keyboard", "floating dots", "account", "app reports")
private val STEP_SHIFT = 40.dp
/** The setup's side margin, on the 8 dp grid. */
private val GUTTER = 24.dp
/** The progress bar's row: 10 dp above the bar, the 4 dp bar, then an 8 dp gap where a scrolled step is cut off. */
private val PROGRESS_HEIGHT = 22.dp
/** From the progress row to a step's picture: the gutter, less the gap already in the row. */
private val STEP_TOP = GUTTER - 8.dp
/** The on-screen Back: 16 dp from the bottom and its 50 dp touch area. */
private val BACK_BAR = 66.dp
/** Under a step: the on-screen Back (16 dp from the bottom, a 50 dp touch area) and a gap above it. */
private val BACK_CLEARANCE = 80.dp
/** The Microphone step without its picture, with room for a line more. */
private val MIC_STEP_TEXT = 440.dp
private val MIC_PICTURE_MIN = 180.dp
private val MIC_PICTURE_MAX = 300.dp
private const val STEP_MS = 300
private const val PROGRESS_MS = 400
private const val GRANT_MS = 300
private const val GRANTED_MS = 450
private const val CARET_MS = 1000
private const val SWAY_MS = 4000

/** "By tapping Agree and start, you accept the Terms…", the Terms and the Privacy policy each a link. */
@Composable
private fun TermsLine(activity: MainActivity, modifier: Modifier = Modifier) {
    val c = appColors
    val terms = stringResource(R.string.terms)
    val policy = stringResource(R.string.privacy_policy)
    val line = stringResource(R.string.terms_line, terms, policy)
    // On the page grey, not a white card: the darker tonal blue keeps the links at 4.5:1 by day (the accent is 4.1:1
    // there), and at night it is the light accent text blue.
    val link = TextLinkStyles(SpanStyle(color = c.tonalInk, fontWeight = FontWeight(Fonts.SEMIBOLD)))
    Text(
        buildAnnotatedString {
            append(line)
            for ((word, url) in listOf(terms to MainActivity.TERMS, policy to MainActivity.PRIVACY_POLICY)) {
                val at = line.indexOf(word).takeIf { it >= 0 } ?: continue
                addLink(LinkAnnotation.Clickable(word, link) { activity.openUrl(url) }, at, at + word.length)
            }
        },
        style = type(14f, 20f),
        color = c.ink2,
        modifier = modifier,
    )
}
