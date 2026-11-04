package app.waveei.android.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.text.LineBreaker
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import app.waveei.android.DebugLog
import app.waveei.android.Dictation
import app.waveei.android.R
import app.waveei.android.Stats
import java.util.EnumMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The keyboard panel drawn on a Canvas: a 20 dp grabber strip over a grid of four rows and
 * five columns. Keys, rows and text scale with one design unit [unit], capped by a height rule, so the panel
 * keeps its proportions on any screen: where the width outgrows the unit (a tablet, landscape), the 411-unit layout
 * sits centred and only the background runs on to the edges.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val actions: Actions) : View(context) {
    interface Actions {
        fun onMicDown()
        fun onMicTap()
        fun onCancel()

        /** Pause a recording, or resume a paused one (the ▶ in the same place). */
        fun onPause()
        fun onRetry()
        fun onBackspace(word: Boolean)
        fun onSpace()
        fun onEnter()
        fun onSwitch()
        fun onSwitchLong()
        fun onStyle()
        fun onTranslate()
        fun onStatus()
        fun onInsertHere()

        /** The chip offering to record on into a recording that was cut off. */
        fun onContinueKept() {}

        /** Paste the text or image copied a moment ago. */
        fun onPaste()

        /** × on the paste offer: the toolbar comes back until something new is copied. */
        fun onPasteDismiss()

        /** Open or close the Clipboard panel: the key, or a hold on the paste offer. */
        fun onClipboard()

        /** Open or close the emoji panel. */
        fun onEmoji()

        /** Hide the keyboard (the grabber dragged down, or TalkBack's action); a recording goes on. */
        fun onHide()

        /** Open the app's settings. */
        fun onSettings()

        /** Open the app on Words › Vocabulary. */
        fun onVocabulary()

        /** Send now: the dictation going on, once it goes in, else what the field holds. */
        fun onAutoSend()

        /** Held, the send key turns sending every dictation right after it goes in on or off. */
        fun onAutoSendLong() {}

        /** A finger sliding on the space bar moves the cursor: [right] a character, [down] a line, either may be 0. */
        fun onCursor(right: Int, down: Int) {}

        /** Clear the whole field. */
        fun onClearField()

        /** A touch outside the open Style or Translate card closes it. */
        fun onCloseList() {}

        /** Held on Style or Translate, the finger slides over the card's rows: the one under it ([rawX], [rawY]) lights. */
        fun onCardSlide(rawX: Float, rawY: Float) {}

        /** Let go after such a slide: the row under the finger is picked; false if there was none. */
        fun onCardLift(rawX: Float, rawY: Float): Boolean = false
    }

    /**
     * How the status line reads: a grey hint, a grey note, ink, or red. Two of them are chips that open their screen,
     * never red as a failure: [ACTION], a step the person takes before dictating (sign in, get Tide, allow the
     * microphone, agree to changed Terms), in the accent like the offers about a dictation (Insert here, Continue
     * recording); [INFO], a state to know about that one tap does not fix (phone not verified), grey as the tonal keys.
     */
    enum class StatusKind { HINT, NOTE, ACTIVE, ERROR, ACTION }

    data class Model(
        val state: Dictation.State = Dictation.State.IDLE,
        /** Recording, but not shown as Listening until the mic's first sound: the ring, Pause and × are there already. */
        val starting: Boolean = false,
        val status: String = "",
        val statusKind: StatusKind = StatusKind.HINT,
        /** The words heard so far while recording, shown in place of the status when there are any. */
        val liveWords: String = "",
        /** The current style and translation by name, for the content descriptions; the halves' labels are fixed. */
        val style: String = "Off",
        val styleOn: Boolean = false,
        val translate: String = "Off",
        val translateOn: Boolean = false,
        val micEnabled: Boolean = true,
        /** Dictation cannot start until a step is taken (the status chip): the mic looks off, a tap still tries. */
        val micBlocked: Boolean = false,
        val canRetry: Boolean = false,
        /** EditorInfo action of the field, for the Enter glyph. */
        val enterAction: Int = EditorInfo.IME_ACTION_NONE,
        val insertHere: Boolean = false,
        /** A cut-off recording offered to record on into, in place of the status; Retry then sends it as it is. */
        val continueKept: String? = null,
        /** Text copied a moment ago, offered in the top row; empty for a plain "Paste". */
        val paste: String? = null,
        /** The preview of a copied image on offer, shown in the paste pill. */
        val pasteImage: Bitmap? = null,
        /** What is open: the Clipboard or Emoji panel over the middle rows, or the Style or Translate card. */
        val open: Key? = null,
        val bufferCount: Int = 0,
        /** The field's send action is pressed right after a dictation is inserted. */
        val autoSend: Boolean = false,
        /** Only the next dictation is sent right after it goes in. */
        val sendOnce: Boolean = false,
        /** The bin asked for its second tap: it turns red. */
        val clearAsked: Boolean = false,
        /**
         * Processing, but the recording is still on its way (no network, or still sending): "Waiting for network…".
         * The status line says so; the busy arc stays the accent, one spinner for every wait.
         */
        val network: Boolean = false,
    )

    /** Every control; GRABBER is only TalkBack's "Hide keyboard", the drag needs no key. */
    enum class Key {
        CLIPBOARD, EMOJI, STYLE, TRANSLATE, SEND, PASTE, PASTE_CLOSE,
        CLEAR, STATUS, BACKSPACE, SETTINGS, PAUSE, RETRY, MIC, CANCEL, ENTER, VOCABULARY, SPACE, SWITCH, GRABBER,
    }

    var model = Model()
        set(value) {
            if (field == value) return
            val old = field
            field = value
            changed(old, value)
            accessibility.invalidateRoot()
            // TalkBack hears what the status line says when it changes (Listening, Failed, Inserted…); the grey idle
            // hint and the live words, which change with every word, are not read out.
            val said = spoken(value)
            if (said != spoken(old) && said.isNotBlank() && value.statusKind != StatusKind.HINT) {
                @Suppress("DEPRECATION")
                announceForAccessibility(said)
            }
            invalidate()
        }

    var colors = KeyboardColors.DAY
        set(value) {
            field = value
            background.setShader(null)
            invalidate()
            // The window's root draws the panel's shadow (drawShadow).
            (parent as? View)?.invalidate()
        }

    var leftHanded = false
        set(value) {
            field = value
            layoutKeys()
            invalidate()
        }

    /** Fixed clock for screenshots: every transition shows its end. Null uses the real one. */
    var frameTimeMs: Long? = null

    /** The design unit in pixels (1 dp at the reference width), for the panels laid over this view. */
    val unit: Float get() = s

    /** The empty band on each side of the centred layout, 0 where the width is the layout's own. */
    val side: Float get() = max(0f, (width - WIDE * sx) / 2)

    /** Top and bottom of the Clipboard and Emoji panels: the whole keyboard under the top row. */
    var middleTop = 0f
        private set
    var middleBottom = 0f
        private set

    // Geometry, in pixels.
    private var s = 1f

    /** The unit the columns are spread by: the width's share of the reference, which [s] may outgrow a little. */
    private var sx = 1f
    private var bottomInset = 0
    private val rects = EnumMap<Key, RectF>(Key::class.java)
    private val trio = RectF()
    private val grabBar = RectF()
    private val grabArea = RectF()

    private val pastePill = RectF()
    private var pasteLabel: CharSequence = ""
    private var micX = 0f
    private var micY = 0f

    // Paints and paths, made once: drawing runs every frame while recording.
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val background = Paint()
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var panelShadowSize = 0f
    private val panelShadowRect = RectF()
    private val retryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val air = Paint(Paint.ANTI_ALIAS_FLAG)
    private val water = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layerPaint = Paint()
    // The mic off: the ball greyed and lifted towards [KeyboardColors.micOff], opaque; never see-through.
    private val dimPaint = Paint()
    private var dimFor: KeyboardColors? = null
    // The recording mark: its softened core and, under it, the same stroke blurred into a wider glow.
    private val recCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val recGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var recGlowSize = 0f
    private val arcFade = ArcFade()
    private val busyPaint = Paint()
    private val busyMatrix = ColorMatrix()
    private var busyShown = -1f
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hintText = textPaint(Fonts.REGULAR)
    private val mediumText = textPaint(Fonts.MEDIUM)
    private val strongText = textPaint(Fonts.SEMIBOLD)
    private val path = Path()
    private val clip = Path()
    // The press's crisp shadow under a shape's top edge: its clip, its path and its blurred paint.
    private val edgeClip = Path()
    private val edgePath = Path()
    private val edgeRect = RectF()
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var edgeBlur = 0f
    private val glyphPath = Path()
    private val frontPath = Path()
    private val waterShader = LinearGradient(0f, 0f, 0f, 1f, KeyboardColors.WATER, WATER_STOPS, Shader.TileMode.CLAMP)
    private val waterMatrix = Matrix()
    private val chip = RectF()
    private val tmp = RectF()
    private val tmp2 = RectF()
    private val imageRect = RectF()
    private val imageCrop = Rect()
    private val icons = HashMap<String, Path>()
    private val twoLines = HashMap<String, StaticLayout>()
    private val micGlyph: Path = PathParser.createPathFromPathData(MIC_GLYPH)
    private val glyphMatrix = Matrix()
    private val spaceLabel = context.getString(R.string.space)
    private val cursorLabel = context.getString(R.string.space_cursor)
    private val styleLabel = context.getString(R.string.kb_style)
    private val translateLabel = context.getString(R.string.kb_translate)

    // Touch.
    private var pressed: Key? = null
    private var repeats = 0
    private var longPressed = false
    // Held on Style or Translate: the card is open under the finger, which picks a row by sliding to it and letting go.
    private var sliding = false
    private var startedOnDown = false
    /** Where the finger on the space bar last moved the cursor from; null until it slides past the touch slop. */
    private var cursorX: Float? = null
    private var cursorY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downRawY = 0f

    /** The finger that started the touch: its lift ends the press, even while another finger is down. */
    private var downPointer = 0
    /** The finger is on the strip over the top row's keys, where there is no key: the pull starts at its first move. */
    private var onGrabber = false
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var panelAnimator: ValueAnimator? = null

    /** [panelAnimator] is taking the panel home, and a new pull may catch it; caught, it waits under the finger. */
    private var springing = false
    private var caught = false

    /** How far the finger has pulled the panel down, px; the panel itself goes less far ([band]). */
    private var pull = 0f

    /** The pull is past HIDE_PULL: letting go now hides the keyboard. */
    private var pastHide = false
    private val accessibility = Accessibility()

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        isFocusable = true
    }

    /** The voice right now, read every frame while recording. */
    var meter: (() -> Float)? = null

    // Layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val metrics = resources.displayMetrics
        // The width spreads the columns, a unit at most 1.1 dp, the panel at most 40 % of the screen. On a
        // phone narrower than the reference the keys and rows shrink less than the width (not too small on a
        // 384 dp phone), up to 1 dp, at most MAX_GAIN past the width's share so the keys keep their gaps.
        // The navigation inset is not part of the panel: it is added below, once.
        val cap = min(metrics.density * MAX_UNIT_DP, metrics.heightPixels * MAX_SCREEN_SHARE / TALL)
        sx = min(width / WIDE, cap)
        s = min(cap, max(sx, min(metrics.density, sx * MAX_GAIN)))
        setMeasuredDimension(width, (TALL * s).toInt() + bottomInset)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        if (bottom != bottomInset) {
            bottomInset = bottom
            requestLayout()
        }
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        background.setShader(null)
        icons.clear()
        twoLines.clear()
        layoutKeys()
    }

    /** A column's centre, mirrored for the left hand. */
    private fun mx(dp: Float) = if (leftHanded) width - x(dp) else x(dp)

    private fun x(dp: Float) = side + dp * sx

    /** A row of the grid, which starts under the grabber strip. */
    private fun y(dp: Float) = (GRAB + dp) * s

    private fun layoutKeys() {
        if (width == 0) return
        fun round(key: Key, cx: Float, cy: Float, size: Float) {
            rects.getOrPut(key, ::RectF).set(cx - size * s / 2, cy - size * s / 2, cx + size * s / 2, cy + size * s / 2)
        }
        fun box(key: Key, left: Float, top: Float, right: Float, bottom: Float) {
            rects.getOrPut(key, ::RectF).set(left, top, right, bottom)
        }
        val axis = x(AXIS)
        val (r1, r2, r3, r4) = ROWS.map(::y)
        // The top row spans the grid's width: its ends line up with the side keys' outer edges.
        round(Key.CLIPBOARD, mx(CLIP_X), r1, TOP_KEY)
        round(Key.EMOJI, mx(EMOJI_X), r1, TOP_KEY)
        // The capsule is symmetric about the mic axis, so the left hand keeps it, halves in their order.
        // The wide keys keep to the columns' spread, so the gaps between them stay.
        box(Key.STYLE, axis - HALF_WIDTH * sx, r1 - TOP_KEY * s / 2, axis, r1 + TOP_KEY * s / 2)
        box(Key.TRANSLATE, axis, r1 - TOP_KEY * s / 2, axis + HALF_WIDTH * sx, r1 + TOP_KEY * s / 2)
        box(Key.SEND, mx(SEND_X) - SEND_WIDTH * sx / 2, r1 - TOP_KEY * s / 2, mx(SEND_X) + SEND_WIDTH * sx / 2, r1 + TOP_KEY * s / 2)
        round(Key.CLEAR, mx(COLUMNS[0]), r2, SIDE_KEY)
        box(Key.STATUS, x(MIDDLE_LEFT), r2 - PILL_HEIGHT * s / 2, x(WIDE - MIDDLE_LEFT), r2 + PILL_HEIGHT * s / 2)
        round(Key.BACKSPACE, mx(COLUMNS[4]), r2, SIDE_KEY)
        round(Key.SETTINGS, mx(COLUMNS[0]), r3, SIDE_KEY)
        round(Key.PAUSE, mx(COLUMNS[1]), r3, TRIO_KEY)
        round(Key.RETRY, mx(COLUMNS[1]), r3, TRIO_KEY)
        round(Key.MIC, axis, r3, BALL)
        round(Key.CANCEL, mx(COLUMNS[3]), r3, TRIO_KEY)
        round(Key.ENTER, mx(COLUMNS[4]), r3, SIDE_KEY)
        round(Key.VOCABULARY, mx(COLUMNS[0]), r4, SIDE_KEY)
        box(Key.SPACE, x(MIDDLE_LEFT), r4 - SPACE_HEIGHT * s / 2, x(WIDE - MIDDLE_LEFT), r4 + SPACE_HEIGHT * s / 2)
        round(Key.SWITCH, mx(COLUMNS[4]), r4, SIDE_KEY)
        trio.set(x(COLUMNS[1]) - TRIO_PAD * sx, r3 - TRIO_HEIGHT * s / 2, x(COLUMNS[3]) + TRIO_PAD * sx, r3 + TRIO_HEIGHT * s / 2)
        micX = axis
        micY = r3
        grabBar.set(axis - GRAB_WIDTH * s / 2, GRAB_TOP * s, axis + GRAB_WIDTH * s / 2, (GRAB_TOP + GRAB_HEIGHT) * s)
        // A drag down from the middle GRAB_ZONE of the top edge hides the keyboard (not the whole width): the strip
        // over the top row, down to its keys' top edge and no further, so the keys keep their own taps, holds and
        // slides. A touch there lights the bar at once and the panel follows from the first move.
        grabArea.set(width * (1 - GRAB_ZONE) / 2, 0f, width * (1 + GRAB_ZONE) / 2, y(ROWS[0] - TOP_KEY / 2))
        box(Key.GRABBER, axis - GRAB_TOUCH_WIDTH * s / 2, 0f, axis + GRAB_TOUCH_WIDTH * s / 2, GRAB * s)
        middleTop = y(PANEL_TOP)
        middleBottom = TALL * s
        val glyph = BALL * s * MIC_GLYPH_SHARE
        glyphMatrix.setScale(glyph / ICON_BOX, glyph / ICON_BOX)
        glyphMatrix.postTranslate(micX - glyph / 2, micY - glyph / 2)
        micGlyph.transform(glyphMatrix, glyphPath)
        placePaste()
    }

    /** The Style / Translate card's frame: 224 × 188 dp, centred on the mic axis, 6 dp under the capsule. */
    fun cardFrame(out: RectF) {
        out.set(x(AXIS) - CARD_WIDTH * s / 2, y(CARD_TOP), x(AXIS) + CARD_WIDTH * s / 2, y(CARD_TOP) + CARD_HEIGHT * s)
    }

    /** Where the card's notch points: the centre of the half that opened it. */
    fun notchX(key: Key): Float = rects.getValue(key).centerX()

    /** The paste pill: thumb or "Paste · <start of the text>", then ×, centred between the clipboard key and the far edge. */
    private fun placePaste() {
        val paste = shownPaste ?: return
        if (width == 0) return
        strongText.textSize = PASTE_TEXT * s
        val image = shownImage != null
        val lead = (if (image) CHIP_PAD_THUMB + THUMB + CHIP_GAP else CHIP_PAD) * s
        val tail = (CHIP_GAP + CLOSE_BOX - CLOSE_PULL + CHIP_PAD) * s
        val area = x(COLUMNS[4] - PASTE_INSET) - x(COLUMNS[0] + PASTE_INSET)
        val room = min(PASTE_MAX_WIDTH * s, area) - lead - tail
        val label = if (paste.isEmpty()) context.getString(R.string.paste) else context.getString(R.string.paste_preview, paste.replace('\n', ' '))
        pasteLabel = TextUtils.ellipsize(label, strongText, room, TextUtils.TruncateAt.END)
        val w = lead + strongText.measureText(pasteLabel, 0, pasteLabel.length) + tail
        val cy = y(ROWS[0])
        pastePill.set(width / 2f - w / 2, cy - PASTE_HEIGHT * s / 2, width / 2f + w / 2, cy + PASTE_HEIGHT * s / 2)
        val closeLeft = pastePill.right - (CHIP_PAD - CLOSE_PULL + CLOSE_BOX) * s
        rects.getOrPut(Key.PASTE, ::RectF).set(pastePill.left, pastePill.top, closeLeft - CLOSE_REACH * s, pastePill.bottom)
        rects.getOrPut(Key.PASTE_CLOSE, ::RectF).set(closeLeft - CLOSE_REACH * s, pastePill.top, pastePill.right, pastePill.bottom)
    }

    /** The mic's touch-down, once its ring and live keys are on screen; a start that fails takes them back at once. */
    private fun startMic() {
        actions.onMicDown()
        // It did not start: all back as it was.
        if (model.state == Dictation.State.IDLE && !model.starting) {
            recMark.jump(0f)
            pauseLive.jump(0f)
            cancelLive.jump(if (cancels(model)) 1f else 0f)
            invalidate()
        }
    }

    // Model changes start the transitions, each from where it is now.

    private fun changed(old: Model, new: Model) {
        if (old.state != new.state) {
            waterFrom[0] = fillNow; waterFrom[1] = crestNow; waterFrom[2] = backCrestNow; waterFrom[3] = backRiseNow
            waterAt = now()
            waterMs = if (new.state == Dictation.State.PAUSED) PAUSE_WATER_MS else WATER_MS
            if (old.state == Dictation.State.PROCESSING && new.state == Dictation.State.IDLE && !new.canRetry && new.statusKind == StatusKind.HINT) landedAt = now()
        }
        if (new.state == Dictation.State.RECORDING && old.state != Dictation.State.RECORDING) voice.reset()
        // The recording mark: there and gone at once, as on the dot; it stays through processing, where it turns into
        // the busy arc.
        val marked = new.starting || new.state == Dictation.State.RECORDING || new.state == Dictation.State.PROCESSING
        val wasMarked = old.starting || old.state == Dictation.State.RECORDING || old.state == Dictation.State.PROCESSING
        if (marked != wasMarked) recMark.jump(if (marked) 1f else 0f)
        // The trio changes at once: Pause ↔ Play, Retry, the ring into the busy arc.
        playing.jump(if (new.state == Dictation.State.PAUSED) 1f else 0f)
        busy.jump(if (new.state == Dictation.State.PROCESSING) 1f else 0f)
        playDisc.jump(if (new.state == Dictation.State.PAUSED) 1f else 0f)
        val retry = showsRetry(new)
        if (retry && !showsRetry(old)) retryAt = now()
        retryShown.jump(if (retry) 1f else 0f)
        // Pause and × turn live and back at once, with the recording.
        pauseLive.jump(if (pauses(new)) 1f else 0f)
        cancelLive.jump(if (cancels(new)) 1f else 0f)
        styleOpen.to(if (new.open == Key.STYLE) 1f else 0f, if (new.open == Key.STYLE) OPEN_MS else CLOSE_MS)
        translateOpen.to(if (new.open == Key.TRANSLATE) 1f else 0f, if (new.open == Key.TRANSLATE) OPEN_MS else CLOSE_MS)
        clipboardOpen.to(if (new.open == Key.CLIPBOARD) 1f else 0f)
        emojiOpen.to(if (new.open == Key.EMOJI) 1f else 0f)
        sendOn.to(if (new.autoSend) 1f else 0f)
        sendOnceOn.to(if (new.sendOnce && !new.autoSend) 1f else 0f)
        if (new.autoSend != old.autoSend) badgeAt = now()
        if (pasteShown(new)) {
            shownPaste = new.paste
            shownImage = new.pasteImage
            placePaste()
        }
        pasteRow.to(if (pasteShown(new)) 1f else 0f, if (pasteShown(new)) PASTE_IN_MS else PASTE_OUT_MS, if (pasteShown(new)) STANDARD else STANDARD_ACCELERATE)
        val look = lookOf(new)
        if (look != current) {
            val words = current.look == Look.LIVE && look.look == Look.LIVE
            if (words && look.text.startsWith(current.text)) {
                // The right-anchored line slides left by the new words as they come in.
                mediumText.textSize = STATUS_TEXT * s
                liveShift = mediumText.measureText(look.text, current.text.length, look.text.length) + liveShift * (1f - liveSlide())
                liveAt = now()
            } else if (!words) {
                previous = current
                statusAt = now()
            }
            current = look
        }
    }

    /** The room the keyboard window leaves above the panel for its shadow, in pixels. */
    val topRoom: Int get() = ceil(TOP_ROOM * resources.displayMetrics.density).toInt()

    /**
     * The panel's soft shadow up over the app, in [topRoom]: drawn by the window's root, behind this view and in the
     * root's coordinates, so it is never cut off at this view's edge.
     */
    fun drawShadow(canvas: Canvas) {
        if (width == 0) return
        if (panelShadow.maskFilter == null || panelShadowSize != s) {
            panelShadowSize = s
            panelShadow.maskFilter = BlurMaskFilter(blurRadius(TOP_SHADOW_BLUR * s), BlurMaskFilter.Blur.NORMAL)
        }
        panelShadow.color = colors.topShadow
        val r = CORNER * s
        val y = top + translationY - TOP_SHADOW_DY * s
        panelShadowRect.set(left.toFloat(), y, right.toFloat(), y + height + r)
        canvas.drawRoundRect(panelShadowRect, r, r, panelShadow)
    }

    // Drawing

    override fun onDraw(canvas: Canvas) {
        updateWater()
        updateSpring()
        drawPanel(canvas)
        drawGrabber(canvas)
        drawTopRow(canvas)
        // An open panel lies over these rows; they stay drawn under it so its reveal runs down over them.
        drawMiddleRows(canvas)
        drawBottomRow(canvas)
        // Moving the cursor with the space bar: the keys fade back, and in the middle a text cursor says what the
        // finger does now (as some keyboards do).
        if (pressed == Key.SPACE && cursorX != null) {
            fill.color = Palette.alpha(colors.bgTop, CURSOR_FADE_ALPHA)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            val cy = height / 2f
            drawIcon(canvas, "cursor-text", CURSOR_ICON * s, width / 2f, cy - CURSOR_ICON * s * 0.6f, colors.ink)
            hintText.textSize = CURSOR_TEXT * s
            hintText.color = colors.ink
            hintText.textAlign = Paint.Align.CENTER
            canvas.drawText(cursorLabel, width / 2f, baseline(hintText, cy + CURSOR_TEXT * s * 0.9f), hintText)
            hintText.textAlign = Paint.Align.LEFT
        }
        if (shouldAnimate()) postInvalidateOnAnimation()
    }

    /** White to ice by day, "night water" at night; the top corners round off, the panel stays on the bottom. */
    private fun drawPanel(canvas: Canvas) {
        val c = colors
        if (background.shader == null) {
            background.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(), intArrayOf(c.bgTop, c.bgTop, c.bgBottom), floatArrayOf(0f, c.bgSplit, 1f), Shader.TileMode.CLAMP,
            )
            background.isAntiAlias = true
        }
        val r = CORNER * s
        tmp.set(0f, 0f, width.toFloat(), height + r)
        canvas.drawRoundRect(tmp, r, r, background)
        line.color = c.topLine
        line.strokeWidth = TOP_LINE * s
        tmp.inset(line.strokeWidth / 2, line.strokeWidth / 2)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), r)
        canvas.drawRoundRect(tmp, r, r, line)
        canvas.restore()
    }

    private fun drawGrabber(canvas: Canvas) {
        // Lit while held: drawn from the touch itself, so it goes out with the finger and cannot stay lit. Grey, a
        // shade darker, as a top-row key darkens under the finger: the grabber is a handle, not an action, so no accent.
        fill.color = if (onGrabber) colors.grabHeld else colors.grab
        canvas.drawRoundRect(grabBar, grabBar.height() / 2, grabBar.height() / 2, fill)
    }

    /** The toolbar, or the paste offer in its place (the rows swap bottom-up). */
    private fun drawTopRow(canvas: Canvas) {
        val p = pasteRow.value()
        if (p < 1f) layer(canvas, 0f, y(0f), width.toFloat(), y(ROWS[0] * 2), 1f - (p / TOOLBAR_OUT_SHARE).coerceAtMost(1f), -PASTE_SHIFT * s * p) {
            drawToolbar(canvas)
        }
        if (p > 0f && shownPaste != null) {
            layer(canvas, 0f, y(0f), width.toFloat(), y(ROWS[0] * 2), ((p - PASTE_IN_FROM) / (1f - PASTE_IN_FROM)).coerceIn(0f, 1f), PASTE_SHIFT * s * (1f - p)) {
                drawPasteRow(canvas)
            }
        }
    }

    private fun drawToolbar(canvas: Canvas) {
        val c = colors
        val clipboard = clipboardOpen.value()
        drawTonal(canvas, Key.CLIPBOARD, "clipboard", clipboard)
        if (model.bufferCount > 0 && model.open != Key.CLIPBOARD) {
            val r = rects.getValue(Key.CLIPBOARD)
            val bx = r.centerX() + BADGE_OFFSET * s
            val by = r.centerY() - BADGE_OFFSET * s
            fill.color = c.badgeRing
            canvas.drawCircle(bx, by, (BADGE / 2 + BADGE_RING) * s, fill)
            fill.color = c.badge
            canvas.drawCircle(bx, by, BADGE / 2 * s, fill)
        }
        drawTonal(canvas, Key.EMOJI, "smiley", emojiOpen.value())
        drawCapsule(canvas)
        drawSend(canvas)
    }

    /** A flat 36 dp key of the top row: grey glyph, accent glyph on an accent tint while its panel is open. */
    private fun drawTonal(canvas: Canvas, key: Key, icon: String, open: Float) {
        val c = colors
        val r = rects.getValue(key)
        pressed(canvas, key, r) {
            fill.color = ColorUtils.blendARGB(c.tonal, c.accentBg, open)
            canvas.drawOval(r, fill)
            tint(canvas, key, r, r.height() / 2)
            drawIcon(canvas, icon, ICON_TOP, r.centerX(), r.centerY(), ColorUtils.blendARGB(c.grey, c.accent, open))
        }
    }

    /** One pill, two equal halves with fixed labels; a half turns accent while on, and fills grey and flips its caret while open. */
    private fun drawCapsule(canvas: Canvas) {
        val c = colors
        val style = rects.getValue(Key.STYLE)
        val translate = rects.getValue(Key.TRANSLATE)
        // Ring and edge included, the capsule takes exactly the row's height, in line with the keys beside it.
        val ring = HAIRLINE * s / 2
        tmp.set(style.left + ring, style.top + ring, translate.right - ring, style.bottom - s)
        val radius = tmp.height() / 2
        fill.color = c.capsuleEdge
        tmp.offset(0f, s)
        canvas.drawRoundRect(tmp, radius, radius, fill)
        tmp.offset(0f, -s)
        fill.color = c.capsule
        canvas.drawRoundRect(tmp, radius, radius, fill)
        line.color = c.capsuleRing
        line.strokeWidth = HAIRLINE * s
        canvas.drawRoundRect(tmp, radius, radius, line)
        drawHalf(canvas, Key.STYLE, styleLabel, model.styleOn, styleOpen.value())
        drawHalf(canvas, Key.TRANSLATE, translateLabel, model.translateOn, translateOpen.value())
        fill.color = c.divider
        canvas.drawRect(style.right - s / 2, style.centerY() - DIVIDER * s / 2, style.right + s / 2, style.centerY() + DIVIDER * s / 2, fill)
    }

    private fun drawHalf(canvas: Canvas, key: Key, label: String, on: Boolean, open: Float) {
        val c = colors
        val r = rects.getValue(key)
        // Pressed or open, one grey fill, inset 3 dp at the outer end, 2 dp at the divider; pressed it is the same fill,
        // never a second plate over it (Material's state layer is the
        // container's own shape, the strongest state shown, not stacked).
        val shown = max(open, presses[key]?.tint() ?: 0f)
        if (shown > 0f) {
            val outer = if (key == Key.STYLE) r.left + OPEN_INSET * s else r.right - OPEN_INSET * s
            val inner = if (key == Key.STYLE) r.right - OPEN_INNER * s else r.left + OPEN_INNER * s
            tmp.set(min(outer, inner), r.top + OPEN_INSET * s, max(outer, inner), r.bottom - OPEN_INSET * s)
            fill.color = Palette.alpha(c.openBg, shown)
            canvas.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill)
        }
        // Blue only says it is on; open, the half only fills grey, never blue.
        val accent = if (on) 1f else 0f
        strongText.textSize = HALF_TEXT * s
        strongText.color = ColorUtils.blendARGB(c.ink, c.accent, accent)
        val textW = strongText.measureText(label)
        val caret = CARET * s
        val left = r.centerX() - (textW + HALF_GAP * s + caret) / 2
        canvas.drawText(label, left, baseline(strongText, r.centerY()), strongText)
        val cx = left + textW + HALF_GAP * s + caret / 2
        canvas.save()
        canvas.rotate(180f * open, cx, r.centerY())
        drawIcon(canvas, "caret-down", CARET, cx, r.centerY(), ColorUtils.blendARGB(c.caret, c.accent, accent))
        canvas.restore()
    }

    /** ↑ only sends; held, it turns Auto-send on or off and a check badge pops. */
    private fun drawSend(canvas: Canvas) {
        val c = colors
        val r = rects.getValue(Key.SEND)
        // Three levels: off, grey; this dictation only (one tap), light blue with a blue arrow; Auto-send on (every
        // dictation), the solid blue key with a white arrow and the check badge.
        val on = sendOn.value()
        val once = sendOnceOn.value()
        pressed(canvas, Key.SEND, r) {
            fill.color = ColorUtils.blendARGB(ColorUtils.blendARGB(c.tonal, c.accentBg, once), c.go, on)
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
            tint(canvas, Key.SEND, r, r.height() / 2)
            drawIcon(canvas, "arrow-up", ICON_SEND, r.centerX(), r.centerY(), ColorUtils.blendARGB(ColorUtils.blendARGB(c.ink, c.accent, once), Color.WHITE, on))
        }
        val badge = badgeScale()
        if (badge > 0f) {
            val bx = r.right - (BADGE_SEND_RIGHT + BADGE_SEND / 2) * s
            val by = r.top + (BADGE_SEND / 2 - BADGE_SEND_UP) * s
            canvas.save()
            canvas.scale(badge, badge, bx, by)
            fill.color = c.badgeRing
            canvas.drawCircle(bx, by, (BADGE_SEND / 2 + BADGE_RING) * s, fill)
            // On the solid blue key the badge turns white with a blue check, so it does not melt into the key.
            fill.color = ColorUtils.blendARGB(c.accent, Color.WHITE, on)
            canvas.drawCircle(bx, by, BADGE_SEND / 2 * s, fill)
            // A hairline on its own edge, the keys' edge colour: by day the white badge keeps its size on the white panel.
            line.color = c.keyEdge
            line.strokeWidth = HAIRLINE * s
            canvas.drawCircle(bx, by, BADGE_SEND / 2 * s, line)
            drawIcon(canvas, "check", BADGE_CHECK, bx, by, ColorUtils.blendARGB(Color.WHITE, c.go, on))
            canvas.restore()
        }
    }

    /** The check badge: 0 → 1.18 → 1 when Auto-send turns on, 1 → 0 when it turns off. */
    private fun badgeScale(): Float {
        if (snap()) return if (model.autoSend) 1f else 0f
        val t = (now() - badgeAt).toFloat()
        return if (model.autoSend) {
            val q = EMPHASIZED_DECELERATE.getInterpolation(((t - BADGE_DELAY_MS) / BADGE_IN_MS).coerceIn(0f, 1f))
            if (q < BADGE_PEAK_AT) BADGE_PEAK * q / BADGE_PEAK_AT else BADGE_PEAK - (BADGE_PEAK - 1f) * (q - BADGE_PEAK_AT) / (1f - BADGE_PEAK_AT)
        } else {
            1f - STANDARD_ACCELERATE.getInterpolation((t / BADGE_OUT_MS).coerceIn(0f, 1f))
        }
    }

    /** The clipboard key stays; then one accent-tint pill: a thumb or "Paste · …", and × on its right, which dismisses it. */
    private fun drawPasteRow(canvas: Canvas) {
        val c = colors
        drawTonal(canvas, Key.CLIPBOARD, "clipboard", clipboardOpen.value())
        val pill = pastePill
        pressed(canvas, Key.PASTE, pill) {
            fill.color = c.accentBg
            canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, fill)
            tint(canvas, Key.PASTE, pill, pill.height() / 2)
            var left = pill.left
            val image = shownImage
            if (image != null) {
                left += CHIP_PAD_THUMB * s
                imageRect.set(left, pill.centerY() - THUMB * s / 2, left + THUMB * s, pill.centerY() + THUMB * s / 2)
                // A square from the top of the image, the part of a screenshot that tells which screen it is.
                val crop = min(image.width, image.height)
                imageCrop.set((image.width - crop) / 2, 0, (image.width + crop) / 2, crop)
                clip.rewind()
                clip.addRoundRect(imageRect, THUMB_RADIUS * s, THUMB_RADIUS * s, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(clip)
                canvas.drawBitmap(image, imageCrop, imageRect, imagePaint)
                canvas.restore()
                line.color = c.keyEdge
                line.strokeWidth = HAIRLINE * s
                canvas.drawRoundRect(imageRect, THUMB_RADIUS * s, THUMB_RADIUS * s, line)
                left = imageRect.right + CHIP_GAP * s
            } else {
                left += CHIP_PAD * s
            }
            strongText.textSize = PASTE_TEXT * s
            strongText.color = c.accent
            canvas.drawText(pasteLabel, 0, pasteLabel.length, left, baseline(strongText, pill.centerY()), strongText)
            val closeX = pill.right - (CHIP_PAD - CLOSE_PULL + CLOSE_BOX / 2) * s
            drawIcon(canvas, "x", ICON_CLOSE, closeX, pill.centerY(), c.accent)
        }
    }

    /** Rows 2 and 3: the bin, the status line and Backspace; Settings, the mic trio and Enter. */
    private fun drawMiddleRows(canvas: Canvas) {
        val c = colors
        drawRaised(canvas, Key.CLEAR, "trash", if (model.clearAsked) c.red else c.grey)
        drawStatus(canvas)
        drawRaised(canvas, Key.BACKSPACE, "backspace", c.ink)
        drawRaised(canvas, Key.SETTINGS, "gear-six", c.grey)
        drawRaised(canvas, Key.ENTER, enterIcon(), c.ink)
        fill.color = c.trio
        canvas.drawRoundRect(trio, trio.height() / 2, trio.height() / 2, fill)
        line.color = c.trioRing
        line.strokeWidth = HAIRLINE * s
        tmp.set(trio)
        tmp.inset(s / 2, s / 2)
        canvas.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, line)
        drawPauseSlot(canvas)
        val cancel = ColorUtils.blendARGB(if (model.micEnabled && !model.micBlocked) c.off else c.dim, c.red, cancelLive.value())
        drawRaised(canvas, Key.CANCEL, "x", cancel, ICON_TRIO)
        drawMic(canvas)
    }

    /** Pause; ▶ on a light accent disc while paused (the bars become the triangle); Retry after a failure. */
    private fun drawPauseSlot(canvas: Canvas) {
        val c = colors
        val retry = retryShown.value()
        if (retry < 1f) layer(canvas, rects.getValue(Key.PAUSE), 1f - retry) {
            val r = rects.getValue(Key.PAUSE)
            val processing = model.state == Dictation.State.PROCESSING || !model.micEnabled || model.micBlocked
            val color = ColorUtils.blendARGB(if (processing) c.dim else c.off, c.ink, pauseLive.value())
            pressed(canvas, Key.PAUSE, r) {
                raised(canvas, r, r.height() / 2, c.key)
                val disc = playDisc.value()
                if (disc > 0f) {
                    fill.color = c.playBg
                    canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2 * disc, fill)
                }
                tint(canvas, Key.PAUSE, r, r.height() / 2)
                drawPauseGlyph(canvas, r.centerX(), r.centerY(), playing.value(), color)
            }
        }
        if (retry > 0f) {
            val r = rects.getValue(Key.RETRY)
            val k = RETRY_FROM + (1f - RETRY_FROM) * retry
            layer(canvas, r, retry) {
                canvas.save()
                canvas.scale(k, k, r.centerX(), r.centerY())
                pressed(canvas, Key.RETRY, r) {
                    retryPaint.color = c.retry
                    retryPaint.setShadowLayer(RETRY_SHADOW_BLUR * s, 0f, RETRY_SHADOW_DY * s, c.retryShadow)
                    canvas.drawOval(r, retryPaint)
                    tint(canvas, Key.RETRY, r, r.height() / 2)
                    drawIcon(canvas, "arrow-counter-clockwise", ICON_TRIO, r.centerX(), r.centerY(), Color.WHITE)
                }
                canvas.restore()
            }
            // One ring at Retry as it comes, never a shake.
            val t = (now() - retryAt - RETRY_RING_FROM_MS).toFloat() / RETRY_RING_MS
            if (!snap() && t in 0f..1f) {
                val q = STANDARD.getInterpolation(t)
                line.color = Palette.alpha(c.accent, RETRY_RING_ALPHA * (1f - q))
                line.strokeWidth = RETRY_RING_STROKE * s
                val radius = (RETRY_RING_START + (RETRY_RING_END - RETRY_RING_START) * q) * s - line.strokeWidth / 2
                canvas.drawCircle(r.centerX(), r.centerY(), radius, line)
            }
        }
    }

    /**
     * Phosphor's filled pause bars, their corners moving onto the two halves of the play triangle as [p] goes 0 → 1,
     * the glyph shifting right to sit optically centred as ▶.
     */
    private fun drawPauseGlyph(canvas: Canvas, cx: Float, cy: Float, p: Float, color: Int) {
        val size = (ICON_TRIO - 2f) * s
        val k = size / ICON_BOX
        val left = cx - size / 2 + PLAY_SHIFT * s * p
        val top = cy - size / 2
        path.rewind()
        for (bar in 0..1) {
            for (corner in 0..3) {
                val i = bar * 8 + corner * 2
                val px = PAUSE_BARS[i] + (PLAY_HALVES[i] - PAUSE_BARS[i]) * p
                val py = PAUSE_BARS[i + 1] + (PLAY_HALVES[i + 1] - PAUSE_BARS[i + 1]) * p
                if (corner == 0) path.moveTo(left + px * k, top + py * k) else path.lineTo(left + px * k, top + py * k)
            }
            path.close()
        }
        fill.color = color
        line.color = color
        // Rounded like the Phosphor shapes: a stroke along the outline softens the corners.
        line.strokeWidth = PAUSE_ROUNDING * k
        canvas.drawPath(path, fill)
        canvas.drawPath(path, line)
    }

    /**
     * The mic ball: white-to-ice air, glossy water with a lighter back wave and a white surface line, the
     * glyph white below the waterline and blue above. It never changes shape or glyph; only the water moves.
     */
    private fun drawMic(canvas: Canvas) {
        val c = colors.mic
        val size = BALL * s
        val r = size / 2
        val left = micX - r
        val top = micY - r
        val dim = !model.micEnabled || model.micBlocked
        // Processing: the ball greys and stops listening, so it reads as busy, not as still taking the voice.
        val grey = if (dim) 0f else busy.value()
        if (dim) {
            // Off: grey and lighter (darker at night), but opaque, so the key is always plainly there.
            if (dimFor != colors) {
                dimFor = colors
                val off = colors.micOff
                val matrix = ColorMatrix().apply { setSaturation(0f) }
                val k = 1f - DIM_LIFT
                matrix.postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            k, 0f, 0f, 0f, DIM_LIFT * Color.red(off),
                            0f, k, 0f, 0f, DIM_LIFT * Color.green(off),
                            0f, 0f, k, 0f, DIM_LIFT * Color.blue(off),
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
                dimPaint.colorFilter = ColorMatrixColorFilter(matrix)
            }
            canvas.saveLayer(left - r, top - r, left + size + r, top + size + r, dimPaint)
        } else if (grey > 0f) {
            // Greyed but opaque: the key never turns see-through.
            if (grey != busyShown) {
                busyShown = grey
                busyMatrix.setSaturation(lerp(1f, BUSY_SATURATION, grey))
                busyPaint.colorFilter = ColorMatrixColorFilter(busyMatrix)
            }
            canvas.saveLayer(left - r, top - r, left + size + r, top + size + r, busyPaint)
        }
        canvas.save()
        // Pressed, the ball goes in a little: a hair smaller and lower, its halo and white rim
        // fainter, and a crisp shadow under its top edge; never a shade over the whole ball.
        val depth = micDepth()
        canvas.translate(0f, MIC_PRESS_DROP * s * depth)
        val k = 1f - (1f - MIC_PRESS_SCALE) * depth
        canvas.scale(k, k, micX, micY)
        if (shadow.maskFilter == null || shadowSize != s) {
            shadowSize = s
            shadow.maskFilter = BlurMaskFilter(blurRadius(BALL * MIC_SHADOW_BLUR * s), BlurMaskFilter.Blur.NORMAL)
        }
        shadow.color = Palette.alpha(c.shadow, 1f - MIC_PRESS_HALO * depth)
        canvas.drawCircle(micX, micY + size * MIC_SHADOW_DY, r, shadow)
        canvas.save()
        clip.rewind()
        clip.addCircle(micX, micY, r, Path.Direction.CW)
        canvas.clipPath(clip)
        if (air.shader == null || airFor != c || airTop != top || airSize != size) {
            airFor = c
            airTop = top
            airSize = size
            air.shader = LinearGradient(0f, top, 0f, top + size, c.air0, c.air1, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(left, top, left + size, top + size, air)
        val surface = top + size * (1f - fillNow)
        // Each wave is two standing swells of their own length and pace: they never fold flat together.
        front.set(crestNow * s * DRIFT_SHARE, size * FRONT_WAVELENGTH, frontPhase)
        back.set(backCrestNow * s * DRIFT_SHARE, size * BACK_WAVELENGTH, backPhase)
        wave(left, surface - size * BACK_RISE - backRiseNow * size, back, size, top + size)
        fill.color = c.back
        canvas.drawPath(path, fill)
        wave(left, surface, front, size, top + size)
        // The water's gradient is fixed to the ball, not to the surface: rising water shows more of it, never darker water.
        waterMatrix.setScale(1f, size * (1f - WATER_FROM))
        waterMatrix.postTranslate(0f, top + size * WATER_FROM)
        waterShader.setLocalMatrix(waterMatrix)
        water.shader = waterShader
        canvas.drawPath(path, water)
        frontPath.set(path)
        // The surface line: the front wave's top edge, whiter with the voice.
        surfaceLine(left, surface, front, size)
        line.color = Palette.alpha(Color.WHITE, lerp(SURFACE_ALPHA, 1f, voiceNow))
        line.strokeWidth = max(SURFACE_MIN, SURFACE_WIDTH * BALL / SURFACE_REFERENCE) * s
        canvas.drawPath(path, line)
        fill.color = c.above
        canvas.drawPath(glyphPath, fill)
        canvas.save()
        canvas.clipPath(frontPath)
        fill.color = Color.WHITE
        canvas.drawPath(glyphPath, fill)
        canvas.restore()
        if (depth > 0f) {
            // Over the edge shadow, the ball darkens as a whole: a dark-blue shade, day and night.
            fill.color = Palette.alpha(MIC_PRESS_SHADE, (if (colors.night) MIC_PRESS_SHADE_NIGHT_ALPHA else MIC_PRESS_SHADE_ALPHA) * depth)
            canvas.drawCircle(micX, micY, r, fill)
            edgeRect.set(micX - r, micY - r, micX + r, micY + r)
            pressEdge(canvas, edgeRect, r, MIC_EDGE * depth, MIC_EDGE_ALPHA * depth)
        }
        line.color = Palette.alpha(c.rim, 1f - MIC_PRESS_RIM * depth)
        line.strokeWidth = MIC_RIM * s
        canvas.drawCircle(micX, micY, r, line)
        canvas.restore()
        line.color = c.ring
        line.strokeWidth = HAIRLINE * s
        canvas.drawCircle(micX, micY, r, line)
        canvas.restore()
        if (dim || grey > 0f) canvas.restore()
        drawRecMark(canvas)
        drawLanded(canvas)
    }

    /**
     * Recording, a soft ring sits a little off the ball: a 4.5 dp stroke blurred by 3 dp at 75 %, over a 12 dp glow
     * at 65 %, between a halo and a ring: it adds a shape, not only a hue, so it reads in sun and in greys. Processing, the ring
     * closes to a 90° arc on a faint track, the only spinner (the status line is text), the same blue while it waits for the network.
     * Outside the busy layer, so the arc is never greyed.
     */
    private fun drawRecMark(canvas: Canvas) {
        val shown = recMark.value()
        if (shown <= 0f || now() < markFrom) return
        val c = colors
        val color = c.rec
        val alpha = shown
        val core = REC_WIDTH * s
        val radius = (BALL / 2 + REC_GAP + REC_CORE / 2) * s
        tmp.set(micX - radius, micY - radius, micX + radius, micY + radius)
        if (recGlowSize != s) {
            recGlowSize = s
            recGlow.maskFilter = BlurMaskFilter(REC_GLOW * s, BlurMaskFilter.Blur.NORMAL)
            recCore.maskFilter = BlurMaskFilter(REC_CORE_BLUR * s, BlurMaskFilter.Blur.NORMAL)
        }
        // Processing: the ring is the arc at once, with its track; with Remove animations, a still arc at 12 o'clock.
        val busyNow = busy.value()
        val sweep = lerp(360f, BUSY_ARC, busyNow)
        val start = if (busyNow <= 0f || snap()) -90f - sweep / 2 + BUSY_ARC / 2 else (now() % SPIN_MS) / SPIN_MS.toFloat() * 360f - 90f
        if (busyNow > 0f) {
            recCore.strokeWidth = core
            recCore.color = Palette.alpha(color, BUSY_TRACK * busyNow * alpha)
            canvas.drawOval(tmp, recCore)
        }
        recGlow.strokeWidth = core
        recGlow.color = Palette.alpha(color, REC_GLOW_ALPHA * alpha)
        recCore.strokeWidth = core
        recCore.color = Palette.alpha(color, REC_CORE_ALPHA * alpha)
        if (sweep >= 359.9f) {
            canvas.drawOval(tmp, recGlow)
            canvas.drawOval(tmp, recCore)
        } else {
            // Its ends fade in, as soft as its blurred sides.
            val shader = arcFade.shader(micX, micY, start, sweep, color)
            recGlow.shader = shader
            recCore.shader = shader
            canvas.drawArc(tmp, start, sweep, false, recGlow)
            canvas.drawArc(tmp, start, sweep, false, recCore)
            recGlow.shader = null
            recCore.shader = null
        }
    }

    /** When the text lands, one light ring leaves the ball. */
    private fun drawLanded(canvas: Canvas) {
        if (snap()) return
        val t = (now() - landedAt).toFloat() / LANDED_MS
        if (t !in 0f..1f) return
        val q = STANDARD.getInterpolation(t)
        line.color = Palette.alpha(LANDED_RING, LANDED_ALPHA * (1f - q))
        line.strokeWidth = (LANDED_STROKE_START + (LANDED_STROKE_END - LANDED_STROKE_START) * q) * s
        canvas.drawCircle(micX, micY, (LANDED_START + (LANDED_END - LANDED_START) * q) * s, line)
    }

    /**
     * A water surface: one long smooth wave of [crest] height and [wavelength], at [phase] in its slow slide (Drift:
     * the front slides one way, the back the other, calm as a tank).
     */
    private class Surface {
        private var crest = 0f
        private var wavelength = 1f
        private var phase = 0f

        fun set(crest: Float, wavelength: Float, phase: Float) {
            this.crest = crest
            this.wavelength = wavelength
            this.phase = phase
        }

        fun at(x: Float): Float = crest * sin(x / wavelength * TWO_PI + phase)
    }

    private val front = Surface()
    private val back = Surface()

    /** A wave across the ball into [path], closed down to [bottom]. */
    private fun wave(left: Float, top: Float, wave: Surface, size: Float, bottom: Float) {
        path.rewind()
        path.moveTo(left, bottom)
        var x = 0f
        val step = size / WAVE_STEPS
        while (x <= size + step / 2) {
            path.lineTo(left + x, top + wave.at(x))
            x += step
        }
        path.lineTo(left + size, bottom)
        path.close()
    }

    private fun surfaceLine(left: Float, top: Float, wave: Surface, size: Float) {
        path.rewind()
        var x = 0f
        val step = size / WAVE_STEPS
        while (x <= size + step / 2) {
            val y = top + wave.at(x)
            if (x == 0f) path.moveTo(left, y) else path.lineTo(left + x, y)
            x += step
        }
    }

    /** Row 4: Vocabulary, the space bar, the keyboard switch. */
    private fun drawBottomRow(canvas: Canvas) {
        val c = colors
        drawRaised(canvas, Key.VOCABULARY, "book-open-text", c.grey)
        val space = rects.getValue(Key.SPACE)
        pressed(canvas, Key.SPACE, space) {
            raised(canvas, space, space.height() / 2, c.key)
            tint(canvas, Key.SPACE, space, space.height() / 2)
            hintText.textSize = SPACE_TEXT * s
            hintText.color = c.hint
            hintText.textAlign = Paint.Align.CENTER
            canvas.drawText(spaceLabel, space.centerX(), baseline(hintText, space.centerY()), hintText)
            hintText.textAlign = Paint.Align.LEFT
        }
        drawRaised(canvas, Key.SWITCH, "keyboard", c.ink)
    }

    private fun drawRaised(canvas: Canvas, key: Key, icon: String, color: Int, iconSize: Float = ICON_KEY) {
        val r = rects.getValue(key)
        pressed(canvas, key, r) {
            raised(canvas, r, r.height() / 2, colors.key)
            tint(canvas, key, r, r.height() / 2)
            drawIcon(canvas, icon, iconSize, r.centerX(), r.centerY(), color)
        }
    }

    /** A raised key: a 1 dp ring around it, its edge 1 dp below, and at night a faint light rim along its top. */
    private fun raised(canvas: Canvas, r: RectF, radius: Float, color: Int) {
        val c = colors
        fill.color = c.keyRing
        tmp2.set(r)
        tmp2.inset(-s, -s)
        canvas.drawRoundRect(tmp2, radius + s, radius + s, fill)
        // Pressed, the edge folds away under the sinking face.
        fill.color = c.keyEdge
        tmp2.set(r)
        tmp2.offset(0f, s * (1f - sinkNow))
        canvas.drawRoundRect(tmp2, radius, radius, fill)
        fill.color = color
        canvas.drawRoundRect(r, radius, radius, fill)
        if (c.keyRim != 0) {
            canvas.save()
            clip.rewind()
            clip.addRoundRect(r, radius, radius, Path.Direction.CW)
            canvas.clipPath(clip)
            fill.color = c.keyRim
            canvas.drawRoundRect(r, radius, radius, fill)
            fill.color = color
            tmp2.set(r)
            tmp2.offset(0f, s)
            canvas.drawRoundRect(tmp2, radius, radius, fill)
            canvas.restore()
        }
    }

    // The status line

    private enum class Look { TEXT, LIVE, CHIP }

    /** [dots]: Listening, with three dots that come one by one and fade together, slowly, while it listens. */
    private data class StatusLook(val look: Look, val text: String, val kind: StatusKind = StatusKind.ACTIVE, val dots: Boolean = false)

    /** What the status line says, for TalkBack's announcement: without the live words. */
    private fun spoken(m: Model): String = when {
        m.insertHere -> context.getString(R.string.insert_here)
        m.continueKept != null -> m.continueKept
        else -> m.status
    }

    private fun lookOf(m: Model): StatusLook = when {
        m.insertHere -> StatusLook(Look.CHIP, context.getString(R.string.insert_here))
        m.continueKept != null -> StatusLook(Look.CHIP, m.continueKept)
        m.state == Dictation.State.IDLE && m.statusKind == StatusKind.ACTION ->
            StatusLook(Look.CHIP, m.status, m.statusKind)
        m.state == Dictation.State.RECORDING && m.liveWords.isNotBlank() ->
            StatusLook(Look.LIVE, m.liveWords.trim().replace(SPACES, " "))
        m.state == Dictation.State.RECORDING -> StatusLook(Look.TEXT, m.status, m.statusKind, dots = true)
        else -> StatusLook(Look.TEXT, m.status, m.statusKind)
    }

    private var current = StatusLook(Look.TEXT, "", StatusKind.HINT)
    private var previous: StatusLook? = null
    private var statusAt = 0L
    private var liveShift = 0f
    private var liveAt = 0L

    private fun liveSlide(): Float = if (snap()) 1f else STANDARD.getInterpolation(((now() - liveAt).toFloat() / LIVE_SLIDE_MS).coerceIn(0f, 1f))

    /** One item at a time; a change cross-fades: the old one out and up, the new one in from below. */
    private fun drawStatus(canvas: Canvas) {
        val r = rects.getValue(Key.STATUS)
        val p = if (snap()) 1f else ((now() - statusAt).toFloat() / STATUS_FADE_MS).coerceIn(0f, 1f)
        val old = previous?.takeIf { p < 1f }
        if (old != null) layer(canvas, r, (1f - 2 * p).coerceAtLeast(0f), -STATUS_SHIFT * s * p) { drawLook(canvas, old, r) }
        if (old == null) {
            drawLook(canvas, current, r)
        } else {
            layer(canvas, r, ((p - STATUS_IN_FROM) / (1f - STATUS_IN_FROM)).coerceIn(0f, 1f), STATUS_SHIFT * s * (1f - p)) { drawLook(canvas, current, r) }
        }
    }

    private fun drawLook(canvas: Canvas, look: StatusLook, r: RectF) {
        val c = colors
        when (look.look) {
            Look.CHIP -> {
                strongText.textSize = CHIP_TEXT * s
                val shown = TextUtils.ellipsize(look.text, strongText, CHIP_MAX_WIDTH * s - 2 * CHIP_PAD_STATUS * s, TextUtils.TruncateAt.END)
                val w = strongText.measureText(shown, 0, shown.length) + 2 * CHIP_PAD_STATUS * s
                tmp.set(r.centerX() - w / 2, r.centerY() - CHIP_HEIGHT * s / 2, r.centerX() + w / 2, r.centerY() + CHIP_HEIGHT * s / 2)
                chip.set(tmp)
                // A chip is always something to tap, so always the accent; a state is plain text.
                pressed(canvas, Key.STATUS, chip) {
                    fill.color = c.accentBg
                    canvas.drawRoundRect(chip, chip.height() / 2, chip.height() / 2, fill)
                    tint(canvas, Key.STATUS, chip, chip.height() / 2)
                    strongText.color = c.accentInk
                    canvas.drawText(shown, 0, shown.length, chip.left + CHIP_PAD_STATUS * s, baseline(strongText, chip.centerY()), strongText)
                }
            }
            Look.LIVE -> drawLive(canvas, look.text, r)
            Look.TEXT -> {
                val paint = if (look.kind == StatusKind.HINT) hintText else mediumText
                paint.textSize = STATUS_TEXT * s
                paint.color = when (look.kind) {
                    StatusKind.HINT, StatusKind.NOTE -> c.hint
                    StatusKind.ACTIVE -> c.ink
                    StatusKind.ERROR -> c.red
                    StatusKind.ACTION -> c.accent
                }
                // One line whenever it fits in the status's narrower text column ("Server busy", "Stopped"); a longer
                // one wraps to two lines in that column rather than running across the panel.
                val column = min(r.width(), STATUS_LINE_MAX * s)
                val fits = paint.measureText(look.text) <= column
                val split = if (fits) -1 else look.text.indexOf(EM_DASH)
                val dots = if (look.dots) paint.measureText(DOTS) else 0f
                if (look.dots && split < 0 && paint.measureText(look.text) + dots <= r.width()) {
                    // The dots' room is kept, so the word stands still while they come and go.
                    val left = r.centerX() - (paint.measureText(look.text) + dots) / 2
                    val y = baseline(paint, r.centerY())
                    canvas.drawText(look.text, left, y, paint)
                    val alpha = paint.alpha
                    val dot = paint.measureText(DOTS, 0, 1)
                    for (i in 0 until 3) {
                        paint.alpha = (alpha * dotShown(i)).toInt()
                        canvas.drawText(DOTS, i, i + 1, left + paint.measureText(look.text) + i * dot, y, paint)
                    }
                    paint.alpha = alpha
                } else if (fits) {
                    canvas.drawText(look.text, r.centerX() - paint.measureText(look.text) / 2, baseline(paint, r.centerY()), paint)
                } else {
                    // Two-line outcomes, 14 / 19, split at the em dash, which goes: the second line starts with its
                    // word, capitalised, never with a dash. Any other long line wraps, balanced.
                    val text = if (split < 0) look.text else look.text.substring(0, split).trimEnd() + "\n" + look.text.substring(split + 1).trim().replaceFirstChar { it.uppercase() }
                    val layout = twoLines.getOrPut("$text|${paint.color}|${paint === hintText}") {
                        if (twoLines.size > TWO_LINE_CACHE) twoLines.clear()
                        val small = TextPaint(paint).apply { textSize = TWO_LINE_TEXT * s }
                        // Narrow enough that a line too long for one goes to two balanced lines, never to one smaller line;
                        // never narrower than its longest word.
                        val longest = text.split(' ', '\n').maxOf { small.measureText(it) }
                        val width = if (split >= 0) column else min(column, max(small.measureText(text) * TWO_LINE_SHARE, longest))
                        StaticLayout.Builder.obtain(text, 0, text.length, small, ceil(width).toInt())
                            .setAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED)
                            .setMaxLines(2)
                            .setEllipsize(TextUtils.TruncateAt.END)
                            .setLineSpacing(TWO_LINE_HEIGHT * s - small.fontSpacing, 1f)
                            .setIncludePad(false)
                            .build()
                    }
                    canvas.save()
                    canvas.translate(r.centerX() - layout.width / 2f, r.centerY() - layout.height / 2f)
                    layout.draw(canvas)
                    canvas.restore()
                }
            }
        }
    }

    /** How much of Listening's [i]th dot shows: each comes on in turn, then the three fade out together. */
    private fun dotShown(i: Int): Float {
        if (snap()) return 1f
        val t = ((now() - statusAt) % DOTS_CYCLE_MS).toFloat()
        val on = STANDARD.getInterpolation(((t - i * DOTS_STEP_MS) / DOTS_FADE_MS).coerceIn(0f, 1f))
        val off = STANDARD.getInterpolation(((t - DOTS_OUT_AT_MS) / DOTS_OUT_MS).coerceIn(0f, 1f))
        return on * (1f - off)
    }

    /**
     * Live words run: one line across the status place, new words coming in through the soft right edge and older
     * ones going out through the soft left one, both edges alike.
     */
    private fun drawLive(canvas: Canvas, words: String, r: RectF) {
        mediumText.textSize = STATUS_TEXT * s
        mediumText.color = colors.live
        liveRect.set(r)
        val shown = words.split(' ').takeLast(LIVE_WORDS).joinToString(" ")
        val shift = liveShift * (1f - liveSlide())
        canvas.saveLayer(liveRect, null)
        canvas.clipRect(liveRect)
        val end = liveRect.right - LIVE_FADE / 6 * s
        canvas.drawText(shown, end - mediumText.measureText(shown) + shift, baseline(mediumText, r.centerY()), mediumText)
        if (fadePaint.shader == null || fadeLeft != liveRect.left || fadeRight != liveRect.right) {
            fadeLeft = liveRect.left
            fadeRight = liveRect.right
            val w = liveRect.width()
            fadePaint.shader = LinearGradient(
                liveRect.left, 0f, liveRect.right, 0f,
                intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, LIVE_FADE * s / w, 1f - LIVE_FADE * s / w, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(liveRect, fadePaint)
        canvas.restore()
    }

    // Drawing helpers

    private fun enterIcon(): String = when (model.enterAction) {
        EditorInfo.IME_ACTION_SEARCH -> "magnifying-glass"
        EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> "arrow-right"
        EditorInfo.IME_ACTION_DONE -> "check"
        EditorInfo.IME_ACTION_SEND -> "paper-plane-tilt"
        else -> "arrow-bend-down-left"
    }

    /** A Phosphor glyph centred on ([cx], [cy]), [size] in design dp. */
    private fun drawIcon(canvas: Canvas, name: String, size: Float, cx: Float, cy: Float, color: Int) {
        val px = size * s
        val icon = icons.getOrPut("$name@$px") { Icons.path(name, px) }
        fill.color = color
        canvas.save()
        canvas.translate(cx - px / 2, cy - px / 2)
        canvas.drawPath(icon, fill)
        canvas.restore()
    }

    /** Draws [draw] pressed: sunk by [PRESS_DROP] dp onto its folding edge, never smaller. */
    private inline fun pressed(canvas: Canvas, key: Key, r: RectF, draw: () -> Unit) {
        val p = presses[key]
        val k = p?.scale() ?: 1f
        // The top row's flat keys only darken, never go in.
        if (k == 1f || key in TOP_ROW) return draw()
        sinkNow = p?.sink() ?: 0f
        canvas.save()
        canvas.translate(0f, PRESS_DROP * s * sinkNow)
        draw()
        canvas.restore()
        sinkNow = 0f
    }

    /** How far the key being drawn is pressed down, for [raised]'s edge. */
    private var sinkNow = 0f

    /**
     * The press over a key's shape, as the mic's: a crisp shadow under its top edge and a shade that
     * darkens the whole key, so a press reads at a glance, by day and at night; × turns light red while it can
     * act.
     */
    private fun tint(canvas: Canvas, key: Key, r: RectF, radius: Float) {
        val t = presses[key]?.tint() ?: return
        if (t <= 0f) return
        val red = key == Key.CANCEL && cancels(model)
        fill.color = if (red) Palette.alpha(colors.redBg, t) else Palette.alpha(if (colors.night) PRESS_SHADE_NIGHT else PRESS_SHADE_DAY, t * if (colors.night) PRESS_SHADE_NIGHT_ALPHA else PRESS_SHADE_DAY_ALPHA)
        canvas.drawRoundRect(r, radius, radius, fill)
        if (key in TOP_ROW) return
        edgeRect.set(r)
        pressEdge(canvas, edgeRect, radius, PRESS_EDGE * t, PRESS_EDGE_ALPHA * t)
    }

    /** A crisp shadow inside [r] under its top edge: the shape less itself moved down by [drop] dp, lightly blurred. */
    private fun pressEdge(canvas: Canvas, r: RectF, radius: Float, drop: Float, alpha: Float) {
        if (alpha <= 0f) return
        if (edgePaint.maskFilter == null || edgeBlur != s) {
            edgeBlur = s
            edgePaint.maskFilter = BlurMaskFilter(blurRadius(PRESS_EDGE_BLUR * s), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        edgeClip.rewind()
        edgeClip.addRoundRect(r, radius, radius, Path.Direction.CW)
        canvas.clipPath(edgeClip)
        edgePath.rewind()
        edgePath.addRect(r.left - r.width(), r.top - r.height(), r.right + r.width(), r.bottom + r.height(), Path.Direction.CW)
        val top = r.top
        r.offset(0f, drop * s)
        edgePath.addRoundRect(r, radius, radius, Path.Direction.CCW)
        r.offset(0f, top - r.top)
        edgePaint.color = Palette.alpha(if (colors.night) PRESS_EDGE_NIGHT else PRESS_EDGE_DAY, alpha)
        canvas.drawPath(edgePath, edgePaint)
        canvas.restore()
    }

    /** Draws [draw] at [alpha], moved down by [dy]; a layer only while it is see-through. */
    private inline fun layer(canvas: Canvas, r: RectF, alpha: Float, dy: Float = 0f, draw: () -> Unit) =
        layer(canvas, r.left - r.width(), r.top - r.height(), r.right + r.width(), r.bottom + r.height(), alpha, dy, draw)

    private inline fun layer(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, alpha: Float, dy: Float, draw: () -> Unit) {
        if (alpha <= 0f) return
        if (alpha >= 1f && dy == 0f) return draw()
        layerPaint.alpha = (alpha * 255).toInt()
        canvas.saveLayer(left, top - abs(dy), right, bottom + abs(dy), layerPaint)
        canvas.translate(0f, dy)
        draw()
        canvas.restore()
    }

    private fun baseline(paint: Paint, centerY: Float): Float = centerY - (paint.descent() + paint.ascent()) / 2

    private fun textPaint(weight: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).also { Fonts.weight(context, it, weight) }

    /** BlurMaskFilter's radius for a Gaussian of this standard deviation, as Skia converts them. */
    private fun blurRadius(sigma: Float) = max(0.5f, (sigma - 0.5f) / 0.57735f)

    // Animation

    private fun now(): Long = frameTimeMs ?: SystemClock.uptimeMillis()

    private fun reduceMotion(): Boolean = !ValueAnimator.areAnimatorsEnabled()

    /** Screenshots and Remove animations show every transition at its end. */
    private fun snap(): Boolean = frameTimeMs != null || reduceMotion()

    /** A value easing to its target over a time, from wherever it was when the target changed. */
    private inner class Tween(private var ms: Long, private var curve: Interpolator = STANDARD, private val delay: Long = 0L) {
        private var from = 0f
        private var target = 0f
        private var at = 0L

        fun to(value: Float, duration: Long = ms, easing: Interpolator = curve) {
            if (value == target) return
            from = value()
            target = value
            ms = duration
            curve = easing
            at = now()
        }

        fun value(): Float {
            if (snap()) return target
            val p = ((now() - at - delay).toFloat() / ms).coerceIn(0f, 1f)
            return from + (target - from) * curve.getInterpolation(p)
        }

        fun moving(): Boolean = !snap() && now() - at < ms + delay

        /** Straight to [value], no easing. */
        fun jump(value: Float) {
            from = value
            target = value
            at = 0L
        }
    }

    // Set with jump only: no easing.
    private val playing = Tween(1L)
    private val playDisc = Tween(1L)
    private val retryShown = Tween(1L)
    private val pauseLive = Tween(1L)
    private val cancelLive = Tween(1L)
    private val styleOpen = Tween(OPEN_MS)
    private val translateOpen = Tween(OPEN_MS)
    private val clipboardOpen = Tween(KEY_TINT_MS)
    private val emojiOpen = Tween(KEY_TINT_MS)
    private val sendOn = Tween(SEND_ON_MS)
    private val pasteRow = Tween(PASTE_IN_MS)
    private val busy = Tween(1L) // Set with jump only.
    private val sendOnceOn = Tween(SEND_ON_MS)
    private val recMark = Tween(1L)
    private val tweens = listOf(playing, playDisc, retryShown, pauseLive, cancelLive, styleOpen, translateOpen, clipboardOpen, emojiOpen, sendOn, sendOnceOn, pasteRow, busy, recMark)

    private var shownPaste: String? = null
    private var shownImage: Bitmap? = null
    private var retryAt = 0L
    private var landedAt = 0L
    private var badgeAt = 0L
    private var fadeLeft = Float.NaN
    private var fadeRight = Float.NaN
    private val liveRect = RectF()
    private var shadowSize = 0f
    private var airFor: KeyboardColors.Mic? = null
    private var airTop = 0f
    private var airSize = 0f

    /** Down, the key sinks to its target in 80 ms and stays down at least 100 ms; up, it springs back with no bounce (dampingRatio 0.92, stiffness 900). */
    private inner class Press(private val key: Key) {
        private var down = false
        private var at = 0L
        private var scaleFrom = 1f
        private var tintFrom = 0f

        // A quick tap: where its press began, so the press goes on in on its own curve until the release.
        private var downAt = 0L
        private var holdScale = 1f
        private var holdTint = 0f

        fun set(on: Boolean) {
            val t = now()
            // A quick tap still shows the whole press: the release waits until the key has been down MIN_HOLD_MS.
            val held = !on && down && t - at < MIN_HOLD_MS
            if (held) {
                downAt = at
                holdScale = scaleFrom
                holdTint = tintFrom
            }
            scaleFrom = if (held) pressTarget(key) else scale()
            tintFrom = if (held) 1f else tint()
            down = on
            at = if (held) at + MIN_HOLD_MS else t
        }

        // The trio's keys press and let go at once; a quick tap still shows the press for MIN_HOLD_MS.
        private val instant = key == Key.PAUSE || key == Key.RETRY || key == Key.CANCEL

        private fun held() = down || now() < at

        fun scale(): Float {
            val target = pressTarget(key)
            if (snap()) return if (down) target else 1f
            if (instant) return if (held()) target else 1f
            val t = (now() - at).toFloat()
            return if (down) {
                scaleFrom + (target - scaleFrom) * STANDARD.getInterpolation((t / PRESS_MS).coerceIn(0f, 1f))
            } else if (t < 0f) {
                // Let go early: it finishes pressing in on the down curve (PRESS_MS < MIN_HOLD_MS), no jump.
                holdScale + (target - holdScale) * STANDARD.getInterpolation(((now() - downAt) / PRESS_MS).coerceIn(0f, 1f))
            } else {
                1f + spring(scaleFrom - 1f, t)
            }
        }

        fun tint(): Float {
            if (snap()) return if (down) 1f else 0f
            if (instant) return if (held()) 1f else 0f
            val t = (now() - at).toFloat()
            if (!down && t < 0f) return holdTint + (1f - holdTint) * ((now() - downAt) / PRESS_MS).coerceIn(0f, 1f)
            return if (down) tintFrom + (1f - tintFrom) * (t / PRESS_MS).coerceIn(0f, 1f) else tintFrom * (1f - (t / TINT_OUT_MS).coerceIn(0f, 1f))
        }

        /** How far down the key is, 0 at rest to 1 fully pressed: the face's drop and the edge's fold follow it. */
        fun sink(): Float {
            val target = pressTarget(key)
            return if (target >= 1f) 0f else ((1f - scale()) / (1f - target)).coerceIn(0f, 1f)
        }

        fun moving(): Boolean = !snap() && if (instant) !down && now() < at else (now() - at).toFloat() < if (down) PRESS_MS else SPRING_MS.toFloat()
    }

    private val presses = EnumMap<Key, Press>(Key::class.java)

    private fun press(key: Key?, on: Boolean) {
        if (key == null || key == Key.MIC || key == Key.GRABBER) return
        if (key == Key.STATUS && current.look != Look.CHIP) return
        presses.getOrPut(key) { Press(key) }.set(on)
    }

    /** How far each key squeezes: round keys most, pills less the wider they are, the capsule halves not at all. */
    private fun pressTarget(key: Key): Float = when (key) {
        Key.STYLE, Key.TRANSLATE -> 1f
        Key.SPACE -> 0.98f
        Key.PASTE, Key.STATUS -> 0.96f
        Key.SEND, Key.PASTE_CLOSE -> 0.94f
        else -> 0.96f
    }

    /** A key-spring released from [x0] away from rest, [t] ms later: the underdamped closed form. */
    private fun spring(x0: Float, t: Float): Float {
        val w = sqrt(KEY_SPRING_STIFFNESS)
        val zeta = KEY_SPRING_DAMPING
        val wd = w * sqrt(1f - zeta * zeta)
        val sec = t / 1000f
        return exp(-zeta * w * sec) * (x0 * cos(wd * sec) + zeta * w * x0 / wd * sin(wd * sec))
    }

    // The mic's own press: 1 or MIC_PRESSED, at once; it only says how deep the press is (micDepth), the ball keeps
    // its size.
    private var micScale = 1f

    private var micDownAt = 0L
    /** The recording ring started from a touch shows from this time on. */
    private var markFrom = 0L

    // A quick tap still shows the whole press: it holds at least MIC_PRESS_HOLD_MS.
    private fun micTarget(): Float {
        val held = pressed == Key.MIC || now() - micDownAt < MIC_PRESS_HOLD_MS
        return if (held && model.micEnabled && model.state != Dictation.State.PROCESSING) MIC_PRESSED else 1f
    }

    /** How far the mic is pressed in, 0 at rest to 1 at [MIC_PRESSED]. */
    private fun micDepth(): Float = ((1f - micScale) / (1f - MIC_PRESSED)).coerceIn(0f, 1f)

    // Frames run through the hold too, so the release comes when it ends.
    private fun springSettled(): Boolean = micTarget() == micScale && now() - micDownAt >= MIC_PRESS_HOLD_MS

    private fun updateSpring() {
        micScale = micTarget()
    }

    // The water

    private var level = 0f
    // The voice above its noise floor: the water's height follows its swell, the waves its level; its colour stays.
    private val voice = VoiceEnvelope()
    private var swell = 0f
    private var voiceNow = 0f
    private var levelAt = 0L
    private val heardAt = LongArray(HEARD)
    private val heard = FloatArray(HEARD)
    private var heardIndex = 0
    private var quietSince = 0L
    // Where each standing wave is in its rise and fall: the crest is scaled by cos of it.
    private var frontPhase = 0f
    private var backPhase = BACK_PHASE
    private var fillNow = IDLE_FILL
    private var crestNow = IDLE_CREST
    private var backCrestNow = IDLE_CREST * BACK_CREST_SHARE
    private var backRiseNow = 0f
    private val waterFrom = floatArrayOf(IDLE_FILL, IDLE_CREST, IDLE_CREST * BACK_CREST_SHARE, 0f)
    private var waterAt = 0L
    private var waterMs = WATER_MS

    private fun updateWater() {
        val t = now()
        val recording = model.state == Dictation.State.RECORDING
        val input = if (recording) meter?.invoke() ?: 0f else 0f
        val dt = (t - levelAt).coerceIn(0L, MAX_LEVEL_STEP_MS).toFloat()
        levelAt = t
        // The noise floor off, then one pole each: a quick attack and a quick release (see VoiceEnvelope).
        voice.step(input, t, dt, instant = snap(), fixedFloor = frameTimeMs != null)
        level = voice.level
        swell = voice.swell
        voiceNow = if (recording) level else 0f
        heardIndex = (heardIndex + 1) % HEARD
        heardAt[heardIndex] = t
        heard[heardIndex] = level
        val lagged = if (snap()) level else heardAgo(t - BACK_LAG_MS)
        if (level < QUIET) {
            if (quietSince == 0L) quietSince = t
        } else {
            quietSince = 0L
        }
        val calm = if (snap() || quietSince == 0L) 0f else STANDARD.getInterpolation(((t - quietSince - CALM_AFTER_MS).toFloat() / CALM_MS).coerceIn(0f, 1f))
        val fill: Float
        val crest: Float
        val backCrest: Float
        val backRise: Float
        when (model.state) {
            // The voice lifts the water and its waves, loud higher than quiet; the pace stays its own. In a silence
            // the water settles back to its rest level in one slow move, never holding halfway and then dropping.
            Dictation.State.RECORDING -> {
                fill = QUIET_FILL + LISTEN_FILL_RANGE * swell
                crest = lerp((CREST_BASE + CREST_GAIN * level) * CREST_UNIT, CALM_CREST, calm)
                backCrest = lerp((CREST_BASE + BACK_CREST_GAIN * lagged) * CREST_UNIT, CALM_BACK_CREST, calm)
                backRise = lerp(BACK_FILL_GAIN * lagged, 0f, calm)
            }
            else -> {
                fill = when (model.state) {
                    Dictation.State.PAUSED -> PAUSED_FILL
                    else -> IDLE_FILL
                }
                crest = when (model.state) {
                    Dictation.State.PAUSED -> PAUSED_CREST
                    Dictation.State.PROCESSING -> BUSY_CREST
                    else -> IDLE_CREST
                }
                backCrest = crest * BACK_CREST_SHARE
                backRise = 0f
            }
        }
        val k = if (snap()) 1f else STANDARD.getInterpolation(((t - waterAt).toFloat() / waterMs).coerceIn(0f, 1f))
        fillNow = lerp(waterFrom[0], fill, k)
        crestNow = lerp(waterFrom[1], crest, k)
        backCrestNow = lerp(waterFrom[2], backCrest, k)
        backRiseNow = lerp(waterFrom[3], backRise, k)
        if (reduceMotion()) {
            // Remove animations: the water keeps its level, without waves.
            crestNow = 0f
            backCrestNow = 0f
        }
        if (frameTimeMs != null) {
            // The design's still frames: each wave at its own place.
            frontPhase = STILL_PHASE
            backPhase = STILL_PHASE + BACK_PHASE
        } else if (!reduceMotion() && model.state != Dictation.State.PROCESSING) {
            // A slow steady slide, the same loud or quiet; the voice only makes the waves higher. While the recording
            // is being processed the waves hold still where they are.
            val step = (t - phaseAt).coerceIn(0L, MAX_STEP_MS).toFloat()
            frontPhase = (frontPhase + TWO_PI / FRONT_SLIDE_MS * step) % TWO_PI
            backPhase = (backPhase - TWO_PI / BACK_SLIDE_MS * step) % TWO_PI
        }
        phaseAt = t
    }

    private var phaseAt = 0L

    /** The smoothed level as it was at [time]: the back wave follows the voice 160 ms late. */
    private fun heardAgo(time: Long): Float {
        for (i in 0 until HEARD) {
            val j = (heardIndex - i + HEARD) % HEARD
            if (heardAt[j] <= time) return heard[j]
        }
        return heard[(heardIndex + 1) % HEARD]
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Frames are drawn only while something moves: the water while recording, a spinner, a transition. */
    private fun shouldAnimate(): Boolean {
        if (snap()) return false
        val t = now()
        if (tweens.any { it.moving() } || presses.values.any { it.moving() } || !springSettled()) return true
        if (t - statusAt < STATUS_FADE_MS || t - liveAt < LIVE_SLIDE_MS || t - waterAt < waterMs) return true
        if (t - landedAt < LANDED_MS || t - retryAt < RETRY_RING_FROM_MS + RETRY_RING_MS) return true
        if (t - badgeAt < BADGE_DELAY_MS + BADGE_IN_MS || t < markFrom) return true
        if (panelOpen()) return false
        return model.state == Dictation.State.RECORDING || model.state == Dictation.State.PROCESSING
    }

    // Touch

    private val longPress = Runnable {
        when (pressed) {
            Key.SWITCH -> {
                longPressed = true
                actions.onSwitchLong()
            }
            Key.SEND -> {
                longPressed = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                actions.onAutoSendLong()
                invalidate()
            }
            // Held, the paste offer and the status line open the clipboard.
            Key.PASTE, Key.STATUS -> {
                longPressed = true
                actions.onClipboard()
            }
            Key.STYLE, Key.TRANSLATE -> slideIntoCard()
            else -> Unit
        }
    }

    /** Held, or dragged down off, Style or Translate: its card opens (if it is not open yet) and the finger slides on it. */
    private fun slideIntoCard() {
        val key = pressed ?: return
        if (sliding) return
        longPressed = true
        sliding = true
        removeCallbacks(longPress)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (model.open != key) click(key)
    }

    private val repeat = object : Runnable {
        override fun run() {
            if (pressed != Key.BACKSPACE) return
            actions.onBackspace(word = repeats >= WORD_AFTER)
            repeats++
            val interval = if (repeats > WORD_AFTER) WORD_INTERVAL_MS else max(MIN_INTERVAL_MS, (FIRST_INTERVAL_MS * Math.pow(ACCELERATION, repeats.toDouble())).toLong())
            postDelayed(this, interval)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downRawY = event.rawY
                downPointer = event.getPointerId(0)
                dragging = false
                // The strip reacts only to a drag down.
                onGrabber = grabArea.contains(event.x, event.y) && (panelAnimator?.isRunning != true || springing)
                caught = false
                if (onGrabber && springing) {
                    // Pulled again on its way home: the panel stops under the finger and follows it from there.
                    panelAnimator?.removeAllListeners()
                    panelAnimator?.cancel()
                    springing = false
                    caught = true
                    downRawY = event.rawY - unband(panel().translationY)
                }
                if (onGrabber) velocity = VelocityTracker.obtain().apply { addMovement(event) }
                // The top row's 48 dp touch targets reach a little over its edge: on the strip, the strip has it.
                val key = if (onGrabber) null else hit(event.x, event.y)
                if (onGrabber) invalidate()
                // An open card closes on a touch anywhere but its halves; the card itself lies over this view.
                if (isCard(model.open) && key != Key.STYLE && key != Key.TRANSLATE) {
                    actions.onCloseList()
                    invalidate()
                    return true
                }
                key ?: return true
                pressed = key
                repeats = 0
                longPressed = false
                cursorX = null
                press(key, true)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                when (key) {
                    // Capture starts on touch-down, and Pause and × go live in this frame; the recording ring shows a
                    // moment later, just after the press (REC_DELAY_MS). Opening the mic can hold the main
                    // thread for a moment, so all is set before it. A start that fails leaves the model idle, and they
                    // go back.
                    Key.MIC -> {
                        micDownAt = now()
                        startedOnDown = model.state == Dictation.State.IDLE && model.micEnabled
                        if (startedOnDown) {
                            markFrom = micDownAt + REC_DELAY_MS
                            recMark.jump(1f)
                            pauseLive.jump(1f)
                            cancelLive.jump(1f)
                            invalidate()
                            postInvalidateDelayed(REC_DELAY_MS)
                            // After this frame is drawn; a panel not on screen draws nothing, so it starts at once.
                            if (isAttachedToWindow) postOnAnimation { post(::startMic) } else startMic()
                        }
                    }
                    Key.BACKSPACE -> {
                        actions.onBackspace(word = false)
                        postDelayed(repeat, REPEAT_DELAY_MS)
                    }
                    // Held still, the space bar turns into the cursor's touchpad; slid at once, it does so too.
                    Key.SPACE -> postDelayed(cursorHold, LONG_PRESS_MS)
                    Key.SEND, Key.SWITCH, Key.PASTE, Key.STATUS -> postDelayed(longPress, LONG_PRESS_MS)
                    Key.STYLE, Key.TRANSLATE -> postDelayed(longPress, CARD_HOLD_MS)
                    else -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                // Dragged down off Style or Translate, the card opens under the finger.
                if ((pressed == Key.STYLE || pressed == Key.TRANSLATE) && !sliding) {
                    val dy = event.y - downY
                    if (dy > ViewConfiguration.get(context).scaledTouchSlop && dy > abs(event.x - downX)) slideIntoCard()
                }
                if (sliding) {
                    actions.onCardSlide(event.rawX, event.rawY)
                    return true
                }
                if (onGrabber && !dragging) {
                    // No key there to keep a tap for: the pull starts at the first move down.
                    if (event.y - downY > abs(event.x - downX)) {
                        dragging = true
                        invalidate()
                    }
                }
                if (dragging) {
                    // The panel holds back against the finger, ever more the further it goes; upward is held at 0.
                    pull = max(0f, event.rawY - downRawY)
                    panel().translationY = band(pull)
                    // Past the point where letting go hides it, a tick, and another back short of it.
                    val past = pull > height * HIDE_PULL
                    if (past != pastHide) {
                        pastHide = past
                        performHapticFeedback(
                            if (Build.VERSION.SDK_INT >= 34) {
                                if (past) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
                            } else {
                                HapticFeedbackConstants.CLOCK_TICK
                            },
                        )
                    }
                    return true
                }
                // Slid from the space bar, the finger moves the cursor through the text, as a touchpad.
                if (pressed == Key.SPACE) slideCursor(event)
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    endDrag(cancelled = false)
                    return true
                }
                if (sliding) {
                    // On a row, that row is picked; anywhere else the card closes (a quick setting).
                    sliding = false
                    release()
                    actions.onCardLift(event.rawX, event.rawY)
                    return true
                }
                endGrab()
                homeIfCaught()
                val key = pressed
                release()
                // Held past a long press, the mic is push-to-talk: lifting the finger, wherever it is, stops the recording it started.
                if (key == Key.MIC && startedOnDown && event.eventTime - event.downTime >= LONG_PRESS_MS) {
                    actions.onMicTap()
                } else if (key != null && rects[key]?.let { expanded(it).contains(event.x, event.y) } == true) {
                    click(key)
                }
            }
            // A second finger stops Backspace's repeat; the first finger lifted while it is down ends the press
            // with no tap, and the second finger's own lift then does nothing.
            MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(repeat)
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == downPointer) cancelTouch()
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    private fun cancelTouch() {
        if (sliding) {
            sliding = false
            actions.onCardLift(Float.NaN, Float.NaN)
        }
        if (dragging) endDrag(cancelled = true) else endGrab()
        homeIfCaught()
        release()
    }

    private val cursorHold = Runnable {
        if (pressed != Key.SPACE || cursorX != null) return@Runnable
        cursorX = downX
        cursorY = downY
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun slideCursor(event: MotionEvent) {
        if (cursorX == null) {
            if (hypot(event.x - downX, event.y - downY) <= ViewConfiguration.get(context).scaledTouchSlop) return
            cursorX = downX
            cursorY = downY
            invalidate()
        }
        val density = resources.displayMetrics.density
        val right = ((event.x - cursorX!!) / (CURSOR_STEP_DP * density)).toInt()
        val down = ((event.y - cursorY) / (CURSOR_LINE_DP * density)).toInt()
        if (right != 0 || down != 0) actions.onCursor(right, down)
        cursorX = cursorX!! + right * CURSOR_STEP_DP * density
        cursorY += down * CURSOR_LINE_DP * density
    }

    private fun release() {
        removeCallbacks(repeat)
        removeCallbacks(longPress)
        removeCallbacks(cursorHold)
        press(pressed, false)
        pressed = null
        invalidate()
    }

    private fun endGrab() {
        if (!onGrabber) return
        onGrabber = false
        velocity?.recycle()
        velocity = null
        invalidate()
    }

    // The keyboard hidden or gone mid-touch may never get the finger's UP or CANCEL: the bar goes out with it.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) return
        // Hidden after all: the check for a hide that did not happen would pull a keyboard shown again within it.
        removeCallbacks(hideCheck)
        if (dragging) endDrag(cancelled = true) else endGrab()
    }

    /**
     * Where the panel sits for the finger's [pull] down: it moves with the first pixel, as UIScrollView's rubber band
     * (c = 0.55 over the view's length), PULL_GIVE of the pull at first, closing in on the panel's height, so it holds
     * back a little at once and more the further it goes.
     */
    private fun band(pull: Float): Float {
        if (height <= 0) return pull
        return height * (1f - 1f / (pull * PULL_GIVE / height + 1f))
    }

    /** The finger's pull for where the panel sits: [band] backwards, for a panel caught on its way home. */
    private fun unband(at: Float): Float =
        if (height <= 0) at else height / PULL_GIVE * (at / (height - at.coerceAtMost(height - 1f)))

    /**
     * The finger pulled past HIDE_PULL of the height, or flung so far as Material's bottom sheet would hide (BottomSheetBehavior.
     * shouldHide: where the panel would be HIDE_FRICTION s on at the finger's speed is past HIDE_THRESHOLD of the
     * height) once it has come at least FLICK_MIN_DP down, the keyboard hides at once, from where the finger left it;
     * else it springs back. So a short flick down on the strip does not hide it. Android slides the keyboard out and apps move their content down with it (Android 11+), so
     * the field comes down along with the keys.
     */
    private fun endDrag(cancelled: Boolean) {
        dragging = false
        val tracker = velocity
        tracker?.computeCurrentVelocity(1000)
        endGrab()
        val panel = panel()
        // Told by the finger's pull, not by the panel, which holds back.
        val down = pull
        pull = 0f
        pastHide = false
        val projected = down + (tracker?.yVelocity ?: 0f) * HIDE_FRICTION
        val flung = down >= FLICK_MIN_DP * resources.displayMetrics.density && projected > height * HIDE_THRESHOLD
        if (!cancelled && (down > height * HIDE_PULL || flung)) {
            caught = false
            panelAnimator?.cancel()
            hide(panel)
        } else {
            springBack(panel)
        }
    }

    private fun hide(panel: View) {
        DebugLog.add("keyboard: hidden by the grabber, pulled ${(panel.translationY / resources.displayMetrics.density).toInt()} dp")
        actions.onHide()
        // The service puts the panel back when the window shows again; if it did not hide, the keys come back here.
        postDelayed(hideCheck, HIDE_CHECK_MS)
    }

    private val hideCheck = Runnable { panel().takeIf { it.translationY != 0f && isShown }?.let(::springBack) }

    /** Caught on its way home and let go without a drag: it goes on home. */
    private fun homeIfCaught() {
        if (!caught) return
        caught = false
        springBack(panel())
    }

    private fun springBack(panel: View) {
        panelAnimator?.cancel()
        val from = panel.translationY
        if (reduceMotion() || from == 0f) {
            panel.translationY = 0f
            return
        }
        panelAnimator = ValueAnimator.ofFloat(0f, SPRING_BACK_MS.toFloat()).apply {
            duration = SPRING_BACK_MS
            interpolator = LINEAR
            // Home is as far as it goes: once there it stays, so the spring's next swing cannot push it down again.
            addUpdateListener { panel.translationY = min(panel.translationY, max(0f, spring(from, it.animatedValue as Float))) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    springing = false
                    panel.translationY = 0f
                }
            })
            springing = true
            start()
        }
    }

    /** What the grabber drags: the whole keyboard window's content, the panels over this view included. */
    private fun panel(): View = parent as? View ?: this

    private fun click(key: Key) {
        Stats.count("key: ${key.name.lowercase()}")
        when (key) {
            Key.CLIPBOARD -> actions.onClipboard()
            Key.EMOJI -> actions.onEmoji()
            Key.STYLE -> actions.onStyle()
            Key.TRANSLATE -> actions.onTranslate()
            Key.SEND -> if (!longPressed) actions.onAutoSend()
            Key.PASTE_CLOSE -> actions.onPasteDismiss()
            Key.PASTE -> if (!longPressed) actions.onPaste()
            Key.CLEAR -> actions.onClearField()
            Key.STATUS -> when {
                longPressed -> Unit
                model.insertHere -> actions.onInsertHere()
                model.continueKept != null -> actions.onContinueKept()
                else -> actions.onStatus()
            }
            Key.BACKSPACE -> Unit
            Key.SETTINGS -> actions.onSettings()
            Key.PAUSE -> if (pauses(model)) actions.onPause()
            Key.RETRY -> if (model.canRetry) actions.onRetry()
            // The touch that started the recording does not stop it when the finger lifts.
            Key.MIC -> if (!startedOnDown) actions.onMicTap()
            Key.CANCEL -> if (cancels(model)) actions.onCancel()
            Key.ENTER -> actions.onEnter()
            Key.VOCABULARY -> actions.onVocabulary()
            // Held still, it is one space when lifted: no run of spaces.
            Key.SPACE -> if (cursorX == null) actions.onSpace()
            Key.SWITCH -> if (!longPressed) actions.onSwitch()
            Key.GRABBER -> actions.onHide()
        }
    }

    private fun isCard(key: Key?) = key == Key.STYLE || key == Key.TRANSLATE

    /** The Clipboard or Emoji panel covers the keyboard under the top row. */
    private fun panelOpen() = model.open == Key.CLIPBOARD || model.open == Key.EMOJI

    /** Retry takes the place of Pause after a failure, while nothing is recording. */
    private fun showsRetry(m: Model) = m.canRetry && m.state == Dictation.State.IDLE

    /** × acts on a dictation going on, and on the recording Retry offers, which it lets go. */
    private fun cancels(m: Model) = m.state != Dictation.State.IDLE || m.starting || showsRetry(m)

    private fun pauses(m: Model) = m.state.open || m.starting

    /** The paste offer takes the top row where Insert here and Continue do not show, and no panel or card is open. */
    private fun pasteShown(m: Model) = m.paste != null && !m.insertHere && m.continueKept == null && m.open == null

    private fun visibleKeys(): List<Key> {
        val keys = ArrayList<Key>()
        if (pasteShown(model)) {
            keys += listOf(Key.CLIPBOARD, Key.PASTE, Key.PASTE_CLOSE)
        } else {
            keys += listOf(Key.CLIPBOARD, Key.EMOJI, Key.STYLE, Key.TRANSLATE, Key.SEND)
        }
        if (!panelOpen()) {
            keys += listOf(
                Key.CLEAR, Key.STATUS, Key.BACKSPACE, Key.SETTINGS,
                if (showsRetry(model)) Key.RETRY else Key.PAUSE, Key.MIC, Key.CANCEL, Key.ENTER,
                Key.VOCABULARY, Key.SPACE, Key.SWITCH,
            )
        }
        return keys
    }

    /** Where a visible key is, for tests. */
    internal fun bounds(key: Key): RectF? = rects[key]?.takeIf { key in visibleKeys() }?.let(::RectF)

    /**
     * The key under a touch: within 48 dp targets, the one the touch is inside or nearest to; between keys closer than
     * 48 dp the targets split at the midpoint.
     */
    private fun hit(x: Float, y: Float): Key? =
        visibleKeys().filter { rects[it]?.let { r -> expanded(r).contains(x, y) } == true }.minWithOrNull(
            compareBy<Key> { distance(rects.getValue(it), x, y) }.thenBy { rects.getValue(it).let { r -> hypot(r.centerX() - x, r.centerY() - y) } },
        )

    private fun distance(r: RectF, x: Float, y: Float): Float =
        hypot(max(0f, max(r.left - x, x - r.right)), max(0f, max(r.top - y, y - r.bottom)))

    /** Touch targets of at least 48 dp around small controls. */
    private fun expanded(r: RectF): RectF {
        val min = 48f * resources.displayMetrics.density
        val dx = max(0f, (min - r.width()) / 2)
        val dy = max(0f, (min - r.height()) / 2)
        return RectF(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy)
    }

    // Accessibility: every control is a virtual view with a label; every hold has a labelled action.

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    private fun label(key: Key): String = when (key) {
        Key.CLIPBOARD -> context.getString(R.string.clipboard)
        Key.EMOJI -> context.getString(R.string.cd_emoji)
        Key.STYLE -> context.getString(R.string.cd_style, model.style)
        Key.TRANSLATE -> context.getString(R.string.cd_translate, model.translate)
        Key.SEND -> context.getString(R.string.send)
        Key.PASTE -> pasteLabel.toString()
        Key.PASTE_CLOSE -> context.getString(R.string.close)
        Key.CLEAR -> context.getString(R.string.cd_clear_field)
        Key.STATUS -> when {
            model.insertHere -> context.getString(R.string.insert_here)
            model.continueKept != null -> model.continueKept!!
            model.state == Dictation.State.RECORDING && model.liveWords.isNotBlank() -> model.liveWords
            else -> model.status
        }
        Key.BACKSPACE -> context.getString(R.string.cd_backspace)
        Key.SETTINGS -> context.getString(R.string.settings)
        Key.PAUSE -> context.getString(if (model.state == Dictation.State.PAUSED) R.string.cd_resume else R.string.cd_pause)
        Key.RETRY -> context.getString(R.string.retry)
        Key.MIC -> context.getString(
            when (model.state) {
                Dictation.State.IDLE -> R.string.cd_mic_start
                Dictation.State.RECORDING -> R.string.cd_mic_stop
                Dictation.State.PAUSED -> R.string.cd_mic_finish
                Dictation.State.PROCESSING -> R.string.processing
            },
        )
        Key.CANCEL -> context.getString(R.string.cancel)
        Key.ENTER -> context.getString(
            when (model.enterAction) {
                EditorInfo.IME_ACTION_SEARCH -> R.string.cd_search
                EditorInfo.IME_ACTION_GO -> R.string.cd_go
                EditorInfo.IME_ACTION_NEXT -> R.string.cd_next
                EditorInfo.IME_ACTION_DONE -> R.string.cd_done
                EditorInfo.IME_ACTION_SEND -> R.string.send
                else -> R.string.cd_enter
            },
        )
        Key.VOCABULARY -> context.getString(R.string.cd_vocabulary)
        Key.SPACE -> spaceLabel
        Key.SWITCH -> context.getString(R.string.cd_switch_keyboard)
        Key.GRABBER -> context.getString(R.string.cd_hide_keyboard)
    }

    private inner class Accessibility : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = hit(x, y)?.ordinal ?: INVALID_ID

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            visibleKeys().forEach { ids += it.ordinal }
            ids += Key.GRABBER.ordinal
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            val key = Key.entries[id]
            node.contentDescription = label(key)
            val r = rects[key] ?: RectF()
            @Suppress("DEPRECATION")
            node.setBoundsInParent(Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            fun hold(label: Int) = node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat(AccessibilityNodeInfo.ACTION_LONG_CLICK, context.getString(label)))
            when (key) {
                Key.SWITCH -> hold(R.string.action_choose_keyboard)
                Key.SEND -> {
                    node.isCheckable = true
                    // The Boolean form, as minSdk 33 has no other.
                    @Suppress("DEPRECATION")
                    node.isChecked = model.autoSend
                    hold(if (model.autoSend) R.string.action_auto_send_off else R.string.action_auto_send_on)
                }
                Key.STATUS, Key.PASTE -> hold(R.string.clipboard)
                // With no dictation possible here (a password field, no account, the free minutes used up), the mic
                // reads as off rather than as a working Start dictation.
                Key.MIC -> node.isEnabled = model.micEnabled || model.state != Dictation.State.IDLE
                else -> Unit
            }
            node.className = "android.widget.Button"
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
            val key = Key.entries[id]
            when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    // A touch's leftovers never change what a TalkBack tap does.
                    startedOnDown = false
                    longPressed = false
                    cursorX = null
                    if (key == Key.MIC && model.state == Dictation.State.IDLE) actions.onMicDown()
                    else if (key == Key.BACKSPACE) actions.onBackspace(word = false)
                    else click(key)
                }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> when (key) {
                    Key.SWITCH -> actions.onSwitchLong()
                    Key.SEND -> actions.onAutoSendLong()
                    Key.STATUS, Key.PASTE -> actions.onClipboard()
                    else -> return false
                }
                else -> return false
            }
            return true
        }
    }

    private companion object {
        /**
         * How much the keyboard gives at first to a pull down on its top strip, a rubber band that holds back more the
         * further it goes, from the first pixel: a little at once (UIScrollView's 0.55 holds back too hard, 1 not at all at
         * first).
         */
        const val PULL_GIVE = 0.8f

        // The reference panel, in dp: 411 × 296, a 16 dp grabber strip on a 280 dp grid whose bottom keys end 11.5 dp
        // over its bottom, which lifts them off the navigation bar (the top row 36 dp,
        // the six side keys 48 dp, the corners 32 dp as Material's extra-large-increased shape).
        const val WIDE = 411f
        const val TALL = 308f
        const val GRAB = 20f
        const val AXIS = 205.5f
        val COLUMNS = floatArrayOf(43f, 124.25f, 205.5f, 286.75f, 368f)
        val ROWS = listOf(29f, 91f, 167.75f, 244.5f)
        const val CLIP_X = 39f
        const val EMOJI_X = 84f
        const val SEND_X = 349.5f
        const val SEND_WIDTH = 85f
        const val HALF_WIDTH = 95.5f
        const val MIDDLE_LEFT = 77f
        const val TOP_KEY = 36f
        const val SIDE_KEY = 48f
        const val TRIO_KEY = 44f
        const val BALL = 82f
        const val PILL_HEIGHT = 46f
        const val SPACE_HEIGHT = 42f
        const val TRIO_PAD = 36f
        const val TRIO_HEIGHT = 62f
        const val PANEL_TOP = 53f
        const val CARD_TOP = 52f
        const val CARD_WIDTH = 224f
        const val CARD_HEIGHT = 188f
        const val CORNER = 40f
        const val TOP_LINE = 0.5f
        /** The panel's shadow up over the app (0 -1 5 in CSS: σ 2.5), and the room above the panel it needs, in dp. */
        const val TOP_SHADOW_BLUR = 2.5f
        const val TOP_SHADOW_DY = 1f
        const val TOP_ROOM = 8f
        const val HAIRLINE = 1f
        const val GRAB_WIDTH = 72f
        const val GRAB_HEIGHT = 5f
        const val GRAB_TOP = 9.5f
        const val GRAB_TOUCH_WIDTH = 120f
        /** The middle share of the top edge a pull starts from. */
        const val GRAB_ZONE = 0.5f
        const val DIVIDER = 18f
        const val OPEN_INSET = 3f
        const val OPEN_INNER = 2f
        const val HALF_TEXT = 15f
        // The label, the gap and the caret of a half: small enough that Translate keeps about 7 dp on each side in
        // its equal half (the key itself keeps its size).
        const val HALF_GAP = 4f
        const val CARET = 11f
        const val ICON_KEY = 23f
        const val ICON_TOP = 21f
        const val ICON_SEND = 20.5f
        const val ICON_TRIO = 23f
        const val ICON_CLOSE = 19f
        const val ICON_BOX = 256f
        const val BADGE = 8f
        const val BADGE_RING = 2f
        const val BADGE_OFFSET = 11f
        const val BADGE_SEND = 14f
        const val BADGE_SEND_RIGHT = 4f
        const val BADGE_SEND_UP = 3f
        const val BADGE_CHECK = 9f
        const val STATUS_TEXT = 15.5f
        const val TWO_LINE_TEXT = 14f
        /** The status text's column: a line wider than this wraps to two, as the two-line outcomes do. */
        const val STATUS_LINE_MAX = 200f
        /** A wrapped status's lines, each about this share of the whole text's width: two balanced lines. */
        const val TWO_LINE_SHARE = 0.6f
        const val TWO_LINE_HEIGHT = 19f
        const val TWO_LINE_CACHE = 8
        const val SPACE_TEXT = 11f
        const val CHIP_TEXT = 15f
        const val CHIP_HEIGHT = 33f
        /** The paste offer: as tall as the top row's keys, its text and × a size up from the status chip. */
        const val PASTE_HEIGHT = 36f
        const val PASTE_TEXT = 16f
        const val CHIP_PAD = 16f
        const val CHIP_PAD_STATUS = 15f
        const val CHIP_PAD_THUMB = 6f
        const val CHIP_GAP = 7f
        const val CHIP_MAX_WIDTH = 250f
        const val CLOSE_BOX = 28f
        const val CLOSE_PULL = 6f
        /** The × takes a few dp more than its glyph, so a short offer's label stays a paste. */
        const val CLOSE_REACH = 10f
        const val THUMB = 28f
        const val THUMB_RADIUS = 6f
        const val PASTE_INSET = 30f
        const val PASTE_MAX_WIDTH = 250f
        const val PASTE_SHIFT = 10f
        const val SPIN_MS = 1100L
        /** Enough words to fill the live line; the ones beyond its left edge are never drawn. */
        const val LIVE_WORDS = 12
        /** The live line's soft edges, alike: older words go out over the left one, new ones come in over the right one. */
        const val LIVE_FADE = 54f
        const val EM_DASH = '—'
        val SPACES = Regex("\\s+")
        const val PLAY_SHIFT = 2f
        const val PAUSE_ROUNDING = 20f
        /** Phosphor's pause bars, then the halves of its play triangle, as corners in the 256 box, inset by the rounding. */
        val PAUSE_BARS = floatArrayOf(50f, 42f, 102f, 42f, 102f, 214f, 50f, 214f, 154f, 42f, 206f, 42f, 206f, 214f, 154f, 214f)
        val PLAY_HALVES = floatArrayOf(74f, 46f, 148f, 87f, 148f, 169f, 74f, 210f, 148f, 87f, 222f, 128f, 222f, 128f, 148f, 169f)
        const val RETRY_FROM = 0.7f
        const val RETRY_SHADOW_BLUR = 4f
        const val RETRY_SHADOW_DY = 2f
        const val RETRY_RING_START = 23f
        const val RETRY_RING_END = 36f
        const val RETRY_RING_STROKE = 2.5f
        const val RETRY_RING_ALPHA = 0.55f
        const val RETRY_RING_FROM_MS = 300L
        const val RETRY_RING_MS = 500L
        const val BADGE_DELAY_MS = 20f
        const val BADGE_IN_MS = 220f
        const val BADGE_OUT_MS = 140f
        const val BADGE_PEAK = 1.18f
        const val BADGE_PEAK_AT = 0.7f

        // The mic ball.
        const val MIC_GLYPH = "M128,180a52.06,52.06,0,0,0,52-52V64A52,52,0,0,0,76,64v64A52.06,52.06,0,0,0,128,180ZM100,64a28,28,0,0,1,56,0v64a28,28,0,0,1-56,0Zm40,155.22V240a12,12,0,0,1-24,0V219.22A92.14,92.14,0,0,1,36,128a12,12,0,0,1,24,0,68,68,0,0,0,136,0,12,12,0,0,1,24,0A92.14,92.14,0,0,1,140,219.22Z"
        const val MIC_GLYPH_SHARE = 0.36f
        const val MIC_SHADOW_DY = 0.07f
        const val MIC_SHADOW_BLUR = 0.09f
        const val MIC_RIM = 3f
        // Drift: the waves' lengths (share of the ball), how long each takes to slide one length, and their height's share.
        const val FRONT_WAVELENGTH = 1.05f
        const val BACK_WAVELENGTH = 0.72f
        const val FRONT_SLIDE_MS = 2600f
        const val BACK_SLIDE_MS = 3400f
        const val DRIFT_SHARE = 0.8f
        const val BACK_PHASE = 1.3f
        const val STILL_PHASE = 0.4f
        const val BACK_RISE = 0.03f
        const val WAVE_STEPS = 60f
        /** The surface line: white at 75 %, up to 100 % with the voice. */
        const val SURFACE_ALPHA = 0.75f
        const val SURFACE_WIDTH = 1.2f
        const val SURFACE_MIN = 0.6f
        const val SURFACE_REFERENCE = 88f
        val WATER_STOPS = floatArrayOf(0f, 0.55f, 1f)
        /** Where in the ball, from its top, the water's gradient starts. */
        const val WATER_FROM = 0.2f
        /** The mic off: no colour, and this share of [KeyboardColors.micOff] over it; fully opaque. */
        const val DIM_LIFT = 0.45f
        /**
         * The recording mark, a hybrid of a halo and a ring, 6 dp off the ball so it does not hug it. The core, 4.5 dp
         * wide, is softened by a 2.2 dp blur at 69 %; the same stroke blurred by 9 dp at 60 % is the glow.
         */
        const val REC_GAP = 6f
        /** The ring of a recording started by a touch shows this long after it. */
        const val REC_DELAY_MS = 50L
        const val REC_CORE = 3f
        const val REC_WIDTH = 4.5f
        const val REC_CORE_BLUR = 2.2f
        const val REC_CORE_ALPHA = 0.69f
        const val REC_GLOW = 9f
        const val REC_GLOW_ALPHA = 0.6f
        /** Processing: a 90° arc on a 16 % track of the same colour, a turn per SPIN_MS. */
        const val BUSY_ARC = 90f
        const val BUSY_TRACK = 0.16f
        const val MIC_PRESSED = 0.94f
        const val MIC_PRESS_HOLD_MS = 110L
        /** Pressed: 99 %, 0.3 dp lower, halo 70 %, rim 50 %, a 3 dp edge shadow at 24 %. */
        const val MIC_PRESS_SCALE = 0.99f
        const val MIC_PRESS_DROP = 0.3f
        const val MIC_PRESS_HALO = 0.3f
        const val MIC_PRESS_RIM = 0.5f
        const val MIC_EDGE = 3f
        const val MIC_EDGE_ALPHA = 0.24f
        /** And, over it, the whole ball darkened: dark blue at 22 % by day, 30 % at night. */
        const val MIC_PRESS_SHADE = 0xFF0A2A66.toInt()
        const val MIC_PRESS_SHADE_ALPHA = 0.22f
        const val MIC_PRESS_SHADE_NIGHT_ALPHA = 0.3f
        const val LANDED_RING = 0xFF8CC8FF.toInt()
        const val LANDED_START = 44f
        const val LANDED_END = 60f
        const val LANDED_STROKE_START = 3f
        const val LANDED_STROKE_END = 1.5f
        const val LANDED_ALPHA = 0.7f
        const val LANDED_MS = 420L

        // Water levels (share of the ball under water) and crests (dp).
        const val IDLE_FILL = 0.40f
        /** Recording, in a silence: the water settles a little lower than at rest (0.40), and the voice lifts it from there. */
        const val QUIET_FILL = 0.34f
        const val IDLE_CREST = 2.0f
        const val PAUSED_FILL = 0.42f
        const val PAUSED_CREST = 0.3f
        const val BUSY_CREST = 0.5f
        const val BUSY_SATURATION = 0.45f
        /** Loud speech lifts the water by up to this share: 0.34 → 0.86. */
        const val LISTEN_FILL_RANGE = 0.52f
        const val CREST_UNIT = 5.6f
        const val CREST_BASE = 0.12f
        const val CREST_GAIN = 1.25f
        const val BACK_CREST_GAIN = 1.1f
        const val BACK_FILL_GAIN = 0.02f
        const val BACK_CREST_SHARE = 0.8f
        const val CALM_CREST = 0.8f
        const val CALM_BACK_CREST = 0.6f
        // How fast the waves and the water follow the voice: VoiceEnvelope's time constants.
        const val BACK_LAG_MS = 160L
        /** Below this, above the noise floor, the voice counts as silence for the calm: 400 ms, then 600 ms to calm. */
        const val QUIET = 0.03f
        const val CALM_AFTER_MS = 400L
        const val CALM_MS = 600f
        const val WATER_MS = 520L
        const val PAUSE_WATER_MS = 400L
        const val HEARD = 64
        const val MAX_LEVEL_STEP_MS = 100L
        const val MAX_STEP_MS = 32L
        const val TWO_PI = (2 * PI).toFloat()

        // Motion.
        val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        val STANDARD_ACCELERATE: Interpolator = PathInterpolator(0.3f, 0f, 1f, 1f)
        val EMPHASIZED_DECELERATE: Interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val LINEAR: Interpolator = LinearInterpolator()
        const val PRESS_MS = 80f
        const val MIN_HOLD_MS = 100L
        const val PRESS_DROP = 1f
        /** The flat keys of the top row, which darken when pressed but never sink or show the edge shadow. */
        val TOP_ROW = setOf(Key.CLIPBOARD, Key.EMOJI, Key.SEND, Key.PASTE)
        const val TINT_OUT_MS = 160f
        const val SPRING_MS = 400L
        const val KEY_SPRING_STIFFNESS = 900f
        const val KEY_SPRING_DAMPING = 0.92f
        /** The press's edge shadow: 2 dp under the top edge, 2.5 dp blur. */
        const val PRESS_EDGE = 2f
        const val PRESS_EDGE_BLUR = 2.5f
        const val PRESS_EDGE_ALPHA = 0.2f
        const val PRESS_EDGE_DAY = 0xFF0A2A66.toInt()
        const val PRESS_EDGE_NIGHT = 0xFF000000.toInt()
        /** The shade over a pressed key, dark enough that a press is plain: dark blue at 12 % by day, black at 30 % at night. */
        const val PRESS_SHADE_DAY = 0xFF0A2A66.toInt()
        const val PRESS_SHADE_DAY_ALPHA = 0.12f
        const val PRESS_SHADE_NIGHT = 0xFF000000.toInt()
        const val PRESS_SHADE_NIGHT_ALPHA = 0.3f
        const val OPEN_MS = 180L
        const val CLOSE_MS = 120L
        const val KEY_TINT_MS = 120L
        const val SEND_ON_MS = 160L
        const val PASTE_IN_MS = 260L
        const val PASTE_OUT_MS = 280L
        const val TOOLBAR_OUT_SHARE = 0.46f
        const val PASTE_IN_FROM = 0.3f
        const val STATUS_FADE_MS = 200L
        const val DOTS = "..."
        const val DOTS_CYCLE_MS = 1800L
        const val DOTS_STEP_MS = 400f
        const val DOTS_FADE_MS = 260f
        const val DOTS_OUT_AT_MS = 1300f
        const val DOTS_OUT_MS = 340f
        const val STATUS_SHIFT = 5f
        const val STATUS_IN_FROM = 0.4f
        const val LIVE_SLIDE_MS = 180L
        // Material's bottom sheet: BottomSheetBehavior.HIDE_FRICTION, HIDE_THRESHOLD.
        const val HIDE_FRICTION = 0.1f
        const val HIDE_THRESHOLD = 0.5f
        // A flick hides only past a touch target's height (48 dp): less is a tap on the top row that slipped.
        const val FLICK_MIN_DP = 48f
        /** The finger's pull, as a share of the height, past which letting go hides the keyboard. */
        const val HIDE_PULL = 0.4f
        const val SPRING_BACK_MS = 420L
        const val HIDE_CHECK_MS = 1000L

        // Grid and touch rules.
        const val MAX_UNIT_DP = 1.1f
        const val MAX_GAIN = 1.04f
        const val MAX_SCREEN_SHARE = 0.40f
        const val REPEAT_DELAY_MS = 400L
        const val FIRST_INTERVAL_MS = 90.0
        const val ACCELERATION = 0.92
        const val MIN_INTERVAL_MS = 30L
        const val WORD_AFTER = 20
        const val WORD_INTERVAL_MS = 180L
        const val LONG_PRESS_MS = 450L
        const val CARD_HOLD_MS = 300L
        /** How far the finger slides along the space bar for the cursor to move one character. */
        const val CURSOR_STEP_DP = 10f
        /** How far the finger slides up or down for the cursor to move one line. */
        const val CURSOR_LINE_DP = 24f
        const val CURSOR_FADE_ALPHA = 0.8f
        const val CURSOR_ICON = 32f
        const val CURSOR_TEXT = 18f
    }
}
