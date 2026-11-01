package app.waveei.android.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import app.waveei.android.DebugLog
import app.waveei.android.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * A floating dot, drawn on a Canvas like the keyboard: the switch dot, a white pearl, or the record dot, white sky
 * over blue water that follows the voice. 18 dp at rest, ×1.3 while touched or [open]. It knows nothing about
 * dictation: [DotService] sets its [mode] and handles its [Actions].
 */
/** A dot held this long (and still) can be dragged: long enough that a finger brushing past it does not move it. */
internal const val DRAG_HOLD_MS = 75L

/**
 * A dot held this long opens its menu: Android's own "Medium" touch & hold delay. A drag starts well before it, so a
 * finger that rests a moment before moving the dot drags it rather than opening the menu.
 */
internal const val MENU_MS = 1000L

@SuppressLint("ViewConstructor")
class DotView(context: Context, private val actions: Actions) : View(context) {
    enum class Mode { IDLE, RECORDING, PAUSED, PROCESSING, SWITCH }

    /** What a dot's menu offers. Each is also an accessibility action on the dot, with its label's id as the action id. */
    enum class Item(val label: Int, val icon: String, val red: Boolean = false) {
        SHOW_KEYBOARD(R.string.show_keyboard, "keyboard"),
        PAUSE(R.string.pause, "pause"),
        RESUME(R.string.resume, "play"),
        CANCEL(R.string.cancel, "x", red = true),
        HIDE(R.string.hide_the_dot, "eye-slash"),
        SETTINGS(R.string.dot_menu_settings, "gear-six"),
    }

    interface Actions {
        fun onTap()

        /** Held still for [MENU_MS]: the dot's menu opens under the finger, as a launcher's does. */
        fun onMenu()

        /** Moved on after the menu opened, away from it: the menu closes and the dot is dragged. */
        fun onMenuLeft()

        /** Which side of the dot its open menu is on: true to the right, null with no menu open. */
        fun menuToRight(): Boolean? = null

        /** Slid on towards the open menu, the finger still down: the row under ([rawX], [rawY]) lights. */
        fun onMenuSlide(rawX: Float, rawY: Float) {}

        /** Let go after such a slide: the row under the finger is picked; off the rows the menu stays open. */
        fun onMenuLift(rawX: Float, rawY: Float) {}

        /** A menu item picked through accessibility, without the menu. */
        fun onItem(item: Item)

        /** The finger has moved this many pixels since it came down on the dot that is being dragged. */
        fun onDrag(dx: Float, dy: Float)

        /** The drag ended; thrown sideways at [speed] pixels a second, [flungRight] says to which edge it flies on. */
        fun onDragEnd(flungRight: Boolean?, speed: Float)

        /**
         * A quick sideways flick before the hold, to the right or to the left: the dot, which stayed put under the
         * finger, flies to that screen edge and tucks away there, at the finger's [speed] in pixels a second, slowing down.
         */
        fun onSwipeAway(toRight: Boolean, speed: Float)

        /** A finger came down on the dot, whatever it turns out to be. */
        fun onTouchStart()

        /** The finger is off the dot again. */
        fun onTouchEnd()
    }

    /** The bubble's veil, day or night as Settings › Theme. The dots themselves look the same at night. */
    var colors = DotColors.DAY
        set(value) {
            field = value
            invalidate()
        }
    var mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            contentDescription = when (value) {
                Mode.IDLE -> context.getString(R.string.dictate)
                Mode.RECORDING -> context.getString(R.string.cd_mic_stop)
                Mode.PAUSED -> context.getString(R.string.cd_resume)
                Mode.PROCESSING -> context.getString(R.string.processing)
                Mode.SWITCH -> context.getString(R.string.cd_switch_keyboard)
            }
            invalidate()
        }

    /** What the dot's menu offers now, for accessibility. */
    var items: List<Item> = emptyList()

    /** How opaque the dot is drawn, from 0 to 1: a resting dot is see-through to the chosen degree. Changes fade. */
    var opacity = 1f
        set(value) {
            if (field == value) return
            field = value
            if (visibility == VISIBLE) fadeTo(value)
        }

    /** The opacity actually drawn: it moves towards [opacity]. */
    private var drawn = 1f
    private var fader: ValueAnimator? = null

    /** Opened by a tap, or busy with a dictation: the dot stays ×1.3 until it rests. */
    var open = false
        set(value) {
            if (field == value) return
            field = value
            resize()
        }

    /** Its menu is open: the dot stays ×1.3 and opaque beside it. */
    var held = false
        set(value) {
            if (field == value) return
            field = value
            resize()
            invalidate()
        }

    /** ×1.3 while touched, open or held; back to a dot otherwise. Never bigger, also while dragged. */
    private fun resize() {
        // Going, it keeps the size it had: a dot dissolves, never shrinks; [appear] takes the new size.
        if (going) return
        val target = sizeNow()
        if (target == sizeTarget) return
        sizeTarget = target
        sizer?.cancel()
        sizer = ValueAnimator.ofFloat(size, target).apply {
            duration = if (target > size) GROW_MS else SHRINK_MS
            interpolator = STANDARD
            addUpdateListener {
                size = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun sizeNow() = if (touching || open || held) OPEN_DOT else 1f

    /** Takes the size it should have at once, while it cannot be seen change. */
    private fun snapSize() {
        sizer?.cancel()
        sizeTarget = sizeNow()
        size = sizeTarget
    }

    /** The dot's size drawn, from 1 (a dot) to [OPEN_DOT]. */
    private var size = 1f
    private var sizeTarget = 1f
    private var sizer: ValueAnimator? = null

    /** Swiped away: half the dot hides past the window's edge, which sits on the screen edge. Changes slide, see [settleTuck]. */
    var tucked = false
        set(value) {
            if (field == value) return
            field = value
            tucker?.cancel()
            tucker = ValueAnimator.ofFloat(tuck, if (value) 1f else 0f).apply {
                duration = FADE_MS
                addUpdateListener {
                    tuck = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    /** How far the dot is drawn towards the edge, from 0 (in place) to 1 (its centre on the edge). */
    private var tuck = 0f
    private var tucker: ValueAnimator? = null

    /** Ends a slide out of or into the edge at once: pulled out by a finger, the dot is under it straight away. */
    fun settleTuck() {
        tucker?.end()
    }

    /** Greyed out, as the keyboard's mic: dictation cannot start now (the week's free minutes are used up). */
    var muted = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** True when the dot rests on the right half of the screen: it is anchored by its right end, and the bubble grows to the left. */
    var rightEdge = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** A short line in a veil bubble beside the dot, away from the edge; null fades it out. */
    var bubble: String? = null
        set(value) {
            field = value
            if (value != null) shownBubble = value
            requestLayout()
            bubbler?.cancel()
            bubbler = ValueAnimator.ofFloat(bubbleIn, if (value != null) 1f else 0f).apply {
                duration = BUBBLE_FADE_MS
                interpolator = if (value != null) EMPHASIZED_DECELERATE else EMPHASIZED_ACCELERATE
                addUpdateListener {
                    bubbleIn = it.animatedValue as Float
                    if (bubble == null && bubbleIn == 0f) {
                        shownBubble = null
                        requestLayout()
                    }
                    invalidate()
                }
                start()
            }
        }

    /** The bubble drawn, kept while it fades out, and how far it has faded in. */
    private var shownBubble: String? = null
    private var bubbleIn = 0f
    private var bubbler: ValueAnimator? = null

    /** 1 while the dot is there, down to 0 once [vanish]ed; a bubble may stay where it was. */
    private var present = 1f
    private var vanisher: ValueAnimator? = null
    val vanished: Boolean get() = present < 1f

    /** 1 while the dot is up, down to 0 as it [hide]s: it dissolves where it is, at its full size. */
    private var leaving = 1f
    private var leaver: ValueAnimator? = null

    /** Up, and not on its way out. */
    val shown: Boolean get() = visibility == VISIBLE && leaver == null

    /** On its way out, or out: by [hide] or [vanish]. */
    private val going: Boolean get() = leaver != null || vanishing
    private var vanishing = false

    /** The pulse and the breath as they were when it began to go: it dissolves as it was, no swell or flicker. */
    private var leftPulse = 1f
    private var leftBreath = 1f

    // The record dot's water, as the keyboard's mic: the voice smoothed for the waves and, slower, for the height;
    // the fill and crests drawn (shares of the dot), and where each drifting wave is along its length.
    private val voice = VoiceEnvelope()
    private var heard = 0f
    private var swell = 0f
    private var quietSince = 0L
    private var fill = REST_FILL
    private var crest = REST_CREST
    private var frontPhase = 0f
    private var backPhase = BACK_PHASE
    private var frameAt = 0L
    private var wasRecording = false

    private val density = resources.displayMetrics.density
    // Greyed, never see-through: the muted dot (no colour, lifted towards white) and the processing one (half colour).
    private val mutedPaint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply {
                setSaturation(0f)
                postConcat(ColorMatrix(floatArrayOf(1 - MUTED_LIFT, 0f, 0f, 0f, MUTED_LIFT * 255, 0f, 1 - MUTED_LIFT, 0f, 0f, MUTED_LIFT * 255, 0f, 0f, 1 - MUTED_LIFT, 0f, MUTED_LIFT * 255, 0f, 0f, 0f, 1f, 0f)))
            },
        )
    }
    private val busyPaint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(BUSY_SATURATION) }) }
    // The recording mark, as the keyboard's mic draws it at two thirds: a softened core over the same stroke blurred into a wider glow.
    private val recCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = DotColors.RING
        maskFilter = BlurMaskFilter(REC_CORE_BLUR * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val arcFade = ArcFade()
    private val recGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(REC_GLOW * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, BUBBLE_TEXT, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        Fonts.weight(context, this, Fonts.MEDIUM)
    }
    private val tmp = RectF()
    private val circle = Path()
    private val back = Path()
    private val front = Path()
    private val surface = Path()
    private val matrix = Matrix()

    // Gradients made once in a unit box and moved onto the dot with a matrix: nothing is made per frame.
    private val water = LinearGradient(0f, 0f, 0f, 1f, KeyboardColors.WATER, WATER_STOPS, Shader.TileMode.CLAMP)
    private val shade = RadialGradient(0f, 0f, 1f, intArrayOf(0, 0, DotColors.SHADE), floatArrayOf(0f, SHADE_FROM, 1f), Shader.TileMode.CLAMP)

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    /** A finger is on the dot: it shows ×1.3 and fully opaque until the finger leaves. */
    private var touching = false
    private var downAt = 0L

    /** Held still for [DRAG_HOLD_MS]: moved now, it is dragged. */
    private var movable = false

    /** Held still for [MENU_MS]: the menu is open; moved on past [MENU_DRAG], it closes and the dot is dragged. */
    private var armed = false

    /** With the menu open, the finger went on towards it: it picks a row by sliding to it and letting go. */
    private var sliding = false
    private var velocity: VelocityTracker? = null

    /** Moves the finger made while the dot went along, for the log: how smoothly a drag was followed. */
    private var moves = 0
    private var firstMoveAt = 0L

    /** Not a real touch on the dot: it moved off, a second finger came down, or it was too short. */
    private var rejected = false
    private val hold = Runnable {
        movable = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        DebugLog.add("dot: held still, move it now")
    }
    private val arm = Runnable {
        armed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        DebugLog.add("dot: held still, menu open")
        actions.onMenu()
    }

    private fun dp(value: Float) = value * density

    /** The width of the round end the dot rests on: the window is anchored by it, and the dot sits in its middle. */
    val slot: Float get() = dp(BUTTON)

    /** The space around the dot inside the window, with the room for its shadow. */
    val margin: Float get() = dp(MARGIN + ROOM)

    /**
     * How far the window reaches past the screen edge it rests by: only the shadow's room is out there, so the dot
     * still rests [MARGIN] from the edge.
     */
    val overhang: Float get() = dp(ROOM)

    /** Where the dot's slot starts inside the view, without the bubble. */
    fun iconLeft(): Float = if (rightEdge) width - margin - slot else margin

    fun iconWidth(): Float = slot

    /** The dot's centre in the view, where it is drawn now (half past the edge when tucked: on the screen edge). */
    fun dotCentre(): PointF {
        val place = iconLeft() + slot / 2
        val edge = if (rightEdge) width - overhang else overhang
        return PointF(place + tuck * (edge - place), margin + slot / 2)
    }

    /** The voice level, 0..1, read every frame while recording: the same meter as the keyboard's mic water. */
    var meter: (() -> Float)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bubbleWidth = shownBubble?.let { text.measureText(it) + dp(BUBBLE_PAD) * 2 + dp(GAP) + dp(BUBBLE_ROOM) } ?: 0f
        val width = slot + bubbleWidth + margin * 2
        // One height for every dot, so a bubble never moves it; room all round for the shadow, and more below where it falls.
        val height = slot + margin * 2 + dp(SHADOW_ROOM)
        setMeasuredDimension(width.toInt() + 1, height.toInt() + 1)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val still = !ValueAnimator.areAnimatorsEnabled()
        val settling = step(now, still)
        val centre = dotCentre()
        shownBubble?.let { drawBubble(canvas, it, iconLeft() + slot / 2, centre.y) }
        if (present > 0f) {
            // A recording dot softly fades in and out, its ring with it, so a recording is never forgotten, also under a
            // finger; a muted one greys, never turns see-through.
            val pulse = if (leaver != null) leftPulse else if (mode == Mode.RECORDING && !still) pulse() else 1f
            val alpha = (if (touching || held) 1f else drawn) * present * leaving * pulse
            // Drawn through a layer of its own, so the transparency shows however Android treats the window.
            val layer = if (alpha < 1f) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).toInt()) else null
            // While a dictation is processing the dot breathes slowly: alive, never blinking.
            val breath = if (leaver != null) leftBreath else if (mode == Mode.PROCESSING && !still) breath() else 1f
            val r = dp(DOT) / 2 * size * breath
            if (mode == Mode.SWITCH) {
                drawPearl(canvas, centre.x, centre.y, r)
            } else {
                val grey = when {
                    muted && mode == Mode.IDLE -> mutedPaint
                    mode == Mode.PROCESSING -> busyPaint
                    else -> null
                }
                val greyed = grey?.let { canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), it) }
                drawRecord(canvas, centre.x, centre.y, r)
                greyed?.let(canvas::restoreToCount)
                if (mode == Mode.RECORDING || mode == Mode.PROCESSING) drawRecMark(canvas, centre.x, centre.y, r, still)
            }
            layer?.let(canvas::restoreToCount)
        }
        // The pulse, the breathing and the water are slow: 30 frames a second are enough and spare the battery,
        // which matters while a dictation waits minutes for a field.
        if (!still && (mode == Mode.RECORDING || mode == Mode.PROCESSING) || settling) postInvalidateDelayed(FRAME_MS)
    }

    /**
     * The keyboard mic's recording mark at two thirds: a 2 dp gap, a 3 dp stroke blurred by 2 dp at 75 % over an 8 dp
     * glow at 65 %, the core #0E70F4. Processing, a 90° arc on an 18 % track turns once per 1.1 s;
     * still with Remove animations, at 12 o'clock.
     */
    private fun drawRecMark(canvas: Canvas, cx: Float, cy: Float, r: Float, still: Boolean) {
        val radius = r + dp(REC_GAP) + dp(REC_CORE) / 2
        tmp.set(cx - radius, cy - radius, cx + radius, cy + radius)
        recCore.strokeWidth = dp(REC_WIDTH)
        recGlow.strokeWidth = dp(REC_WIDTH)
        recGlow.color = Palette.alpha(DotColors.RING, REC_GLOW_ALPHA)
        if (mode == Mode.RECORDING) {
            recCore.alpha = (REC_CORE_ALPHA * 255).toInt()
            canvas.drawOval(tmp, recGlow)
            canvas.drawOval(tmp, recCore)
            return
        }
        recCore.alpha = (BUSY_TRACK * 255).toInt()
        canvas.drawOval(tmp, recCore)
        recCore.alpha = (REC_CORE_ALPHA * 255).toInt()
        val start = if (still) -90f else (SystemClock.uptimeMillis() % SPIN_MS) / SPIN_MS.toFloat() * 360f - 90f
        // Its ends fade in, as soft as its blurred sides, as on the keyboard's mic.
        val shader = arcFade.shader(cx, cy, start, BUSY_ARC, DotColors.RING)
        recGlow.shader = shader
        recCore.shader = shader
        canvas.drawArc(tmp, start, BUSY_ARC, false, recGlow)
        canvas.drawArc(tmp, start, BUSY_ARC, false, recCore)
        recGlow.shader = null
        recCore.shader = null
    }

    /** Moves the water towards its state; true while it is still on its way there. */
    private fun step(now: Long, still: Boolean): Boolean {
        val dt = (if (frameAt == 0L) 0L else now - frameAt).coerceIn(0L, MAX_STEP_MS).toFloat()
        frameAt = now
        // As the keyboard's mic water, from the same meter 40 times a second: the waves follow the voice quickly, the
        // height slower, as a mass.
        // The noise floor off first, with the keyboard's time constants (VoiceEnvelope).
        val input = if (mode == Mode.RECORDING) meter?.invoke() ?: 0f else 0f
        if (mode == Mode.RECORDING && !wasRecording) voice.reset()
        wasRecording = mode == Mode.RECORDING
        voice.step(input, now, dt, instant = still)
        heard = voice.level
        swell = voice.swell
        if (mode == Mode.RECORDING && heard < QUIET) {
            if (quietSince == 0L) quietSince = now
        } else {
            quietSince = 0L
        }
        // In a silence the waves settle in one slow move.
        val calm = if (quietSince == 0L) 0f else STANDARD.getInterpolation(((now - quietSince - CALM_AFTER_MS) / CALM_MS).coerceIn(0f, 1f))
        val (fillTo, crestTo) = when (mode) {
            Mode.RECORDING -> (QUIET_FILL + LISTEN_FILL_RANGE * swell) to (CREST_BASE + CREST_VOICE * heard) * (1f - calm) + CALM_CREST * calm
            Mode.IDLE, Mode.SWITCH -> REST_FILL to REST_CREST
            Mode.PAUSED -> PAUSED_FILL to PAUSED_CREST
            Mode.PROCESSING -> REST_FILL to BUSY_CREST
        }
        val ease = 1f - exp(-dt / EASE_MS)
        fill += (fillTo - fill) * ease
        crest += ((if (still) 0f else crestTo) - crest) * ease
        // Drift: a long front wave slides one way, the back one the other, at a steady pace loud or quiet.
        if (!still) {
            frontPhase = (frontPhase + TWO_PI / FRONT_SLIDE_MS * dt) % TWO_PI
            backPhase = (backPhase - TWO_PI / BACK_SLIDE_MS * dt) % TWO_PI
        }
        return abs(fillTo - fill) > SETTLED || abs((if (still) 0f else crestTo) - crest) > SETTLED
    }

    /** The two soft shadows under both dots, drawn with the dot's body. */
    private fun drawBody(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        paint.shader = null
        paint.color = color
        paint.setShadowLayer(dp(FAR_SHADOW_BLUR), 0f, dp(FAR_SHADOW_Y), DotColors.SHADOW)
        canvas.drawCircle(cx, cy, r, paint)
        paint.setShadowLayer(dp(NEAR_SHADOW_BLUR), 0f, dp(NEAR_SHADOW_Y), DotColors.SHADOW)
        canvas.drawCircle(cx, cy, r, paint)
        paint.clearShadowLayer()
    }

    /** The faint shade towards the lower edge, from a light spot up and left of the centre. */
    private fun drawShade(canvas: Canvas, cx: Float, cy: Float, r: Float, reach: Float) {
        matrix.setScale(2 * r * reach, 2 * r * reach)
        matrix.postTranslate(cx - r + 2 * r * SHADE_X, cy - r + 2 * r * SHADE_Y)
        shade.setLocalMatrix(matrix)
        paint.color = OPAQUE
        paint.shader = shade
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }

    private fun drawPearl(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawBody(canvas, cx, cy, r, DotColors.PEARL)
        drawShade(canvas, cx, cy, r, PEARL_REACH)
    }

    private fun drawRecord(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        drawBody(canvas, cx, cy, r, DotColors.SKY)
        val s = 2 * r
        val left = cx - r
        val top = cy - r
        val level = top + s * (1f - fill)
        val amp = crest * s
        circle.reset()
        circle.addCircle(cx, cy, r, Path.Direction.CW)
        wave(back, left, s, level - s * BACK_RISE, amp * BACK_AMP, s * BACK_LENGTH, backPhase, top + s)
        back.op(circle, Path.Op.INTERSECT)
        paint.color = DotColors.BACK_WAVE
        canvas.drawPath(back, paint)
        wave(front, left, s, level, amp, s * FRONT_LENGTH, frontPhase, top + s)
        front.op(circle, Path.Op.INTERSECT)
        // The gradient is fixed to the dot, not to the surface: rising water shows more of it, never darker water.
        matrix.setScale(1f, s * (1f - WATER_FROM))
        matrix.postTranslate(0f, top + s * WATER_FROM)
        water.setLocalMatrix(matrix)
        // Opaque, or the paint's alpha would thin the gradient.
        paint.color = OPAQUE
        paint.shader = water
        canvas.drawPath(front, paint)
        paint.shader = null
        surface.reset()
        for (i in 0..WAVE_STEPS) {
            val x = s * i / WAVE_STEPS
            val y = level + amp * sin(x / (s * FRONT_LENGTH) * TWO_PI + frontPhase)
            if (i == 0) surface.moveTo(left + x, y) else surface.lineTo(left + x, y)
        }
        canvas.save()
        canvas.clipPath(circle)
        line.color = if (mode == Mode.RECORDING) Palette.alpha(0xFFFFFFFF.toInt(), SURFACE_ALPHA + (1f - SURFACE_ALPHA) * heard) else DotColors.SURFACE
        line.strokeWidth = maxOf(dp(SURFACE_MIN), s * SURFACE_WIDTH)
        canvas.drawPath(surface, line)
        canvas.restore()
        drawShade(canvas, cx, cy, r, RECORD_REACH)
    }

    /** A water body from [left] across [width]: its surface a sine around [level], down to [bottom]. */
    private fun wave(path: Path, left: Float, width: Float, level: Float, amp: Float, length: Float, phase: Float, bottom: Float) {
        path.reset()
        path.moveTo(left, bottom)
        for (i in 0..WAVE_STEPS) {
            val x = width * i / WAVE_STEPS
            path.lineTo(left + x, level + amp * sin(x / length * 2 * Math.PI.toFloat() + phase))
        }
        path.lineTo(left + width, bottom)
        path.close()
    }

    /** Fades the dot in to [opacity], from [from] or from wherever it is now. */
    fun appear(from: Float = drawn) {
        visibility = VISIBLE
        vanisher?.cancel()
        leaver?.cancel()
        leaver = null
        leaving = 1f
        present = 1f
        vanishing = false
        snapSize()
        drawn = from
        fadeTo(opacity)
    }

    /** Dissolves where it is, never smaller, then goes; with animations off, at once. */
    fun hide() {
        if (leaver != null) return
        // A finger on it lets go of it: no menu or drag on a dot that goes, and the touch ends.
        removeCallbacks(hold)
        removeCallbacks(arm)
        if (touching) {
            rejected = true
            release()
        }
        sizer?.cancel()
        sizeTarget = size
        val still = !ValueAnimator.areAnimatorsEnabled()
        leftPulse = if (mode == Mode.RECORDING && !still) pulse() else 1f
        leftBreath = if (mode == Mode.PROCESSING && !still) breath() else 1f
        if (still) {
            gone()
            return
        }
        leaver = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = LEAVE_MS
            interpolator = STANDARD
            addUpdateListener {
                leaving = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) gone()
                }
            })
            start()
        }
    }

    private fun gone() {
        leaver = null
        leaving = 1f
        fader?.cancel()
        snapSize()
        drawn = 0f
        visibility = GONE
    }

    /** Hide the dot: it dissolves at its full size; a bubble can still show where it was. */
    fun vanish() {
        vanisher?.cancel()
        vanishing = true
        sizer?.cancel()
        sizeTarget = size
        vanisher = ValueAnimator.ofFloat(present, 0f).apply {
            duration = VANISH_MS
            interpolator = EMPHASIZED_ACCELERATE
            addUpdateListener {
                present = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun fadeTo(target: Float) {
        fader?.cancel()
        fader = ValueAnimator.ofFloat(drawn, target).apply {
            duration = FADE_MS
            interpolator = STANDARD
            addUpdateListener {
                drawn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** A slow, small swell of the dot's size. */
    /** The recording dot's opacity: a slow, gentle fade between full and [PULSE_DEPTH] lighter. */
    private fun pulse(): Float {
        val phase = (SystemClock.uptimeMillis() % PULSE_MS) / PULSE_MS.toFloat() * 2 * Math.PI
        return 1f - PULSE_DEPTH * (0.5f - 0.5f * cos(phase).toFloat())
    }

    private fun breath(): Float {
        val phase = (SystemClock.uptimeMillis() % BREATH_MS) / BREATH_MS.toFloat() * 2 * Math.PI
        return 1f + BREATH_DEPTH * (0.5f - 0.5f * cos(phase).toFloat())
    }

    private fun drawBubble(canvas: Canvas, value: String, cx: Float, cy: Float) {
        val width = text.measureText(value) + dp(BUBBLE_PAD) * 2
        val h = dp(BUBBLE_HEIGHT)
        val left = if (rightEdge) cx - slot / 2 - dp(GAP) - width else cx + slot / 2 + dp(GAP)
        val top = cy - h / 2 + dp(BUBBLE_RISE) * (1f - bubbleIn)
        tmp.set(left, top, left + width, top + h)
        colors.drawVeil(canvas, tmp, h / 2, density, veilPaint, bubbleIn)
        text.color = Palette.alpha(colors.ink, bubbleIn)
        canvas.drawText(value, tmp.centerX(), tmp.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    /**
     * A tap acts. Held still for [DRAG_HOLD_MS] the dot buzzes and moves with the finger; held still on for the
     * [MENU_MS] it buzzes again and its menu opens, the finger still down. Moved on from there towards the menu, the
     * finger slides over its rows and letting go on one picks it; moved any other way, as on a launcher's icon
     * (Launcher3's PreDragCondition), the menu closes and the dot is dragged. Before the first buzz it stays put: only a quick sideways flick sends it to the edge, and anything else (a brush,
     * a slow slide) does nothing.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // On its way out it takes no touch.
        if (leaver != null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                downAt = event.eventTime
                dragging = false
                movable = false
                armed = false
                sliding = false
                rejected = false
                moves = 0
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                track(event)
                postDelayed(hold, DRAG_HOLD_MS)
                postDelayed(arm, MENU_MS)
                // Under the finger it is drawn opaque: the fade starts from there on release, with no dip.
                fader?.cancel()
                drawn = 1f
                touching = true
                resize()
                invalidate()
                actions.onTouchStart()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!dragging) reject("a second finger")
            MotionEvent.ACTION_MOVE -> {
                // The system's touch slop: moved past it before the hold, it is no tap (a flick is told at
                // the lift); after the hold, it starts the drag.
                track(event)
                val moved = maxOf(abs(event.rawX - downX), abs(event.rawY - downY))
                if (!dragging && !sliding && !rejected) {
                    if (armed && moved > dp(MENU_DRAG)) {
                        // Towards the menu, more sideways than not, it slides onto the rows; any other way it drags.
                        val dx = event.rawX - downX
                        val toMenu = actions.menuToRight()?.let { (dx > 0) == it && abs(dx) * 2 > abs(event.rawY - downY) } ?: false
                        if (toMenu) {
                            sliding = true
                        } else {
                            dragging = true
                            actions.onMenuLeft()
                        }
                    } else if (movable && !armed && moved > slop) {
                        dragging = true
                        removeCallbacks(arm)
                    } else if (!movable && moved > slop) {
                        reject("it moved ${((event.rawX - downX) / density).toInt()}, ${((event.rawY - downY) / density).toInt()} dp after ${event.eventTime - downAt} ms")
                    }
                }
                if (sliding) actions.onMenuSlide(event.rawX, event.rawY)
                // From where the finger came down, not step by step: no rounding or stop at an edge builds up.
                if (dragging) {
                    if (moves++ == 0) firstMoveAt = event.eventTime
                    actions.onDrag(event.rawX - downX, event.rawY - downY)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                removeCallbacks(arm)
                release()
                val held = event.eventTime - downAt
                val dx = event.rawX - downX
                val speed = velocity?.run { computeCurrentVelocity(1000); abs(xVelocity) } ?: 0f
                val vx = velocity?.xVelocity ?: 0f
                val vy = velocity?.yVelocity ?: 0f
                // Let go while moving fast sideways: it flies on to that edge.
                val flung = speed > dp(FLING) && abs(vx) > 1.5f * abs(vy)
                when {
                    // Moved after the hold and let go while thrown sideways: it flies on to that edge, as a flick does.
                    dragging -> {
                        dragging = false
                        DebugLog.add("dot: dragged, $moves moves in ${event.eventTime - firstMoveAt} ms${if (flung) ", thrown ${if (vx > 0) "right" else "left"} at ${(speed / density).toInt()} dp/s" else ""}")
                        actions.onDragEnd(if (flung) vx > 0 else null, speed)
                    }
                    // Before the hold, a quick sideways flick: it flies to the edge the flick points to.
                    !movable && flung && sideways(dx, event.rawY - downY, dp(SWIPE)) -> {
                        DebugLog.add("dot: flicked ${if (vx > 0) "right" else "left"}, ${(speed / density).toInt()} dp/s")
                        actions.onSwipeAway(vx > 0, speed)
                    }
                    !movable && sideways(dx, event.rawY - downY, dp(SWIPE)) -> DebugLog.add("dot: slid ${(dx / density).toInt()} dp at ${(speed / density).toInt()} dp/s, too slow for a flick, nothing to do")
                    sliding -> {
                        sliding = false
                        actions.onMenuLift(event.rawX, event.rawY)
                    }
                    rejected -> Unit
                    event.x < 0 || event.y < 0 || event.x > width || event.y > height -> reject("released outside")
                    held < MIN_TAP_MS -> reject("too short, $held ms")
                    // Held still past [MENU_MS]: the menu opened then, and stays.
                    armed -> Unit
                    else -> actions.onTap()
                }
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                removeCallbacks(arm)
                release()
                velocity?.recycle()
                velocity = null
                if (sliding) {
                    sliding = false
                    actions.onMenuSlide(Float.NaN, Float.NaN)
                }
                if (dragging) {
                    dragging = false
                    actions.onDragEnd(null, 0f)
                }
            }
        }
        return true
    }

    /** Mostly sideways and further than [least]. */
    private fun sideways(dx: Float, dy: Float, least: Float) = abs(dx) > least && abs(dx) > 1.5f * abs(dy)

    /** The finger's movement on the screen, not in the dot's window, which moves along with it while dragged. */
    private fun track(event: MotionEvent) {
        val onScreen = MotionEvent.obtain(event).apply { setLocation(event.rawX, event.rawY) }
        velocity?.addMovement(onScreen)
        onScreen.recycle()
    }

    private fun release() {
        if (!touching) return
        touching = false
        if (drawn != opacity) fadeTo(opacity)
        resize()
        invalidate()
        actions.onTouchEnd()
    }

    private fun reject(reason: String) {
        rejected = true
        removeCallbacks(hold)
        removeCallbacks(arm)
        DebugLog.add("dot: touch ignored, $reason")
    }

    override fun performClick(): Boolean {
        super.performClick()
        actions.onTap()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        for (item in items) info.addAction(AccessibilityNodeInfo.AccessibilityAction(item.label, context.getString(item.label)))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val item = items.firstOrNull { it.label == action } ?: return super.performAccessibilityAction(action, arguments)
        actions.onItem(item)
        return true
    }

    private companion object {
        const val BUTTON = 32f
        const val DOT = 18f

        /** How much bigger the dot is touched, open, busy, held by its menu or dragged. */
        const val OPEN_DOT = 1.3f

        /** The recording mark: gap, core and glow (dp), the glow's strength; processing, the arc, its track and its turn. */
        const val REC_GAP = 2f
        const val REC_CORE = 2f
        const val REC_WIDTH = 3f
        const val REC_CORE_BLUR = 2f
        const val REC_CORE_ALPHA = 0.75f
        const val REC_GLOW = 8f
        const val REC_GLOW_ALPHA = 0.65f
        const val BUSY_ARC = 90f
        const val BUSY_TRACK = 0.18f
        const val SPIN_MS = 1100L
        /** Processing, the water greys as the mic's: saturation 0.45. */
        const val BUSY_SATURATION = 0.45f
        /** The surface line's alpha in a silence (up to 1 when loud). */
        const val SURFACE_ALPHA = 0.8f

        /** How long the dot takes to fade in or to its resting opacity, and to grow on a touch or shrink back. */
        const val FADE_MS = 180L
        const val GROW_MS = 140L
        const val SHRINK_MS = 180L

        /** Hide the dot: it dissolves over this time. */
        const val VANISH_MS = 160L

        /** Going (a tap that brings the keyboard back, text landed): it dissolves over this time, never shrinking. */
        const val LEAVE_MS = 220L

        /** With the menu open, a move this far (dp) closes it and drags the dot: Launcher3's deep_shortcuts_start_drag_threshold. */
        const val MENU_DRAG = 16f
        /** From the screen edge to the dot's slot: the dot's centre rests 20 dp from the edge. */
        const val MARGIN = 4f
        const val GAP = 8f

        /**
         * Room on every side for the shadow (blur 12, about 12 dp past the dot) around the dot at ×1.3 and while
         * it breathes, so the window does not cut it; the window reaches this far past the screen edge.
         */
        const val ROOM = 6f

        /** More room below, where the shadow falls (y +4). */
        const val SHADOW_ROOM = 4f

        /** Both dots' shadows (0 1 2 and 0 4 12 in CSS) as Android shadow radii. */
        const val NEAR_SHADOW_Y = 1f
        const val NEAR_SHADOW_BLUR = 1f
        const val FAR_SHADOW_Y = 4f
        const val FAR_SHADOW_BLUR = 9.5f

        /** The lower-edge shade: from a light spot at 35 %, 30 % of the dot, clear for its first 35 %, out to this reach. */
        const val SHADE_X = 0.35f
        const val SHADE_Y = 0.3f
        const val SHADE_FROM = 0.35f
        const val PEARL_REACH = 0.955f
        const val RECORD_REACH = 0.8f

        /**
         * The record dot's water, the keyboard's mic water in shares of the dot: at rest, paused, processing, and with
         * the voice from silence to loud; in a long silence the waves calm to [CALM_CREST].
         */
        const val REST_FILL = 0.40f
        const val REST_CREST = 0.02f
        const val PAUSED_FILL = 0.42f
        const val PAUSED_CREST = 0.003f
        const val BUSY_CREST = 0.005f
        // As the keyboard's mic: a little under rest in a silence, up to 0.86 with a loud voice.
        const val QUIET_FILL = 0.34f
        const val LISTEN_FILL_RANGE = 0.52f
        const val CREST_BASE = 0.0066f
        const val CREST_VOICE = 0.068f
        const val CALM_CREST = 0.008f
        const val QUIET = 0.03f
        const val CALM_AFTER_MS = 400L
        const val CALM_MS = 600f

        /** Drift: the waves' lengths, how long each takes to slide one length, the back wave's rise, height and offset. */
        const val FRONT_LENGTH = 1.05f
        const val BACK_LENGTH = 0.72f
        const val FRONT_SLIDE_MS = 2600f
        const val BACK_SLIDE_MS = 3400f
        const val BACK_RISE = 0.03f
        const val BACK_AMP = 0.8f
        const val BACK_PHASE = 1.3f
        const val TWO_PI = 2 * Math.PI.toFloat()
        const val WAVE_STEPS = 24
        const val SURFACE_WIDTH = 0.03f
        const val SURFACE_MIN = 0.6f
        val WATER_STOPS = floatArrayOf(0f, 0.55f, 1f)

        /** Where in the dot, from its top, the water's gradient starts. */
        const val WATER_FROM = 0.2f

        /** How fast the water eases to a new state (ms); the voice's own time constants are VoiceEnvelope's. */
        const val EASE_MS = 100f
        const val SETTLED = 0.002f
        const val MAX_STEP_MS = 100L

        /** How often the pulsing, breathing or flowing dot redraws. */
        const val FRAME_MS = 33L

        /** The bubble: veil, 32 dp high, 13 sp, rising 8 dp as it fades in; room beside it for its shadow. */
        const val BUBBLE_HEIGHT = 32f
        const val BUBBLE_TEXT = 13f
        const val BUBBLE_PAD = 15f
        const val BUBBLE_RISE = 8f
        const val BUBBLE_ROOM = 10f
        const val BUBBLE_FADE_MS = 200L

        /** A flick: at least this far sideways, in dp; a dot by the edge has little room for more. */
        const val SWIPE = 16f

        /** A flick, or a drag let go, faster than this sideways, in dp a second, is a throw to the edge. */
        const val FLING = 1_000f

        /** A touch shorter than this is a brush, not a tap. */
        const val MIN_TAP_MS = 30L

        /** A greyed-out record dot, as the keyboard draws its mic: no colour, 45 % white over it, opaque. */
        const val MUTED_LIFT = 0.45f
        const val OPAQUE = 0xFF000000.toInt()
        /** The recording dot's pulse: its period and how much lighter it gets. */
        const val PULSE_MS = 1600L
        const val PULSE_DEPTH = 0.45f
        const val BREATH_MS = 1_800L
        const val BREATH_DEPTH = 0.18f

        val STANDARD = PathInterpolator(0.2f, 0f, 0f, 1f)
        val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
