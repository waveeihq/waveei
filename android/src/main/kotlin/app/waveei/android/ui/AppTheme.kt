package app.waveei.android.ui

import android.animation.ValueAnimator
import android.app.Activity
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.waveei.android.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** The app's colours, by day and at night, under the same names. */
@Immutable
data class AppColors(
    val dark: Boolean,
    val page: Color,
    val box: Color,
    val line: Color,
    val line2: Color,
    val tonal: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val icon: Color,
    val chev: Color,
    /** Filled buttons, switches, ticks of progress: stays a mid blue at night, with white on it. */
    val blue: Color,
    val blueBox: Color,
    val blueSoft: Color,
    val blueInk: Color,
    /** Accent text and glyphs: links, text buttons, the avatar; lighter at night. */
    val acc: Color,
    /** Accent text on [blueBox], the tonal pills: a shade darker by day, so it keeps 4.5:1 there. */
    val tonalInk: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    /** Red buttons with white text (a dialog's Delete): [red] by day; at night [red] is a light text red, too pale under white. */
    val redFill: Color,
    /** The quieter red of the lines under a red one (what deleting the account does): lighter, still 4.5:1 on a dialog. */
    val redSoft: Color,
    /** The ring of what is not chosen or done yet (radio, check, tick, the switch's outline): 3:1 on the box. */
    val off: Color,
    /** Decorative grey glyphs (an empty list's): lighter than [off], as they carry no state. */
    val offFill: Color,
    val veil: Color,
    /** The veil where nothing behind it can be blurred: nearly opaque, as the keyboard's veilOpaque. */
    val veilOpaque: Color,
    val scrimGuide: Color,
    val switchOff: Color,
    val knobOff: Color,
    val segmentTrack: Color,
    val segmentOn: Color,
    val fieldInDialog: Color,
    val buttonOff: Color,
    val buttonOffInk: Color,
    val searchButton: Color,
    val searchField: Color,
    /** Dark glyphs on the page: the back arrow and the menu icons. */
    val glyph: Color,
    val okTile: Color,
    val infoInk: Color,
    val greyNote: Color,
    val greyNoteInk: Color,
    val track: Color,
    val pageDot: Color,
)

private val DAY = AppColors(
    dark = false,
    page = Color(0xFFF3F5F8), box = Color.White, line = Color(0xFFEEF1F4), line2 = Color(0xFFD9DEE5), tonal = Color(0xFFE8EEF6),
    ink = Color(0xFF15191F), ink2 = Color(0xFF5A6270), ink3 = Color(0xFF687180), icon = Color(0xFF5A6270), chev = Color(0xFF8A93A0),
    blue = Color(0xFF0E70F4), blueBox = Color(0xFFD9E9FF), blueSoft = Color(0xFFEDF4FF), blueInk = Color(0xFF0E70F4), acc = Color(0xFF0E70F4), tonalInk = Color(0xFF0B5FD0),
    green = Color(0xFF1FA45B), amber = Color(0xFFE89B00), red = Color(0xFFD92D20), redFill = Color(0xFFD92D20), redSoft = Color(0xFFC9473D), off = Color(0xFF8A93A0), offFill = Color(0xFFC3CAD4),
    veil = Color.White.copy(alpha = 0.78f), veilOpaque = Color.White.copy(alpha = 0.94f), scrimGuide = Color.White.copy(alpha = 0.72f),
    switchOff = Color(0xFFC9D0D9), knobOff = Color.White, segmentTrack = Color(0xFFEEF1F5), segmentOn = Color.White,
    fieldInDialog = Color.White.copy(alpha = 0.9f), buttonOff = Color(0xFFE3E8EF), buttonOffInk = Color(0xFF8E97A5),
    searchButton = Color(0xFFF1F4F8), searchField = Color(0xFFF6F8FB), glyph = Color(0xFF2A3340),
    okTile = Color(0xFFDDF3E6), infoInk = Color(0xFF1E3A5F), greyNote = Color(0xFFF1F3F6), greyNoteInk = Color(0xFF3E4756),
    track = Color(0xFFE3E8EF), pageDot = Color(0xFFC9D3E0),
)

private val NIGHT = AppColors(
    dark = true,
    page = Color(0xFF0E1520), box = Color(0xFF172131), line = Color(0xFF222D3E), line2 = Color(0xFF2E3A4E), tonal = Color(0xFF222D3E),
    ink = Color(0xFFE4EAF3), ink2 = Color(0xFF97A3B6), ink3 = Color(0xFF7D899C), icon = Color(0xFFA9B4C6), chev = Color(0xFF6E7A8E),
    // 0xFF1E6FE6: white text on it at 4.7:1 (WCAG AA 4.5:1), and 3.9:1 against the page.
    blue = Color(0xFF1E6FE6), blueBox = Color(0xFF1B2D47), blueSoft = Color(0xFF15263E), blueInk = Color(0xFF6DB0FF), acc = Color(0xFF6DB0FF), tonalInk = Color(0xFF6DB0FF),
    green = Color(0xFF34C27A), amber = Color(0xFFF2B53C), red = Color(0xFFFF7A6E), redFill = Color(0xFFD9372B), redSoft = Color(0xFFFFA39A), off = Color(0xFF6B7684), offFill = Color(0xFF4F5A6B),
    veil = Color(0xCC1E293C), veilOpaque = Color(0xF01E293C), scrimGuide = Color(0xB80E1520),
    switchOff = Color(0xFF3A4659), knobOff = Color(0xFFE4EAF3), segmentTrack = Color(0xFF0E1520), segmentOn = Color(0xFF2B374B),
    fieldInDialog = Color(0x990E1520), buttonOff = Color(0xFF222D3E), buttonOffInk = Color(0xFF7D899C),
    searchButton = Color(0xFF222D3E), searchField = Color(0xFF222D3E), glyph = Color(0xFFC9D3E1),
    okTile = Color(0xFF173326), infoInk = Color(0xFFCFE2FF), greyNote = Color(0xFF1C2533), greyNoteInk = Color(0xFFB8C2D2),
    track = Color(0xFF2B374B), pageDot = Color(0xFF3A4659),
)

val LocalAppColors = staticCompositionLocalOf { DAY }

/** The colours of the theme in use. */
val appColors: AppColors
    @Composable get() = LocalAppColors.current

/** The app's easings. */
object Motion {
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val decelerate = CubicBezierEasing(0f, 0f, 0f, 1f)
    val accelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val inOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}

/** The app's theme: the tokens above, Inter everywhere, and Material's slots filled from them for what still reads them. */
@Composable
fun AppTheme(theme: Prefs.Theme, content: @Composable () -> Unit) {
    val dark = when (theme) {
        Prefs.Theme.SYSTEM -> isSystemInDarkTheme()
        Prefs.Theme.LIGHT -> false
        Prefs.Theme.DARK -> true
    }
    val c = if (dark) NIGHT else DAY
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.blue, onPrimary = Color.White, primaryContainer = c.blueBox, onPrimaryContainer = c.blueInk,
        background = c.page, onBackground = c.ink, surface = c.page, onSurface = c.ink,
        surfaceContainer = c.box, surfaceContainerLow = c.box, surfaceContainerHigh = c.box, surfaceContainerHighest = c.tonal,
        surfaceVariant = c.tonal, onSurfaceVariant = c.ink2, outline = c.off, outlineVariant = c.line, error = c.red,
    )
    // The bars' icons follow the app's theme, which may differ from the system's.
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val density = LocalDensity.current.density
    val frameNanos = (1_000_000_000 / (view.display?.refreshRate ?: 60f)).toLong()
    CompositionLocalProvider(LocalAppColors provides c, LocalOverscrollFactory provides EdgeShiftFactory(density, frameNanos)) {
        MaterialTheme(colorScheme = scheme, typography = TYPOGRAPHY, content = content)
    }
}

/** Every scrolling list of the app gets the scroll edge below instead of Android's stretch. */
private data class EdgeShiftFactory(val density: Float, val frameNanos: Long) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = EdgeShift(density, frameNanos)
}

/**
 * The scroll edge: past the edge the content follows the finger like a rubber band, close
 * to it at first and ever more slowly, as UIScrollView's band over the list's own height, and springs back on release;
 * a fling that hits the edge runs on through the same band by its velocity and comes back the same way. No stretch,
 * no glow, no wall. With animations off (reduce motion) the edge is a hard stop.
 */
private class EdgeShift(private val density: Float, private val frameNanos: Long) : OverscrollEffect {
    /** How far the finger, or a fling, has gone past the edge, in px; the content follows it through [band]. */
    private var pull by mutableStateOf(Offset.Zero)

    /**
     * Only while the finger pulls. Compose takes a touch on a list whose overscroll is in progress for a drag at once, so
     * during the spring back (up to a second) the first tap on an item did nothing; now it presses the item, and a real
     * drag still takes the content from the spring once it moves (applyToFling).
     */
    override val isInProgress: Boolean get() = pull != Offset.Zero && steps != released

    /** The fling going on ran into the edge: what it had left goes past it (applyToFling). */
    private var edgeHit = false

    /** Counts the finger's steps, so a drag that starts while the content springs back stops the spring (applyToFling). */
    private var steps = 0

    /** [steps] when the finger last let go: the pull since then is the spring's, not the finger's. */
    private var released = -1

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        if (source == NestedScrollSource.UserInput) steps++
        // Back towards the edge first: the pull gives way before the list scrolls again.
        val back = Offset(relax(pull.x, delta.x), relax(pull.y, delta.y))
        if (back != Offset.Zero) pull += back
        val consumed = performScroll(delta - back)
        val left = delta - back - consumed
        // A drag pulls the content past the edge. A fling's step that meets it goes on past it too, so that frame moves
        // as far as the ones before it; the rest of the fling's speed goes to applyToFling.
        if (source != NestedScrollSource.UserInput && left != Offset.Zero) {
            edgeHit = true
            if (ValueAnimator.areAnimatorsEnabled()) pull += left / GIVE
        }
        if (source != NestedScrollSource.UserInput || !ValueAnimator.areAnimatorsEnabled() || left == Offset.Zero) return back + consumed
        pull += left
        return delta
    }

    /** The part of [delta] that takes the pull back towards 0, never past it. */
    private fun relax(pull: Float, delta: Float): Float =
        if (pull == 0f || sign(delta) == sign(pull)) 0f else if (abs(delta) >= abs(pull)) -pull else delta

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        // Released past the edge and thrown further out: back with the spring. Thrown back into the list, as Compose's
        // own stretch does, the fling runs: its first steps take the pull back (applyToScroll), the list flings on.
        // performFling returns what it consumed (OverscrollEffect's contract) and is called exactly once: taking its
        // answer for what was left would spring every fling out and back where it stopped, mid-list too, a twitch at the
        // end of every fling.
        released = steps
        val outward = velocity.x * pull.x > 0f || velocity.y * pull.y > 0f || pull != Offset.Zero && velocity == Velocity.Zero
        edgeHit = false
        val left = if (outward) velocity.also { performFling(Velocity.Zero) } else velocity - performFling(velocity)
        val past = pull != Offset.Zero
        if (!ValueAnimator.areAnimatorsEnabled()) {
            pull = Offset.Zero
            return
        }
        // No cap: the band itself keeps a fast fling short, so the list never brakes as it reaches the edge. A fling
        // that reaches the edge goes on at the list's own speed: the band moves the content GIVE of the pull's speed
        // there, so the pull starts at the speed over GIVE (without it the list would lose half its speed at the edge, as if
        // it hit a wall). A release past the edge is the finger's own speed already. A fling that stopped short of the
        // edge has nothing to show.
        // A fling that met the edge has its first step past it already, so it is checked first.
        fun out(v: Float, p: Float) = when {
            edgeHit -> v / GIVE
            past -> if (sign(v) != sign(p)) 0f else v
            else -> 0f
        }
        val start = Velocity(out(left.x, pull.x), out(left.y, pull.y))
        if (pull == Offset.Zero && start == Velocity.Zero) return
        // Played from a frame before the first: animate() shows the value it starts from on its first frame, so the
        // content would stand still for a frame where it met the edge or was let go past it.
        val spring = TargetBasedAnimation(if (edgeHit) FLING_SPRING else EDGE_SPRING, Offset.VectorConverter, pull, Offset.Zero, Offset(start.x, start.y))
        var from = Long.MIN_VALUE
        // A new drag takes the content from where the spring has it, as Animatable.snapTo stops animateTo in Compose's
        // own overscroll sample. Compose launches this fling apart from the drag and never cancels it, so the spring
        // would go on setting the pull over the finger's for up to a second: the content would stand, then jump.
        val stepsAtRelease = steps
        do {
            val played = withFrameNanos { now ->
                if (steps != stepsAtRelease) return@withFrameNanos null
                if (from == Long.MIN_VALUE) from = now - frameNanos
                (now - from).also { pull = spring.getValueFromNanos(it) }
            } ?: return
        } while (!spring.isFinishedFromNanos(played))
        pull = Offset.Zero
    }

    /** Where the content sits for a pull: GIVE of the finger's travel at first, closing in on [length] (UIScrollView's band). */
    private fun band(pull: Float, length: Int): Float {
        val reach = if (length > 0) length.toFloat() else REACH_DP * density
        return sign(pull) * reach * (1f - 1f / (abs(pull) * GIVE / reach + 1f))
    }

    override val node: DelegatableNode = object : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            // Read while placing: the pull moves the content without measuring it again.
            return layout(placeable.width, placeable.height) {
                placeable.placeWithLayer(band(pull.x, placeable.width).roundToInt(), band(pull.y, placeable.height).roundToInt())
            }
        }
    }
}

private const val GIVE = 0.55f
// Before the list is measured.
private const val REACH_DP = 90f

// Critically damped: back to the edge in one smooth move, never past it.
// Done within half a pixel: the band is placed in whole pixels.
private val EDGE_SPRING = spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = Offset(0.5f, 0.5f))
// A fling that runs into the edge: a softer spring, so the content carries on past it and turns back, rather than
// stopping at it (stiffness 100, as super-ultra/ScrollMechanics bounces a decelerating UIScrollView at its bound).
private val FLING_SPRING = spring(dampingRatio = 1f, stiffness = 100f, visibilityThreshold = Offset(0.5f, 0.5f))

/** Inter in every Material text style, so nothing falls back to the system font. */
private val TYPOGRAPHY = Typography().let { t ->
    fun TextStyle.inter() = copy(fontFamily = Fonts.family)
    Typography(
        displayLarge = t.displayLarge.inter(), displayMedium = t.displayMedium.inter(), displaySmall = t.displaySmall.inter(),
        headlineLarge = t.headlineLarge.inter(), headlineMedium = t.headlineMedium.inter(), headlineSmall = t.headlineSmall.inter(),
        titleLarge = t.titleLarge.inter(), titleMedium = t.titleMedium.inter(), titleSmall = t.titleSmall.inter(),
        bodyLarge = t.bodyLarge.inter(), bodyMedium = t.bodyMedium.inter(), bodySmall = t.bodySmall.inter(),
        labelLarge = t.labelLarge.inter(), labelMedium = t.labelMedium.inter(), labelSmall = t.labelSmall.inter(),
    )
}

/** Text in the design's type scale: size, line height and weight. */
fun type(size: Float, line: Float, weight: Int = Fonts.REGULAR, letterSpacing: Float = 0f) = TextStyle(
    fontFamily = Fonts.family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = letterSpacing.sp,
)
