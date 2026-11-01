package app.waveei.android.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import app.waveei.android.Prefs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** The keyboard's colours, day and night. */
class KeyboardColors private constructor(
    val night: Boolean,
    /** The panel: [bgTop] down to the middle, then deepening to [bgBottom]. */
    val bgTop: Int,
    val bgBottom: Int,
    val bgSplit: Float,
    /** The hairline along the panel's top edge, and the soft shadow it casts up over the app. */
    val topLine: Int,
    val topShadow: Int,
    val key: Int,
    /** A raised key's edge 1 dp below it and the 1 dp ring around it; at night also a light top rim. */
    val keyEdge: Int,
    val keyRing: Int,
    val keyRim: Int,
    /** The top row's flat keys. */
    val tonal: Int,
    val ink: Int,
    val grey: Int,
    val off: Int,
    val dim: Int,
    val hint: Int,
    val caret: Int,
    val capsule: Int,
    val capsuleEdge: Int,
    val capsuleRing: Int,
    val divider: Int,
    val trio: Int,
    val trioRing: Int,
    val badge: Int,
    val badgeRing: Int,
    val grab: Int,
    /** The grabber bar while a finger holds or pulls it. */
    val grabHeld: Int,
    /** The panels (Clipboard, History, Emoji). */
    val panelBg: Int,
    val panelLine: Int,
    val card: Int,
    val cardRing: Int,
    val row: Int,
    val segment: Int,
    val segmentOn: Int,
    val meta: Int,
    val muted: Int,
    val clearRing: Int,
    /** Veil glass over the keyboard: the tint over the blur, and the opaque one where nothing can be blurred. */
    val veil: Int,
    val veilOpaque: Int,
    val veilRim: Int,
    val veilRing: Int,
    val veilShadow: Int,
    val veilLine: Int,
    val veilNotch: Int,
    val veilEdge: Int,
    val openBg: Int,
    val accent: Int,
    val accentBg: Int,
    /** Accent text on [accentBg] (the chips, Insert and Retry): a shade darker by day, for 4.5:1, as the app's tonal ink. */
    val accentInk: Int,
    val red: Int,
    val redBg: Int,
    val live: Int,
    val liveSoft: Int,
    val retry: Int,
    val retryShadow: Int,
    val playBg: Int,
    /** The tint over a pressed key. */
    val press: Int,
    val amber: Int,
    val green: Int,
    /** ↑ with Auto-send on (every dictation is sent): the solid blue key, its arrow white. */
    val go: Int,
    /** The recording mark around the mic: a crisp, saturated ring with a short glow, ≥ 3:1 on the trio and the panel. */
    val rec: Int,
    /** The mic off (no dictation possible now): its greyed ball is lifted towards this, never made see-through. */
    val micOff: Int,
    val mic: Mic,
) {
    /** The mic ball. */
    class Mic(
        val air0: Int,
        val air1: Int,
        val back: Int,
        val above: Int,
        val rim: Int,
        val ring: Int,
        val shadow: Int,
    )

    companion object {
        /**
         * The water itself is the same by day and at night: a gentle fall from its crest down, so the ball reads flat
         * rather than as a lit sphere.
         */
        val WATER = intArrayOf(0xFF5CB4FC.toInt(), 0xFF358CF1.toInt(), 0xFF2A7DE8.toInt())

        val DAY = KeyboardColors(
            night = false,
            bgTop = 0xFFFFFFFF.toInt(), bgBottom = 0xFFEAF4FF.toInt(), bgSplit = 0.5f, topLine = rgba(16, 24, 40, .10f), topShadow = rgba(18, 24, 33, .10f),
            key = 0xFFFFFFFF.toInt(), keyEdge = rgba(11, 60, 140, .16f), keyRing = rgba(11, 60, 140, .06f), keyRim = 0,
            tonal = 0xFFF1F4F8.toInt(), ink = 0xFF0B0F14.toInt(), grey = 0xFF5B616B.toInt(), off = 0xFFB0B6BF.toInt(),
            dim = 0xFFB5BAC2.toInt(), hint = 0xFF5B616B.toInt(), caret = 0xFF7A818B.toInt(),
            capsule = 0xFFFFFFFF.toInt(), capsuleEdge = rgba(11, 60, 140, .16f), capsuleRing = rgba(11, 60, 140, .15f),
            divider = rgba(11, 15, 20, .12f), trio = 0xFFF2F5F9.toInt(), trioRing = rgba(11, 60, 140, .08f),
            badge = 0xFF0E70F4.toInt(), badgeRing = 0xFFFFFFFF.toInt(), grab = rgba(11, 15, 20, .11f), grabHeld = rgba(11, 15, 20, .28f),
            panelBg = 0xFFF4F6F9.toInt(), panelLine = 0xFFE6E8EC.toInt(), card = 0xFFFFFFFF.toInt(), cardRing = rgba(11, 15, 20, .05f),
            row = 0xFFEEF0F3.toInt(), segment = 0xFFE7EAEF.toInt(), segmentOn = 0xFFFFFFFF.toInt(), meta = 0xFF6E7684.toInt(),
            // Muted rows and empty lists are text to act on: the keyboard's grey, 5.5:1 on a row (it was 2.8:1).
            muted = 0xFF5B616B.toInt(), clearRing = 0xFFD9DEE6.toInt(),
            veil = rgba(255, 255, 255, .78f), veilOpaque = rgba(255, 255, 255, .94f), veilRim = rgba(255, 255, 255, .7f),
            veilRing = rgba(18, 24, 33, .07f), veilShadow = rgba(18, 24, 33, .07f), veilLine = 0xFFEEF1F4.toInt(),
            veilNotch = 0xFFFFFFFF.toInt(), veilEdge = rgba(18, 24, 33, .10f), openBg = rgba(11, 15, 20, .07f),
            accent = 0xFF0E70F4.toInt(), accentBg = 0xFFE5F0FF.toInt(), accentInk = 0xFF0B5FD0.toInt(), red = 0xFFD92D20.toInt(), redBg = 0xFFFCE4E1.toInt(),
            live = 0xFF0B0F14.toInt(), liveSoft = 0xFF9FB6D6.toInt(), retry = 0xFF0E70F4.toInt(), retryShadow = rgba(14, 112, 244, .28f),
            playBg = 0xFFE3EEFF.toInt(), press = rgba(11, 60, 140, .09f),
            // The app's own green and amber, as they mean the same (inserted, waiting): the system's #34C759 is 2.2:1.
            amber = 0xFFE89B00.toInt(), green = 0xFF1FA45B.toInt(),
            // Recording ring 4.14:1 on the trio, 4.52:1 on white.
            go = 0xFF0E70F4.toInt(), rec = 0xFF0E70F4.toInt(), micOff = 0xFFFFFFFF.toInt(),
            mic = Mic(
                air0 = 0xFFFFFFFF.toInt(), air1 = 0xFFF1F7FF.toInt(), back = 0xFFBFE2FF.toInt(), above = 0xFF0E70F4.toInt(),
                rim = rgba(255, 255, 255, .95f), ring = rgba(11, 15, 20, .14f), shadow = rgba(10, 79, 196, .26f),
            ),
        )

        /** "Night water": navy deepening downward, never pure black. */
        val NIGHT = KeyboardColors(
            night = true,
            bgTop = 0xFF141C2A.toInt(), bgBottom = 0xFF0F2445.toInt(), bgSplit = 0.48f, topLine = rgba(255, 255, 255, .08f), topShadow = rgba(0, 0, 0, .40f),
            key = 0xFF212B3B.toInt(), keyEdge = rgba(0, 0, 0, .35f), keyRing = rgba(150, 180, 230, .06f), keyRim = rgba(255, 255, 255, .05f),
            tonal = 0xFF1C2533.toInt(), ink = 0xFFE4EAF3.toInt(), grey = 0xFF97A3B6.toInt(), off = 0xFF525D6F.toInt(),
            dim = 0xFF4D5768.toInt(), hint = 0xFF97A3B6.toInt(), caret = 0xFF77839A.toInt(),
            capsule = 0xFF212B3B.toInt(), capsuleEdge = rgba(0, 0, 0, .35f), capsuleRing = rgba(150, 180, 230, .12f),
            divider = rgba(228, 234, 243, .12f), trio = 0xFF19212F.toInt(), trioRing = rgba(150, 180, 230, .07f),
            badge = 0xFF6DB0FF.toInt(), badgeRing = 0xFF212B3B.toInt(), grab = rgba(228, 234, 243, .15f), grabHeld = rgba(228, 234, 243, .34f),
            panelBg = 0xFF121A27.toInt(), panelLine = rgba(150, 180, 230, .08f), card = 0xFF1C2533.toInt(), cardRing = rgba(150, 180, 230, .06f),
            row = rgba(150, 180, 230, .08f), segment = 0xFF1C2533.toInt(), segmentOn = 0xFF2B374B.toInt(), meta = 0xFF8592A6.toInt(),
            muted = 0xFF7D899C.toInt(), clearRing = rgba(150, 180, 230, .16f),
            veil = rgba(30, 41, 60, .80f), veilOpaque = rgba(30, 41, 60, .94f), veilRim = rgba(150, 180, 230, .12f),
            veilRing = 0, veilShadow = rgba(0, 0, 0, .22f), veilLine = rgba(150, 180, 230, .10f),
            veilNotch = 0xFF1E293C.toInt(), veilEdge = rgba(150, 180, 230, .16f), openBg = rgba(228, 234, 243, .10f),
            accent = 0xFF6DB0FF.toInt(), accentBg = rgba(109, 176, 255, .15f), accentInk = 0xFF6DB0FF.toInt(), red = 0xFFFF7A6E.toInt(), redBg = rgba(255, 122, 110, .16f),
            live = 0xFFE9EEF6.toInt(), liveSoft = 0xFF6F8DB8.toInt(), retry = 0xFF2F86F0.toInt(), retryShadow = rgba(47, 134, 240, .28f),
            playBg = rgba(109, 176, 255, .18f), press = rgba(150, 180, 230, .10f),
            amber = 0xFFF2B53C.toInt(), green = 0xFF34C27A.toInt(),
            // A more saturated blue than the night accent for the ring: 5.34:1 on the trio; white on [go] 3.63:1.
            go = 0xFF2F86F0.toInt(), rec = 0xFF3D95FF.toInt(), micOff = 0xFF1C2533.toInt(),
            mic = Mic(
                air0 = 0xFF263349.toInt(), air1 = 0xFF212E44.toInt(), back = rgba(78, 155, 255, .38f), above = 0xFF8EC3FF.toInt(),
                rim = rgba(255, 255, 255, .16f), ring = rgba(120, 170, 255, .22f), shadow = rgba(47, 140, 255, .34f),
            ),
        )

        /** Settings › Theme, as for the app: System follows the phone's dark mode. */
        fun resolve(context: Context, theme: Prefs.Theme): KeyboardColors = when (theme) {
            Prefs.Theme.LIGHT -> DAY
            Prefs.Theme.DARK -> NIGHT
            Prefs.Theme.SYSTEM -> {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) NIGHT else DAY
            }
        }

        fun rgba(r: Int, g: Int, b: Int, a: Float): Int = Color.argb((a * 255 + 0.5f).toInt(), r, g, b)
    }
}

/**
 * Veil glass for a pop-up inside the keyboard window: what lies behind it, blurred and saturated, under the
 * veil tint, with a 1 dp ring, a light inner rim and the minimal shadow. A canvas that cannot blur
 * (software drawing) gets the opaque veil instead. The owner leaves [ROOM_DP] around the glass for the shadow.
 */
class Veil(private val owner: View) {
    private val density = owner.resources.displayMetrics.density
    private var node: RenderNode? = null
    private var waiting: View.OnLayoutChangeListener? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val box = RectF()
    private val tmp = RectF()

    /** Takes what [behind] shows under the owner now, at a quarter of the size: the blur hides the detail anyway. */
    fun capture(behind: View) {
        val w = owner.width
        val h = owner.height
        if (w == 0 || h == 0 || behind.width == 0) return
        val at = IntArray(2)
        val from = IntArray(2)
        // Where the owner sits untransformed: a pop-up still scaling in would otherwise take the glass from elsewhere.
        val parent = owner.parent as? View
        if (parent != null) {
            parent.getLocationInWindow(at)
            at[0] += owner.left - parent.scrollX
            at[1] += owner.top - parent.scrollY
        } else {
            owner.getLocationInWindow(at)
        }
        behind.getLocationInWindow(from)
        val bitmap = Bitmap.createBitmap(ceil(w / SCALE).toInt().coerceAtLeast(1), ceil(h / SCALE).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        // Anything the software canvas cannot draw (a hardware bitmap) leaves the card on its plain fallback colour.
        runCatching {
            Canvas(bitmap).apply {
                scale(1 / SCALE, 1 / SCALE)
                translate((from[0] - at[0]).toFloat(), (from[1] - at[1]).toFloat())
                behind.draw(this)
            }
        }.onFailure { return }
        node = RenderNode("veil").apply {
            setPosition(0, 0, w, h)
            val canvas = beginRecording()
            canvas.drawBitmap(bitmap, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = SATURATE })
            endRecording()
            val blur = BLUR_DP * density
            setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
        }
    }

    /**
     * As [capture], once the owner has its new size and place: at once when it is laid out, else right after its next
     * layout, before it draws. Captured too early, the glass is the flat fallback.
     */
    fun captureLaidOut(behind: View) {
        waiting?.let(owner::removeOnLayoutChangeListener)
        waiting = null
        if (owner.isLaidOut && !owner.isLayoutRequested) {
            capture(behind)
            owner.invalidate()
            return
        }
        waiting = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                v.removeOnLayoutChangeListener(this)
                waiting = null
                capture(behind)
                v.invalidate()
            }
        }.also(owner::addOnLayoutChangeListener)
    }

    fun forget() {
        waiting?.let(owner::removeOnLayoutChangeListener)
        waiting = null
        node = null
    }

    /**
     * The glass over [rect] (owner coordinates), rounded by [radius]; at least [ROOM_DP] must be left around it for
     * the shadow. The ring and the rim leave out [cut] (a notch's base).
     */
    fun draw(canvas: Canvas, rect: RectF, radius: Float, colors: KeyboardColors, cut: Path? = null) {
        val blurred = node?.takeIf { canvas.isHardwareAccelerated }
        // On whole pixels, with lines of whole pixels, so the ring and the rim are crisp.
        val line = max(1f, round(density))
        box.set(round(rect.left), round(rect.top), round(rect.right), round(rect.bottom))
        // The shadow (0 4 14), cast by a shape the glass then covers.
        paint.style = Paint.Style.FILL
        paint.color = colors.veilOpaque
        paint.setShadowLayer(SHADOW_BLUR_DP * density, 0f, SHADOW_DY_DP * density, colors.veilShadow)
        canvas.drawRoundRect(box, radius, radius, paint)
        paint.clearShadowLayer()
        if (blurred != null) {
            canvas.save()
            clip.rewind()
            clip.addRoundRect(box, radius, radius, Path.Direction.CW)
            canvas.clipPath(clip)
            canvas.drawRenderNode(blurred)
            paint.color = colors.veil
            canvas.drawRect(box, paint)
            canvas.restore()
        }
        canvas.save()
        if (cut != null) canvas.clipOutPath(cut)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = line
        // Day: a 1 dp ink ring just outside the edge; none at night.
        if (colors.veilRing != 0) {
            paint.color = colors.veilRing
            tmp.set(box)
            tmp.inset(-line / 2, -line / 2)
            canvas.drawRoundRect(tmp, radius + line / 2, radius + line / 2, paint)
        }
        // The light rim just inside it.
        paint.color = colors.veilRim
        tmp.set(box)
        tmp.inset(line / 2, line / 2)
        canvas.drawRoundRect(tmp, radius - line / 2, radius - line / 2, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
    }

    companion object {
        /** The room a pop-up leaves around its glass, so its shadow is never cut off. */
        const val ROOM_DP = 20f
        private const val SCALE = 4f
        private const val BLUR_DP = 24f
        /** 0 4 14 in CSS: Android's shadow radius for a 14 dp blur, as DotColors. */
        private const val SHADOW_BLUR_DP = 11f
        private const val SHADOW_DY_DP = 4f
        private val SATURATE: ColorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.6f) })

        /** [ROOM_DP] in whole pixels. */
        fun room(view: View): Int = ceil(ROOM_DP * view.resources.displayMetrics.density).toInt()
    }
}

/** The press timings, for the presses the panels draw themselves (KeyboardView has the keys' own). */
object KeyPress {
    const val PRESS_MS = 50L
    /** The pressed look holds at least this long, even for a quick tap. */
    const val MIN_HOLD_MS = 90L
    const val TINT_OUT_MS = 120L
    /** How long the key-spring runs; it is settled (≈ 180–220 ms) well before. */
    const val SPRING_MS = 400L
    private const val STIFFNESS = 1700f
    private const val DAMPING = 0.55f
    private val STANDARD = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** A key-spring released from [x0] away from rest, [t] ms later: the underdamped closed form. */
    fun spring(x0: Float, t: Float): Float {
        val w = sqrt(STIFFNESS)
        val wd = w * sqrt(1f - DAMPING * DAMPING)
        val sec = t / 1000f
        return exp(-DAMPING * w * sec) * (x0 * cos(wd * sec) + DAMPING * w * x0 / wd * sin(wd * sec))
    }

    /** The key-spring as an interpolator over [SPRING_MS], for a View's animate(). */
    val SPRING = TimeInterpolator { 1f - spring(1f, it * SPRING_MS) }

    /**
     * One press drawn on a canvas: at down it squeezes to [target] and tints over [PRESS_MS]; the pressed look holds
     * [MIN_HOLD_MS], then the scale springs back and the tint fades over [TINT_OUT_MS]. Draw with [scale] and [tint],
     * and again next frame while [moving].
     */
    class Tween(private val target: Float) {
        private var down = false
        private var at = 0L
        private var scaleFrom = 1f
        private var tintFrom = 0f

        // A quick tap: where its press began, so the press goes on in on its own curve until the release.
        private var downAt = 0L
        private var holdScale = 1f
        private var holdTint = 0f

        fun set(on: Boolean) {
            val t = SystemClock.uptimeMillis()
            val held = !on && down && t - at < MIN_HOLD_MS
            if (held) {
                downAt = at
                holdScale = scaleFrom
                holdTint = tintFrom
            }
            scaleFrom = if (held) target else scale()
            tintFrom = if (held) 1f else tint()
            down = on
            at = if (held) at + MIN_HOLD_MS else t
        }

        /** Back at rest at once, as when the press turns into a scroll. */
        fun cancel() {
            down = false
            at = 0L
            scaleFrom = 1f
            tintFrom = 0f
        }

        fun scale(): Float {
            if (snap()) return if (down) target else 1f
            val t = (SystemClock.uptimeMillis() - at).toFloat()
            return when {
                down -> scaleFrom + (target - scaleFrom) * STANDARD.getInterpolation((t / PRESS_MS).coerceIn(0f, 1f))
                // Let go early: it finishes pressing in on the down curve, no jump.
                t < 0f -> holdScale + (target - holdScale) * STANDARD.getInterpolation(((SystemClock.uptimeMillis() - downAt) / PRESS_MS.toFloat()).coerceIn(0f, 1f))
                else -> 1f + spring(scaleFrom - 1f, t)
            }
        }

        fun tint(): Float {
            if (snap()) return if (down) 1f else 0f
            val t = (SystemClock.uptimeMillis() - at).toFloat()
            if (!down && t < 0f) return holdTint + (1f - holdTint) * ((SystemClock.uptimeMillis() - downAt) / PRESS_MS.toFloat()).coerceIn(0f, 1f)
            return if (down) tintFrom + (1f - tintFrom) * (t / PRESS_MS).coerceIn(0f, 1f) else tintFrom * (1f - (t / TINT_OUT_MS).coerceIn(0f, 1f))
        }

        fun moving(): Boolean = !snap() && SystemClock.uptimeMillis() - at < if (down) PRESS_MS else SPRING_MS

        private fun snap() = !ValueAnimator.areAnimatorsEnabled()
    }
}

/**
 * The reveal of a panel over the middle rows: it unrolls down from the top row (a clip growing from the top, 240 ms,
 * standard) while [shift] brings its content down the last 8 dp; closing rolls it back up (180 ms,
 * standard-accelerate).
 */
class Reveal(private val panel: View, private val shift: (Float) -> Unit) {
    private var animator: ValueAnimator? = null
    private var value = 1f

    fun open(unit: Float) {
        set(0f, unit)
        run(1f, unit, OPEN_MS, STANDARD, null)
    }

    fun close(unit: Float, done: () -> Unit) = run(0f, unit, CLOSE_MS, STANDARD_ACCELERATE, done)

    private fun run(to: Float, unit: Float, ms: Long, curve: TimeInterpolator, done: (() -> Unit)?) {
        animator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            set(to, unit)
            done?.invoke()
            return
        }
        animator = ValueAnimator.ofFloat(value, to).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { set(it.animatedValue as Float, unit) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                // A close cut short by an opening never hides the panel the opening shows.
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) done?.invoke()
                }
            })
            start()
        }
    }

    private fun set(v: Float, unit: Float) {
        value = v
        panel.clipBounds = if (v >= 1f) null else Rect(0, 0, panel.width, (panel.height * v).toInt())
        shift(-SHIFT * unit * (1 - v))
    }

    private companion object {
        const val SHIFT = 8f
        const val OPEN_MS = 240L
        const val CLOSE_MS = 180L
        val STANDARD = PathInterpolator(0.2f, 0f, 0f, 1f)
        val STANDARD_ACCELERATE = PathInterpolator(0.3f, 0f, 1f, 1f)
    }
}

/** A Phosphor icon (ui/Icons.kt) as a Drawable, for the panels' plain Views. */
class IconDrawable(name: String, private val sizePx: Float, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val path = Icons.path(name, sizePx)

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.exactCenterX() - sizePx / 2, b.exactCenterY() - sizePx / 2)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun getIntrinsicWidth() = ceil(sizePx).toInt()
    override fun getIntrinsicHeight() = ceil(sizePx).toInt()
    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
