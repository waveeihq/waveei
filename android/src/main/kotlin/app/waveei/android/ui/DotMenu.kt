package app.waveei.android.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.HapticFeedbackConstants
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.animation.PathInterpolator

/**
 * A dot's menu: a veil card beside the dot, nothing behind it dimmed. It lives in its own overlay window, only as big
 * as the card and its shadow, so touches around it still reach the app below; a touch outside closes it.
 * [onPick] gets the item tapped, or slid to from the dot and let go on, or null for a touch outside; [onClosed] comes
 * once it has faded out.
 */
@SuppressLint("ViewConstructor")
class DotMenu(
    context: Context,
    items: List<DotView.Item>,
    private val colors: DotColors,
    private val onPick: (DotView.Item?) -> Unit,
    private val onClosed: (DotMenu) -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val card = LinearLayout(context)
    private val rows: List<TextView>
    private var animator: ValueAnimator? = null
    private var closing = false
    private var hovered: TextView? = null
    private val spot = IntArray(2)

    private fun dp(value: Float) = (value * density).toInt()

    init {
        val room = dp(SHADOW_ROOM)
        setPadding(room, room, room, room)
        clipToPadding = false
        clipChildren = false
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(PAD), dp(PAD_Y), dp(PAD), dp(PAD_Y))
        card.background = Veil()
        addView(card, LayoutParams(dp(WIDTH), LayoutParams.WRAP_CONTENT))
        rows = items.map { item ->
            val tint = if (item.red) colors.red else colors.icon
            TextView(context).apply {
                text = context.getString(item.label)
                textSize = TEXT
                typeface = Fonts.typeface(context)
                fontVariationSettings = "'wght' ${Fonts.MEDIUM}"
                setTextColor(if (item.red) colors.red else colors.ink)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ROW_PAD), 0, dp(ROW_PAD), 0)
                compoundDrawablePadding = dp(ICON_GAP)
                setCompoundDrawablesWithIntrinsicBounds(IconDrawable(item.icon, dp(ICON).toFloat(), tint), null, null, null)
                // Pressed, the row lights as a round plate inside the card's round corners.
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
                        setColor(Palette.alpha(this@DotMenu.colors.ink, PRESSED_ALPHA))
                        cornerRadius = (RADIUS - PAD) * density
                    })
                }
                setOnClickListener { close(item) }
                card.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW)))
            }
        }
    }

    /** The finger held on the dot slides over the rows: the one under ([rawX], [rawY]) on the screen lights. */
    fun slideTo(rawX: Float, rawY: Float) {
        val row = rowAt(rawX, rawY)
        if (row === hovered) return
        hovered?.isPressed = false
        hovered = row
        if (row != null) {
            row.isPressed = true
            row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    /** The finger let go: the row under it is picked. Returns false if it was over none (the menu stays open). */
    fun liftAt(rawX: Float, rawY: Float): Boolean {
        hovered?.isPressed = false
        hovered = null
        val row = rowAt(rawX, rawY) ?: return false
        row.performClick()
        return true
    }

    /**
     * The row for the finger at ([rawX], [rawY]) on the screen: the one level with it, anywhere across the card and
     * [SLIDE_REACH] beside it; above the card the first, below it the last, so the finger need not aim at a row.
     */
    private fun rowAt(rawX: Float, rawY: Float): TextView? {
        if (closing || rows.isEmpty() || rawX.isNaN()) return null
        card.getLocationOnScreen(spot)
        val reach = SLIDE_REACH * density
        if (rawX < spot[0] - reach || rawX > spot[0] + card.width * card.scaleX + reach) return null
        return rows.firstOrNull { row ->
            row.getLocationOnScreen(spot)
            rawY < spot[1] + row.height * card.scaleY
        } ?: rows.last()
    }

    /** Grows out of the dot: from a fifth of its size at the side facing the dot, at [pivotY] (px from the card's top). */
    fun open(fromRight: Boolean, pivotY: Float) {
        card.pivotX = if (fromRight) card.measuredWidth.toFloat() else 0f
        card.pivotY = pivotY
        show(0f)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_MS
            interpolator = EMPHASIZED_DECELERATE
            addUpdateListener { show(it.animatedValue as Float) }
            start()
        }
    }

    /**
     * The fade is on the whole window, not the card: a card below full opacity is drawn through a layer of its own
     * size, which would cut its shadow off at its edges. This one has the room round the card.
     */
    private fun show(p: Float) {
        card.scaleX = 0.2f + 0.8f * p
        card.scaleY = card.scaleX
        alpha = (1.6f * p).coerceAtMost(1f)
        rows.forEachIndexed { i, row -> row.alpha = (1.4f * p - 0.14f * i).coerceIn(0f, 1f) }
    }

    /** Tapped item or a touch outside: tells [onPick] at once, then fades out. */
    private fun close(item: DotView.Item?) {
        if (closing) return
        onPick(item)
        dismiss()
    }

    /** Closes without a pick: shrinks a little and fades, then [onClosed]. */
    fun dismiss() {
        if (closing) return
        closing = true
        animator?.cancel()
        val from = alpha
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CLOSE_MS
            interpolator = EMPHASIZED_ACCELERATE
            addUpdateListener {
                val p = it.animatedValue as Float
                card.scaleX = 1f - 0.1f * p
                card.scaleY = card.scaleX
                alpha = from * (1f - p)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onClosed(this@DotMenu)
            })
            start()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // The room left round the card for its shadow is part of the window, so a touch there is outside too.
        val onShadow = event.actionMasked == MotionEvent.ACTION_DOWN &&
            (event.x < card.left || event.x > card.right || event.y < card.top || event.y > card.bottom)
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE || onShadow) {
            close(null)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    /** The card's veil glass with its ring, hairline and soft shadow. */
    private inner class Veil : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            colors.drawVeil(canvas, rect, RADIUS * density, density, paint)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        /** The card: 184 dp wide, radius 20, 15 sp, as the keyboard's and the app's menus; whole dp only. */
        const val WIDTH = 184f
        const val RADIUS = 20f
        const val PAD = 6f
        /** Above the first row and under the last, so the card does not feel cramped. */
        const val PAD_Y = 10f
        /** A row's height: a full 48 dp touch target. */
        const val ROW = 48f
        const val ROW_PAD = 12f
        const val ICON = 20f
        const val ICON_GAP = 12f
        const val TEXT = 15f
        const val PRESSED_ALPHA = 0.08f

        /** How far beside the card (dp) a finger slid from the dot still picks its rows. */
        const val SLIDE_REACH = 12f

        /** Room around the card for its shadow, inside the window. */
        const val SHADOW_ROOM = 16f

        /** From the dot's centre to the card's near edge. */
        const val GAP = 24f
        const val OPEN_MS = 260L
        const val CLOSE_MS = 140L

        private val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        private val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
