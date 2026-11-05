package app.waveei.android.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import app.waveei.android.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.emoji2.emojipicker.R as EmojiR

/**
 * The emoji panel over the keyboard's grid under the top row: a category pill on the outer side, ⌫ on the hand's
 * side, and the emoji in one card that scrolls, Recent first. A hold opens the skin tones as veil glass. Its own view,
 * since the AndroidX picker cannot take this header; the emoji list, the recents and the chosen skin tones are the
 * picker's own, so they carry over from it.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val onPick: (String) -> Unit,
    private val onBackspace: (word: Boolean) -> Unit,
) : View(context) {
    var colors = KeyboardColors.DAY
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    /** The keyboard's design unit in pixels. */
    var unit = 1f
        set(value) {
            if (field == value) return
            field = value
            emojiText.textSize = EMOJI * value
            emptyText.textSize = EMPTY_TEXT * value
            popupText.textSize = POPUP_EMOJI * value
            icons.clear()
            place()
            invalidate()
        }

    var leftHanded = false
        set(value) {
            if (field == value) return
            field = value
            place()
            invalidate()
        }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var data: Data? = null
    private val sticky = HashMap<String, String>()

    /** The grid, row by row of [COLUMNS]; null fills the end of a section's last row. */
    private val grid = ArrayList<String?>()
    private val sectionRow = IntArray(CATEGORIES.size)
    private var recentCount = 0

    private val pill = RectF()
    private val back = RectF()
    private val card = RectF()
    private var cell = 0f
    private var scroll = 0f
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null

    /** Which chip shows as current, eased between categories while the grid scrolls. */
    private var chipAt = 0f
    private var chipTo = -1
    private var chipAnimator: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val emojiText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val popupText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val emptyText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        Fonts.weight(context, this, Fonts.REGULAR)
    }
    private val icons = HashMap<String, Path>()
    private val tmp = RectF()
    private val veil = Veil(this)
    private val room = Veil.room(this)

    // Touch.
    private enum class Target { NONE, BACK, CATEGORY, GRID, POPUP, HOLD }

    private var target = Target.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var pressedCell = -1
    private var backPressed = false

    /** ⌫ squeezes as a narrow pill; a touched emoji takes the tint, both held at least 90 ms. */
    private val backPress = KeyPress.Tween(BACK_PRESS)
    private val cellPress = KeyPress.Tween(1f)
    private var tintCell = -1
    private var repeats = 0

    // The skin-tone popup.
    private var popupBase: String? = null
    private var popupItems: List<String> = emptyList()
    private var popupColumns = 1
    private val popup = RectF()
    private var popupHover = -1
    private var popupDown = -1
    private var popupShown = 0f

    /** The panel unrolls from the top row, as the Clipboard panel. */
    private val reveal = Reveal(this) {
        shift = it
        invalidate()
    }
    private var shift = 0f
    private val accessibility = Accessibility()

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        isFocusable = true
    }

    /** Opens on the recents as they are now, at the top, the emoji read in the background the first time. */
    fun open() {
        closePopup()
        scroller.forceFinished(true)
        scroll = 0f
        readChoices()
        data = cache
        if (data == null) {
            val app = context.applicationContext
            Thread {
                val loaded = load(app)
                post {
                    data = loaded
                    build()
                }
            }.start()
        }
        build()
        chipAnimator?.cancel()
        chipTo = current()
        chipAt = chipTo.toFloat()
        reveal.open(unit)
    }

    /** Closing: the panel rolls back up, then [done]. */
    fun close(done: () -> Unit) {
        closePopup()
        reveal.close(unit, done)
    }

    private fun readChoices() {
        sticky.clear()
        prefs.getString(STICKY, null)?.split('|')?.forEach { pair ->
            val parts = pair.split('=')
            if (parts.size == 2) sticky[parts[0]] = parts[1]
        }
    }

    private fun recents(): List<String> =
        prefs.getString(RECENT, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    private fun build() {
        cellPress.cancel()
        tintCell = -1
        grid.clear()
        // Recents pass the same test as the sections: one this phone cannot draw (saved on another, or before an
        // update) never shows as an empty box.
        val sections = listOf(recents().filter(::shows).take(RECENT_SHOWN)) + (data?.sections ?: emptyList())
        recentCount = sections[0].size
        CATEGORIES.indices.forEach { i ->
            sectionRow[i] = grid.size / COLUMNS
            sections.getOrNull(i)?.let { grid.addAll(it) }
            // No recents yet: Recent keeps one empty row, which says so, so the panel still opens on it and its chip
            // leads there, as on other keyboards.
            if (i == 0 && recentCount == 0) repeat(COLUMNS) { grid.add(null) }
            while (grid.size % COLUMNS != 0) grid.add(null)
        }
        clampScroll()
        accessibility.invalidateRoot()
        invalidate()
    }

    // Layout

    // The padding, set on a wide screen, keeps the panel to the keys' centred band.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = place()

    private fun place() {
        val u = unit
        val l = paddingLeft.toFloat()
        val w = (width - paddingRight).toFloat()
        val top = HEADER_TOP * u
        val bottom = top + HEADER * u
        // ⌫ where the Clipboard panel's Clear sits, on the hand's side; the categories on the other.
        if (leftHanded) {
            back.set(l + SIDE * u, top, l + (SIDE + BACK_WIDTH) * u, bottom)
            pill.set(l + (SIDE + BACK_WIDTH + GAP) * u, top, w - SIDE * u, bottom)
        } else {
            pill.set(l + SIDE * u, top, w - (SIDE + BACK_WIDTH + GAP) * u, bottom)
            back.set(w - (SIDE + BACK_WIDTH) * u, top, w - SIDE * u, bottom)
        }
        card.set(l + SIDE * u, CARD_TOP * u, w - SIDE * u, height - CARD_BOTTOM * u)
        cell = (card.width() - 2 * GRID_PAD * u) / COLUMNS
        clampScroll()
    }

    private fun rowHeight() = ROW * unit

    private fun rows() = grid.size / COLUMNS

    private fun maxScroll(): Float = max(0f, rows() * rowHeight() + 2 * GRID_TOP * unit - card.height())

    private fun clampScroll() {
        scroll = scroll.coerceIn(0f, maxScroll())
    }

    private fun chipRect(i: Float, out: RectF) {
        val slot = (pill.width() - 2 * PILL_PAD * unit) / CATEGORIES.size
        val cx = pill.left + PILL_PAD * unit + slot * (i + 0.5f)
        val w = min(CHIP_WIDTH * unit, slot)
        out.set(cx - w / 2, pill.centerY() - CHIP_HEIGHT * unit / 2, cx + w / 2, pill.centerY() + CHIP_HEIGHT * unit / 2)
    }

    private fun categoryAt(x: Float): Int {
        val slot = (pill.width() - 2 * PILL_PAD * unit) / CATEGORIES.size
        return floor((x - pill.left - PILL_PAD * unit) / slot).toInt().coerceIn(0, CATEGORIES.size - 1)
    }

    /** The category at the top of the card: the last one that starts at or above it. */
    private fun current(): Int {
        val top = scroll + rowHeight() / 2
        var at = 0
        CATEGORIES.indices.forEach { if (sectionRow[it] * rowHeight() <= top && hasRows(it)) at = it }
        return at
    }

    private fun hasRows(i: Int) = (if (i + 1 < CATEGORIES.size) sectionRow[i + 1] else rows()) > sectionRow[i]

    private fun cellRect(index: Int, out: RectF) {
        val row = index / COLUMNS
        val left = card.left + GRID_PAD * unit + (index % COLUMNS) * cell
        val top = card.top + GRID_TOP * unit + row * rowHeight() - scroll
        out.set(left, top, left + cell, top + rowHeight())
    }

    private fun cellAt(x: Float, y: Float): Int {
        if (y < card.top || y > card.bottom || x < card.left || x > card.right) return -1
        val column = floor((x - card.left - GRID_PAD * unit) / cell).toInt()
        val row = floor((y - card.top - GRID_TOP * unit + scroll) / rowHeight()).toInt()
        if (column !in 0 until COLUMNS || row !in 0 until rows()) return -1
        val index = row * COLUMNS + column
        return index.takeIf { grid[it] != null } ?: -1
    }

    /** What a cell shows: a recent as it was picked; any other with the skin tone chosen for it last. */
    private fun shown(index: Int): String {
        val emoji = grid[index]!!
        return if (index < sectionRow[1] * COLUMNS) emoji else sticky[emoji] ?: emoji
    }

    private fun visibleCells(): IntRange {
        if (rows() == 0) return IntRange.EMPTY
        val first = floor((scroll - GRID_TOP * unit) / rowHeight()).toInt().coerceAtLeast(0)
        val last = ceil((scroll + card.height()) / rowHeight()).toInt().coerceAtMost(rows() - 1)
        return first * COLUMNS until (last + 1) * COLUMNS
    }

    // Scrolling

    private fun scrollTo(value: Float) {
        scroll = value.coerceIn(0f, maxScroll())
        follow()
        invalidate()
    }

    /** The chip follows the grid. */
    private fun follow() {
        val now = current()
        if (now == chipTo) return
        chipTo = now
        chipAnimator?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            chipAt = now.toFloat()
            return
        }
        chipAnimator = ValueAnimator.ofFloat(chipAt, now.toFloat()).apply {
            duration = CHIP_MS
            interpolator = IN_OUT
            addUpdateListener {
                chipAt = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun scrollToCategory(i: Int) {
        val to = (sectionRow[i] * rowHeight()).coerceIn(0f, maxScroll())
        scroller.forceFinished(true)
        if (!ValueAnimator.areAnimatorsEnabled()) return scrollTo(to)
        scroller.startScroll(0, scroll.toInt(), 0, (to - scroll).toInt(), CATEGORY_SCROLL_MS)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (!scroller.computeScrollOffset()) return
        scrollTo(scroller.currY.toFloat())
        if (scroller.isFinished) accessibility.invalidateRoot() else postInvalidateOnAnimation()
    }

    // Picking

    private fun pick(emoji: String) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPick(emoji)
        // Newest first, once each, as the AndroidX picker keeps them; the row shows them from the next opening, so
        // the grid never moves under the finger.
        prefs.edit().putString(RECENT, (listOf(emoji) + recents().filter { it != emoji }).joinToString(",")).apply()
    }

    private fun pickVariant(i: Int) {
        val base = popupBase ?: return
        val emoji = popupItems[i]
        // The skin tone picked becomes the one the grid shows for it, as in the AndroidX picker.
        if (emoji == base) sticky.remove(base) else sticky[base] = emoji
        prefs.edit().putString(STICKY, sticky.entries.joinToString("|") { "${it.key}=${it.value}" }).apply()
        closePopup()
        pick(emoji)
    }

    private val repeat = object : Runnable {
        override fun run() {
            if (!backPressed) return
            // As the keyboard's own ⌫: faster the longer it is held, then whole words.
            onBackspace(repeats >= WORD_AFTER)
            repeats++
            val interval = if (repeats > WORD_AFTER) WORD_INTERVAL_MS else max(MIN_INTERVAL_MS, (FIRST_INTERVAL_MS * Math.pow(ACCELERATION, repeats.toDouble())).toLong())
            postDelayed(this, interval)
        }
    }

    private val hold = Runnable {
        val index = pressedCell
        if (index < 0) return@Runnable
        if (openPopup(index)) {
            target = Target.HOLD
            pressedCell = -1
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** The skin tones of the emoji in [index], over it; false when it has none. */
    private fun openPopup(index: Int): Boolean {
        val data = data ?: return false
        val emoji = shown(index)
        val base = data.baseOf[emoji] ?: emoji
        val items = data.variants[base] ?: return false
        cellPress.cancel()
        tintCell = -1
        // Captured before the popup shows, so the glass never blurs itself.
        veil.capture(this)
        popupBase = base
        popupItems = items
        // Up to three rows: six across, more for the emoji with two people's tones.
        popupColumns = min(items.size, min(POPUP_MAX_COLUMNS, max(POPUP_COLUMNS, ceil(items.size / POPUP_ROWS).toInt())))
        val u = unit
        val w = popupColumns * POPUP_CELL * u + 2 * POPUP_PAD * u
        val h = ceil(items.size / popupColumns.toFloat()) * POPUP_CELL * u + 2 * POPUP_PAD * u
        cellRect(index, tmp)
        val left = (tmp.centerX() - w / 2).coerceIn(POPUP_EDGE * u, max(POPUP_EDGE * u, width - POPUP_EDGE * u - w))
        val above = tmp.top - POPUP_GAP * u - h
        val top = if (above >= 0f) above else min(tmp.bottom + POPUP_GAP * u, max(0f, height - h))
        // On whole pixels, so its ring and rim are crisp.
        popup.set(left.roundToInt().toFloat(), top.roundToInt().toFloat(), (left + w).roundToInt().toFloat(), (top + h).roundToInt().toFloat())
        popupHover = -1
        popupDown = -1
        if (ValueAnimator.areAnimatorsEnabled()) {
            popupShown = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = POPUP_MS
                interpolator = EMPHASIZED_DECELERATE
                addUpdateListener {
                    popupShown = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            popupShown = 1f
        }
        accessibility.invalidateRoot()
        invalidate()
        return true
    }

    private fun closePopup() {
        if (popupBase == null) return
        popupBase = null
        popupItems = emptyList()
        veil.forget()
        accessibility.invalidateRoot()
        invalidate()
    }

    private fun popupCell(x: Float, y: Float): Int {
        if (!popup.contains(x, y)) return -1
        val u = unit
        val column = floor((x - popup.left - POPUP_PAD * u) / (POPUP_CELL * u)).toInt().coerceIn(0, popupColumns - 1)
        val row = floor((y - popup.top - POPUP_PAD * u) / (POPUP_CELL * u)).toInt().coerceAtLeast(0)
        return (row * popupColumns + column).takeIf { it < popupItems.size } ?: -1
    }

    private fun popupCellRect(i: Int, out: RectF) {
        val u = unit
        val left = popup.left + POPUP_PAD * u + (i % popupColumns) * POPUP_CELL * u
        val top = popup.top + POPUP_PAD * u + (i / popupColumns) * POPUP_CELL * u
        out.set(left, top, left + POPUP_CELL * u, top + POPUP_CELL * u)
    }

    // Touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = x
                downY = y
                lastY = y
                dragging = false
                if (popupBase != null) {
                    // Open from an earlier hold: a tap on a skin tone picks it, a tap elsewhere closes it.
                    popupDown = popupCell(x, y)
                    if (popupDown < 0) {
                        closePopup()
                        target = Target.NONE
                    } else {
                        target = Target.POPUP
                        popupHover = popupDown
                        invalidate()
                    }
                    return true
                }
                target = when {
                    expanded(back).contains(x, y) -> Target.BACK
                    y < card.top && pill.contains(x, y) -> Target.CATEGORY
                    y >= card.top -> Target.GRID
                    else -> Target.NONE
                }
                when (target) {
                    Target.BACK -> {
                        backPressed = true
                        backPress.set(true)
                        repeats = 0
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onBackspace(false)
                        postDelayed(repeat, REPEAT_DELAY_MS)
                    }
                    Target.GRID -> {
                        velocity = VelocityTracker.obtain().apply { addMovement(event) }
                        pressedCell = cellAt(x, y)
                        if (pressedCell >= 0) {
                            tintCell = pressedCell
                            cellPress.set(true)
                            postDelayed(hold, LONG_PRESS_MS)
                        }
                    }
                    else -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> when (target) {
                Target.HOLD, Target.POPUP -> {
                    val over = popupCell(x, y)
                    if (over != popupHover) {
                        popupHover = over
                        invalidate()
                    }
                }
                Target.GRID -> {
                    velocity?.addMovement(event)
                    if (!dragging && abs(y - downY) > ViewConfiguration.get(context).scaledTouchSlop) {
                        dragging = true
                        removeCallbacks(hold)
                        pressedCell = -1
                        // A scroll is no press: the tint goes at once.
                        cellPress.cancel()
                        tintCell = -1
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) scrollTo(scroll + lastY - y)
                    lastY = y
                }
                else -> Unit
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(hold)
                removeCallbacks(repeat)
                when (target) {
                    // Slid onto a skin tone and let go: that one; let go anywhere else, the popup stays for a tap.
                    Target.HOLD -> if (popupHover >= 0) pickVariant(popupHover)
                    Target.POPUP -> {
                        val over = popupCell(x, y)
                        if (over >= 0 && over == popupDown) pickVariant(over)
                        popupHover = -1
                    }
                    Target.GRID -> {
                        if (dragging) {
                            velocity?.let {
                                it.addMovement(event)
                                it.computeCurrentVelocity(1000)
                                scroller.fling(0, scroll.toInt(), 0, -it.yVelocity.toInt(), 0, 0, 0, maxScroll().toInt())
                                postInvalidateOnAnimation()
                            }
                        } else if (pressedCell >= 0 && cellAt(x, y) == pressedCell) {
                            pick(shown(pressedCell))
                        }
                    }
                    Target.CATEGORY -> if (pill.contains(x, y)) scrollToCategory(categoryAt(x))
                    else -> Unit
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                removeCallbacks(repeat)
                endTouch()
            }
        }
        return true
    }

    private fun endTouch() {
        if (backPressed) backPress.set(false)
        if (tintCell >= 0) cellPress.set(false)
        backPressed = false
        pressedCell = -1
        dragging = false
        target = Target.NONE
        velocity?.recycle()
        velocity = null
        invalidate()
    }

    /** At least 48 dp to aim at, as every key. */
    private fun expanded(r: RectF): RectF {
        val min = TARGET_DP * resources.displayMetrics.density
        val dx = max(0f, (min - r.width()) / 2)
        val dy = max(0f, (min - r.height()) / 2)
        return RectF(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hold)
        removeCallbacks(repeat)
        super.onDetachedFromWindow()
    }

    // Drawing

    override fun onDraw(canvas: Canvas) {
        val u = unit
        val w = width.toFloat()
        val c = colors
        canvas.drawColor(c.panelBg)
        val line = u.coerceAtLeast(1f)
        fill.color = c.panelLine
        canvas.drawRect(0f, 0f, w, line, fill)
        canvas.save()
        canvas.translate(0f, shift)
        // The categories: a tonal pill, the current one on a white chip.
        fill.color = c.segment
        canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, fill)
        chipRect(chipAt, tmp)
        fill.color = CHIP_SHADOW
        tmp.offset(0f, u)
        canvas.drawRoundRect(tmp, CHIP_RADIUS * u, CHIP_RADIUS * u, fill)
        tmp.offset(0f, -u)
        fill.color = c.segmentOn
        canvas.drawRoundRect(tmp, CHIP_RADIUS * u, CHIP_RADIUS * u, fill)
        CATEGORIES.forEachIndexed { i, category ->
            chipRect(i.toFloat(), tmp)
            icon(canvas, category.icon, tmp.centerX(), tmp.centerY(), ICON * u, if (i == chipTo) c.ink else c.grey)
        }
        // ⌫: an outline pill, squeezed and tinted while pressed.
        canvas.save()
        val squeeze = backPress.scale()
        canvas.scale(squeeze, squeeze, back.centerX(), back.centerY())
        val backTint = backPress.tint()
        if (backTint > 0f) {
            fill.color = Palette.alpha(c.press, backTint)
            canvas.drawRoundRect(back, back.height() / 2, back.height() / 2, fill)
        }
        stroke.color = c.clearRing
        stroke.strokeWidth = u
        tmp.set(back)
        tmp.inset(u / 2, u / 2)
        canvas.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, stroke)
        icon(canvas, "backspace", back.centerX(), back.centerY(), BACK_ICON * u, c.ink)
        canvas.restore()
        // The emoji, in a card that scrolls under the header.
        canvas.save()
        canvas.clipRect(card.left, card.top, card.right, card.bottom)
        fill.color = c.card
        canvas.drawRoundRect(card, CARD_RADIUS * u, CARD_RADIUS * u, fill)
        stroke.color = c.cardRing
        tmp.set(card)
        tmp.inset(u / 2, u / 2)
        canvas.drawRoundRect(tmp, CARD_RADIUS * u, CARD_RADIUS * u, stroke)
        canvas.clipRect(card.left, card.top + u, card.right, card.bottom - u)
        if (recentCount == 0) {
            cellRect(0, tmp)
            emptyText.color = c.muted
            canvas.drawText(context.getString(R.string.emoji_no_recent), card.centerX(), tmp.centerY() - (emptyText.ascent() + emptyText.descent()) / 2, emptyText)
        }
        val metrics = emojiText.fontMetrics
        val lift = (metrics.ascent + metrics.descent) / 2
        for (i in visibleCells()) {
            if (grid[i] == null) continue
            cellRect(i, tmp)
            val cellTint = if (i == tintCell) cellPress.tint() else 0f
            if (cellTint > 0f) {
                fill.color = Palette.alpha(c.press, cellTint)
                val r = min(tmp.width(), tmp.height()) / 2 - PRESS_INSET * u
                canvas.drawCircle(tmp.centerX(), tmp.centerY(), r, fill)
            }
            canvas.drawText(shown(i), tmp.centerX(), tmp.centerY() - lift, emojiText)
        }
        canvas.restore()
        canvas.restore()
        if (popupBase != null) drawPopup(canvas)
        if (backPress.moving() || cellPress.moving()) postInvalidateOnAnimation()
    }

    private fun drawPopup(canvas: Canvas) {
        val u = unit
        canvas.save()
        val scale = POPUP_FROM + (1 - POPUP_FROM) * popupShown
        canvas.scale(scale, scale, popup.centerX(), popup.bottom)
        val alpha = (popupShown * 255).toInt()
        // The layer leaves the room the shadow needs, so it is never cut off square.
        canvas.saveLayerAlpha(popup.left - room, popup.top - room, popup.right + room, popup.bottom + room, alpha)
        veil.draw(canvas, popup, POPUP_RADIUS * u, colors)
        val metrics = popupText.fontMetrics
        val lift = (metrics.ascent + metrics.descent) / 2
        popupItems.forEachIndexed { i, emoji ->
            popupCellRect(i, tmp)
            if (i == popupHover) {
                fill.color = colors.accentBg
                tmp.inset(POPUP_HOVER_INSET * u, POPUP_HOVER_INSET * u)
                canvas.drawRoundRect(tmp, POPUP_HOVER_RADIUS * u, POPUP_HOVER_RADIUS * u, fill)
                popupCellRect(i, tmp)
            }
            canvas.drawText(emoji, tmp.centerX(), tmp.centerY() - lift, popupText)
        }
        canvas.restore()
        canvas.restore()
    }

    private fun icon(canvas: Canvas, name: String, cx: Float, cy: Float, size: Float, color: Int) {
        val path = icons.getOrPut(name) { Icons.path(name, size) }
        fill.color = color
        canvas.save()
        canvas.translate(cx - size / 2, cy - size / 2)
        canvas.drawPath(path, fill)
        canvas.restore()
    }

    // Accessibility: the categories, ⌫, the emoji in view and the skin tones are virtual views; the grid scrolls.

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val page = card.height() - rowHeight()
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> if (scroll < maxScroll()) scrollTo(scroll + page) else return false
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> if (scroll > 0f) scrollTo(scroll - page) else return false
            else -> return super.performAccessibilityAction(action, arguments)
        }
        accessibility.invalidateRoot()
        return true
    }

    private inner class Accessibility : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            if (popupBase != null) return popupCell(x, y).takeIf { it >= 0 }?.plus(POPUP_ID) ?: INVALID_ID
            return when {
                expanded(back).contains(x, y) -> BACK_ID
                y < card.top && pill.contains(x, y) -> categoryAt(x)
                else -> cellAt(x, y).takeIf { it >= 0 }?.plus(CELL_ID) ?: INVALID_ID
            }
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            if (popupBase != null) {
                popupItems.indices.forEach { ids += POPUP_ID + it }
                return
            }
            CATEGORIES.indices.forEach { ids += it }
            ids += BACK_ID
            visibleCells().filter { grid[it] != null && cellShows(it) }.forEach { ids += CELL_ID + it }
        }

        /** A cell at least half inside the card. */
        private fun cellShows(i: Int): Boolean {
            cellRect(i, tmp)
            return tmp.centerY() > card.top && tmp.centerY() < card.bottom
        }

        override fun onPopulateNodeForHost(node: AccessibilityNodeInfoCompat) {
            node.isScrollable = true
            if (scroll > 0f) node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
            if (scroll < maxScroll()) node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val r = RectF()
            when {
                id >= POPUP_ID -> {
                    popupCellRect(id - POPUP_ID, r)
                    node.contentDescription = popupItems.getOrNull(id - POPUP_ID).orEmpty()
                }
                id >= CELL_ID -> {
                    val i = id - CELL_ID
                    cellRect(i, r)
                    r.intersect(card)
                    node.contentDescription = grid.getOrNull(i)?.let { shown(i) }.orEmpty()
                    val data = data
                    val emoji = grid.getOrNull(i)?.let { shown(i) }
                    if (emoji != null && data != null && data.variants.containsKey(data.baseOf[emoji] ?: emoji)) {
                        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(AccessibilityNodeInfo.ACTION_LONG_CLICK, context.getString(R.string.emoji_skin_tones)))
                    }
                }
                id == BACK_ID -> {
                    r.set(back)
                    node.contentDescription = context.getString(R.string.cd_backspace)
                }
                else -> {
                    chipRect(id.toFloat(), r)
                    node.contentDescription = context.getString(CATEGORIES[id].label)
                    node.isSelected = id == chipTo
                }
            }
            @Suppress("DEPRECATION")
            node.setBoundsInParent(Rect(r.left.toInt(), (r.top + shift).toInt(), r.right.toInt(), (r.bottom + shift).toInt()))
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.className = "android.widget.Button"
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> when {
                    id >= POPUP_ID -> pickVariant(id - POPUP_ID)
                    id >= CELL_ID -> grid.getOrNull(id - CELL_ID)?.let { pick(shown(id - CELL_ID)) }
                    id == BACK_ID -> onBackspace(false)
                    else -> {
                        scroller.forceFinished(true)
                        scrollTo(sectionRow[id] * rowHeight())
                        invalidateRoot()
                    }
                }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> if (id in CELL_ID until POPUP_ID) return openPopup(id - CELL_ID) else return false
                else -> return false
            }
            return true
        }
    }

    /** The bundled emoji by category, and the variants (skin tones) of those that have any. */
    class Data(val sections: List<List<String>>, val variants: Map<String, List<String>>, val baseOf: Map<String, String>)

    private class Category(val label: Int, val icon: String, val raw: Int)

    companion object {
        @Volatile
        private var cache: Data? = null

        private val glyphs = Paint()

        // Only the emoji this phone can draw, as the AndroidX picker shows them; with no emoji font at all (a test
        // machine) nothing would show, so then every one is kept.
        private val check by lazy { synchronized(glyphs) { glyphs.hasGlyph(PROBE) } }

        fun shows(emoji: String) = !check || synchronized(glyphs) { glyphs.hasGlyph(emoji) }

        /** Reads the emoji lists once per process; slow the first time, so off the main thread. */
        fun load(context: Context): Data = cache ?: synchronized(this) { cache ?: read(context).also { cache = it } }

        private fun read(context: Context): Data {
            val variants = HashMap<String, List<String>>()
            val baseOf = HashMap<String, String>()
            val sections = CATEGORIES.drop(1).map { category ->
                context.resources.openRawResource(category.raw).bufferedReader().useLines { lines ->
                    lines.mapNotNull { line ->
                        val parts = line.split(',').filter { it.isNotEmpty() }
                        val base = parts.firstOrNull()?.takeIf(::shows) ?: return@mapNotNull null
                        // A line is the emoji, then its variants; some repeat the emoji among them.
                        val all = parts.filter(::shows).distinct()
                        if (all.size > 1) {
                            variants[base] = all
                            all.forEach { baseOf[it] = base }
                        }
                        base
                    }.toList()
                }
            }
            return Data(sections, variants, baseOf)
        }

        private val CATEGORIES = listOf(
            Category(R.string.emoji_recent, "clock-counter-clockwise", 0),
            Category(R.string.emoji_smileys, "smiley", EmojiR.raw.emoji_category_emotions),
            // Android 10 and later draw the gender-neutral people, so the picker lists those (minSdk is 33).
            Category(R.string.emoji_people, "hand-waving", EmojiR.raw.emoji_category_people_gender_inclusive),
            Category(R.string.emoji_animals, "paw-print", EmojiR.raw.emoji_category_animals_nature),
            Category(R.string.emoji_food, "coffee", EmojiR.raw.emoji_category_food_drink),
            Category(R.string.emoji_travel, "airplane", EmojiR.raw.emoji_category_travel_places),
            Category(R.string.emoji_activities, "soccer-ball", EmojiR.raw.emoji_category_activity),
            Category(R.string.emoji_objects, "lightbulb", EmojiR.raw.emoji_category_objects),
            Category(R.string.emoji_symbols, "heart", EmojiR.raw.emoji_category_symbols),
            Category(R.string.emoji_flags, "flag", EmojiR.raw.emoji_category_flags),
        )

        /** The AndroidX picker's own store: the recents and skin tones picked there carry over. */
        internal const val PREFS = "androidx.emoji2.emojipicker.preferences"
        private const val RECENT = "pref_key_recent_emoji"
        private const val STICKY = "pref_key_sticky_variant"
        private const val PROBE = "😀"
        private const val RECENT_SHOWN = 27

        private const val BACK_ID = 10
        private const val CELL_ID = 1000
        private const val POPUP_ID = 100_000

        // Sizes in dp at the reference width.
        private const val SIDE = 12f
        private const val GAP = 8f
        private const val HEADER_TOP = 6f
        private const val HEADER = 32f
        private const val BACK_WIDTH = 46f
        private const val BACK_ICON = 20f
        /** A narrow pill squeezes deepest. */
        private const val BACK_PRESS = 0.94f
        private const val PILL_PAD = 2f
        private const val CHIP_WIDTH = 30f
        private const val CHIP_HEIGHT = 28f
        private const val CHIP_RADIUS = 14f
        private const val CHIP_SHADOW = 0x1F0B285A
        private const val ICON = 17f
        private const val CARD_TOP = 44f
        private const val CARD_RADIUS = 16f
        /** The card stops this far above the grid's bottom, so four whole 38 dp rows are in view. */
        private const val CARD_BOTTOM = 10f
        private const val GRID_PAD = 4f
        private const val GRID_TOP = 1f
        /** "No recent emoji", in the empty Recent row: the clipboard's empty note's size and colour. */
        private const val EMPTY_TEXT = 13.5f
        private const val COLUMNS = 9
        private const val ROW = 38f
        private const val EMOJI = 22f
        private const val PRESS_INSET = 1f
        private const val POPUP_CELL = 40f
        private const val POPUP_PAD = 6f
        private const val POPUP_GAP = 4f
        private const val POPUP_EDGE = 8f
        private const val POPUP_RADIUS = 18f
        private const val POPUP_EMOJI = 25f
        private const val POPUP_HOVER_INSET = 2f
        private const val POPUP_HOVER_RADIUS = 10f
        private const val POPUP_COLUMNS = 6
        private const val POPUP_MAX_COLUMNS = 9
        private const val POPUP_ROWS = 3f
        private const val POPUP_FROM = 0.96f
        private const val TARGET_DP = 48f

        // Motion: the chip as the Clipboard | History thumb; the popup as the other veil pop-ups.
        private const val CHIP_MS = 200L
        private const val CATEGORY_SCROLL_MS = 300
        private const val POPUP_MS = 200L
        private val IN_OUT = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        private val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

        // As the keyboard's ⌫ (KeyboardView).
        private const val REPEAT_DELAY_MS = 400L
        private const val FIRST_INTERVAL_MS = 90.0
        private const val ACCELERATION = 0.92
        private const val MIN_INTERVAL_MS = 30L
        private const val WORD_AFTER = 20
        private const val WORD_INTERVAL_MS = 180L
        private const val LONG_PRESS_MS = 450L
    }
}
