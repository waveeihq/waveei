package app.waveei.android.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.waveei.android.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The Clipboard | History panel over the keyboard's grid under the top row: segmented tabs, a Clear pill
 * on the hand's side, rows in one card that scrolls, a veil ⋯ menu hanging from a History row. Plain Views, so that
 * TalkBack reads every row. Sizes follow the keyboard's design unit.
 */
@SuppressLint("ViewConstructor")
class ListPanel(context: Context) : FrameLayout(context) {
    enum class Tab { CLIPBOARD, HISTORY }

    /** What a row's meta starts with: a coloured dot, or a green check. */
    sealed interface Mark {
        data class Dot(val color: Int) : Mark
        data object Done : Mark
    }

    data class Action(val label: String, val run: () -> Unit)

    data class MenuItem(val icon: String, val label: String, val run: () -> Unit)

    data class Row(
        val text: String,
        val meta: String,
        val muted: Boolean = false,
        val mark: Mark? = null,
        /** Said after the meta in the accent, as "On the clipboard". */
        val tag: String? = null,
        /** A copied image's preview, shown as a thumb before [text]. */
        val image: Bitmap? = null,
        val main: Action? = null,
        val copy: (() -> Unit)? = null,
        val delete: (() -> Unit)? = null,
        /** The ⋯ menu's items; no ⋯ without them. */
        val more: List<MenuItem> = emptyList(),
    )

    private var colors = KeyboardColors.DAY
    private var unit = 1f
    private var hand = false
    private var tab: Tab? = null
    private val lines = Paint()
    private val content = FrameLayout(context)
    private val tabs = Segment(context)
    private val clear = FrameLayout(context)
    private val clearLabel = TextView(context)
    private val armedLabel = TextView(context)
    private val clearFill = GradientDrawable()
    private val card = FrameLayout(context)
    private val scroll = ScrollView(context).apply {
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
    }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val empty = TextView(context)

    /** The list a tab switch leaves, as a picture sliding out. */
    private val ghost = ImageView(context)
    private val menuLayer = FrameLayout(context)
    private val menu = VeilBox(context)
    private val room = Veil.room(this)
    private var clearArmed = false

    /** 0 shows "Clear", 1 "Tap again to clear"; the pill's width and red tint follow it. */
    private var clearShown = 0f
    private var clearAnimator: ValueAnimator? = null
    private var clearWidth = 0
    private var armedWidth = 0
    private var onClear: () -> Unit = {}

    /** The clear confirmed, waiting for the rows to fade. */
    private var clearing: Runnable? = null
    private var rebuilding = false
    private var shows = 0

    /** While an Insert lands or the rows clear, the panel takes no other tap. */
    private var busy = false

    /** The rows folding away. */
    private val going = HashSet<View>()

    /** The panel's close waits for the Insert pill's release. */
    private var closeAt = 0L
    private var closing: Runnable? = null
    private val disarm = Runnable { setClear(false, DISARM_MS) }
    private val reveal = Reveal(this) { content.translationY = it }

    init {
        setWillNotDraw(false)
        scroll.addView(rows)
        card.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        clear.addView(clearLabel)
        clear.addView(armedLabel)
        clear.background = clearFill
        clearLabel.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        armedLabel.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        content.addView(tabs)
        content.addView(clear)
        content.addView(card)
        content.addView(empty)
        ghost.visibility = GONE
        ghost.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        content.addView(ghost)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        menuLayer.addView(menu)
        menuLayer.visibility = GONE
        // A tap anywhere off the menu closes it.
        menuLayer.setOnClickListener { closeMenu() }
        addView(menuLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.clipToOutline = true
    }

    /**
     * Shows [rows] under the tabs, [tab] selected; [onClear] runs on the Clear pill's second tap. [leftHanded] puts
     * Clear on the left, the hand's side, as the emoji panel's ⌫. Another tab while the panel shows slides the lists;
     * the rows that [onClear] leaves come in after the clear.
     */
    fun show(
        tab: Tab,
        rows: List<Row>,
        empty: String,
        onTab: (Tab) -> Unit,
        onClear: () -> Unit,
        colors: KeyboardColors,
        unit: Float,
        leftHanded: Boolean,
    ) {
        val was = this.tab
        val sameTab = was == tab
        val slide = was != null && !sameTab && isShown && KeyboardMotion.animates()
        // History is to the right of Clipboard: going there, the old list leaves to the left.
        val direction = if (tab == Tab.HISTORY) -1f else 1f
        if (slide) leave(direction)
        shows++
        this.tab = tab
        this.colors = colors
        this.unit = unit
        this.hand = leftHanded
        this.onClear = onClear
        closeMenu()
        val u = unit
        val c = colors
        lines.color = c.panelLine
        setBackgroundColor(c.panelBg)
        // The tabs: a 232 dp segmented control, the selected tab on a white chip that slides.
        tabs.removeAllViews()
        tabs.colors = c
        tabs.unit = u
        val pad = (SEGMENT_PAD * u).toInt()
        tabs.setPadding(pad, pad, pad, pad)
        listOf(Tab.CLIPBOARD to R.string.clipboard, Tab.HISTORY to R.string.buffer_title).forEach { (value, label) ->
            val on = value == tab
            tabs.addView(label(context.getString(label), TAB_TEXT, if (on) c.ink else c.grey, if (on) Fonts.TITLE else Fonts.MEDIUM).apply {
                isSelected = on
                setOnClickListener { if (!on) onTab(value) }
            }, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
        tabs.onPick = { i -> (if (i == 0) Tab.CLIPBOARD else Tab.HISTORY).let { if (it != tab) onTab(it) } }
        tabs.slide(if (tab == Tab.CLIPBOARD) 0f else 1f, slide)
        tabs.layoutParams = LayoutParams((SEGMENT_WIDTH * u).toInt(), (SEGMENT_HEIGHT * u).toInt()).apply {
            gravity = if (leftHanded) Gravity.END else Gravity.START
            topMargin = (HEADER_TOP * u).toInt()
            leftMargin = (SIDE * u).toInt()
            rightMargin = (SIDE * u).toInt()
        }
        // Clear: an outline pill; the first tap asks, the second within 3 s clears. Asking stays over a
        // refresh of the same list.
        if (!sameTab || rows.isEmpty()) {
            clearArmed = false
            removeCallbacks(disarm)
            clearAnimator?.cancel()
            clearShown = 0f
        }
        styleText(clearLabel, CLEAR_TEXT, c.accent, Fonts.SEMIBOLD)
        styleText(armedLabel, CLEAR_TEXT, c.red, Fonts.SEMIBOLD)
        clearLabel.text = context.getString(R.string.clear)
        armedLabel.text = context.getString(R.string.tap_again_to_clear)
        clearWidth = natural(clearLabel)
        armedWidth = natural(armedLabel)
        // Each label keeps its own width, so neither wraps while the pill grows or shrinks.
        clearLabel.layoutParams = LayoutParams(clearWidth, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        armedLabel.layoutParams = LayoutParams(armedWidth, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        clearFill.cornerRadius = SEGMENT_HEIGHT / 2 * u
        clearFill.setStroke(u.toInt().coerceAtLeast(1), c.clearRing)
        clear.isEnabled = rows.isNotEmpty()
        clear.alpha = if (rows.isNotEmpty()) 1f else DISABLED_ALPHA
        clear.setOnClickListener {
            if (clearArmed) confirmClear() else setClear(true, ARM_MS)
        }
        pressFeel(clear, SEGMENT_HEIGHT / 2, c.press) { if (clearArmed) WIDE_PRESS else NARROW_PRESS }
        clear.layoutParams = LayoutParams(clearWidth, (SEGMENT_HEIGHT * u).toInt()).apply {
            gravity = if (leftHanded) Gravity.START else Gravity.END
            topMargin = (HEADER_TOP * u).toInt()
            leftMargin = (SIDE * u).toInt()
            rightMargin = (SIDE * u).toInt()
        }
        applyClear()
        // The rows, in one card that scrolls; the card keeps its height with fewer rows.
        val scrollY = scroll.scrollY
        this.rows.removeAllViews()
        // A row without Copy, Delete or ⋯ keeps their places empty, so the buttons stay in columns row under row.
        val slots = Slots(rows.any { it.copy != null }, rows.any { it.delete != null }, rows.any { it.more.isNotEmpty() })
        rows.forEachIndexed { i, row ->
            this.rows.addView(rowView(row, last = i == rows.lastIndex, slots), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (ROW * u).roundToInt()))
        }
        card.background = GradientDrawable().apply {
            setColor(c.card)
            cornerRadius = CARD_RADIUS * u
            setStroke(u.toInt().coerceAtLeast(1), c.cardRing)
        }
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, CARD_RADIUS * unit)
        }
        card.visibility = if (rows.isEmpty()) GONE else VISIBLE
        card.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = (CARD_TOP * u).toInt()
            leftMargin = (SIDE * u).toInt()
            rightMargin = (SIDE * u).toInt()
            bottomMargin = (CARD_BOTTOM * u).toInt()
        }
        scroll.post { scroll.scrollTo(0, if (sameTab) scrollY else 0) }
        styleText(this.empty, ROW_TEXT, c.muted, Fonts.REGULAR)
        this.empty.text = empty
        this.empty.gravity = Gravity.CENTER
        this.empty.isSingleLine = false
        this.empty.visibility = if (rows.isEmpty()) VISIBLE else GONE
        this.empty.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = (CARD_TOP * u).toInt()
            leftMargin = (SIDE * u).toInt()
            rightMargin = (SIDE * u).toInt()
            bottomMargin = (CARD_BOTTOM * u).toInt()
        }
        // A list being cleared keeps fading; anything else lands at rest, then comes in as its change says.
        if (clearing == null) {
            rest(card)
            rest(this.empty)
        }
        val list = if (rows.isEmpty()) this.empty else card
        when {
            slide -> arrive(list, direction)
            rebuilding && KeyboardMotion.animates() -> {
                // The empty state rises in, 6 dp, once the card has gone.
                list.alpha = 0f
                list.translationY = CLEAR_RISE * u
                list.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(EMPTY_IN_MS).setInterpolator(STANDARD).start()
            }
        }
    }

    /** The panel unrolls from the top row. */
    fun open() {
        closing?.let(::removeCallbacks)
        closing = null
        closeAt = 0L
        busy = false
        reveal.open(unit)
    }

    /** Closing: the panel rolls back up, then [done]; after an Insert, once its pill is released. */
    fun close(done: () -> Unit) {
        closeMenu()
        closing?.let(::removeCallbacks)
        closing = null
        val wait = closeDelayMs
        if (wait <= 0L) return reveal.close(unit, done)
        closing = Runnable {
            closing = null
            reveal.close(unit, done)
        }.also { postDelayed(it, wait) }
    }

    /**
     * How long [close] waits before the panel starts rolling up: after an Insert, until 140 ms after the tap;
     * else 0. The clipboard key's tint can wait as long.
     */
    val closeDelayMs: Long
        get() = (closeAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = busy || super.onInterceptTouchEvent(event)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = busy || super.onTouchEvent(event)

    private fun natural(label: TextView): Int {
        label.setPadding((CLEAR_PAD * unit).toInt(), 0, (CLEAR_PAD * unit).toInt(), 0)
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        label.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        return label.measuredWidth
    }

    /** The first tap on Clear: the pill grows to "Tap again to clear" with a red tint; back on its own after 3 s. */
    private fun setClear(armed: Boolean, ms: Long) {
        clearArmed = armed
        removeCallbacks(disarm)
        clearAnimator?.cancel()
        val to = if (armed) 1f else 0f
        if (!KeyboardMotion.animates() || !isShown) {
            clearShown = to
            applyClear()
        } else {
            clearAnimator = ValueAnimator.ofFloat(clearShown, to).apply {
                duration = ms
                interpolator = if (armed) STANDARD else IN_OUT
                addUpdateListener {
                    clearShown = it.animatedValue as Float
                    applyClear()
                }
                start()
            }
        }
        if (armed) postDelayed(disarm, CLEAR_CONFIRM_MS)
    }

    private fun applyClear() {
        val p = clearShown
        val params = clear.layoutParams
        val width = (clearWidth + (armedWidth - clearWidth) * p).roundToInt()
        if (params != null && params.width != width) {
            params.width = width
            clear.layoutParams = params
        }
        clearFill.setColor(Palette.alpha(colors.red, CLEAR_TINT * p))
        // Each label only while the pill is near its width, so neither is seen cut by the pill's edge or over the other.
        clearLabel.alpha = (1f - p / LABEL_SPAN).coerceIn(0f, 1f)
        armedLabel.alpha = ((p - 1f + LABEL_SPAN) / LABEL_SPAN).coerceIn(0f, 1f)
        clear.contentDescription = if (clearArmed) armedLabel.text else clearLabel.text
    }

    /**
     * The second tap on Clear: the rows in view fade and rise 6 dp one after another, the pill goes back, the card fades;
     * then [onClear] runs and the empty state comes in.
     */
    private fun confirmClear() {
        setClear(false, CLEAR_BACK_MS)
        val run = onClear
        if (!KeyboardMotion.animates() || !isShown || card.visibility != VISIBLE) return finishClear(run)
        busy = true
        var shown = 0
        for (i in 0 until rows.childCount) {
            val row = rows.getChildAt(i)
            if (row.bottom <= scroll.scrollY || row.top >= scroll.scrollY + scroll.height) continue
            row.animate().alpha(0f).translationY(-CLEAR_RISE * unit).setStartDelay(ROWS_STAGGER_MS * shown++).setDuration(ROWS_OUT_MS)
                .setInterpolator(STANDARD_ACCELERATE).start()
        }
        card.animate().alpha(0f).translationX(0f).setStartDelay(CARD_FADE_AT_MS).setDuration(CARD_FADE_MS).setInterpolator(LINEAR).start()
        // Timed apart from the card's fade, so that nothing cancelling it can lose a clear already confirmed.
        clearing = Runnable { finishClear(run) }.also { postDelayed(it, CARD_FADE_AT_MS + CARD_FADE_MS) }
    }

    private fun finishClear(run: () -> Unit) {
        clearing = null
        busy = false
        val before = shows
        rebuilding = true
        try {
            run()
        } finally {
            rebuilding = false
        }
        // Nothing rebuilt the list: what is there comes back.
        if (shows == before) {
            rest(card)
            for (i in 0 until rows.childCount) rest(rows.getChildAt(i))
        }
    }

    private fun rest(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    /** The list on show leaves as a picture, 24 dp towards [direction], fading. */
    private fun leave(direction: Float) {
        val old = if (card.visibility == VISIBLE) card else empty
        if (old.width == 0 || old.height == 0) return
        val picture = Bitmap.createBitmap(old.width, old.height, Bitmap.Config.ARGB_8888)
        // A thumb the software canvas cannot draw (a hardware bitmap): the list just goes.
        runCatching { old.draw(Canvas(picture)) }.onFailure { return }
        ghost.animate().cancel()
        ghost.setImageBitmap(picture)
        ghost.layoutParams = LayoutParams(old.width, old.height).apply {
            leftMargin = old.left
            topMargin = old.top
        }
        ghost.visibility = VISIBLE
        ghost.alpha = old.alpha
        ghost.translationX = old.translationX
        ghost.animate().alpha(0f).translationX(direction * SLIDE * unit).setStartDelay(0).setDuration(LIST_OUT_MS).setInterpolator(STANDARD_ACCELERATE)
            .withEndAction {
                ghost.visibility = GONE
                ghost.setImageDrawable(null)
            }.start()
    }

    /** The new list comes in from 24 dp the other side. */
    private fun arrive(view: View, direction: Float) {
        view.alpha = 0f
        view.translationX = -direction * SLIDE * unit
        view.animate().alpha(1f).translationX(0f).setStartDelay(LIST_IN_AT_MS).setDuration(LIST_IN_MS).setInterpolator(STANDARD).start()
    }

    /** The row fades, then folds to nothing with the rows below following; then [delete] runs. */
    private fun removeRow(view: ViewGroup, delete: () -> Unit) {
        if (!KeyboardMotion.animates() || !view.isShown) return delete()
        if (!going.add(view)) return
        // No second tap on a row that is going.
        for (i in 0 until view.childCount) view.getChildAt(i).isEnabled = false
        view.animate().alpha(0f).setStartDelay(0).setDuration(ROW_FADE_MS).setInterpolator(STANDARD_ACCELERATE).start()
        ValueAnimator.ofInt(view.height, 0).apply {
            startDelay = ROW_FOLD_AT_MS
            duration = ROW_FOLD_MS
            interpolator = IN_OUT
            addUpdateListener {
                view.layoutParams.height = it.animatedValue as Int
                view.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    going.remove(view)
                    delete()
                }
            })
            start()
        }
    }

    /** The Insert pill is released, the text goes in at 80 ms, and the panel's close waits until 140 ms. */
    private fun runMain(action: Action) {
        if (busy) return
        if (!KeyboardMotion.animates()) return action.run()
        busy = true
        closeAt = SystemClock.uptimeMillis() + INSERT_CLOSE_MS
        postDelayed({
            busy = false
            action.run()
        }, INSERT_TEXT_MS)
    }

    /** A 46 dp row: thumb, text over meta, then the main pill, Copy, Delete and ⋯; a hairline under all but the last. */
    private class Slots(val copy: Boolean, val delete: Boolean, val more: Boolean)

    private fun rowView(row: Row, last: Boolean, slots: Slots): View {
        val u = unit
        val c = colors
        return LinearLayout(context).apply {
            val line = this
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((ROW_START * u).toInt(), 0, (ROW_END * u).toInt(), 0)
            if (!last) background = Hairline(c.row, u.toInt().coerceAtLeast(1))
            if (row.image != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(row.image)
                    contentDescription = row.text
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, THUMB_RADIUS * unit)
                    }
                    clipToOutline = true
                }, LinearLayout.LayoutParams((THUMB * u).toInt(), (THUMB * u).toInt()).apply { marginEnd = (GAP * u).toInt() })
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(row.text, ROW_TEXT, if (row.muted) c.muted else c.ink, Fonts.REGULAR).apply {
                    setLineHeight((ROW_LINE * u).toInt())
                })
                addView(text("", META_TEXT, c.meta, Fonts.REGULAR).apply {
                    text = meta(row)
                    setLineHeight((META_LINE * u).toInt())
                    when (val mark = row.mark) {
                        is Mark.Dot -> setCompoundDrawables(GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(mark.color)
                            setSize((DOT * u).toInt(), (DOT * u).toInt())
                            setBounds(0, 0, (DOT * u).toInt(), (DOT * u).toInt())
                        }, null, null, null)
                        Mark.Done -> setCompoundDrawables(IconDrawable("check", CHECK * u, c.green).apply {
                            setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                        }, null, null, null)
                        null -> Unit
                    }
                    compoundDrawablePadding = (MARK_GAP * u).toInt()
                })
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (TEXT_END * u).toInt() })
            row.main?.let { action ->
                // At least 66 dp, so Insert and Retry line up row under row.
                addView(label(action.label, PILL_TEXT, c.accentInk, Fonts.TITLE).apply {
                    setPadding((PILL_PAD * u).toInt(), 0, (PILL_PAD * u).toInt(), 0)
                    minWidth = (PILL_MIN_WIDTH * u).toInt()
                    background = round(c.accentBg, PILL_HEIGHT / 2)
                    pressFeel(this, PILL_HEIGHT / 2, Palette.alpha(c.accent, INSERT_TINT), insert = true) { NARROW_PRESS }
                    setOnClickListener { runMain(action) }
                }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, (PILL_HEIGHT * u).toInt()))
            }
            when {
                row.copy != null -> addView(button("copy", ICON, c.grey, R.string.copy, row.copy))
                slots.copy -> addView(slot())
            }
            when {
                row.delete != null -> addView(button("trash", ICON, c.grey, R.string.delete) { removeRow(line, row.delete) })
                slots.delete -> addView(slot())
            }
            when {
                row.more.isNotEmpty() -> {
                    val more = button("dots-three", MORE_ICON, c.grey, R.string.kb_more) {}
                    more.setOnClickListener { openMenu(more, row.more) }
                    addView(more)
                }
                slots.more -> addView(slot())
            }
        }
    }

    private fun meta(row: Row): CharSequence {
        val tag = row.tag ?: return row.meta
        return SpannableStringBuilder(row.meta).apply {
            append(" · ")
            val start = length
            append(tag)
            setSpan(ForegroundColorSpan(colors.accent), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** The empty place of a button another row has. */
    private fun slot() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams((BUTTON * unit).toInt(), (BUTTON * unit).toInt()).apply { marginStart = (BUTTON_GAP * unit).toInt() }
    }

    private fun button(icon: String, size: Float, color: Int, label: Int, run: () -> Unit) = ImageView(context).apply {
        setImageDrawable(IconDrawable(icon, size * unit, color))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(label)
        pressFeel(this, BUTTON / 2, colors.press) { ROUND_PRESS }
        setOnClickListener { run() }
        layoutParams = LinearLayout.LayoutParams((BUTTON * unit).toInt(), (BUTTON * unit).toInt()).apply { marginStart = (BUTTON_GAP * unit).toInt() }
    }

    /**
     * A press on a pill or button: at down it squeezes to [target] and takes [tint] over 50 ms; the pressed look holds
     * at least 90 ms, then the scale springs back (key-spring) and the tint fades over 120 ms. An [insert] pill is
     * held until 60 ms after the tap, then released over 80 ms, linear.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun pressFeel(view: View, radius: Float, tint: Int, insert: Boolean = false, target: () -> Float) {
        val face = round(tint, radius).apply { alpha = 0 }
        view.foreground = face
        var downAt = 0L
        var fade: ValueAnimator? = null
        fun tintTo(to: Int, ms: Long, curve: TimeInterpolator) {
            fade?.cancel()
            if (!KeyboardMotion.animates()) {
                face.alpha = to
                return
            }
            fade = ValueAnimator.ofInt(face.alpha, to).apply {
                duration = ms
                interpolator = curve
                addUpdateListener { face.alpha = it.animatedValue as Int }
                start()
            }
        }
        val release = Runnable {
            val ms = if (insert) INSERT_RELEASE_MS else KeyPress.SPRING_MS
            if (KeyboardMotion.animates()) {
                view.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(ms).setInterpolator(if (insert) LINEAR else KeyPress.SPRING).start()
            } else {
                view.animate().cancel()
                view.scaleX = 1f
                view.scaleY = 1f
            }
            tintTo(0, if (insert) INSERT_RELEASE_MS else KeyPress.TINT_OUT_MS, LINEAR)
        }
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (v.isEnabled) {
                    downAt = event.eventTime
                    v.removeCallbacks(release)
                    val to = target()
                    if (KeyboardMotion.animates()) {
                        v.animate().scaleX(to).scaleY(to).setStartDelay(0).setDuration(KeyPress.PRESS_MS).setInterpolator(STANDARD).start()
                    } else {
                        v.animate().cancel()
                        v.scaleX = to
                        v.scaleY = to
                    }
                    tintTo(255, KeyPress.PRESS_MS, STANDARD)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (downAt != 0L) {
                    val held = event.eventTime - downAt
                    val tapped = insert && event.actionMasked == MotionEvent.ACTION_UP
                    downAt = 0L
                    v.postDelayed(release, max(KeyPress.MIN_HOLD_MS - held, if (tapped) INSERT_HOLD_MS else 0L).coerceAtLeast(0L))
                }
            }
            false
        }
    }

    /** The veil menu grows from the ⋯ it hangs from. */
    private fun openMenu(anchor: View, items: List<MenuItem>) {
        val u = unit
        menu.colors = colors
        menu.unit = u
        menu.room = room
        menu.removeAllViews()
        menu.setPadding(room, room + (MENU_PAD * u).toInt(), room, room + (MENU_PAD * u).toInt())
        items.forEachIndexed { i, item ->
            menu.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(colors.press))
                }
                // After the background, which would set the padding to its own (none).
                setPadding((MENU_ITEM_PAD * u).toInt(), 0, (MENU_ITEM_PAD * u).toInt(), 0)
                addView(ImageView(context).apply {
                    setImageDrawable(IconDrawable(item.icon, MENU_ICON * u, colors.ink))
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams((MENU_ICON * u).toInt(), (MENU_ICON * u).toInt()).apply { marginEnd = (MENU_ICON_GAP * u).toInt() })
                addView(text(item.label, MENU_TEXT, colors.ink, Fonts.MEDIUM))
                setOnClickListener {
                    closeMenu()
                    item.run()
                }
                if (KeyboardMotion.animates()) {
                    alpha = 0f
                    translationY = MENU_ITEM_RISE * u
                    animate().alpha(1f).translationY(0f).setStartDelay(MENU_ITEM_DELAY_MS + MENU_ITEM_STAGGER_MS * i).setDuration(MENU_ITEM_MS).setInterpolator(STANDARD).start()
                }
            }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (MENU_ITEM * u).toInt()))
        }
        // Where the ⋯ is in the panel, the rows' scroll included.
        val at = android.graphics.Rect(0, 0, anchor.width, anchor.height)
        offsetDescendantRectToMyCoords(anchor, at)
        val width = (MENU_WIDTH * u).toInt()
        val height = ((MENU_ITEM * items.size + 2 * MENU_PAD) * u).toInt()
        val right = at.right
        val below = at.bottom
        val top = if (below + height <= this.height) below else (at.top - height).coerceAtLeast(0)
        // The glass inside, with room around it for its shadow.
        menu.layoutParams = LayoutParams(width + 2 * room, height + 2 * room).apply {
            leftMargin = (right - width).coerceAtLeast(0) - room
            topMargin = top - room
        }
        menu.capture(content)
        menuLayer.visibility = VISIBLE
        menu.pivotX = (room + width).toFloat()
        menu.pivotY = (if (top >= below) room else room + height).toFloat()
        menu.animate().cancel()
        if (!KeyboardMotion.animates()) {
            menu.alpha = 1f
            menu.scaleX = 1f
            menu.scaleY = 1f
            return
        }
        menu.alpha = 0f
        menu.scaleX = MENU_FROM
        menu.scaleY = MENU_FROM
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(MENU_MS).setInterpolator(STANDARD).start()
    }

    private fun closeMenu() {
        if (menuLayer.visibility != VISIBLE) return
        if (!KeyboardMotion.animates()) {
            menu.animate().cancel()
            menuLayer.visibility = GONE
            return
        }
        menu.animate().alpha(0f).scaleX(MENU_TO).scaleY(MENU_TO).setDuration(MENU_CLOSE_MS).setInterpolator(STANDARD_ACCELERATE)
            .withEndAction { menuLayer.visibility = GONE }.start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(disarm)
        // A clear already confirmed is never lost with the window.
        clearing?.let {
            removeCallbacks(it)
            it.run()
        }
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val line = unit.coerceAtLeast(1f)
        canvas.drawRect(0f, 0f, width.toFloat(), line, lines)
    }

    private fun text(value: String, size: Float, color: Int, weight: Int) = TextView(context).apply {
        text = value
        styleText(this, size, color, weight)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    /** A centred one-line label (a tab, a pill): maxLines, since a single line scrolls off when centred unattached. */
    private fun label(value: String, size: Float, color: Int, weight: Int) = TextView(context).apply {
        text = value
        styleText(this, size, color, weight)
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun styleText(view: TextView, size: Float, color: Int, weight: Int) {
        view.setTextColor(color)
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit)
        view.typeface = Fonts.typeface(context)
        view.fontVariationSettings = "'wght' $weight"
        view.includeFontPadding = false
    }

    private fun round(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * unit
    }

    /** The Clipboard | History control: a tonal track, and the white chip of the current tab, which slides. */
    private class Segment(context: Context) : LinearLayout(context) {
        var colors = KeyboardColors.DAY
        var unit = 1f
        private var thumb = 0f
        private var animator: ValueAnimator? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()

        /** Called with the tab (0 or 1) the chip was slid onto. */
        var onPick: (Int) -> Unit = {}
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var sliding = false
        private var near = 0
        private var from = 0

        init {
            orientation = HORIZONTAL
            setWillNotDraw(false)
        }

        // Press and slide along the track: the chip follows the finger and settles on the nearer tab when let go.
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    sliding = false
                }
                MotionEvent.ACTION_MOVE -> startSlide(e)
            }
            return sliding
        }

        private fun startSlide(e: MotionEvent) {
            if (sliding || abs(e.x - downX) <= slop) return
            sliding = true
            near = thumb.roundToInt()
            from = near
            animator?.cancel()
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!sliding) {
                // A press on the track's edge, between the tabs, reaches here first.
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> downX = e.x
                    MotionEvent.ACTION_MOVE -> startSlide(e)
                }
                if (!sliding) return true
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val slot = (width - paddingLeft - paddingRight) / 2f
                    thumb = ((e.x - paddingLeft - slot / 2) / slot).coerceIn(0f, 1f)
                    if (thumb.roundToInt() != near) {
                        near = thumb.roundToInt()
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    sliding = false
                    val to = thumb.roundToInt()
                    slide(to.toFloat(), true)
                    onPick(to)
                }
                MotionEvent.ACTION_CANCEL -> {
                    sliding = false
                    slide(from.toFloat(), true)
                }
            }
            return true
        }

        /** The chip to the first tab (0) or the second (1); [animate]d, it slides over 200 ms, in-out. */
        fun slide(to: Float, animate: Boolean) {
            animator?.cancel()
            if (!animate || !KeyboardMotion.animates()) {
                thumb = to
                invalidate()
                return
            }
            animator = ValueAnimator.ofFloat(thumb, to).apply {
                duration = THUMB_MS
                interpolator = IN_OUT
                addUpdateListener {
                    thumb = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            paint.color = colors.segment
            box.set(0f, 0f, width.toFloat(), h)
            canvas.drawRoundRect(box, h / 2, h / 2, paint)
            // The chip, with a soft shadow 1 dp under it.
            val slot = (width - paddingLeft - paddingRight) / 2f
            val left = paddingLeft + slot * thumb
            val radius = CHIP_RADIUS * unit
            val lift = unit.toInt().toFloat()
            paint.color = CHIP_SHADOW
            box.set(left, paddingTop + lift, left + slot, h - paddingBottom)
            canvas.drawRoundRect(box, radius, radius, paint)
            paint.color = colors.segmentOn
            box.set(left, paddingTop.toFloat(), left + slot, h - paddingBottom - lift)
            canvas.drawRoundRect(box, radius, radius, paint)
        }
    }

    /** The hairline under a row, inside it, so the rows keep their height and it goes with its row. */
    private class Hairline(color: Int, private val px: Int) : Drawable() {
        private val paint = Paint().apply { this.color = color }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(b.left.toFloat(), (b.bottom - px).toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** A pop-up of veil glass over what lies behind it in the panel, with [room] around the glass for its shadow. */
    private class VeilBox(context: Context) : LinearLayout(context) {
        var colors = KeyboardColors.DAY
        var unit = 1f
        var room = 0
        private val veil = Veil(this)
        private val frame = RectF()
        private val clip = Path()

        init {
            orientation = VERTICAL
            setWillNotDraw(false)
        }

        /** Laid out first, so the glass knows its place. */
        fun capture(behind: View) = veil.captureLaidOut(behind)

        private fun glass(): RectF = frame.apply { set(room.toFloat(), room.toFloat(), (width - room).toFloat(), (height - room).toFloat()) }

        override fun onDraw(canvas: Canvas) {
            veil.draw(canvas, glass(), MENU_RADIUS * unit, colors)
        }

        // The items' pressed fill stays inside the glass's round corners.
        override fun dispatchDraw(canvas: Canvas) {
            clip.rewind()
            clip.addRoundRect(glass(), MENU_RADIUS * unit, MENU_RADIUS * unit, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            super.dispatchDraw(canvas)
            canvas.restore()
        }
    }

    companion object {
        /** How tall a copied image's preview is decoded, for the rows and the paste offer. */
        const val IMAGE_HEIGHT_DP = 96
        private const val HEADER_TOP = 6f
        private const val SIDE = 12f
        private const val SEGMENT_WIDTH = 232f
        private const val SEGMENT_HEIGHT = 34f
        private const val SEGMENT_PAD = 2f
        private const val CHIP_RADIUS = 14f
        private const val CHIP_SHADOW = 0x1F0B285A
        private const val TAB_TEXT = 14.5f
        private const val CLEAR_TEXT = 14.5f
        private const val CLEAR_PAD = 14f
        private const val CLEAR_TINT = 0.10f
        private const val CLEAR_CONFIRM_MS = 3_000L
        private const val CARD_TOP = 46f
        /** The card runs down to this far above the grid's bottom; a row cut at its foot shows it scrolls. */
        private const val CARD_BOTTOM = 10f
        private const val CARD_RADIUS = 16f
        private const val ROW = 46f
        private const val ROW_START = 14f
        private const val ROW_END = 6f
        private const val ROW_TEXT = 15f
        private const val ROW_LINE = 19f
        private const val META_TEXT = 12f
        private const val META_LINE = 15f
        private const val DOT = 6f
        private const val CHECK = 13f
        private const val MARK_GAP = 5f
        private const val TEXT_END = 4f
        private const val THUMB = 34f
        private const val THUMB_RADIUS = 7f
        private const val GAP = 6f
        private const val PILL_TEXT = 14f
        private const val PILL_HEIGHT = 32f
        private const val PILL_PAD = 13f
        private const val PILL_MIN_WIDTH = 66f
        private const val BUTTON = 36f
        private const val BUTTON_GAP = 4f
        private const val ICON = 19f
        private const val MORE_ICON = 21f
        // One menu spec with the app's and the dots' (radius 20, 48 dp rows, 12 dp in, 20 dp icons 12 dp from the
        // text); 184 dp wide, as the dots' menu, where the app's 200 would crowd the panel.
        private const val MENU_WIDTH = 184f
        private const val MENU_RADIUS = 20f
        private const val MENU_PAD = 4f
        private const val MENU_ITEM = 48f
        private const val MENU_ITEM_PAD = 12f
        private const val MENU_ICON = 20f
        private const val MENU_ICON_GAP = 12f
        private const val MENU_TEXT = 15f
        private const val MENU_FROM = 0.86f
        private const val MENU_TO = 0.95f
        private const val MENU_MS = 180L
        private const val MENU_CLOSE_MS = 120L
        private const val MENU_ITEM_MS = 100L
        private const val MENU_ITEM_DELAY_MS = 30L
        private const val MENU_ITEM_STAGGER_MS = 20L
        private const val MENU_ITEM_RISE = 5f
        private const val DISABLED_ALPHA = 0.38f

        // How far a press squeezes; narrower pills deeper.
        private const val NARROW_PRESS = 0.94f
        private const val WIDE_PRESS = 0.96f
        private const val ROUND_PRESS = 0.96f

        // The thumb slides, the old list leaves 24 dp one way, the new one comes from 24 dp the other.
        private const val THUMB_MS = 200L
        private const val SLIDE = 24f
        private const val LIST_OUT_MS = 120L
        private const val LIST_IN_AT_MS = 60L
        private const val LIST_IN_MS = 160L

        // The row fades 0–120, folds 100–280.
        private const val ROW_FADE_MS = 120L
        private const val ROW_FOLD_AT_MS = 100L
        private const val ROW_FOLD_MS = 180L

        // Clear.
        private const val ARM_MS = 180L
        /** The part of the Clear pill's growth over which each of its labels fades. */
        private const val LABEL_SPAN = 0.3f
        private const val DISARM_MS = 200L
        private const val CLEAR_BACK_MS = 160L
        private const val CLEAR_RISE = 6f
        private const val ROWS_OUT_MS = 140L
        private const val ROWS_STAGGER_MS = 40L
        private const val CARD_FADE_AT_MS = 200L
        private const val CARD_FADE_MS = 80L
        private const val EMPTY_IN_MS = 140L

        // Insert: pressed until 60, released 60–140 with an accent tint of 14 %; the text at 80, the close at 140.
        private const val INSERT_TINT = 0.14f
        private const val INSERT_HOLD_MS = 60L
        private const val INSERT_RELEASE_MS = 80L
        private const val INSERT_TEXT_MS = 80L
        private const val INSERT_CLOSE_MS = 140L

        private val STANDARD = PathInterpolator(0.2f, 0f, 0f, 1f)
        private val STANDARD_ACCELERATE = PathInterpolator(0.3f, 0f, 1f, 1f)
        private val IN_OUT = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        private val LINEAR = LinearInterpolator()
    }
}


/**
 * The one floating card for the Style and Translate lists: veil glass, 224 × 188 dp, four item slots
 * that scroll with more, a hairline and a pinned row; a notch on its top edge points at the half that opened it.
 * The view is larger than the card by [Veil.ROOM_DP] all round, for the shadow. Plain Views, so TalkBack reads every
 * row.
 */
@SuppressLint("ViewConstructor")
class ListCard(context: Context) : FrameLayout(context) {
    data class Item(val label: String, val hint: String?, val selected: Boolean, val onPick: () -> Unit)

    /** The row under the hairline: "Style settings" or "Add language". */
    data class Pinned(val icon: String, val label: String, val onPick: () -> Unit)

    private var colors = KeyboardColors.DAY
    private var unit = 1f
    private val veil = Veil(this)
    private val frame = RectF()
    private val room = Veil.room(this)
    private val line = max(1, resources.displayMetrics.density.roundToInt()).toFloat()
    private val notchPath = Path()
    private val notchBase = Path()
    private val clip = Path()
    private val notchFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notchEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var notchX = 0f
    private val items = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(context).apply {
        overScrollMode = OVER_SCROLL_NEVER
        addView(items)
    }
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** Open, or opening; closing counts as closed. */
    var shown = false
        private set

    init {
        setWillNotDraw(false)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visibility = GONE
    }

    /**
     * Shows [list] and [pinned] in [frame] (keyboard coordinates) with the notch at [notch] (x in the keyboard), over
     * [behind], which the glass blurs. Open already, the card stays, the notch slides and the rows cross-fade.
     */
    fun show(list: List<Item>, pinned: Pinned, frame: RectF, notch: Float, behind: View, colors: KeyboardColors, unit: Float) {
        this.colors = colors
        val switching = shown && this.unit == unit
        this.unit = unit
        val notchH = notchHeight()
        val params = (layoutParams as? LayoutParams) ?: LayoutParams(0, 0)
        params.width = frame.width().roundToInt() + 2 * room
        params.height = frame.height().roundToInt() + notchH + 2 * room
        params.leftMargin = frame.left.roundToInt() - room
        params.topMargin = frame.top.roundToInt() - notchH - room
        // The room never makes the keyboard window any larger than the card itself would.
        params.rightMargin = -room
        params.bottomMargin = -room
        layoutParams = params
        val target = notch - params.leftMargin
        fill(list, pinned)
        shown = true
        animate().cancel()
        if (!KeyboardMotion.animates()) {
            notchX = target
            content.animate().cancel()
            content.alpha = 1f
            if (!switching) veil.captureLaidOut(behind)
            visibility = VISIBLE
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            invalidate()
            return
        }
        if (switching) {
            val from = notchX
            animate().setUpdateListener {
                notchX = from + (target - from) * it.animatedFraction
                invalidate()
            }.alpha(1f).scaleX(1f).scaleY(1f).setDuration(SWITCH_MS).setInterpolator(STANDARD).withEndAction(null).start()
            content.alpha = 0f
            content.animate().alpha(1f).setDuration(ROWS_FADE_MS).start()
            return
        }
        notchX = target
        // Once the card has its size and place; it is not part of [behind], so the glass never blurs itself.
        veil.captureLaidOut(behind)
        visibility = VISIBLE
        alpha = 0f
        scaleX = OPEN_FROM
        scaleY = OPEN_FROM
        pivotX = target
        pivotY = room.toFloat()
        animate().setUpdateListener(null).alpha(1f).scaleX(1f).scaleY(1f).setDuration(OPEN_MS).setInterpolator(EMPHASIZED_DECELERATE).withEndAction(null).start()
    }

    private var hovered: View? = null
    private val spot = IntArray(2)

    /** A finger held from Style or Translate slides over the rows: the one under ([rawX], [rawY]) on the screen lights. */
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

    /** The finger let go: the row under it is picked. Returns false if it was over none (the card stays open). */
    fun liftAt(rawX: Float, rawY: Float): Boolean {
        hovered?.isPressed = false
        hovered = null
        val row = rowAt(rawX, rawY) ?: return false
        row.performClick()
        return true
    }

    /** The row on the screen at ([rawX], [rawY]), among those in sight. */
    private fun rowAt(rawX: Float, rawY: Float): View? {
        if (!shown || rawX.isNaN()) return null
        fun on(view: View): Boolean {
            view.getLocationOnScreen(spot)
            return rawX >= spot[0] && rawX < spot[0] + view.width && rawY >= spot[1] && rawY < spot[1] + view.height
        }
        if (on(scroll)) return (0 until items.childCount).map(items::getChildAt).firstOrNull(::on)
        return content.getChildAt(content.childCount - 1)?.takeIf(::on)
    }

    /** Closing: the card fades and shrinks a little, then goes. */
    fun close(animated: Boolean = true) {
        hovered?.isPressed = false
        hovered = null
        if (!shown) return
        shown = false
        animate().cancel()
        if (!animated || !KeyboardMotion.animates()) {
            visibility = GONE
            veil.forget()
            return
        }
        animate().setUpdateListener(null).alpha(0f).scaleX(CLOSE_TO).scaleY(CLOSE_TO).setDuration(CLOSE_MS).setInterpolator(EMPHASIZED_ACCELERATE)
            .withEndAction {
                visibility = GONE
                veil.forget()
            }.start()
    }

    private fun fill(list: List<Item>, pinned: Pinned) {
        val u = unit
        val c = colors
        content.removeAllViews()
        items.removeAllViews()
        content.setPadding(room, room + notchHeight() + (PAD * u).toInt(), room, room + (PAD * u).toInt())
        list.forEach { item ->
            items.addView(row(if (item.selected) "check" else null, item.label, item.hint, item.selected, pinned = false, item.onPick), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (ROW * u).toInt()))
        }
        scroll.isVerticalScrollBarEnabled = list.size > SLOTS
        scroll.scrollBarSize = (THUMB * u).toInt()
        scroll.verticalScrollbarThumbDrawable = GradientDrawable().apply {
            setColor(Palette.alpha(c.meta, THUMB_ALPHA))
            cornerRadius = THUMB * u
        }
        content.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (SLOTS * ROW * u).toInt()))
        content.addView(View(context).apply { setBackgroundColor(c.veilLine) }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, u.toInt().coerceAtLeast(1)).apply {
            leftMargin = (HAIRLINE_INSET * u).toInt()
            rightMargin = (HAIRLINE_INSET * u).toInt()
        })
        content.addView(row(pinned.icon, pinned.label, null, selected = false, pinned = true, pinned.onPick), LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, (ROW * u).toInt()))
        // The current item scrolled into view.
        val current = list.indexOfFirst { it.selected }
        scroll.post { scroll.scrollTo(0, ((current - SLOTS + 1).coerceAtLeast(0) * ROW * u).toInt()) }
    }

    private fun row(icon: String?, label: String, hint: String?, selected: Boolean, pinned: Boolean, onPick: () -> Unit): View {
        val u = unit
        val c = colors
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isSelected = selected
            // The lit row is round, inset from the card's edges as the card itself is round.
            background = StateListDrawable().apply {
                val fill = GradientDrawable().apply {
                    setColor(Palette.alpha(c.accent, PRESSED_TINT))
                    cornerRadius = PRESSED_RADIUS * u
                }
                addState(intArrayOf(android.R.attr.state_pressed), InsetDrawable(fill, (PRESSED_INSET * u).toInt(), 0, (PRESSED_INSET * u).toInt(), 0))
            }
            // After the background: a background with insets sets the view's padding to them, which would leave the rows
            // at 6 dp on both sides, the hints 7 dp from the card's edge.
            setPadding((ROW_START * u).toInt(), 0, (ROW_END * u).toInt(), 0)
            addView(ImageView(context).apply {
                if (icon != null) setImageDrawable(IconDrawable(icon, ICON * u, c.accent))
                scaleType = ImageView.ScaleType.CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((CHECK_COLUMN * u).toInt(), LayoutParams.MATCH_PARENT).apply { marginEnd = (CHECK_GAP * u).toInt() })
            val weight = when {
                selected -> Fonts.TITLE
                pinned -> Fonts.SEMIBOLD
                else -> Fonts.MEDIUM
            }
            addView(text(label, NAME * u, if (selected || pinned) c.accent else c.ink, weight), LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            if (hint != null) addView(text(hint, HINT * u, c.meta, Fonts.MEDIUM).apply { setPadding((HINT_GAP * u).toInt(), 0, 0, 0) })
            setOnClickListener { onPick() }
        }
    }

    private fun text(value: String, px: Float, color: Int, weight: Int) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        typeface = Fonts.typeface(context)
        fontVariationSettings = "'wght' $weight"
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun notchHeight() = (NOTCH_HEIGHT * unit).roundToInt()

    /** The card itself, inside the room for its shadow and under the notch. */
    private fun card(): RectF = frame.apply { set(room.toFloat(), (room + notchHeight()).toFloat(), (width - room).toFloat(), (height - room).toFloat()) }

    override fun onDraw(canvas: Canvas) {
        val card = card()
        val apex = room.toFloat()
        val top = card.top
        val half = NOTCH_WIDTH * unit / 2
        // The notch's base, where the card's ring and rim give way to it, so the two are one shape.
        notchBase.rewind()
        notchBase.moveTo(notchX - half, top + line)
        notchBase.lineTo(notchX - half, top)
        notchBase.lineTo(notchX, apex)
        notchBase.lineTo(notchX + half, top)
        notchBase.lineTo(notchX + half, top + line)
        notchBase.close()
        veil.draw(canvas, card, RADIUS * unit, colors, cut = notchBase)
        // The notch: veil-white down to the card's edge, its two slanted edges in the veil's edge colour.
        notchPath.rewind()
        notchPath.moveTo(notchX - half, top)
        notchPath.lineTo(notchX, apex)
        notchPath.lineTo(notchX + half, top)
        notchPath.close()
        notchFill.color = colors.veilNotch
        canvas.drawPath(notchPath, notchFill)
        notchPath.rewind()
        notchPath.moveTo(notchX - half, top)
        notchPath.lineTo(notchX, apex)
        notchPath.lineTo(notchX + half, top)
        notchEdge.color = colors.veilEdge
        notchEdge.strokeWidth = line
        canvas.drawPath(notchPath, notchEdge)
    }

    // The rows' pressed fill stays inside the card's round corners.
    override fun dispatchDraw(canvas: Canvas) {
        clip.rewind()
        clip.addRoundRect(card(), RADIUS * unit, RADIUS * unit, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    private companion object {
        const val NOTCH_WIDTH = 12f
        const val NOTCH_HEIGHT = 6f
        const val RADIUS = 18f
        const val PAD = 6f
        const val SLOTS = 4
        const val ROW = 35f
        // The checks keep their place; the hints (casual, polished, EN…) end 18 dp from the card's edge, as far
        // as the checks start from the other one.
        const val ROW_START = 6f
        const val ROW_END = 18f
        const val CHECK_COLUMN = 20f
        const val CHECK_GAP = 10f
        const val ICON = 17f
        const val NAME = 15f
        const val HINT = 12f
        const val HINT_GAP = 10f
        const val HAIRLINE_INSET = 12f
        const val THUMB = 3f
        const val THUMB_ALPHA = 0.5f
        const val PRESSED_TINT = 0.10f
        const val PRESSED_INSET = 6f
        const val PRESSED_RADIUS = 12f
        const val OPEN_FROM = 0.96f
        const val CLOSE_TO = 0.98f
        const val OPEN_MS = 180L
        const val CLOSE_MS = 120L
        const val SWITCH_MS = 160L
        const val ROWS_FADE_MS = 120L
        val STANDARD = PathInterpolator(0.2f, 0f, 0f, 1f)
        val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val EMPHASIZED_ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    }
}
