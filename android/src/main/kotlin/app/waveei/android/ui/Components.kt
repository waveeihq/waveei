package app.waveei.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error as fieldError
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.waveei.android.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A Phosphor glyph in [tint]. */
@Composable
fun Glyph(name: String, size: Dp, tint: Color, modifier: Modifier = Modifier, description: String? = null) =
    Icon(Icons.vector(name), contentDescription = description, tint = tint, modifier = modifier.size(size))

/** The screen's bar: back arrow, title, and an optional text action on the right. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, action: (@Composable RowScope.() -> Unit)? = null) {
    val c = appColors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Glyph("arrow-left", 24.dp, c.glyph, description = stringResource(R.string.back)) }
        Text(title, style = type(22f, 28f, Fonts.SEMIBOLD), color = c.ink, modifier = Modifier
            .weight(1f)
            .padding(start = 2.dp)
            .semantics { heading() })
        action?.invoke(this)
    }
}

/** A text button in the accent (its darker shade by day, 5.4:1 on the page): the bar's action, Check, Send, Remove; its touch area at least 48 dp. */
@Composable
fun TextAction(text: String, enabled: Boolean = true, color: Color = appColors.tonalInk, onClick: () -> Unit) {
    Text(
        text,
        style = type(16f, 24f, Fonts.SEMIBOLD),
        color = if (enabled) color else appColors.ink3,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** [GHOST_RED]: a ghost pill for what removes something at once (the log's Clear). */
enum class PillKind { FILLED, GHOST, GHOST_RED, RED, TONAL }

/**
 * A 36 dp pill, one size and type for every kind: Add, Save replacement, the dialogs' confirm; grey while it
 * does not apply. It is drawn 36 dp tall, its touch area is 48 dp (Material's minimum), overlapping the row around it.
 */
@Composable
fun PillButton(text: String, enabled: Boolean = true, kind: PillKind = PillKind.FILLED, icon: String? = null, modifier: Modifier = Modifier, wrap: Boolean = false, onClick: () -> Unit) {
    val c = appColors
    val (fill, ink) = when {
        !enabled -> c.buttonOff to c.buttonOffInk
        kind == PillKind.FILLED -> c.blue to Color.White
        kind == PillKind.RED -> c.redFill to Color.White
        kind == PillKind.TONAL -> c.blueBox to c.tonalInk
        kind == PillKind.GHOST_RED -> Color.Transparent to c.red
        else -> Color.Transparent to c.acc
    }
    val ghost = kind == PillKind.GHOST || kind == PillKind.GHOST_RED
    Row(
        modifier
            .minimumInteractiveComponentSize()
            // [wrap]: a label too long for the width goes on to a second line, the pill growing with it, never cut.
            .then(if (wrap) Modifier.heightIn(min = 36.dp) else Modifier.height(36.dp))
            .clip(if (wrap) RoundedCornerShape(18.dp) else CircleShape)
            .background(fill)
            .then(if (ghost && enabled) Modifier.border(1.dp, c.line2, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Glyph(icon, 14.dp, ink)
        Text(
            text,
            style = type(14f, 20f, Fonts.SEMIBOLD),
            color = ink,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            textAlign = TextAlign.Center,
            modifier = if (wrap) Modifier.padding(vertical = 9.dp) else Modifier,
        )
    }
}

/** A group label: small caps over a card. */
@Composable
fun GroupLabel(text: String, top: Dp = 24.dp) = Text(
    text.uppercase(),
    style = type(14f, 20f, Fonts.TITLE, letterSpacing = 0.6f),
    color = appColors.ink2,
    modifier = Modifier
        .padding(start = 32.dp, end = 32.dp, top = top)
        .semantics { heading() },
)

/** A rounded group of rows, a card, under an optional label. */
@Composable
fun Group(title: String? = null, top: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) GroupLabel(title)
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = if (title != null) 8.dp else top)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(appColors.box),
        content = content,
    )
}

/** The line between two rows of a group. */
@Composable
fun Divider() = Box(
    Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(appColors.line),
)

/** From this font scale on, a row's side parts move under its words, so no word is broken to make room. */
const val LARGE_FONT = 1.3f

/** The 13 sp note under a group. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) =
    Text(text, style = type(14f, 20f), color = appColors.ink2, modifier = modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp))

/**
 * A row: an optional icon, the title (15 sp, medium) with its description under it, and what sits on the right. A row
 * that does not apply right now is there but greyed out.
 */
@Composable
fun SettingRow(
    title: String,
    description: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    icon: String? = null,
    titleColor: Color = appColors.ink,
    descriptionColor: Color = appColors.ink2,
    titleWeight: Int = Fonts.MEDIUM,
    /** For a switch's or a checkbox's row: its state, so TalkBack reads the row as one ([role]), on or off. */
    toggled: Boolean? = null,
    role: Role = Role.Switch,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = appColors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                when {
                    onClick == null -> Modifier
                    toggled != null -> Modifier.toggleable(toggled, enabled = enabled, role = role) { onClick() }
                    else -> Modifier.clickable(enabled = enabled, onClick = onClick)
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Greyed out, only the title and the controls dim: the description says why, so it stays readable.
        val dim = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA)
        if (icon != null) Box(dim) { Glyph(icon, 22.dp, c.icon) }
        Column(Modifier.weight(1f)) {
            Text(title, style = type(16f, 24f, titleWeight), color = titleColor, modifier = dim)
            if (description != null) Text(description, style = type(14f, 20f), color = descriptionColor, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) Box(dim) { Trailing(trailing) }
    }
}

/**
 * What sits at a row's end, no taller than a pill: a pill's 48 dp touch area reaches into the row's padding rather
 * than making the row taller, so rows keep their 56 dp rhythm.
 */
@Composable
fun Trailing(content: @Composable RowScope.() -> Unit) = Row(
    Modifier.heightIn(max = 36.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    content = content,
)

/** The caret of a row that opens something. */
@Composable
fun Chevron() = Glyph("caret-right", 18.dp, appColors.chev)

/** A row that opens another screen. */
@Composable
fun NavRow(
    title: String,
    description: String? = null,
    icon: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    titleWeight: Int = Fonts.MEDIUM,
    onClick: () -> Unit,
) {
    // A large font moves the value under the title, so the title is never broken inside a word to make room for it.
    val below = value != null && LocalDensity.current.fontScale >= LARGE_FONT
    SettingRow(title, if (below) listOfNotNull(description, value).joinToString("\n") else description, enabled, onClick = onClick, icon = icon, titleWeight = titleWeight) {
        if (value != null && !below) Text(value, style = type(14f, 20f), color = appColors.ink2, maxLines = 1)
        Chevron()
    }
}

/** A row whose action happens elsewhere (system settings, a file): its glyph on the right. */
@Composable
fun ActionRow(title: String, description: String? = null, glyph: String, enabled: Boolean = true, onClick: () -> Unit) {
    SettingRow(title, description, enabled, onClick = onClick) { Glyph(glyph, 20.dp, appColors.icon) }
}

@Composable
fun SwitchRow(title: String, description: String?, checked: Boolean, enabled: Boolean = true, descriptionColor: Color = appColors.ink2, onChange: (Boolean) -> Unit) {
    SettingRow(title, description, enabled, onClick = { onChange(!checked) }, descriptionColor = descriptionColor, toggled = checked) {
        Toggle(checked)
    }
}

/** A row with a checkbox at its end, in a list where several can be picked. */
@Composable
fun CheckRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(title, description, onClick = { onChange(!checked) }, toggled = checked, role = Role.Checkbox) { Check(checked) }
}

/**
 * The switch, 44 × 26 dp; it only shows the state, its row takes the tap. Off, it stays light: the pale track
 * and the white knob, with a hairline ring a shade darker than the track so it does not melt into the card.
 */
@Composable
fun Toggle(checked: Boolean) {
    val c = appColors
    val track by animateColorAsState(if (checked) c.blue else c.switchOff, tween(TOGGLE_MS), label = "track")
    // Off, the outline is the 3:1 grey of what is not on yet, so the switch reads on the white card.
    val ring by animateColorAsState(if (checked) c.blue else c.off, tween(TOGGLE_MS), label = "ring")
    val knob by animateDpAsState(if (checked) 21.dp else 3.dp, tween(TOGGLE_MS, easing = Motion.standard), label = "knob")
    Box(
        Modifier
            .size(44.dp, 26.dp)
            .clip(CircleShape)
            .background(track)
            .border(1.5.dp, ring, CircleShape),
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 3.dp)
                .size(20.dp)
                .dropShadow(CircleShape, Shadow(radius = 2.dp, color = Color.Black.copy(alpha = 0.18f), offset = DpOffset(0.dp, 1.dp)))
                .clip(CircleShape)
                .background(if (checked) Color.White else c.knobOff),
        )
    }
}

@Composable
fun <T> SegmentedRow(title: String, description: String?, options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
    ) {
        Text(title, style = type(16f, 24f, Fonts.MEDIUM), color = appColors.ink)
        if (description != null) Text(description, style = type(14f, 20f), color = appColors.ink2, modifier = Modifier.padding(top = 2.dp))
        Segmented(options, selected, enabled, onSelect)
    }
}

/**
 * Segments in a tonal track; the chosen one sits on a white chip that slides between them. Each segment is 42 dp
 * tall in a 48 dp track, so it is a full touch target (Material's segmented button is 40 dp, its target 48).
 * Tap a segment, or press and slide along the track: the chip follows the finger, ticks as it passes a segment,
 * and settles on the nearest one when let go.
 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    val c = appColors
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val at = remember { Animatable(index.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(index)
    LaunchedEffect(index) { if (!dragging) at.animateTo(index.toFloat(), tween(SEGMENT_MS, easing = Motion.inOut)) }
    BoxWithConstraints(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.segmentTrack)
            .padding(3.dp),
    ) {
        val gap = 4.dp
        val width = (maxWidth - gap * (options.size - 1)) / options.size
        val last = (options.size - 1).toFloat()
        val slide = Modifier.pointerInput(enabled, options.size, width) {
            if (!enabled || options.size < 2) return@pointerInput
            val step = (width + gap).toPx()
            val half = width.toPx() / 2
            fun under(x: Float) = ((x - half) / step).coerceIn(0f, last)
            var near = 0
            detectHorizontalDragGestures(
                onDragStart = { start ->
                    dragging = true
                    near = at.value.roundToInt()
                    scope.launch { at.snapTo(under(start.x)) }
                },
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    val to = under(change.position.x)
                    scope.launch { at.snapTo(to) }
                    if (to.roundToInt() != near) {
                        near = to.roundToInt()
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                    }
                },
                onDragEnd = {
                    val to = at.value.roundToInt()
                    dragging = false
                    scope.launch { at.animateTo(to.toFloat(), tween(SEGMENT_MS, easing = Motion.inOut)) }
                    if (to != current) select(options[to].first)
                },
                onDragCancel = {
                    dragging = false
                    scope.launch { at.animateTo(current.toFloat(), tween(SEGMENT_MS, easing = Motion.inOut)) }
                },
            )
        }
        val shown = if (dragging) at.value.roundToInt() else index
        Box(
            Modifier
                .offset(x = (width + gap) * at.value)
                .size(width, SEGMENT_HEIGHT)
                .then(if (c.dark) Modifier else Modifier.dropShadow(RoundedCornerShape(12.dp), Shadow(radius = 2.dp, color = Color(0x1F102040), offset = DpOffset(0.dp, 1.dp))))
                .clip(RoundedCornerShape(12.dp))
                .background(c.segmentOn)
                // The ring makes the chosen one stand out (3:1 and more) where the white chip alone does not.
                .border(1.5.dp, c.blue, RoundedCornerShape(12.dp)),
        )
        Row(Modifier.selectableGroup().then(slide), horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEachIndexed { i, (value, label) ->
                Box(
                    Modifier
                        .size(width, SEGMENT_HEIGHT)
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(i == index, enabled = enabled, role = Role.RadioButton) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = type(14f, 20f, Fonts.SEMIBOLD), color = if (i == shown) c.ink else c.ink2, maxLines = 1)
                }
            }
        }
    }
}

/** A row that shows the current choice and opens a single-choice dialog. */
@Composable
fun <T> ChoiceRow(title: String, description: String?, options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    NavRow(title, description, value = options.firstOrNull { it.first == selected }?.second.orEmpty(), enabled = enabled) { open = true }
    if (open) ChoiceDialog(title, options, selected, onSelect = onSelect) { open = false }
}

/** A row whose action, hard to undo, runs only once confirmed in a dialog: [question] as its title, [text] under it. */
@Composable
fun ConfirmRow(
    title: String,
    description: String,
    question: String,
    text: String,
    confirm: String,
    enabled: Boolean = true,
    titleColor: Color = appColors.ink,
    destructive: Boolean = true,
    dismiss: String? = null,
    onConfirm: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, description, enabled, onClick = { open = true }, titleColor = titleColor)
    if (open) ConfirmDialog(question, text, confirm, onConfirm = onConfirm, destructive = destructive, dismiss = dismiss) { open = false }
}

@Composable
fun DialogTitle(text: String, color: Color = appColors.ink) = Text(text, style = type(22f, 28f, Fonts.TITLE, letterSpacing = -0.2f), color = color, modifier = Modifier.padding(bottom = 4.dp))

@Composable
fun DialogText(text: String, small: Boolean = false) =
    Text(text, style = if (small) type(14f, 20f) else type(16f, 24f), color = appColors.ink2)

/**
 * A dialog's buttons, on the right: a text button and a filled pill (red for what cannot be undone). Where both do not
 * fit in a row in full (a narrow screen, a large font), they stack, the action on top, as Material 3 does: a label is
 * never cut short ("Cancel subscription" read as "Cancel").
 */
@Composable
fun DialogButtons(dismiss: String?, confirm: String?, destructive: Boolean = false, confirmEnabled: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit = {}) {
    Layout(
        content = {
            if (dismiss != null) TextAction(dismiss, color = if (confirm == null) appColors.acc else appColors.ink2, onClick = onDismiss)
            if (confirm != null) PillButton(confirm, confirmEnabled, if (destructive) PillKind.RED else PillKind.FILLED, wrap = true, onClick = onConfirm)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) { buttons, constraints ->
        val gap = BUTTON_GAP.roundToPx()
        val width = constraints.maxWidth
        val row = buttons.sumOf { it.maxIntrinsicWidth(constraints.maxHeight) } + gap * (buttons.size - 1) <= width
        val placeables = buttons.map { it.measure(Constraints(maxWidth = width)) }
        if (row) {
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(width, height) {
                var x = width - placeables.sumOf { it.width } - gap * (placeables.size - 1)
                placeables.forEach {
                    it.placeRelative(x, (height - it.height) / 2)
                    x += it.width + gap
                }
            }
        } else {
            // Stacked: the action first, each on the right.
            val order = placeables.reversed()
            layout(width, order.sumOf { it.height }) {
                var y = 0
                order.forEach {
                    it.placeRelative(width - it.width, y)
                    y += it.height
                }
            }
        }
    }
}

private val BUTTON_GAP = 8.dp

/** The dialog's card: title, body and buttons, 12 dp apart. */
@Composable
fun DialogCard(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    VeilDialog(onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/**
 * Confirm something hard to undo: the question as the title, what follows under it, then the way back and a red button
 * (blue where it takes nothing away, as Resubscribe). [dismiss] names the way back where "Cancel" would clash with the
 * action ("Keep Tide" beside "Cancel subscription").
 */
@Composable
fun ConfirmDialog(title: String?, text: String, confirm: String, onConfirm: () -> Unit, destructive: Boolean = true, dismiss: String? = null, onDismiss: () -> Unit) {
    DialogCard(onDismiss) {
        if (title != null) DialogTitle(title)
        DialogText(text)
        DialogButtons(dismiss ?: stringResource(R.string.cancel), confirm, destructive = destructive, onDismiss = onDismiss) {
            onDismiss()
            onConfirm()
        }
    }
}

/** A single-choice dialog: a tap picks and closes. */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    DialogCard(onDismiss) {
        DialogTitle(title)
        Column(
            Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            options.forEach { (value, label) ->
                Option(label, value == selected, Role.RadioButton, onClick = {
                    onSelect(value)
                    onDismiss()
                }) { Radio(value == selected) }
            }
        }
        DialogButtons(stringResource(R.string.close), null, onDismiss = onDismiss)
    }
}

/** A multiple-choice dialog. */
/** An option of a choice dialog: TalkBack reads it as a radio button or a checkbox, with its state. */
@Composable
private fun Option(label: String, chosen: Boolean, role: Role, onClick: () -> Unit, mark: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (role == Role.Checkbox) Modifier.toggleable(chosen, role = role) { onClick() } else Modifier.selectable(chosen, role = role, onClick = onClick)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        mark()
        Text(label, style = type(16f, 24f), color = appColors.ink)
    }
}

@Composable
private fun Radio(on: Boolean) {
    val c = appColors
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(2.dp, if (on) c.blue else c.off, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(c.blue),
        )
    }
}

@Composable
private fun Check(on: Boolean) {
    val c = appColors
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (on) Modifier.background(c.blue) else Modifier.border(2.dp, c.off, RoundedCornerShape(8.dp))),
        contentAlignment = Alignment.Center,
    ) { if (on) Glyph("check", 13.dp, Color.White) }
}

/** A labelled text field: 14 dp radius, a hairline ring, blue while typing in it. */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = true,
    minHeight: Dp = 50.dp,
    inDialog: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    error: String? = null,
) {
    val c = appColors
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        // TalkBack hears the label as the field's name, once, on the field itself.
        if (label != null) Text(label, style = type(14f, 20f, Fonts.SEMIBOLD), color = c.ink2, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp).clearAndSetSemantics {})
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = type(16f, 24f).copy(color = c.ink),
            cursorBrush = SolidColor(c.blue),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = label ?: placeholder
                    if (error != null) fieldError(error)
                },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (inDialog) c.fieldInDialog else c.box)
                        .border(if (focused) 2.dp else 1.dp, if (focused) c.blue else c.line2, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    if (value.isEmpty()) Text(placeholder, style = type(16f, 24f), color = c.ink3)
                    inner()
                }
            },
        )
    }
}

/** A search field: 46 dp pill, the magnifying glass, a 2 dp blue ring while typing. */
@Composable
fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fill: Color = appColors.box,
    focusRequester: FocusRequester? = null,
    startPadding: Dp = 18.dp,
) {
    val c = appColors
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = type(16f, 24f).copy(color = c.ink),
        cursorBrush = SolidColor(c.blue),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .then(if (focused) Modifier.border(2.dp, c.blue, CircleShape) else Modifier)
                    .padding(start = startPadding, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Glyph("magnifying-glass", 20.dp, c.ink3)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = type(16f, 24f), color = c.ink2, maxLines = 1)
                    inner()
                }
            }
        },
    )
}

/** An empty list: a grey glyph, a line and an example. */
@Composable
fun EmptyState(glyph: String, title: String, text: String? = null, top: Dp = 48.dp) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = top),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Glyph(glyph, 36.dp, appColors.offFill)
        Text(title, style = type(16f, 24f, Fonts.SEMIBOLD), color = appColors.ink2, modifier = Modifier.padding(top = 8.dp))
        if (text != null) Text(text, style = type(14f, 20f), color = appColors.ink3, textAlign = TextAlign.Center)
    }
}

/** A green tick, or the empty grey ring of what does not pass yet. */
@Composable
fun Tick(done: Boolean, modifier: Modifier = Modifier) {
    val c = appColors
    Box(
        modifier
            .size(24.dp)
            .clip(CircleShape)
            .then(if (done) Modifier.background(c.green) else Modifier.border(2.dp, c.off, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (done) Glyph("check", 13.dp, Color.White)
    }
}

/** The 8 dp status dot of a History row. */
@Composable
fun StatusDot(color: Color) = Box(
    Modifier
        .size(8.dp)
        .clip(CircleShape)
        .background(color),
)

/** A caret that turns: 90° points down (closed), −90° up (open). */
@Composable
fun Caret(open: Boolean, size: Dp, tint: Color, durationMs: Int) {
    val turn by animateFloatAsState(if (open) -90f else 90f, tween(durationMs, easing = Motion.standard), label = "caret")
    Glyph("caret-right", size, tint, Modifier.rotate(turn))
}

/** A line on a light accent fill (setup, the guide): the privacy line, or a grey note. */
@Composable
fun InfoLine(text: String, glyph: String = "shield-check", grey: Boolean = false, modifier: Modifier = Modifier) {
    val c = appColors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (grey) c.greyNote else c.blueSoft)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(glyph, 16.dp, if (grey) c.icon else c.blue, Modifier.padding(top = 2.dp))
        Text(text, style = type(14f, 20f, Fonts.MEDIUM), color = if (grey) c.greyNoteInk else c.infoInk)
    }
}

/** A thin progress bar in the accent. */
@Composable
fun ProgressLine(progress: Float?, modifier: Modifier = Modifier) {
    val c = appColors
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(c.track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress ?: 1f)
                .height(4.dp)
                .clip(CircleShape)
                .background(if (progress == null) c.blue.copy(alpha = 0.4f) else c.blue),
        )
    }
}

/** A column that keeps its rows a little apart from the bottom edge. */
@Composable
fun BottomSpace() = Spacer(Modifier.height(24.dp))

/** Text that ends in an ellipsis on one line. */
@Composable
fun OneLine(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) =
    Text(text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)

/** How a row that does not apply right now shows: there, but greyed out, at Material's disabled 38 %, as the keyboard. */
const val DISABLED_ALPHA = 0.38f
private const val TOGGLE_MS = 150
private const val SEGMENT_MS = 200
private val SEGMENT_HEIGHT = 42.dp
