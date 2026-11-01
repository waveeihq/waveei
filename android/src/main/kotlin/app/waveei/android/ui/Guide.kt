package app.waveei.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.waveei.android.R
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The usage guide: four cards over Home behind the guide's veil, once after setup. It rises in, the cards swipe with
 * their art at 0.55× the text, and it sinks out on Skip, Start talking or Back.
 * [page] is the card it opens on.
 */
@Composable
fun UsageGuide(page: Int = 0, onDone: () -> Unit) {
    Overlay { shown, gone ->
        val c = appColors
        val veil = remember { Animatable(0f) }
        val card = remember { Animatable(0f) }
        // The card's opacity on its own, gentler curve: on the rise's it would be all there in a few frames.
        val fade = remember { Animatable(0f) }
        LaunchedEffect(shown) {
            if (shown) {
                launch { veil.animateTo(1f, tween(VEIL_IN_MS, easing = Motion.standard)) }
                launch { fade.animateTo(1f, tween(CARD_IN_MS, delayMillis = 100, easing = Motion.standard)) }
                card.animateTo(1f, tween(CARD_IN_MS, delayMillis = 100, easing = Motion.emphasizedDecelerate))
            } else {
                launch { card.animateTo(0f, tween(CARD_OUT_MS, delayMillis = 40, easing = Motion.emphasizedAccelerate)) }
                veil.animateTo(0f, tween(VEIL_MS, delayMillis = 80, easing = Motion.standard))
                gone()
            }
        }
        BackHandler(enabled = shown, onBack = onDone)
        val pager = rememberPagerState(page) { PAGES }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(c.scrimGuide.copy(alpha = c.scrimGuide.alpha * veil.value)) }
                // Home stays out of reach while the guide is up.
                .pointerInput(Unit) { detectTapGestures {} }
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            val height = min(CARD_HEIGHT.value, maxHeight.value - 32f).dp
            val top = min(CARD_TOP.value, (maxHeight - height).value - 16f).coerceAtLeast(16f).dp
            // A short card (landscape, a larger display size) shrinks the art, so the words keep their room.
            val art = if (height < SHORT_CARD) height * SHORT_ART_SHARE else ART_HEIGHT
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = top, start = 16.dp, end = 16.dp)
                    .widthIn(max = CARD_MAX_WIDTH)
                    .fillMaxWidth()
                    .height(height)
                    .graphicsLayer {
                        val p = card.value
                        alpha = if (shown) fade.value else p
                        translationY = (if (shown) 56.dp else 24.dp).toPx() * (1f - p)
                        scaleX = if (shown) 0.96f + 0.04f * p else 0.98f + 0.02f * p
                        scaleY = scaleX
                    }
                    .then(cardShadow(c.dark))
                    .clip(CARD_SHAPE)
                    .background(c.box),
            ) {
                HorizontalPager(
                    pager,
                    flingBehavior = PagerDefaults.flingBehavior(
                        pager,
                        snapAnimationSpec = tween(SETTLE_MS, easing = Motion.emphasizedDecelerate),
                        snapPositionalThreshold = 0.25f,
                    ),
                    modifier = Modifier.weight(1f),
                ) { at -> GuidePage(at, (pager.currentPage - at) + pager.currentPageOffsetFraction, art) }
                Footer(pager, onDone)
            }
        }
    }
}

/** One card: its art (moving at 0.55× the text while it swipes), then its words. */
@Composable
private fun GuidePage(page: Int, offset: Float, art: Dp) {
    val c = appColors
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(art)
                .clipToBounds()
                .background(artBackground(page, c.dark)),
            contentAlignment = Alignment.Center,
        ) {
            // Drawn for the design's 379 dp card and centred, so a narrower card crops its sides.
            Box(
                Modifier
                    .requiredSize(CARD_WIDTH, ART_HEIGHT)
                    .graphicsLayer {
                        translationX = offset * size.width * (1f - PARALLAX)
                        scaleX = art / ART_HEIGHT
                        scaleY = scaleX
                    },
            ) {
                when (page) {
                    0 -> WelcomeArt()
                    1 -> KeyboardArt()
                    2 -> HistoryArt()
                    else -> DataArt()
                }
            }
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
        ) {
            when (page) {
                // Every card has its eyebrow, so the titles sit on one line as the cards slide.
                0 -> {
                    Eyebrow(stringResource(R.string.guide_welcome))
                    GuideTitle(stringResource(R.string.guide_welcome_title), top = 8.dp)
                    GuideBody(stringResource(R.string.guide_welcome_body))
                }
                1 -> {
                    Eyebrow(stringResource(R.string.guide_eyebrow_keyboard))
                    GuideTitle(stringResource(R.string.guide_keyboard), top = 8.dp)
                    GuideBody(stringResource(R.string.guide_keyboard_body))
                    Spacer(Modifier.height(10.dp))
                    GuideItem("microphone", stringResource(R.string.guide_keyboard_1))
                    GuideItem("pause-circle", stringResource(R.string.guide_keyboard_2))
                    GuideItem("sparkle", stringResource(R.string.guide_keyboard_3))
                    GuideItem("book-open-text", stringResource(R.string.guide_keyboard_4))
                    GuideItem("image", stringResource(R.string.guide_keyboard_5))
                }
                2 -> {
                    Eyebrow(stringResource(R.string.guide_eyebrow_history))
                    GuideTitle(stringResource(R.string.guide_nothing_lost), top = 8.dp)
                    GuideBody(stringResource(R.string.guide_nothing_lost_body))
                }
                else -> {
                    Eyebrow(stringResource(R.string.guide_eyebrow_privacy))
                    GuideTitle(stringResource(R.string.guide_your_data), top = 8.dp)
                    InfoLine(stringResource(R.string.leaves_phone), modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
                    GuideItem("device-mobile", stringResource(R.string.guide_history_stays))
                    GuideItem("trash", stringResource(R.string.guide_privacy_deletes))
                }
            }
        }
    }
}

/** The small accent line over a card's title. */
@Composable
private fun Eyebrow(text: String) =
    Text(text.uppercase(), style = type(14f, 20f, Fonts.SEMIBOLD, letterSpacing = 0.8f), color = appColors.acc)

@Composable
private fun GuideTitle(text: String, top: Dp = 4.dp) =
    Text(text, style = type(22f, 28f, Fonts.TITLE, letterSpacing = -0.3f), color = appColors.ink, modifier = Modifier.padding(top = top))

@Composable
private fun GuideBody(text: String) = Text(text, style = type(16f, 24f), color = appColors.ink2, modifier = Modifier.padding(top = 10.dp))

/** A line of a card: its glyph in a blue circle, then the words. */
@Composable
private fun GuideItem(glyph: String, text: String) {
    val c = appColors
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(c.blueBox),
            contentAlignment = Alignment.Center,
        ) { Glyph(glyph, 18.dp, c.acc) }
        Text(text, style = type(16f, 24f), color = c.ink, modifier = Modifier.padding(start = 14.dp))
    }
}

/** Page dots, Skip and Next; the last card has Done instead, which closes the guide. */
@Composable
private fun Footer(pager: PagerState, onDone: () -> Unit) {
    val c = appColors
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES - 1
    Row(
        Modifier
            .fillMaxWidth()
            .height(FOOTER_HEIGHT)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val position = pager.currentPage + pager.currentPageOffsetFraction
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(PAGES) { i ->
                val w = (1f - abs(i - position)).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .size(width = (6f + 12f * w).dp, height = 6.dp)
                        .clip(CircleShape)
                        .background(lerp(c.pageDot, c.acc, w)),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (!last) TextAction(stringResource(R.string.guide_skip), onClick = onDone)
        Row(
            Modifier
                .padding(start = 8.dp)
                .height(44.dp)
                .clip(CircleShape)
                .background(c.blue)
                .clickable(role = Role.Button) {
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1, animationSpec = tween(SETTLE_MS, easing = Motion.emphasizedDecelerate)) }
                }
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(if (last) R.string.done else R.string.next), style = type(16f, 24f, Fonts.SEMIBOLD), color = Color.White, maxLines = 1)
            Glyph(if (last) "check" else "arrow-right", 20.dp, Color.White)
        }
    }
}

/** Card 1: the mic ball in its ring, the voice going out as bars, and the message it becomes, typed once. */
@Composable
private fun BoxScope.WelcomeArt() {
    val c = appColors
    val loop = rememberInfiniteTransition(label = "welcome")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(WELCOME_MS, easing = LinearEasing)), label = "t")
    val message = stringResource(R.string.guide_sample_message)
    val typed = remember { Animatable(0f) }
    LaunchedEffect(Unit) { typed.animateTo(1f, tween(TYPE_MS, delayMillis = 300, easing = LinearEasing)) }
    Box(
        Modifier
            .offset(50.dp, 33.dp)
            .size(134.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (c.dark) 0.04f else 0.3f))
            .border(1.dp, Color.White.copy(alpha = if (c.dark) 0.1f else 0.8f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { MicBall(108.dp, level = 0.42f + 0.04f * sin(2 * PI * t).toFloat()) }
    // The voice: five bars breathing out of step.
    Row(
        Modifier
            .offset(203.dp, 100.dp - 12.dp)
            .height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BAR_HEIGHTS.forEachIndexed { i, h ->
            val breath = 0.65f + 0.35f * sin(2 * PI * (t * 2 + i * 0.18f)).toFloat()
            Box(
                Modifier
                    .size(3.5.dp, (h * breath).dp)
                    .clip(CircleShape)
                    .background(c.blue.copy(alpha = 0.5f + 0.12f * i.coerceAtMost(4 - i))),
            )
        }
    }
    Column(
        Modifier
            .offset(219.dp, 66.dp)
            .width(130.dp)
            .dropShadow(RoundedCornerShape(16.dp), Shadow(radius = 10.dp, color = Color(0x14142850), offset = DpOffset(0.dp, 3.dp)))
            .clip(RoundedCornerShape(16.dp))
            .background(if (c.dark) c.tonal else Color.White.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(stringResource(R.string.guide_message).uppercase(), style = type(10.5f, 14f, Fonts.SEMIBOLD, letterSpacing = 0.6f), color = c.ink2)
        Box {
            // The full text keeps the bubble's size while it types.
            Text(message, style = type(13.5f, 19f, Fonts.SEMIBOLD), color = Color.Transparent)
            Text(message.take((message.length * typed.value).toInt()), style = type(13.5f, 19f, Fonts.SEMIBOLD), color = c.ink)
        }
    }
}

/** Card 2: the keyboard, tilted and breathing, with a tap ring on its mic; Tap to talk and Vocabulary pinned on it. */
@Composable
private fun BoxScope.KeyboardArt() {
    val c = appColors
    val loop = rememberInfiniteTransition(label = "keyboard")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(KEYBOARD_MS, easing = LinearEasing)), label = "t")
    val ring by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(RING_MS, easing = FastOutSlowInEasing)), label = "ring")
    val board = if (c.dark) Color(0xFF141C2A) else Color(0xFFF8FAFC)
    val key = if (c.dark) c.tonal else Color.White
    val keyLine = if (c.dark) c.line2 else c.line
    Box(
        Modifier
            .offset(68.dp, 22.dp)
            .size(256.dp, 196.dp)
            .graphicsLayer { rotationZ = -4f + 2.5f * sin(2 * PI * t).toFloat() }
            .dropShadow(RoundedCornerShape(24.dp), Shadow(radius = 18.dp, color = Color(0x1F142850), offset = DpOffset(0.dp, 6.dp)))
            .clip(RoundedCornerShape(24.dp))
            .background(board)
            .border(1.5.dp, if (c.dark) c.line2 else Color.White, RoundedCornerShape(24.dp)),
    ) {
        Box(
            Modifier
                .offset(108.dp, 10.dp)
                .size(40.dp, 4.dp)
                .clip(CircleShape)
                .background(c.line2),
        )
        MiniKey("clipboard", 16.dp, 28.dp, 26.dp, key, keyLine)
        MiniKey("smiley", 48.dp, 28.dp, 26.dp, key, keyLine)
        Row(
            Modifier
                .offset(82.dp, 27.dp)
                .height(28.dp)
                .clip(CircleShape)
                .background(key)
                .border(1.dp, keyLine, CircleShape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.guide_style), style = type(10.5f, 14f, Fonts.SEMIBOLD), color = c.ink)
            Glyph("caret-down", 9.dp, c.ink3, Modifier.padding(start = 2.dp, end = 10.dp))
            Text(stringResource(R.string.guide_translate), style = type(10.5f, 14f, Fonts.SEMIBOLD), color = c.ink)
            Glyph("caret-down", 9.dp, c.ink3, Modifier.padding(start = 2.dp))
        }
        Box(
            Modifier
                .offset(200.dp, 26.dp)
                .size(46.dp, 28.dp)
                .clip(CircleShape)
                .background(c.tonal),
            contentAlignment = Alignment.Center,
        ) { Glyph("arrow-up", 14.dp, c.glyph) }
        MiniKey("trash", 12.dp, 76.dp, 32.dp, key, keyLine)
        MiniKey("gear-six", 12.dp, 124.dp, 32.dp, key, keyLine)
        MiniKey("backspace", 212.dp, 68.dp, 32.dp, key, keyLine)
        MiniKey("arrow-bend-down-left", 212.dp, 116.dp, 32.dp, key, keyLine)
        Text(
            stringResource(R.string.tap_to_talk),
            style = type(10.5f, 14f, Fonts.MEDIUM),
            color = c.ink3,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 84.dp),
        )
        Box(
            Modifier
                .offset(54.dp, 124.dp)
                .size(148.dp, 40.dp)
                .clip(CircleShape)
                .background(c.segmentTrack),
        )
        MiniKey("pause", 60.dp, 130.dp, 28.dp, key, keyLine)
        Box(
            Modifier
                .offset(168.dp, 130.dp)
                .size(28.dp),
            contentAlignment = Alignment.Center,
        ) { Glyph("x", 12.dp, c.ink3.copy(alpha = 0.6f)) }
        Canvas(
            Modifier
                .offset(98.dp, 90.dp)
                .size(60.dp),
        ) {
            drawCircle(c.blue.copy(alpha = 0.35f * (1f - ring)), radius = size.width / 2 * (0.85f + 0.3f * ring), style = Stroke(2.dp.toPx()))
        }
        MicBall(
            48.dp,
            Modifier.offset(104.dp, 96.dp),
        )
    }
    GuideChip("f:microphone", stringResource(R.string.tap_to_talk), Modifier.offset(37.dp, 22.dp))
    GuideChip("book-open-text", stringResource(R.string.vocabulary), Modifier.offset(229.dp, 150.dp))
}

/** A round key of the keyboard art at ([x], [y]). */
@Composable
private fun MiniKey(glyph: String, x: Dp, y: Dp, size: Dp, fill: Color, line: Color) {
    Box(
        Modifier
            .offset(x, y)
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, line, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Glyph(glyph, size * 0.45f, appColors.icon) }
}

/** A white chip on a card's art: an accent glyph and a few words. */
@Composable
private fun GuideChip(glyph: String, text: String, modifier: Modifier) {
    val c = appColors
    Row(
        modifier
            .height(32.dp)
            .dropShadow(CircleShape, Shadow(radius = 10.dp, color = Color(0x1A142850), offset = DpOffset(0.dp, 3.dp)))
            .clip(CircleShape)
            .background(c.box)
            .then(if (c.dark) Modifier.border(1.dp, c.line2, CircleShape) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Glyph(glyph, 16.dp, c.acc)
        Text(text, style = type(13.5f, 18f, Fonts.SEMIBOLD), color = c.ink, maxLines = 1)
    }
}

/**
 * Card 3: History with a kept recording between two texts. Its Retry is pressed, spins, and the text lands in its row
 * (the time only, never "Inserted").
 */
@Composable
private fun BoxScope.HistoryArt() {
    val c = appColors
    val loop = rememberInfiniteTransition(label = "history")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(HISTORY_MS, easing = LinearEasing)), label = "t")
    val landed = ((t - 0.62f) / 0.1f).coerceIn(0f, 1f) * (1f - ((t - 0.94f) / 0.06f).coerceIn(0f, 1f))
    Column(
        Modifier
            .offset(48.dp, 24.dp)
            .width(282.dp)
            .graphicsLayer { rotationZ = -1.5f }
            .dropShadow(RoundedCornerShape(22.dp), Shadow(radius = 18.dp, color = Color(0x1A142850), offset = DpOffset(0.dp, 6.dp)))
            .clip(RoundedCornerShape(22.dp))
            .background(if (c.dark) c.box else Color.White.copy(alpha = 0.92f))
            .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
        HistoryLine(stringResource(R.string.guide_sample_first), "12:44")
        Box(Modifier.height(58.dp)) {
            Row(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 1f - landed }
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.amber.copy(alpha = if (c.dark) 0.16f else 0.14f))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    OneLine("${stringResource(R.string.status_not_transcribed)} · 0:42", type(14f, 19f, Fonts.SEMIBOLD), c.ink2)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(c.amber)
                        Text(stringResource(R.string.status_kept), style = type(12.5f, 17f), color = c.ink2, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                val pressed = t in 0.18f..0.26f
                Box(
                    Modifier
                        .graphicsLayer {
                            scaleX = if (pressed) 0.93f else 1f
                            scaleY = scaleX
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (t in 0.26f..0.62f) {
                        Canvas(Modifier.size(22.dp)) {
                            drawArc(c.acc, startAngle = 360f * (t - 0.26f) / 0.18f, sweepAngle = 270f, useCenter = false, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                        }
                    } else {
                        PillButton(stringResource(R.string.retry), kind = PillKind.TONAL, icon = "arrow-counter-clockwise") {}
                    }
                }
            }
            Box(Modifier.graphicsLayer {
                alpha = landed
                translationY = 6.dp.toPx() * (1f - landed)
            }) { HistoryLine(stringResource(R.string.guide_sample_message), "12:46") }
        }
        HistoryLine(stringResource(R.string.guide_sample_second), "12:20")
    }
}

@Composable
private fun HistoryLine(text: String, time: String) {
    val c = appColors
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        OneLine(text, type(14.5f, 19f, Fonts.SEMIBOLD), c.ink)
        Text(time, style = type(12.5f, 17f), color = c.ink2)
    }
}

/** Card 4: phone → Waveei server → model along a dashed line, a dot going out and coming back, and why. */
@Composable
private fun BoxScope.DataArt() {
    val c = appColors
    val loop = rememberInfiniteTransition(label = "data")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(DATA_MS, easing = LinearEasing)), label = "t")
    val tileY = 44.dp
    // 108 dp apart, so "Waveei server" and "Speech model" stand clear of each other under their tiles.
    val centres = listOf(82.dp, 190.dp, 298.dp)
    Canvas(Modifier.fillMaxSize()) {
        val y = (tileY + TILE / 2).toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()), -t * 9.dp.toPx())
        for (i in 0..1) {
            drawLine(
                c.blue.copy(alpha = 0.4f),
                Offset((centres[i] + TILE / 2).toPx(), y),
                Offset((centres[i + 1] - TILE / 2).toPx(), y),
                strokeWidth = 2.dp.toPx(),
                pathEffect = dash,
            )
        }
        // Out to the server and back: a sine there and back.
        val s = (1f - cos(2 * PI * t).toFloat()) / 2f
        val x = (centres[0] + TILE / 2 + 6.dp).toPx() + s * (centres[1] - centres[0] - TILE - 12.dp).toPx()
        drawCircle(c.blue.copy(alpha = 0.2f), radius = 10.dp.toPx(), center = Offset(x, y))
        drawCircle(c.blue, radius = 6.dp.toPx(), center = Offset(x, y))
    }
    DataTile("microphone", R.string.guide_your_phone, centres[0], tileY, server = false)
    DataTile("waveform", R.string.guide_server, centres[1], tileY, server = true)
    DataTile("sparkle", R.string.guide_model, centres[2], tileY, server = false)
    GuideChip("shield-check", stringResource(R.string.guide_to_make_text), Modifier.align(Alignment.TopCenter).padding(top = 148.dp))
}

@Composable
private fun DataTile(glyph: String, label: Int, centre: Dp, top: Dp, server: Boolean) {
    val c = appColors
    Column(
        Modifier
            .offset(centre - 60.dp, top)
            .width(120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(TILE)
                .dropShadow(RoundedCornerShape(18.dp), Shadow(radius = 12.dp, color = Color(0x1A142850), offset = DpOffset(0.dp, 4.dp)))
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (server) Brush.verticalGradient(listOf(Color(0xFF9CC6FF), Color(0xFF4F93F2))) else Brush.verticalGradient(listOf(c.box, c.box)),
                ),
            contentAlignment = Alignment.Center,
        ) { Glyph(glyph, 26.dp, if (server) Color.White else c.acc) }
        Text(stringResource(label), style = type(13.5f, 18f, Fonts.SEMIBOLD), color = c.ink, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Each card's art behind it: soft day tints, navy at night. */
private fun artBackground(page: Int, dark: Boolean): Brush {
    // Card 2 at night ends in navy: its day mint, darkened, would read as a green smudge on the navy card.
    val colors = when (page) {
        0 -> if (dark) listOf(0xFF16243A, 0xFF1B2140, 0xFF142B3A) else listOf(0xFFE7F0FE, 0xFFF0EDFD, 0xFFE5F5FB)
        1 -> if (dark) listOf(0xFF15233A, 0xFF17263A, 0xFF15263A) else listOf(0xFFE3EDFC, 0xFFF1F5F8, 0xFFE4F6EF)
        2 -> if (dark) listOf(0xFF15233A, 0xFF1A2333, 0xFF2A2621) else listOf(0xFFE5EEFD, 0xFFF6F3EE, 0xFFFCEDDA)
        else -> if (dark) listOf(0xFF13292B, 0xFF172131, 0xFF172A44) else listOf(0xFFE1F4EB, 0xFFF4F6F8, 0xFFD9E7FD)
    }.map { Color(it) }
    // Card 1 and 3 shade from the bottom left, 2 and 4 from the top left.
    return if (page % 2 == 0) {
        Brush.linearGradient(colors, start = Offset(0f, Float.POSITIVE_INFINITY), end = Offset(Float.POSITIVE_INFINITY, 0f))
    } else {
        Brush.linearGradient(colors)
    }
}

/** A card's shadow: a deep soft drop, a near one and a hairline. */
private fun cardShadow(dark: Boolean): Modifier {
    val tint = if (dark) Color.Black else Color(0xFF142850)
    return Modifier
        .dropShadow(CARD_SHAPE, Shadow(radius = 64.dp, color = tint.copy(alpha = if (dark) 0.4f else 0.22f), offset = DpOffset(0.dp, 24.dp)))
        .dropShadow(CARD_SHAPE, Shadow(radius = 18.dp, color = tint.copy(alpha = if (dark) 0.2f else 0.1f), offset = DpOffset(0.dp, 6.dp)))
        .dropShadow(CARD_SHAPE, Shadow(radius = 0.dp, spread = 1.dp, color = if (dark) Color(0x1F96B4E6) else tint.copy(alpha = 0.05f)))
}

private const val PAGES = 4
private const val PARALLAX = 0.55f
private val CARD_SHAPE = RoundedCornerShape(28.dp)
private val CARD_WIDTH = 379.dp
private val CARD_MAX_WIDTH = 420.dp
private val CARD_HEIGHT = 632.dp
private val CARD_TOP = 100.dp
private val ART_HEIGHT = 200.dp
private val SHORT_CARD = 560.dp
private const val SHORT_ART_SHARE = 0.35f
private val FOOTER_HEIGHT = 84.dp
private val TILE = 58.dp
private val BAR_HEIGHTS = listOf(10f, 20f, 26f, 18f, 9f)
private const val VEIL_MS = 240
// Material 3 durations: the veil medium4, the card long2, so the guide settles in rather than pops up.
private const val VEIL_IN_MS = 400
private const val CARD_IN_MS = 500
private const val CARD_OUT_MS = 200
private const val SETTLE_MS = 320
private const val WELCOME_MS = 4000
private const val TYPE_MS = 1400
private const val KEYBOARD_MS = 6000
private const val RING_MS = 2000
private const val HISTORY_MS = 3200
private const val DATA_MS = 2800
