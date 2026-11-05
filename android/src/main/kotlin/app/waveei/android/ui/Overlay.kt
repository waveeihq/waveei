package app.waveei.android.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.popup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Pop-ups drawn inside the app's own window, over the screens: dialogs, menus and the usage guide. Being in the same
 * window, a pop-up can blur what is behind it (veil glass) and nothing has to be dimmed.
 */
class Overlays {
    internal val entries = mutableStateListOf<Entry>()

    /** One pop-up: [shown] turns false when its caller drops it, and [gone] takes it away once it has animated out. */
    internal class Entry(val content: @Composable (shown: Boolean, gone: () -> Unit) -> Unit) {
        var shown by mutableStateOf(true)
    }
}

private val LocalOverlays = staticCompositionLocalOf<Overlays?> { null }

/** What the screens drew in the last frame, for the veil glass to blur. */
private val LocalBackdrop = staticCompositionLocalOf<GraphicsLayer?> { null }

/** The app's content with its pop-ups over it. */
@Composable
fun OverlayHost(content: @Composable () -> Unit) {
    val overlays = remember { Overlays() }
    val backdrop = rememberGraphicsLayer()
    CompositionLocalProvider(LocalOverlays provides overlays, LocalBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize()) {
            // While a pop-up is up, TalkBack stays in it: the screens under it leave the accessibility tree.
            val modal = overlays.entries.any { it.shown }
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (modal) Modifier.clearAndSetSemantics {} else Modifier)
                    .drawWithContent {
                        backdrop.record { this@drawWithContent.drawContent() }
                        drawLayer(backdrop)
                    },
            ) { content() }
            overlays.entries.forEach { entry ->
                key(entry) { entry.content(entry.shown) { overlays.entries.remove(entry) } }
            }
        }
    }
}

/**
 * Shows [content] over the screens for as long as this is composed. [content] gets whether it is still wanted, and
 * calls the second argument when it has animated out.
 */
@Composable
fun Overlay(content: @Composable (shown: Boolean, gone: () -> Unit) -> Unit) {
    val host = LocalOverlays.current
    val latest by rememberUpdatedState(content)
    if (host == null) {
        // Composed outside the host (a screen on its own in a test): drawn in place.
        Box(Modifier.fillMaxSize()) { latest(true) {} }
        return
    }
    val entry = remember { Overlays.Entry { shown, gone -> latest(shown, gone) } }
    DisposableEffect(host) {
        host.entries += entry
        onDispose { entry.shown = false }
    }
}

/**
 * A dialog: veil glass in the middle, inset 24 dp, over the guide's veil. A tap beside it or Back dismisses it. It fades
 * in and grows from 0.96 (200 ms, emphasized decelerate) and fades out in 150 ms. It stays above the
 * system bars and the keyboard, and scrolls where it does not fit, so its buttons are always in reach.
 */
@Composable
fun VeilDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Overlay { shown, gone ->
        val progress = remember { Animatable(0f) }
        LaunchedEffect(shown) {
            if (shown) {
                progress.animateTo(1f, tween(DIALOG_IN_MS, easing = Motion.emphasizedDecelerate))
            } else {
                progress.animateTo(0f, tween(DIALOG_OUT_MS, easing = Motion.emphasizedAccelerate))
                gone()
            }
        }
        BackHandler(enabled = shown, onBack = onDismiss)
        val c = appColors
        Box(
            Modifier
                .fillMaxSize()
                // The screen behind under the guide's veil, so the dialog reads as one card above it.
                .drawBehind { drawRect(c.scrimGuide.copy(alpha = c.scrimGuide.alpha * progress.value)) }
                .pointerInput(shown) { if (shown) detectTapGestures { onDismiss() } }
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
            contentAlignment = Alignment.Center,
        ) {
            VeilSurface(
                DIALOG_SHAPE,
                Modifier
                    .padding(24.dp)
                    .graphicsLayer {
                        val p = progress.value
                        alpha = p
                        scaleX = 0.96f + 0.04f * p
                        scaleY = scaleX
                        // Alpha on each drawing, not on an offscreen copy the size of the card that would cut off its shadow.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    // Taps on the card stay on it.
                    .pointerInput(Unit) { detectTapGestures {} }
                    .semantics {
                        dialog()
                        isTraversalGroup = true
                    },
            ) { Box(Modifier.verticalScroll(rememberScrollState())) { content() } }
        }
    }
}

/**
 * Veil glass: what is behind blurred by 24 dp and saturated 1.6×, white 78 % (night navy 80 %) over it, an inner
 * hairline and the minimal shadow. Where the window cannot blur (drawn in software), the tint
 * is the nearly opaque veil instead and nothing behind is drawn, as the keyboard's veil does, so the text behind never
 * runs through the card's own.
 */
@Composable
fun VeilSurface(shape: Shape, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = appColors
    val backdrop = LocalBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .then(
                if (c.dark) {
                    Modifier.dropShadow(shape, Shadow(radius = 14.dp, color = Color.Black.copy(alpha = 0.22f), offset = DpOffset(0.dp, 4.dp)))
                } else {
                    // The outer ring crisp (no blur), then the soft shadow.
                    Modifier
                        .dropShadow(shape, Shadow(radius = 0.dp, color = INK.copy(alpha = 0.07f), spread = 1.dp))
                        .dropShadow(shape, Shadow(radius = 14.dp, color = INK.copy(alpha = 0.07f), offset = DpOffset(0.dp, 4.dp)))
                },
            ),
    ) {
        if (backdrop != null) {
            // Blurred over a margin beyond the card, then cut to its shape: the blur's edge never shows on the card.
            Box(
                Modifier
                    .matchParentSize()
                    .clip(shape),
            ) {
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val margin = BLUR_MARGIN.roundToPx()
                            val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + 2 * margin, constraints.maxHeight + 2 * margin))
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-margin, -margin) }
                        }
                        .graphicsLayer { renderEffect = veilEffect(24.dp.toPx()) }
                        .drawBehind {
                            val margin = BLUR_MARGIN.toPx()
                            translate(margin - origin.x, margin - origin.y) { drawLayer(backdrop) }
                        },
                )
            }
        }
        Box(
            Modifier
                .matchParentSize()
                // Decided as it draws: a canvas drawn in software (a screenshot of the view) cannot run the blur,
                // so there the tint is the opaque veil (as Tank's still water).
                .drawBehind {
                    val soft = backdrop == null || !drawContext.canvas.nativeCanvas.isHardwareAccelerated
                    drawOutline(shape.createOutline(size, layoutDirection, this), if (soft) c.veilOpaque else c.veil)
                }
                .clip(shape)
                .innerShadow(shape, Shadow(radius = 0.dp, spread = 1.dp, color = if (c.dark) HAIRLINE_NIGHT else Color.White.copy(alpha = 0.7f))),
        )
        content()
    }
}

/** A menu entry: its glyph, its text, red for what removes something. */
class MenuItem(val glyph: String, val text: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * A menu of veil glass hanging from [anchor] (in the window), its right edge at the anchor's: it grows from its
 * top-right corner, 0.86 → 1 in 180 ms, and its items rise in one after another. A tap beside it closes it.
 */
@Composable
fun VeilMenu(anchor: Rect, items: List<MenuItem>, onDismiss: () -> Unit) {
    Overlay { shown, gone ->
        val c = appColors
        val progress = remember { Animatable(0f) }
        LaunchedEffect(shown) {
            if (shown) {
                progress.animateTo(1f, tween(MENU_IN_MS, easing = Motion.standard))
            } else {
                progress.animateTo(0f, tween(MENU_OUT_MS, easing = Motion.accelerate))
                gone()
            }
        }
        BackHandler(enabled = shown, onBack = onDismiss)
        val density = LocalDensity.current
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .pointerInput(shown) { if (shown) detectTapGestures { onDismiss() } },
        ) {
            // Rows grow with a larger font: once drawn, the menu's own height places it.
            var drawn by remember { mutableIntStateOf(0) }
            val (x, y, below) = with(density) {
                val height = maxOf((12.dp + MENU_ROW * items.size).toPx(), drawn.toFloat()).toDp()
                val right = maxWidth.toPx() - 16.dp.toPx()
                val x = (anchor.right - MENU_WIDTH.toPx()).coerceIn(16.dp.toPx(), right - MENU_WIDTH.toPx())
                // Below the anchor, or above it where there is no room left under it.
                val below = anchor.bottom + height.toPx() + 16.dp.toPx() <= maxHeight.toPx()
                Triple(x, if (below) anchor.bottom else anchor.top - height.toPx(), below)
            }
            VeilSurface(
                MENU_SHAPE,
                Modifier
                    .offset { IntOffset(x.toInt(), y.toInt()) }
                    .width(MENU_WIDTH)
                    .graphicsLayer {
                        val p = progress.value
                        alpha = (1.8f * p).coerceAtMost(1f)
                        val scale = if (shown) 0.86f + 0.14f * p else 0.95f + 0.05f * p
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(1f, if (below) 0f else 1f)
                        // As the dialog: the shadow is not cut off while the menu fades.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .pointerInput(Unit) { detectTapGestures {} }
                    .onSizeChanged { drawn = it.height }
                    .semantics { popup() },
            ) {
                Column(Modifier.padding(6.dp)) {
                    items.forEachIndexed { i, item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = MENU_ROW)
                                .graphicsLayer {
                                    // Each item 30 + 20·i ms in, for 100 ms, of the menu's 180.
                                    val t = ((progress.value * MENU_IN_MS - 30 - 20 * i) / 100f).coerceIn(0f, 1f)
                                    alpha = if (shown) t else 1f
                                    translationY = if (shown) 5.dp.toPx() * (1f - t) else 0f
                                }
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onDismiss()
                                    item.onClick()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Glyph(item.glyph, 20.dp, if (item.destructive) c.red else c.glyph)
                            // A larger font wraps to a second line, then ends in an ellipsis, never cut.
                            Text(item.text, style = type(16f, 24f, Fonts.MEDIUM), color = if (item.destructive) c.red else c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** The blur and the saturation of veil glass, in one effect. */
private fun veilEffect(radius: Float) = RenderEffect.createChainEffect(
    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.6f) })),
    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
).asComposeRenderEffect()

private val INK = Color(0xFF121821)
private val BLUR_MARGIN = 24.dp
private val HAIRLINE_NIGHT = Color(0x1F96B4E6)
private val DIALOG_SHAPE = RoundedCornerShape(28.dp)
private const val DIALOG_IN_MS = 200
private const val DIALOG_OUT_MS = 150
private val MENU_SHAPE = RoundedCornerShape(16.dp)
private val MENU_WIDTH = 200.dp
/** A menu row: a full 48 dp touch target, as the keyboard's and the dots' menus. */
private val MENU_ROW = 48.dp
private const val MENU_IN_MS = 180
private const val MENU_OUT_MS = 120
