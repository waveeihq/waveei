package app.waveei.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.waveei.android.Account
import app.waveei.android.DebugLog
import app.waveei.android.MainActivity
import app.waveei.android.R
import app.waveei.core.api.FreeTier
import kotlinx.coroutines.delay

/** Home: the tank with the status, Access with one-tap fixes, and the way to everything else. */
@Composable
fun HomeScreen(activity: MainActivity, nav: Navigator) {
    @Suppress("UNUSED_VARIABLE") val refresh = activity.refresh
    val prefs = activity.prefs
    val mic = activity.micGranted()
    val keyboard = activity.keyboardEnabled()
    val dots = activity.dotServiceOn()
    var offer by rememberPref({ prefs.screenshotOffer }) { prefs.screenshotOffer = it }
    val images = offer && activity.photosGranted()
    // The free tier, while on, needs no account.
    val ready = mic && keyboard && (activity.tide() || FreeTier.ON)
    val status = when {
        !mic || !keyboard -> R.string.home_steps
        ready -> R.string.home_ready
        !activity.signedIn() -> R.string.sign_in_description
        else -> R.string.tide_needed
    }
    var disclosure by remember { mutableStateOf(false) }
    val access = listOf(
        Access("microphone", stringResource(R.string.microphone), mic, stringResource(if (mic) R.string.allowed else R.string.not_allowed), stringResource(R.string.mic_needed) + ".", stringResource(R.string.allow), activity::openAppSettings, activity::requestMic),
        Access("keyboard", stringResource(R.string.group_keyboard), keyboard, stringResource(if (keyboard) R.string.on else R.string.off), stringResource(R.string.reason_keyboard), stringResource(R.string.turn_on), activity::openKeyboardSettings, activity::openKeyboardSettings),
        Access("hand", stringResource(R.string.floating_icons), dots, stringResource(if (dots) R.string.access_dots_on else R.string.off), stringResource(R.string.reason_dots), stringResource(R.string.turn_on), activity::openAccessibilitySettings) { disclosure = true },
        Access(
            "image",
            stringResource(R.string.images),
            images,
            stringResource(
                when {
                    images -> R.string.access_photos_allowed
                    activity.photosPartial() -> R.string.photos_needed
                    offer -> R.string.not_allowed
                    else -> R.string.off
                },
            ),
            stringResource(R.string.reason_images),
            stringResource(R.string.allow),
            activity::openAppSettings,
        ) {
            offer = true
            activity.requestPhotos()
        },
    )

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val search = remember { Animatable(if (searching) 1f else 0f) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(searching) {
        if (searching) {
            focus.requestFocus()
            search.animateTo(1f, tween(SEARCH_OPEN_MS, easing = Motion.emphasizedDecelerate))
        } else {
            focusManager.clearFocus()
            search.animateTo(0f, tween(SEARCH_CLOSE_MS, easing = Motion.emphasizedAccelerate))
            query = ""
        }
    }
    BackHandler(enabled = searching) { searching = false }

    // The usage guide, once, a moment after setup ends.
    var guide by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (prefs.guidePending) {
            delay(GUIDE_DELAY_MS)
            prefs.guidePending = false
            guide = true
        }
    }

    val p = search.value
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
    ) {
        TitleCard(
            appName = activity.applicationInfo.loadLabel(activity.packageManager).toString(),
            level = TANK_LEVELS[access.count { it.passed }],
            status = stringResource(status),
            p = p,
            searching = searching,
            query = query,
            onQuery = { query = it },
            onOpen = { searching = true },
            focus = focus,
        )
        Box {
            if (p < 1f) {
                Column(
                    Modifier.graphicsLayer {
                        alpha = (1f - 2f * p).coerceIn(0f, 1f)
                        translationY = -16.dp.toPx() * p
                    },
                ) {
                    AccessGroup(access)
                    Group {
                        NavRow(stringResource(R.string.group_account), icon = "user-circle", titleWeight = Fonts.ROW) { nav.go(Screen.ACCOUNT) }
                        Divider()
                        NavRow(stringResource(R.string.pro), icon = "waveei", value = if (activity.paymentFailed()) stringResource(R.string.payment_failed) else null, titleWeight = Fonts.ROW) { nav.go(Screen.PLAN) }
                        Divider()
                        NavRow(stringResource(R.string.buffer_title), icon = "clock-counter-clockwise", titleWeight = Fonts.ROW) { nav.go(Screen.BUFFER) }
                        Divider()
                        NavRow(stringResource(R.string.words), icon = "book-open-text", titleWeight = Fonts.ROW) { nav.go(Screen.WORDS) }
                        Divider()
                        NavRow(stringResource(R.string.styles_translation), icon = "sparkle", titleWeight = Fonts.ROW) { nav.go(Screen.STYLES) }
                        Divider()
                        NavRow(stringResource(R.string.settings), icon = "gear-six", titleWeight = Fonts.ROW) { nav.go(Screen.SETTINGS) }
                    }
                    Group {
                        // About the app rather than dictation, so a group of their own under the daily rows, drawn the same.
                        NavRow(stringResource(R.string.privacy), icon = "shield-check", titleWeight = Fonts.ROW) { nav.go(Screen.PRIVACY) }
                        Divider()
                        NavRow(stringResource(R.string.feedback), icon = "paper-plane-tilt", titleWeight = Fonts.ROW) { nav.go(Screen.FEEDBACK) }
                        Divider()
                        NavRow(stringResource(R.string.about), icon = "info", titleWeight = Fonts.ROW) { nav.go(Screen.ABOUT) }
                    }
                    BottomSpace()
                }
            }
            if (p > 0f) {
                Column(
                    Modifier.graphicsLayer {
                        alpha = ((p - 0.35f) / 0.5f).coerceIn(0f, 1f)
                        translationY = 12.dp.toPx() * (1f - p)
                    },
                ) {
                    if (query.isNotBlank()) SearchResults(settingsItems(activity, nav) + homeItems(activity, nav), query)
                    BottomSpace()
                }
            }
        }
    }
    if (disclosure) {
        DotDisclosure(onAgree = {
            prefs.floatingOn = true
            activity.openAccessibilitySettings()
        }) { disclosure = false }
    }
    if (guide) UsageGuide { guide = false }
    var terms by rememberPref({ prefs.termsAccepted }) { prefs.termsAccepted = it }
    if (prefs.onboardingDone && terms < MainActivity.TERMS_VERSION) TermsDialog(activity) { terms = MainActivity.TERMS_VERSION }
}

/**
 * Changed Terms, asked once on Home: "Read terms" opens them and leaves the question open; only Agree closes it, so
 * there is no Cancel and a tap outside does nothing. The title says what happened, the text what to do; Agree is the
 * one filled button.
 */
@Composable
private fun TermsDialog(activity: MainActivity, onAgree: () -> Unit) {
    DialogCard({}) {
        DialogTitle(stringResource(R.string.terms_updated))
        DialogText(stringResource(R.string.terms_updated_body))
        DialogButtons(stringResource(R.string.read_terms), stringResource(R.string.agree), onDismiss = { activity.openUrl(MainActivity.TERMS) }) {
            DebugLog.add("home: terms ${MainActivity.TERMS_VERSION} accepted")
            onAgree()
        }
    }
}

/** What one Access row is about: whether it passes, its status, the reason it is needed and the one fix. */
/** One permission: [fix] asks for it while it is missing; once given, a tap on the row opens its system [settings]. */
private class Access(val glyph: String, val name: String, val passed: Boolean, val status: String, val reason: String, val action: String, val settings: () -> Unit, val fix: () -> Unit)

/**
 * The tank as the title card: Waveei, the status, and the search button that grows into the field:
 * at [p] = 1 the card is 78 dp, holding only the field.
 */
@Composable
private fun TitleCard(appName: String, level: Float, status: String, p: Float, searching: Boolean, query: String, onQuery: (String) -> Unit, onOpen: () -> Unit, focus: FocusRequester) {
    val c = appColors
    BoxWithConstraints(
        Modifier
            // As much room over the card as under the last group (BottomSpace): the screen breathes at both ends.
            .padding(start = 16.dp, end = 16.dp, top = 24.dp)
            .fillMaxWidth()
            .height(lerp(244.dp, 78.dp, p))
            .then(
                // The hero shadow by day; at night a faint hairline instead.
                if (c.dark) {
                    Modifier.dropShadow(TANK_SHAPE, Shadow(radius = 0.dp, color = TANK_HAIRLINE_NIGHT, spread = 1.dp))
                } else {
                    Modifier
                        .dropShadow(TANK_SHAPE, Shadow(radius = 2.dp, color = Color(20, 40, 80).copy(alpha = 0.04f), offset = DpOffset(0.dp, 1.dp)))
                        .dropShadow(TANK_SHAPE, Shadow(radius = 30.dp, color = Color(20, 60, 140).copy(alpha = 0.22f), spread = (-20).dp, offset = DpOffset(0.dp, 10.dp)))
                },
            )
            .clip(TANK_SHAPE)
            .background(c.box),
    ) {
        HomeTank(
            level,
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = (1f - 1.4f * p).coerceIn(0f, 1f)
                    translationY = 90.dp.toPx() * p
                },
        )
        Column(
            Modifier
                .padding(start = 20.dp, end = 20.dp, top = 18.dp)
                .graphicsLayer {
                    alpha = (1f - 2.5f * p).coerceIn(0f, 1f)
                    translationY = -8.dp.toPx() * p
                },
        ) {
            Text(appName, style = type(28f, 36f, Fonts.TITLE, letterSpacing = -0.3f), color = c.ink)
            // The status changes in place: the old line rises out, the new one in.
            AnimatedContent(
                status,
                transitionSpec = {
                    (fadeIn(tween(STATUS_MS, easing = Motion.standard)) + slideInVertically(tween(STATUS_MS, easing = Motion.standard)) { it / 6 }) togetherWith
                        (fadeOut(tween(STATUS_MS, easing = Motion.standard)) + slideOutVertically(tween(STATUS_MS, easing = Motion.standard)) { -it / 6 })
                },
                label = "status",
            ) { Text(it, style = type(16f, 24f), color = c.ink2, modifier = Modifier.padding(top = 2.dp)) }
        }
        // The button at the top right, 36 dp, grows into the 46 dp field across the card.
        val full = maxWidth - 28.dp
        val width = lerp(36.dp, full, p)
        val height = lerp(36.dp, 46.dp, p)
        Box(
            Modifier
                .offset { IntOffset(lerp((maxWidth - 14.dp - 36.dp).roundToPx().toFloat(), 14.dp.roundToPx().toFloat(), p).toInt(), 16.dp.roundToPx()) }
                .size(width, height)
                .clip(CircleShape)
                .background(lerpColor(c.searchButton, c.searchField, p))
                // Closed, the field is out of TalkBack's and the Tab key's reach: the button below opens it.
                .then(if (searching) Modifier else Modifier.clearAndSetSemantics { }),
            contentAlignment = Alignment.CenterStart,
        ) {
            SearchField(
                query,
                onQuery,
                stringResource(R.string.search),
                Modifier
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .width(full)
                    .focusProperties { canFocus = searching },
                fill = Color.Transparent,
                focusRequester = focus,
                startPadding = lerp(8.5.dp, 14.dp, p),
            )
        }
        // The closed button's tap, 48 dp around its 36 dp circle (Material's minimum touch target), named for TalkBack.
        if (!searching) {
            val label = stringResource(R.string.search)
            Box(
                Modifier
                    .offset { IntOffset((maxWidth - 14.dp - 36.dp - SEARCH_REACH).roundToPx(), (16.dp - SEARCH_REACH).roundToPx()) }
                    .size(36.dp + SEARCH_REACH * 2)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onOpen)
                    .semantics { contentDescription = label },
            )
        }
    }
}

/**
 * Access: a header with four ticks, then a row for each thing still off, with its one button and, under the caret, why
 * it is needed. A tap on the header shows all four; one that passes leaves its row.
 */
@Composable
private fun AccessGroup(access: List<Access>) {
    val c = appColors
    var expanded by rememberSaveable { mutableStateOf(false) }
    // For TalkBack the ticks are a count, and the header says whether the list is open.
    val count = stringResource(R.string.access_count, access.count { it.passed }, access.size)
    val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Group {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { expanded = !expanded }
                .semantics { stateDescription = state }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.home_access), style = type(16f, 24f, Fonts.MEDIUM), color = c.ink, modifier = Modifier.weight(1f))
            AnimatedVisibility(!expanded, enter = fadeIn(tween(EXPAND_MS / 3)), exit = fadeOut(tween(COLLAPSE_MS / 3))) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .semantics { contentDescription = count },
                ) {
                    access.forEach { HeaderTick(it.passed) }
                }
            }
            Caret(expanded, 20.dp, c.chev, if (expanded) EXPAND_MS else COLLAPSE_MS)
        }
        // Only the first missing step is filled: where to start; the rest are tonal.
        val next = access.indexOfFirst { !it.passed }
        access.forEachIndexed { k, item -> AccessRow(item, expanded, k, k == next) }
    }
}

/** A header tick: when it fills, it swells to 1.18 and back. */
@Composable
private fun HeaderTick(passed: Boolean) {
    val scale = remember { Animatable(1f) }
    var was by remember { mutableStateOf(passed) }
    LaunchedEffect(passed) {
        if (passed && !was) scale.animateTo(1f, keyframes { durationMillis = 320; 1.18f at 160 })
        was = passed
    }
    Tick(passed, Modifier.scale(scale.value))
}

@Composable
private fun AccessRow(item: Access, expanded: Boolean, k: Int, first: Boolean) {
    val c = appColors
    // Just granted: the row stays a moment with its tick, then leaves.
    var lingering by remember { mutableStateOf(false) }
    var was by remember { mutableStateOf(item.passed) }
    LaunchedEffect(item.passed) {
        if (item.passed && !was) {
            lingering = true
            delay(GRANTED_STAY_MS)
            lingering = false
        }
        was = item.passed
    }
    var reason by rememberSaveable { mutableStateOf(false) }
    AnimatedVisibility(
        expanded || !item.passed || lingering,
        enter = expandVertically(tween(EXPAND_MS, easing = Motion.standard)) + fadeIn(tween(EXPAND_MS * 6 / 10, delayMillis = EXPAND_MS * (15 + 10 * k) / 100)),
        exit = shrinkVertically(tween(GRANTED_LEAVE_MS, easing = Motion.emphasizedDecelerate)) + fadeOut(tween(GRANTED_LEAVE_MS * 6 / 10)),
    ) {
        Column {
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Glyph(item.glyph, 22.dp, c.icon)
                // The name and status are the reason's toggle, the caret after the status: one tap target the size of
                // the text, not a 13 dp caret on a line of its own.
                val why = stringResource(R.string.why_needed)
                Column(
                    Modifier
                        .weight(1f)
                        .then(if (!item.passed) Modifier.clickable(onClickLabel = why) { reason = !reason } else Modifier.clickable(onClick = item.settings)),
                ) {
                    Text(item.name, style = type(16f, 24f, Fonts.MEDIUM), color = c.ink)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.status, style = type(14f, 20f), color = c.ink2)
                        if (!item.passed) Caret(reason, 14.dp, c.chev, REASON_MS)
                    }
                }
                // The button turns into the tick once granted.
                Trailing {
                    AnimatedContent(
                        item.passed,
                        transitionSpec = { fadeIn(tween(GRANT_MS)) togetherWith fadeOut(tween(GRANT_MS * 3 / 10)) },
                        label = "grant",
                    ) { passed ->
                        if (passed) {
                            Tick(true)
                        } else {
                            Box(
                                Modifier
                                    .minimumInteractiveComponentSize()
                                    // At least 92 × 36 dp, wider for a larger font.
                                    .widthIn(min = 92.dp)
                                    .heightIn(min = 36.dp)
                                    .clip(CircleShape)
                                    .background(if (first) c.blue else c.blueBox)
                                    .clickable(role = Role.Button, onClick = item.fix)
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(item.action, style = type(14f, 20f, Fonts.SEMIBOLD), color = if (first) Color.White else c.tonalInk, maxLines = 1) }
                        }
                    }
                }
            }
            AnimatedVisibility(
                reason && !item.passed,
                enter = expandVertically(tween(REASON_MS, easing = Motion.standard)) + fadeIn(tween(REASON_MS, delayMillis = REASON_MS / 5)),
                exit = shrinkVertically(tween(REASON_MS, easing = Motion.standard)) + fadeOut(tween(REASON_MS / 2)),
            ) {
                Text(
                    item.reason,
                    style = type(14f, 20f, Fonts.MEDIUM),
                    color = c.ink2,
                    modifier = Modifier
                        .padding(start = 52.dp, end = 16.dp, bottom = 12.dp)
                        .offset(y = (-2).dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.page)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private fun lerp(start: Dp, stop: Dp, fraction: Float): Dp = start + (stop - start) * fraction

private val TANK_SHAPE = RoundedCornerShape(28.dp)
/** How far the search button's touch area reaches past its 36 dp circle: to 48 dp. */
private val SEARCH_REACH = 6.dp
private val TANK_HAIRLINE_NIGHT = Color(150, 180, 230).copy(alpha = 0.06f)
private const val SEARCH_OPEN_MS = 320
private const val SEARCH_CLOSE_MS = 250
private const val STATUS_MS = 350
private const val EXPAND_MS = 360
private const val COLLAPSE_MS = 260
private const val REASON_MS = 220
private const val GRANT_MS = 300
private const val GRANTED_STAY_MS = 900L
private const val GRANTED_LEAVE_MS = 300
private const val GUIDE_DELAY_MS = 5000L
