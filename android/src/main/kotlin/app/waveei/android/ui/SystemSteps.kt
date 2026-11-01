package app.waveei.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.waveei.android.MainActivity
import app.waveei.android.R
import app.waveei.android.Updater
import kotlin.math.roundToInt

/*
 * Drawn hints for the steps Android takes the person through outside the app: the microphone prompt, Accessibility
 * with the restricted setting, installing an update. Small phones with the system's screens as they look on a recent
 * Android, the place to tap ringed. They are pictures: TalkBack reads the step's text, not them.
 */

/** A step of a list: its number, or a tick once done; the current one in the accent with what to do under it. */
enum class StepState { TODO, NOW, DONE }

@Composable
fun StepItem(number: Int, state: StepState, title: String, detail: String? = null, picture: (@Composable () -> Unit)? = null) {
    val c = appColors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    when (state) {
                        StepState.NOW -> c.blue
                        StepState.DONE -> c.okTile
                        StepState.TODO -> c.track
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (state == StepState.DONE) {
                Glyph("check", 14.dp, c.green)
            } else {
                Text(number.toString(), style = type(13f, 16f, Fonts.SEMIBOLD), color = if (state == StepState.NOW) Color.White else c.ink3)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = type(16f, 22f, if (state == StepState.NOW) Fonts.SEMIBOLD else Fonts.MEDIUM),
                color = if (state == StepState.NOW) c.ink else c.ink2,
            )
            if (state == StepState.NOW) {
                if (detail != null) Text(detail, style = type(14f, 20f), color = c.ink2, modifier = Modifier.padding(top = 2.dp))
                if (picture != null) Box(Modifier.padding(top = 12.dp).clearAndSetSemantics {}) { picture() }
            }
        }
    }
}

/** Two phones, the first's tap leading to the second; drawn smaller where they do not fit side by side. */
@Composable
fun PhonePair(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    BoxWithConstraints {
        val scale = (maxWidth / PAIR_W).coerceAtMost(1f)
        Row(Modifier.scaled(scale), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PAIR_GAP)) {
            first()
            Glyph("arrow-right", 14.dp, appColors.chev)
            second()
        }
    }
}

/** Measured at its own size, drawn and laid out at [scale] of it. */
private fun Modifier.scaled(scale: Float) = layout { measurable, _ ->
    val p = measurable.measure(Constraints())
    val w = (p.width * scale).roundToInt()
    val h = (p.height * scale).roundToInt()
    layout(w, h) {
        p.placeWithLayer((w - p.width) / 2, (h - p.height) / 2) {
            scaleX = scale
            scaleY = scale
        }
    }
}

/**
 * The first update's guide: the two checks Android makes once, unknown sources then Play Protect, drawn. Its action
 * opens the setting and the download goes on by itself on coming back, or starts it where it is allowed already.
 */
@Composable
fun UpdateGuide(activity: MainActivity) {
    @Suppress("UNUSED_VARIABLE") val refresh = activity.refresh
    val allowed = activity.canInstall()
    val install = Updater.install
    val started = install is Updater.Install.Downloading || install == Updater.Install.Installing
    val close = { activity.updateGuide = false }
    DialogCard(close) {
        DialogTitle(stringResource(R.string.update_guide_title))
        DialogText(stringResource(R.string.update_guide_body), small = true)
        Column {
            StepItem(1, if (allowed) StepState.DONE else StepState.NOW, stringResource(R.string.update_guide_allow), stringResource(R.string.update_guide_allow_detail)) {
                PhonePair({ UnknownSourcesPicture(false) }, { UnknownSourcesPicture(true) })
            }
            StepItem(2, if (allowed) StepState.NOW else StepState.TODO, stringResource(R.string.update_guide_tap))
            StepItem(3, if (allowed) StepState.NOW else StepState.TODO, stringResource(R.string.update_guide_scan), stringResource(R.string.update_guide_scan_detail)) {
                PhonePair({ UpdatePromptPicture() }, { PlayProtectScanPicture() })
            }
            if (allowed) {
                Column(Modifier.padding(start = 36.dp, top = 8.dp)) {
                    Text(stringResource(R.string.update_guide_blocked), style = type(16f, 22f, Fonts.SEMIBOLD), color = appColors.ink)
                    Text(stringResource(R.string.update_guide_blocked_detail), style = type(14f, 20f), color = appColors.ink2, modifier = Modifier.padding(top = 2.dp))
                    Box(Modifier.padding(top = 12.dp).clearAndSetSemantics {}) {
                        PhonePair({ PlayProtectBlockedPicture(false) }, { PlayProtectBlockedPicture(true) })
                    }
                }
            }
        }
        if (started) {
            activity.updateStatus?.let { Text(it, style = type(14f, 20f), color = appColors.ink2) }
            DialogButtons(stringResource(R.string.got_it), null, onDismiss = close)
        } else {
            DialogButtons(
                stringResource(R.string.not_now),
                stringResource(if (allowed) R.string.update else R.string.open_settings),
                onDismiss = close,
                onConfirm = activity::continueUpdate,
            )
        }
    }
}

// The phone and the system's screens: fixed light colours, as Android draws its own, whatever the app's theme.
private val FRAME = Color(0xFF1A1E25)
private val SYS_PAGE = Color(0xFFF6F8FD)
private val SYS_CARD = Color.White
private val SYS_INK = Color(0xFF1B1F26)
private val SYS_INK2 = Color(0xFF5F6673)
private val SYS_GREY = Color(0xFFAEB5C0)
private val SYS_ACCENT = Color(0xFF2F5FC4)
private val SYS_TONAL = Color(0xFFDDE5F4)
private val SYS_DIM = Color(0x6B141A24)
private val SYS_DIALOG = Color(0xFFF1F4FA)
private val TAP = Color(0xFF0E70F4)
private val APP_TILE = Brush.verticalGradient(listOf(Color(0xFF5AA7FF), Color(0xFF0E70F4)))
/** The colours a drawn system prompt takes: Android's light ones, and its dark ones for the mic prompt at night. */
private class SysLook(val page: Color, val ink: Color, val accent: Color, val onAccent: Color, val tonal: Color, val dim: Color, val dialog: Color)
private val SYS_LIGHT = SysLook(SYS_PAGE, SYS_INK, SYS_ACCENT, Color.White, SYS_TONAL, SYS_DIM, SYS_DIALOG)
private val SYS_DARK = SysLook(
    page = Color(0xFF111318), ink = Color(0xFFE2E2E9), accent = Color(0xFFADC6FF), onAccent = Color(0xFF002E69),
    tonal = Color(0xFF3E4759), dim = Color(0x99000000), dialog = Color(0xFF282A2F),
)
private val LocalSys = staticCompositionLocalOf { SYS_LIGHT }
private val SHIELD = Brush.linearGradient(listOf(Color(0xFF34A853), Color(0xFF1E8E3E)))

@Composable
fun MiniPhone(width: Dp = PHONE_W, height: Dp = PHONE_H, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .size(width, height)
            .clip(RoundedCornerShape(22.dp))
            .background(FRAME)
            // At night the dark frame would sink into the page: a hairline keeps its edge.
            .border(1.dp, appColors.line2, RoundedCornerShape(22.dp))
            .padding(4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(LocalSys.current.page),
    ) {
        Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(7.dp).clip(CircleShape).background(FRAME))
        content()
    }
}

/** A screen of Settings: ← and its title, then [rows]. */
@Composable
private fun BoxScope.SysScreen(title: String, backTap: Boolean = false, rows: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 9.dp, end = 9.dp, top = 20.dp)) {
        Box {
            Text("←", style = type(12f, 14f), color = SYS_INK)
            if (backTap) TapMark(Modifier.offset((-7).dp, (-5).dp))
        }
        Text(title, style = type(13.5f, 17f, Fonts.MEDIUM), color = SYS_INK, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        rows()
    }
}

@Composable
private fun SysHeader(text: String) =
    Text(text, style = type(7.5f, 10f, Fonts.SEMIBOLD), color = SYS_ACCENT, modifier = Modifier.padding(start = 3.dp, top = 4.dp, bottom = 3.dp))

@Composable
private fun SysCard(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SYS_CARD).padding(vertical = 1.dp), content = content)

@Composable
private fun SysRow(text: String, sub: String? = null, grey: Boolean = false, tile: Boolean = false, switch: Boolean? = null, tap: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(28.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tile) Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(APP_TILE).then(if (grey) Modifier.background(Color(0x99FFFFFF)) else Modifier))
            Column(Modifier.weight(1f)) {
                Text(text, style = type(8.5f, 11f, Fonts.MEDIUM), color = if (grey) SYS_GREY else SYS_INK, maxLines = 1)
                if (sub != null) Text(sub, style = type(7f, 9f), color = if (grey) SYS_GREY else SYS_INK2, maxLines = 1)
            }
            if (switch != null) SysSwitch(switch)
        }
        if (tap) TapMark(Modifier.align(Alignment.CenterEnd).offset(x = (-2).dp))
    }
}

@Composable
private fun SysSwitch(on: Boolean) {
    Box(Modifier.size(22.dp, 12.dp).clip(CircleShape).background(if (on) SYS_ACCENT else Color(0xFFC9D0DA)), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.padding(2.dp).size(8.dp).clip(CircleShape).background(Color.White))
    }
}

/** Where to tap: a ring in the app's blue around a soft fill. */
@Composable
private fun TapMark(modifier: Modifier = Modifier) {
    Box(modifier.size(20.dp).clip(CircleShape).background(TAP.copy(alpha = 0.22f)).border(1.5.dp, TAP, CircleShape))
}

/** The screen dimmed under a system dialog at [top]. */
@Composable
private fun BoxScope.SysDialog(top: Dp, content: @Composable ColumnScope.() -> Unit) {
    val sys = LocalSys.current
    Box(Modifier.fillMaxSize().background(sys.dim))
    Column(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = top, start = 9.dp, end = 9.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(sys.dialog)
            .padding(10.dp),
        content = content,
    )
}

@Composable
private fun SysTitle(text: String) = Text(text, style = type(9.5f, 12.5f, Fonts.MEDIUM), color = SYS_INK)

@Composable
private fun SysText(text: String) = Text(text, style = type(7.5f, 10.5f), color = SYS_INK2, modifier = Modifier.padding(top = 4.dp))

/** A full-width button of a system dialog; [filled] is the one in the accent. */
@Composable
private fun SysButton(text: String, filled: Boolean = false, tap: Boolean = false) {
    Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(22.dp)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(if (filled) LocalSys.current.accent else LocalSys.current.tonal),
            contentAlignment = Alignment.Center,
        ) { Text(text, style = type(8f, 10f, Fonts.MEDIUM), color = if (filled) LocalSys.current.onAccent else LocalSys.current.ink, maxLines = 1) }
        if (tap) TapMark(Modifier.align(Alignment.CenterEnd).offset(x = 4.dp))
    }
}

/** Text buttons at a system dialog's foot, right-aligned; [tapAt] is the one ringed. */
@Composable
private fun SysActions(vararg labels: String, tapAt: Int = -1, spread: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = if (spread) Arrangement.SpaceBetween else Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        labels.forEachIndexed { i, label ->
            Box {
                Text(label, style = type(8.5f, 11f, Fonts.SEMIBOLD), color = if (i == labels.lastIndex && !spread || i == tapAt) SYS_ACCENT else SYS_INK2)
                if (i == tapAt) TapMark(Modifier.align(Alignment.Center).offset(x = 6.dp))
            }
        }
    }
}

@Composable
private fun AppHeader(name: String, icon: Brush = APP_TILE) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.5.dp)).background(icon))
        Text(name, style = type(8f, 10f, Fonts.SEMIBOLD), color = SYS_INK)
    }
}

/** An app behind a dialog: its name as a title. */
@Composable
private fun BoxScope.AppBehind() {
    Text(stringResource(R.string.app_name_plain), style = type(13.5f, 17f, Fonts.MEDIUM), color = LocalSys.current.ink, modifier = Modifier.padding(start = 12.dp, top = 30.dp))
}

// The pictures

/** Android's microphone prompt, "While using the app" ringed; dark at night, as the phone shows it in its dark theme. */
@Composable
fun MicPromptPicture() = CompositionLocalProvider(LocalSys provides if (MaterialTheme.colorScheme.background.luminance() < 0.5f) SYS_DARK else SYS_LIGHT) {
    MiniPhone(160.dp, 300.dp) { MicPrompt() }
}

@Composable
private fun BoxScope.MicPrompt() {
    val ink = LocalSys.current.ink
    AppBehind()
    SysDialog(112.dp) {
        Glyph("microphone", 14.dp, ink, Modifier.align(Alignment.CenterHorizontally))
        Text(stringResource(R.string.sys_mic_question), style = type(9f, 12f, Fonts.MEDIUM), color = ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp))
        SysButton(stringResource(R.string.sys_while_using), filled = true, tap = true)
        SysButton(stringResource(R.string.sys_only_this_time))
        SysButton(stringResource(R.string.sys_dont_allow))
    }
}

/** Accessibility: Waveei grey under the installed apps, ringed. */
@Composable
fun GreyInAccessibilityPicture() = MiniPhone {
    SysScreen(stringResource(R.string.sys_accessibility)) {
        SysHeader(stringResource(R.string.sys_installed_apps))
        SysCard {
            SysRow(stringResource(R.string.sys_select_to_speak), stringResource(R.string.off), tile = false)
            SysRow(stringResource(R.string.app_name_plain), stringResource(R.string.off), grey = true, tile = true, tap = true)
        }
        SysHeader(stringResource(R.string.sys_display))
        SysCard {
            SysRow(stringResource(R.string.sys_display_size))
            SysRow(stringResource(R.string.sys_color_motion))
        }
    }
}

/** "Restricted setting" over Waveei's page, OK ringed. */
@Composable
fun RestrictedDialogPicture() = MiniPhone {
    SysScreen(stringResource(R.string.app_name_plain)) {
        SysCard { SysRow(stringResource(R.string.sys_use_app), grey = true, switch = false) }
    }
    SysDialog(86.dp) {
        SysTitle(stringResource(R.string.sys_restricted_setting))
        SysText(stringResource(R.string.sys_restricted_body))
        SysActions(stringResource(R.string.sys_ok), tapAt = 0)
    }
}

/** App info with its ⋮ ringed; [open], the menu with "Allow restricted settings" ringed. */
@Composable
fun AppInfoMenuPicture(open: Boolean) = MiniPhone {
    Column(Modifier.fillMaxSize().padding(start = 9.dp, end = 9.dp, top = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("←", style = type(12f, 14f), color = SYS_INK)
            Box {
                Text("⋮", style = type(13f, 14f, Fonts.SEMIBOLD), color = SYS_INK)
                if (!open) TapMark(Modifier.offset((-8).dp, (-4).dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(APP_TILE))
            Text(stringResource(R.string.app_name_plain), style = type(10f, 13f, Fonts.MEDIUM), color = SYS_INK, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        SysCard {
            SysRow(stringResource(R.string.sys_notifications))
            SysRow(stringResource(R.string.sys_permissions))
            SysRow(stringResource(R.string.sys_storage))
        }
    }
    if (open) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 30.dp, end = 6.dp)
                .width(104.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White)
                .border(0.5.dp, Color(0x22000000), RoundedCornerShape(9.dp))
                .padding(vertical = 3.dp),
        ) {
            Text(stringResource(R.string.sys_uninstall_all), style = type(8f, 10f, Fonts.MEDIUM), color = SYS_INK, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            Box(Modifier.fillMaxWidth().background(Color(0xFFEAF1FF))) {
                Text(stringResource(R.string.sys_allow_restricted), style = type(8f, 10f, Fonts.MEDIUM), color = SYS_INK, modifier = Modifier.padding(start = 8.dp, end = 18.dp, top = 6.dp, bottom = 6.dp))
                TapMark(Modifier.align(Alignment.CenterEnd).offset(x = 2.dp))
            }
        }
    }
}

/** Waveei's page in Accessibility, its switch ringed. */
@Composable
fun UseServicePicture() = MiniPhone {
    SysScreen(stringResource(R.string.app_name_plain)) {
        SysCard { SysRow(stringResource(R.string.sys_use_app), switch = false, tap = true) }
        Spacer(Modifier.height(6.dp))
        SysCard { SysRow(stringResource(R.string.sys_app_shortcut), switch = false) }
    }
}

/** "Allow Waveei to have full control of your device?", Allow ringed. */
@Composable
fun FullControlPicture() = MiniPhone {
    SysScreen(stringResource(R.string.app_name_plain)) {
        SysCard { SysRow(stringResource(R.string.sys_use_app), switch = false) }
    }
    SysDialog(60.dp) {
        SysTitle(stringResource(R.string.sys_full_control))
        SysButton(stringResource(R.string.allow), filled = true, tap = true)
        SysButton(stringResource(R.string.sys_deny))
    }
}

/** "Install unknown apps" for Waveei: [on] off with the switch ringed, or on with ← ringed. */
@Composable
fun UnknownSourcesPicture(on: Boolean) = MiniPhone {
    SysScreen(stringResource(R.string.sys_install_unknown), backTap = on) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(APP_TILE))
            Text(stringResource(R.string.app_name_plain), style = type(9f, 12f, Fonts.MEDIUM), color = SYS_INK, modifier = Modifier.padding(top = 3.dp))
        }
        SysCard { SysRow(stringResource(R.string.sys_allow_source), switch = on, tap = !on) }
    }
}

/** Android's "Do you want to update this app?", Update ringed. */
@Composable
fun UpdatePromptPicture() = MiniPhone {
    AppBehind()
    SysDialog(150.dp) {
        AppHeader(stringResource(R.string.app_name_plain))
        SysText(stringResource(R.string.sys_update_question))
        SysActions(stringResource(R.string.cancel), stringResource(R.string.update), tapAt = 1)
    }
}

/** Play Protect's offer to scan, Scan app ringed. */
@Composable
fun PlayProtectScanPicture() = MiniPhone {
    AppBehind()
    SysDialog(118.dp) {
        AppHeader(stringResource(R.string.sys_play_protect), SHIELD)
        Spacer(Modifier.height(4.dp))
        SysButton(stringResource(R.string.sys_scan_app), filled = true, tap = true)
        SysButton(stringResource(R.string.sys_dont_install))
    }
}

/** Play Protect's "Unsafe app blocked": More details ringed, or [details] open with Install anyway ringed. */
@Composable
fun PlayProtectBlockedPicture(details: Boolean) = MiniPhone {
    AppBehind()
    SysDialog(118.dp) {
        AppHeader(stringResource(R.string.sys_play_protect), SHIELD)
        Text(stringResource(R.string.sys_unsafe_blocked), style = type(9.5f, 12.5f, Fonts.MEDIUM), color = SYS_INK, modifier = Modifier.padding(top = 6.dp))
        if (details) {
            Box(Modifier.padding(top = 6.dp)) {
                Text(stringResource(R.string.sys_install_anyway), style = type(8.5f, 11f, Fonts.SEMIBOLD), color = SYS_ACCENT)
                TapMark(Modifier.align(Alignment.CenterEnd).offset(x = 18.dp))
            }
            SysActions(stringResource(R.string.sys_ok))
        } else {
            SysActions(stringResource(R.string.sys_more_details), stringResource(R.string.sys_ok), tapAt = 0, spread = true)
        }
    }
}

private val PHONE_W = 128.dp
private val PHONE_H = 256.dp
private val PAIR_GAP = 6.dp
private val PAIR_W = PHONE_W * 2 + PAIR_GAP * 2 + 14.dp
