package app.waveei.android

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import app.waveei.android.ui.EmojiPanel
import app.waveei.android.ui.KeyboardColors
import app.waveei.android.ui.KeyboardView
import app.waveei.android.ui.ListCard
import app.waveei.android.ui.ListPanel
import app.waveei.android.ui.appLabel
import app.waveei.android.ui.languageName
import app.waveei.android.ui.styleName
import app.waveei.core.buffer.BufferEntry
import app.waveei.core.buffer.BufferStatus
import app.waveei.core.field.AlreadyInField
import app.waveei.core.style.Style
import app.waveei.core.text.separatorAfter
import app.waveei.core.text.separatorBefore
import app.waveei.core.text.wordDeleteLength
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** The keyboard: the panel, its lists and the link between the focused field and [Dictation]. */
class KeyboardService : InputMethodService(), KeyboardView.Actions, Dictation.Observer, Dictation.Target {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var keyboard: KeyboardView
    private lateinit var list: ListPanel
    private lateinit var emoji: EmojiPanel
    private lateinit var card: ListCard
    private var colors = KeyboardColors.DAY

    private var micDisabledReason: Int? = null
    /** A start failure or a local note (copied, already in this field), shown until the next action. */
    private var note: String? = null
    /** What is open: the Clipboard or Emoji panel, or the Style or Translate card. */
    private var openList: KeyboardView.Key? = null

    /** Which tab of the clipboard panel shows: the clipboard (true) or the buffer. */
    private var clipboardTab = false
    private var insertHere: BufferEntry? = null
    private val showRaw = HashSet<Long>()

    /** What the system clipboard holds, as last read; the default keyboard may read it. */
    private data class Clip(val text: String, val copiedAt: Long, val sensitive: Boolean, val image: File? = null)

    private var clip: Clip? = null

    /**
     * The copy time of the clip pasted from the offer (or dismissed), so the offer goes away after one use, in every
     * field. Stored: switching to another keyboard and back makes a new instance of this service.
     */
    private var pastedClipAt: Long
        get() = prefs.pastedClipAt
        set(value) { prefs.pastedClipAt = value }
    // The newest clip the Clipboard panel has shown; a clip copied later lights the key's badge.
    private var shownClipAt = System.currentTimeMillis()

    /** The copy time of the clip last described in the log, so each copy is logged once. */
    private var loggedClipAt = 0L
    private lateinit var clipboard: ClipboardManager
    private val clipChanged = ClipboardManager.OnPrimaryClipChangedListener {
        readClip()
        if (openList == KeyboardView.Key.CLIPBOARD && clipboardTab) showClipboard()
        render()
    }

    /** The app this keyboard was opened in; leaving it brings the usual keyboard back. */
    private var session: String? = null

    private var clearAskedAt = 0L
    private var dismissAskedAt = 0L
    private val leave = object : Runnable {
        override fun run() {
            // Still in that app, only no field focused (a message sent, the keyboard hidden): looked at again later.
            val app = session ?: return
            val dots = DotService.instance
            if (dots != null && dots.showsApp(app) != false) {
                main.postDelayed(this, LEAVE_MS)
                return
            }
            DebugLog.add("keyboard: no window of ${DebugLog.name(app, "an app")} on screen${if (dots == null) " (the dot service is not running)" else ": ${dots.describeWindows()}"}")
            leaveSession()
        }
    }

    // Some chat apps start the keys and leave the window hidden, its room kept empty on screen.
    private val showIfHidden = Runnable {
        if (windowShown) return@Runnable
        requestShowSelf(0)
        DebugLog.add("keyboard: started without its window, shown")
    }

    private val bufferChanged: () -> Unit = {
        findInsertHere()
        if (openList == KeyboardView.Key.CLIPBOARD && !clipboardTab) showBuffer()
        render()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.addPrimaryClipChangedListener(clipChanged)
        // Images left from a process that ended: the list that knew them is gone.
        clipsDir().listFiles()?.filter { ClipboardHistory.image(it) == null }?.forEach { it.delete() }
        readClip()
    }

    /**
     * The panel runs on under the navigation bar, with icons that read on it. Android 15 draws the keyboard
     * there itself but lays a grey scrim over the three buttons unless told not to; Android 13 and 14 paint the bar
     * black, so it takes the panel's bottom colour.
     */
    private fun paintNavigationBar() {
        val window = window?.window ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = colors.bgBottom
        }
        window.isNavigationBarContrastEnforced = false
        val light = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (colors == KeyboardColors.NIGHT) 0 else light, light)
    }

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        if (!::keyboard.isInitialized || !isInputViewShown) return
        outInsets.contentTopInsets += keyboard.topRoom
        outInsets.visibleTopInsets += keyboard.topRoom
    }

    /** Never the whole screen, in landscape too: the chat stays in sight above the keys, as with AOSP's keyboard. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        keyboard = KeyboardView(this, this)
        // The water follows the voice 40 times a second, not once per 100 ms chunk.
        keyboard.meter = { VoiceLevel.level }
        list = ListPanel(this).apply { visibility = View.GONE }
        emoji = EmojiPanel(this, ::typeEmoji, ::onBackspace).apply { visibility = View.GONE }
        card = ListCard(this)
        // Room above the panel for its shadow over the app; the app keeps that strip and its touches (onComputeInsets).
        val root = object : FrameLayout(this) {
            override fun dispatchDraw(canvas: Canvas) {
                keyboard.drawShadow(canvas)
                super.dispatchDraw(canvas)
            }
        }.apply { setPadding(0, keyboard.topRoom, 0, 0) }
        root.addView(keyboard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0))
        root.addView(emoji, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0))
        // The Style / Translate card floats over the keys; it places itself.
        root.addView(card, FrameLayout.LayoutParams(0, 0))
        keyboard.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeList() }
        applySettings()
        return root
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        main.removeCallbacks(leave)
        // A restart counts too: a browser or a Compose app moves to another field that way.
        Dictation.inputStarted()
        // A send tapped for the field in front is not for the next one.
        sendOnce = false
        val app = attribute.packageName
        // Another app's input: the keyboard leaves the app it is in only once that app is off the screen, the same test
        // as with no field at all; a window that takes input for a moment over it (an overlay, a dialog) does not count.
        val was = session
        if (was != null && app != null && app != was) {
            val dots = DotService.instance
            val shown = dots?.showsApp(was)
            DebugLog.add("keyboard: input started in ${DebugLog.name(app, "another app")} while in ${DebugLog.name(was, "an app")}${if (shown != false && dots != null) ", still on screen, kept" else ""}")
            // Kept: looked at again each second, as with no field, so it still leaves once that app is gone.
            if (dots == null || shown == false) leaveSession() else main.postDelayed(leave, LEAVE_MS)
        }
        if (session == null) session = app
        // The floating switch button picked this keyboard; some chat apps then leave it hidden.
        if (SystemClock.uptimeMillis() - showRequestedAt < SHOW_AFTER_SWITCH_MS) {
            showRequestedAt = 0L
            requestShowSelf(0)
            DebugLog.add("keyboard: shown after the switch")
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        micDisabledReason = if (isPassword(info)) R.string.password_field else null
        if (!restarting) {
            closeList(animated = false)
            // A note from the last dictation does not stay over a new field, where it hides the paste offer;
            // one with Retry stays until it is dealt with.
            Dictation.forgetMessage()
        }
        note = null
        applySettings()
        Dictation.addObserver(this)
        Dictation.addTarget(this)
        BufferStore.addListener(bufferChanged)
        // Back in a field after another keyboard: what the person corrected there in the last dictation.
        info.packageName?.takeIf { micDisabledReason == null && Learning.watches(it) }?.let { app -> fieldText()?.let { Learning.seen(app, it) } }
        Dictation.deliverWaiting()
        // The week's free minutes used up: the mic stays grey until the server says Tide was bought.
        if (Account.freeUsedUp) Dictation.refreshPlan { main.post(::render) }
        findInsertHere()
        readClip()
        readScreenshot()
        watchScreenshots(true)
        render()
        main.postDelayed(showIfHidden, SHOW_CHECK_MS)
    }

    /** The system slides the keyboard in; the keys rise and fade in a little behind it, so it comes up softer. */
    override fun onWindowShown() {
        super.onWindowShown()
        windowShown = true
        DotService.instance?.keyboardShownOrHidden()
        if (!::keyboard.isInitialized) return
        val panel = keyboard.parent as? View ?: return
        settle(panel)
        panel.alpha = 0f
        panel.translationY = RISE_DP * resources.displayMetrics.density
        panel.animate().alpha(1f).translationY(0f).setDuration(RISE_MS).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        // A rise that stalls (the window not drawing yet) still ends with the keys in place.
        main.postDelayed(settleRise, RISE_MS + RISE_SETTLE_MS)
    }

    private val settleRise = Runnable { (keyboard.parent as? View)?.takeIf { it.alpha < 1f }?.let(::settle) }

    override fun onWindowHidden() {
        super.onWindowHidden()
        windowShown = false
        DotService.instance?.keyboardShownOrHidden()
        if (::keyboard.isInitialized) (keyboard.parent as? View)?.let(::settle)
    }

    /** The keys where they end up: a rise left unfinished never keeps them invisible. */
    private fun settle(panel: View) {
        main.removeCallbacks(settleRise)
        panel.animate().cancel()
        panel.alpha = 1f
        panel.translationY = 0f
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        main.removeCallbacks(showIfHidden)
        // A send tapped here is for the message in front of the person, not for a text that goes in minutes later.
        sendOnce = false
        // Recording continues while the keyboard is hidden; the result then goes to the buffer.
        // Without the recording service (Android refuses it while Accessibility is off) the microphone goes
        // silent once the keyboard hides, so the dictation stops here and keeps what was said.
        if (Dictation.state.open && !RecordingService.active) {
            DebugLog.add("keyboard: hidden while recording without the recording service, stopping")
            Dictation.stop()
        }
        Dictation.removeTarget(this)
        Dictation.removeObserver(this)
        BufferStore.removeListener(bufferChanged)
        watchScreenshots(false)
        keepScreenOn(false)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // Another field in the same app starts input right away; nothing does once the person has left the app.
        main.postDelayed(leave, LEAVE_MS)
    }

    override fun onDestroy() {
        windowShown = false
        main.removeCallbacks(leave)
        main.removeCallbacks(showIfHidden)
        clipboard.removePrimaryClipChangedListener(clipChanged)
        Dictation.removeTarget(this)
        Dictation.removeObserver(this)
        BufferStore.removeListener(bufferChanged)
        watchScreenshots(false)
        super.onDestroy()
    }

    private fun applySettings() {
        if (!::keyboard.isInitialized) return
        colors = KeyboardColors.resolve(this, prefs.theme)
        keyboard.colors = colors
        keyboard.leftHanded = prefs.leftHanded
        emoji.colors = colors
        emoji.leftHanded = prefs.leftHanded
        paintNavigationBar()
    }

    // Dictation.Observer

    override fun onDictationChanged() {
        keepScreenOn(Dictation.state.open)
        render()
    }

    // Dictation.Target

    override fun knowsField(field: String): Boolean = !field.startsWith(DotService.FIELD_PREFIX)

    override fun seesOtherField(app: String?, field: String?): Boolean =
        field != null && knowsField(field) && isInputViewShown && app == currentInputEditorInfo?.packageName && !sameField(field, signature(currentInputEditorInfo))

    override fun insert(text: String, app: String?, field: String?, id: Long?): Boolean {
        val input = currentInputConnection ?: return false.also { DebugLog.add("keyboard: no input connection, not inserted here") }
        if (!isInputViewShown) return false.also { DebugLog.add("keyboard: not shown, not inserted here") }
        if (app != null && app != currentInputEditorInfo?.packageName) {
            return false.also { DebugLog.add("keyboard: started in another app, not inserted here") }
        }
        // Dictation never goes into a password field, as the mic is off there.
        if (currentInputEditorInfo?.let(::isPassword) == true) return false.also { DebugLog.add("keyboard: a password field, not inserted here") }
        // The dictation began in another field of this app: the text goes to the buffer, not here.
        val here = signature(currentInputEditorInfo)
        if (field != null && !sameField(field, here)) {
            // Not the fields themselves: a field's hint may name the person written to.
            DebugLog.add("keyboard: another field has the focus, not inserting")
            return false
        }
        val before = input.getTextBeforeCursor(SEPARATOR_CONTEXT, 0)?.toString().orEmpty()
        // A message sent at once needs nothing after it; otherwise the separator follows the text right away.
        // Sent only as the dictation goes in, which is only into the field it began in (Dictation.sameInput); never
        // later, from its wait, when the person may have typed on.
        val send = (prefs.autoSend || sendOnce) && id != null && id == Dictation.current && !Dictation.styleDropped
        val after = if (send) "" else separatorAfter(input.getTextAfterCursor(1, 0)?.toString().orEmpty(), prefs.separator)
        val insertion = separatorBefore(before, prefs.separator) + text + after
        // Some chat apps drop the field while the keyboard stays: a commit into a field that is gone
        // fails or reads back something else, and then the text goes to the next target or waits for the field. A field
        // that took the text and changed it (capitals, a length limit, quotes) has it, its start at least: never twice.
        val landed = input.commitText(insertion, 1) && input.getTextBeforeCursor(insertion.length, 0)?.toString().let { now ->
            now != null && (now.trim() == insertion.trim() || now.contains(text.trim().take(LANDED_PROBE), ignoreCase = true))
        }
        if (!landed) {
            DebugLog.add("keyboard: the field did not take the text, not inserted here")
            return false
        }
        if (send) {
            sendOnce = false
            send(input, currentInputEditorInfo?.packageName.orEmpty(), text)
        }
        return true
    }

    /**
     * Sends the message: the field's own send action (send, go, search), else (some chat apps' fields have
     * none, Enter makes a new line there) the app's send button, pressed by the accessibility service while the field
     * still holds [text] (empty: whatever it holds). ↑ only sends: a Next or Done action stays on Enter.
     */
    private fun send(input: InputConnection, app: String, text: String) {
        val action = editorAction()
        if (action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_SEARCH) {
            input.performEditorAction(action)
            return
        }
        val dot = DotService.instance ?: return DebugLog.add("keyboard: auto-send needs the accessibility service, not sent")
        dot.pressSend(app, text)
    }

    override val incognito: Boolean
        get() = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    // KeyboardView.Actions

    override fun onMicDown() {
        micDisabledReason?.let { return showNote(getString(it)) }
        closeList()
        note = null
        // A send tapped before the mic is for this dictation; × clears it, and so does leaving the field.
        val info = currentInputEditorInfo
        val app = info?.packageName.orEmpty()
        val error = Dictation.start(app, signature(info), prefs.styleFor(app), prefs.translateTo)
        if (error != null) showNote(error)
    }

    // Paused, the mic finishes and ▶ in the Pause key records on.
    override fun onMicTap() {
        when (Dictation.state) {
            Dictation.State.RECORDING, Dictation.State.PAUSED -> Dictation.stop()
            else -> Unit
        }
    }

    override fun onCancel() {
        sendOnce = false
        if (Dictation.state == Dictation.State.IDLE) {
            // × while Retry is offered asks first, as the bin: a second tap soon after lets the recording go, and
            // it waits in History.
            val now = SystemClock.uptimeMillis()
            val ask = getString(R.string.dismiss_again)
            if (now - dismissAskedAt > CLEAR_CONFIRM_MS) {
                dismissAskedAt = now
                showNote(ask)
                main.postDelayed({ if (note == ask) { note = null; render() } }, CLEAR_CONFIRM_MS)
                return
            }
            dismissAskedAt = 0L
            if (note == ask) note = null
            cutOff()?.let { row -> BufferStore.update(row.id) { it.copy(interrupted = false) } }
            Dictation.dismiss()
            render()
            return
        }
        Dictation.cancel()
    }

    override fun onPasteDismiss() {
        pastedClipAt = clip?.copiedAt ?: return
        render()
    }

    override fun onPause() {
        when (Dictation.state) {
            Dictation.State.RECORDING -> Dictation.pause()
            Dictation.State.PAUSED -> Dictation.resume()
            else -> Unit
        }
    }

    override fun onRetry() {
        note = null
        val cutOff = cutOff()
        if (Dictation.retryId == null && cutOff != null) Dictation.retry(cutOff.id) else Dictation.retry()
    }

    override fun onContinueKept() {
        cutOff()?.let { continueKept(it) }
    }

    /** The newest recording cut off by a crash or a kill, which the keyboard asks about: continue it or send it. */
    private fun cutOff(): BufferEntry? = if (Dictation.state != Dictation.State.IDLE) {
        null
    } else {
        BufferStore.all().filter { it.interrupted && it.status == BufferStatus.KEPT && BufferStore.audioFile(it) != null }.maxByOrNull { it.id }
    }

    override fun onBackspace(word: Boolean) {
        val input = currentInputConnection ?: return
        val selected = input.getSelectedText(0)
        when {
            !selected.isNullOrEmpty() -> input.commitText("", 1)
            word -> {
                val before = input.getTextBeforeCursor(WORD_CONTEXT, 0) ?: return
                val length = wordDeleteLength(before)
                if (length > 0) input.deleteSurroundingText(length, 0)
            }
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    /** The bin asks first: the field is cleared by a second tap soon after, a stray tap only shows the question. */
    override fun onClearField() {
        val input = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        if (now - clearAskedAt > CLEAR_CONFIRM_MS) {
            clearAskedAt = now
            val ask = getString(R.string.clear_field_again)
            showNote(ask)
            main.postDelayed({ if (note == ask) { note = null; render() } }, CLEAR_CONFIRM_MS)
            return
        }
        clearAskedAt = 0L
        if (note == getString(R.string.clear_field_again)) note = null
        render()
        input.beginBatchEdit()
        val whole = fieldText()
        if (whole != null) {
            input.setSelection(0, whole.length)
            input.commitText("", 1)
        } else {
            // The app does not share its text: what lies around the cursor goes.
            val before = input.getTextBeforeCursor(CLEAR_CONTEXT, 0)?.length ?: 0
            val after = input.getTextAfterCursor(CLEAR_CONTEXT, 0)?.length ?: 0
            input.commitText("", 1)
            input.deleteSurroundingText(before, after)
        }
        input.endBatchEdit()
    }

    override fun onSpace() {
        currentInputConnection?.commitText(" ", 1)
    }

    override fun onEnter() {
        val input = currentInputConnection ?: return
        val action = editorAction()
        if (action == null) sendKeyChar('\n') else input.performEditorAction(action)
    }

    override fun onSwitch() {
        DebugLog.add("keyboard: switch key${if (DotService.instance == null) ", the dot service is not running" else ""}")
        // The dot service's switch shows the other keyboard where the keyboard's own switch leaves it hidden.
        if (DotService.instance?.switchBack() == true) return
        if (!switchBack()) return showPicker()
        // Some chat apps leave the other keyboard hidden; the dot service brings it up.
        DotService.instance?.checkKeyboardShown()
    }

    /** To the keyboard chosen in settings, or else to the one the person came from. */
    private fun switchBack(): Boolean {
        val target = prefs.switchBackKeyboard.ifEmpty { null }
            ?: previousKeyboard(this, id(this))
        if (target != null) {
            val result = runCatching { switchInputMethod(target) }
            DebugLog.add("keyboard: to ${DebugLog.name(target, "another keyboard")}: ${result.exceptionOrNull()?.javaClass?.simpleName ?: "done"}")
            if (result.isSuccess) return true
        }
        val previous = switchToPreviousInputMethod()
        DebugLog.add("keyboard: to the previous keyboard: ${if (previous) "done" else "none, the picker opens"}")
        return previous
    }

    /**
     * The person left the app this keyboard was opened in; a running dictation keeps it until the next app.
     * Back in that app within [RETURN_MS] (5 minutes), the dot service brings this keyboard back; each app on its own.
     */
    private fun leaveSession() {
        if (Dictation.state != Dictation.State.IDLE) return
        val app = session ?: return
        session = null
        // Already switched away by hand: that choice stays, and nothing brings this keyboard back.
        val current = current(this)
        if (current != id(this)) return
        if (!switchBack()) return
        returnTo[app] = SystemClock.uptimeMillis() + RETURN_MS
        DebugLog.add("keyboard: left an app, the usual keyboard is back; this one returns there for ${RETURN_MS / 60_000} min")
    }

    override fun onSwitchLong() = showPicker()

    override fun onHide() {
        requestHideSelf(0)
    }

    override fun onSettings() = openApp("SETTINGS")

    override fun onVocabulary() = openApp("VOCABULARY")

    /** The app on one of its screens (MainActivity.EXTRA_SCREEN). */
    private fun openApp(screen: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), MainActivity.splashless())
    }

    /**
     * A tap while recording stops it and sends the dictation as soon as it goes in. Otherwise
     * it sends what the field holds now; with nothing in it, or while a dictation is written, it only remembers to send
     * the next dictation once it goes in, and a second tap forgets that again.
     */
    override fun onAutoSend() {
        if (Dictation.state.open) {
            sendOnce = true
            Dictation.stop()
            render()
            return
        }
        val input = currentInputConnection
        val written = input != null &&
            (input.getTextBeforeCursor(SEPARATOR_CONTEXT, 0)?.toString().orEmpty() + input.getTextAfterCursor(SEPARATOR_CONTEXT, 0)?.toString().orEmpty()).isNotBlank()
        if (Dictation.state == Dictation.State.IDLE && written) {
            sendOnce = false
            send(input!!, currentInputEditorInfo?.packageName.orEmpty(), "")
        } else {
            sendOnce = !sendOnce
            DebugLog.add("keyboard: send ${if (sendOnce) "remembered for the next dictation" else "forgotten"}")
        }
        render()
    }

    /** Held, the key sends every dictation from then on, or no longer. */
    override fun onAutoSendLong() {
        prefs.autoSend = !prefs.autoSend
        sendOnce = false
        render()
    }

    override fun onCursor(right: Int, down: Int) {
        repeat(abs(right)) { sendDownUpKeyEvents(if (right > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT) }
        // Up and down only where there are lines: in a one-line field the app takes them to move the focus to another view.
        val lines = (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (lines) repeat(abs(down)) { sendDownUpKeyEvents(if (down > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
    }

    override fun onStyle() = open(KeyboardView.Key.STYLE)

    override fun onTranslate() = open(KeyboardView.Key.TRANSLATE)

    override fun onCloseList() = closeList()

    override fun onCardSlide(rawX: Float, rawY: Float) = card.slideTo(rawX, rawY)

    // Held open, the card is a quick setting: lifting the finger picks the row under it, or closes it with none.
    override fun onCardLift(rawX: Float, rawY: Float): Boolean = card.liftAt(rawX, rawY).also { if (!it) closeList() }

    override fun onStatus() {
        // A dictation the plan stopped (free minutes used up, Tide needed, a phone that cannot be verified for the free
        // minutes) stays kept for Retry; its chip opens Plan. Changed Terms open Home, where their dialog asks.
        val terms = getString(R.string.agree_new_terms)
        when {
            Dictation.message == terms || note == terms || (note == null && Dictation.message == null && micDisabledReason == null && prefs.termsPending) -> openApp("HOME")
            Dictation.message != null && Dictation.retryId != null && planChip(Dictation.message!!) == null -> Unit
            // Signed out, the week's free minutes used up: signing in adds more, so Account, where Sign in is.
            Dictation.message?.let(::planChip) == getString(R.string.free_used_up_tap) || note?.let(::planChip) == getString(R.string.free_used_up_tap) ||
                (note == null && Dictation.message == null && Account.freeUsedUp && !Account.signedIn) -> openApp("ACCOUNT")
            Dictation.message?.let(::planChip) != null || note?.let(::planChip) != null || (note == null && Account.freeUsedUp) -> openApp("PLAN")
            note == getString(R.string.no_account) || note == getString(R.string.no_mic_permission) ->
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), MainActivity.splashless())
        }
    }


    /**
     * What a status the plan stopped reads as, its chip opening Plan: the week's free minutes used up says so first,
     * Tide needed (no free tier) only asks for Tide; a phone the free minutes cannot verify, "Phone not verified".
     * Null for any other status.
     */
    private fun planChip(status: String): String? = when {
        status.contains(getString(R.string.error_unverified)) -> getString(R.string.error_unverified)
        status.contains(getString(R.string.free_used_up)) -> usedUpChip()
        status.contains(getString(R.string.tide_needed)) -> getString(R.string.tide_needed_tap)
        else -> null
    }

    /** The week's free minutes used up: signed out, the chip offers the account's extra minutes; signed in, it says so. */
    private fun usedUpChip(): String = getString(if (Account.signedIn) R.string.free_used_up_chip else R.string.free_used_up_tap)

    override fun onInsertHere() {
        insertHere?.let { insertEntry(it) }
    }

    override fun onPaste() {
        val clip = clip ?: return
        if (clip.image != null) {
            // Not kept yet (or the copy failed): the image is still on the clipboard, the field pastes it itself.
            val kept = ClipboardHistory.image(clip.image)
            if (kept != null) {
                insertImage(kept)
            } else {
                val pasted = currentInputConnection?.performContextMenuAction(android.R.id.paste) == true
                DebugLog.add("clipboard: image not kept yet, ${if (pasted) "pasted" else "not pasted"} by the field")
            }
            pastedClipAt = clip.copiedAt
            return render()
        }
        if (currentInputConnection?.commitText(clip.text, 1) != true) return
        pastedClipAt = clip.copiedAt
        render()
    }

    // The key opens on the clipboard; the History tab is one tap away.
    override fun onClipboard() {
        if (openList != KeyboardView.Key.CLIPBOARD) clipboardTab = true
        open(KeyboardView.Key.CLIPBOARD)
    }

    override fun onEmoji() = open(KeyboardView.Key.EMOJI)

    private fun typeEmoji(emoji: String) {
        val input = currentInputConnection
        val inserted = input?.commitText(emoji, 1) == true
        DebugLog.add("emoji: picked, ${if (inserted) "inserted" else if (input == null) "not inserted, no field" else "the field refused it"}")
    }

    // Clipboard

    private fun readClip() {
        clip = runCatching {
            val description = clipboard.primaryClipDescription ?: return@runCatching null
            val item = clipboard.primaryClip?.getItemAt(0)
            val sensitive = description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
            val uri = item?.uri
            // What kind of clip came, never its content: shows in the log why a copied image is not offered.
            if (description.timestamp != loggedClipAt) {
                loggedClipAt = description.timestamp
                val types = (0 until description.mimeTypeCount).joinToString { description.getMimeType(it) }
                DebugLog.add("clipboard: new clip [$types], ${if (uri != null) "uri from ${DebugLog.name(uri.authority, "an app")}" else "no uri"}${if (sensitive) ", sensitive" else ""}")
            }
            // An image we put on the clipboard ourselves to paste it is no new copy: the clip in hand stays, pasted.
            if (uri != null && uri.authority == clipsAuthority()) return@runCatching clip
            if (uri != null && description.hasMimeType("image/*")) {
                if (sensitive) return@runCatching null
                val mime = contentResolver.getType(uri) ?: description.filterMimeTypes("image/*")?.firstOrNull() ?: "image/*"
                return@runCatching Clip("", description.timestamp, sensitive = false, keepImage(uri, mime, description.timestamp))
            }
            val text = item?.coerceToText(this)?.toString().orEmpty()
            text.takeIf { it.isNotBlank() }?.let { Clip(it, description.timestamp, sensitive) }
        }.onFailure { DebugLog.add("clipboard: not read: ${it.javaClass.simpleName}") }.getOrNull()
        clip?.takeUnless { it.sensitive || it.image != null }?.let { ClipboardHistory.add(it.text, it.copiedAt) }
    }

    /** A screenshot taken while the keyboard is up is offered at once. */
    private val screenshotTaken = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            readScreenshot()
            render()
        }
    }

    private var watching = false

    private fun watchScreenshots(on: Boolean) {
        if (on == watching) return
        if (on && !screenshotsAllowed()) return
        watching = on
        if (on) {
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotTaken)
        } else {
            contentResolver.unregisterContentObserver(screenshotTaken)
        }
    }

    private fun screenshotsAllowed() = prefs.screenshotOffer && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    /** A screenshot newer than the clip in hand, from within the paste offer time, is offered in its place. */
    private fun readScreenshot() {
        if (!screenshotsAllowed()) return
        val since = System.currentTimeMillis() - PASTE_OFFER_MS
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf((since / 1000).toString(), "%Screenshots%"))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.MIME_TYPE)
        val shot = runCatching {
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns, query, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                Triple(uri, c.getLong(1) * 1000, c.getString(2) ?: "image/png")
            }
        }.onFailure { DebugLog.add("clipboard: screenshot not read: ${it.javaClass.simpleName}") }.getOrNull() ?: return
        val (uri, takenAt, mime) = shot
        if (takenAt <= (clip?.copiedAt ?: 0L)) return
        clip = Clip("", takenAt, sensitive = false, keepImage(uri, mime, takenAt))
        DebugLog.add("clipboard: screenshot offered")
    }

    private fun clipsDir(): File = File(cacheDir, "clips").apply { mkdirs() }

    private fun clipsAuthority(): String = "$packageName.clips"

    /**
     * The file a copied image is kept in, so it can be inserted after the clipboard moves on. The copy
     * and its preview are made in the background the first time the clip is seen.
     */
    private fun keepImage(uri: Uri, mime: String, copiedAt: Long): File {
        // Put on the clipboard by inserting from the list: the file is already kept.
        if (uri.authority == clipsAuthority()) return File(clipsDir(), uri.lastPathSegment.orEmpty())
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
        val file = File(clipsDir(), "clip-$copiedAt.$extension")
        // The new file marks the copy as started, so a clip read again meanwhile is not copied twice.
        if (!file.createNewFile()) return file
        Thread {
            runCatching {
                // Copied no further than the limit: a huge or endless image never fills the phone's storage.
                contentResolver.openInputStream(uri)!!.use { input ->
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (total <= MAX_IMAGE_BYTES) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            total += read
                        }
                    }
                }
                check(file.length() <= MAX_IMAGE_BYTES) { "too big, over ${MAX_IMAGE_BYTES / 1024} KB" }
                val height = (ListPanel.IMAGE_HEIGHT_DP * resources.displayMetrics.density).toInt()
                val thumbnail = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    // A hardware bitmap cannot be drawn on the software canvas the list card's glass is captured with.
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val scale = height.toFloat() / info.size.height
                    if (scale < 1f) decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), height)
                }
                ClipboardHistory.addImage(file, mime, thumbnail, copiedAt)
                DebugLog.add("clipboard: image kept, $mime ${file.length() / 1024} KB")
            }.onFailure {
                // Left empty, not deleted: the same clip read again is not copied again. Cleared on the next start.
                file.writeBytes(ByteArray(0))
                DebugLog.add("clipboard: image not kept, $mime: ${it.javaClass.simpleName}")
            }
            main.post {
                if (openList == KeyboardView.Key.CLIPBOARD && clipboardTab) showClipboard()
                // The paste chip gets the image's preview.
                render()
            }
        }.start()
        return file
    }

    /** Puts a kept image in the field: as content when the field takes images, else through the field's own paste. */
    private fun insertImage(item: ClipboardHistory.Item) {
        val file = item.image ?: return
        val mime = item.mime ?: "image/*"
        val uri = FileProvider.getUriForFile(this, clipsAuthority(), file)
        val types = currentInputEditorInfo?.contentMimeTypes.orEmpty()
        if (types.any { ClipDescription.compareMimeTypes(mime, it) }) {
            val content = InputContentInfo(uri, ClipDescription(getString(R.string.clip_image), arrayOf(mime)))
            if (currentInputConnection?.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) == true) {
                DebugLog.add("clipboard: image inserted as content")
                return
            }
        }
        clipboard.setPrimaryClip(ClipData.newUri(contentResolver, getString(R.string.clip_image), uri))
        val pasted = currentInputConnection?.performContextMenuAction(android.R.id.paste) == true
        DebugLog.add("clipboard: image ${if (pasted) "pasted" else "not pasted"} by the field, which takes ${types.joinToString().ifEmpty { "no content" }}")
    }

    /** The clip to offer in place of the status: copied a moment ago, not pasted yet, not already in the field. */
    private fun pasteOffer(): Clip? {
        val clip = clip ?: return null
        if (clip.copiedAt == pastedClipAt || System.currentTimeMillis() - clip.copiedAt > PASTE_OFFER_MS) return null
        if (micDisabledReason != null) return null
        val field = fieldText()
        if (clip.image == null && field != null && field.contains(clip.text)) return null
        return clip
    }

    /** The clipboard tab: what the system clipboard holds now, then the recent copies. */
    private fun showClipboard() {
        shownClipAt = max(shownClipAt, clip?.copiedAt ?: 0L)
        val field = currentInputConnection != null
        val rows = ArrayList<ListPanel.Row>()
        val onClipboard = getString(R.string.clip_current)
        val current = clip
        if (current != null && current.image == null) {
            rows += ListPanel.Row(
                text = if (current.sensitive) getString(R.string.clip_sensitive) else current.text,
                meta = time(current.copiedAt),
                muted = current.sensitive,
                tag = onClipboard,
                // A clip marked sensitive stays hidden: no Insert, no Copy.
                main = if (field && !current.sensitive) {
                    ListPanel.Action(getString(R.string.insert)) {
                        closeList()
                        if (currentInputConnection?.commitText(current.text, 1) == true) pastedClipAt = current.copiedAt
                        render()
                    }
                } else {
                    null
                },
                copy = if (current.sensitive) null else ({ copy(current.text) }),
                delete = {
                    runCatching { clipboard.clearPrimaryClip() }
                    ClipboardHistory.all().filter { it.text == current.text }.forEach { ClipboardHistory.remove(it) }
                    readClip()
                    showClipboard()
                    render()
                },
            )
        }
        ClipboardHistory.all().filterNot { current != null && current.image == null && it.text == current.text }.forEach { item ->
            val isCurrent = item.image != null && item.image == current?.image
            rows += ListPanel.Row(
                text = if (item.image != null) getString(R.string.clip_image) else item.text,
                meta = time(item.copiedAt),
                tag = if (isCurrent) onClipboard else null,
                image = item.thumbnail,
                main = if (field) {
                    ListPanel.Action(getString(R.string.insert)) {
                        closeList()
                        if (item.image != null) insertImage(item) else currentInputConnection?.commitText(item.text, 1)
                    }
                } else {
                    null
                },
                copy = { if (item.image != null) copyImage(item) else copy(item.text) },
                delete = {
                    if (isCurrent) runCatching { clipboard.clearPrimaryClip() }
                    ClipboardHistory.remove(item)
                    showClipboard()
                },
            )
        }
        // Clear empties both: the recent copies and what the system clipboard holds.
        showRows(ListPanel.Tab.CLIPBOARD, rows, getString(R.string.clipboard_empty)) {
            runCatching { clipboard.clearPrimaryClip() }
            ClipboardHistory.clear()
            readClip()
            showClipboard()
            render()
        }
    }

    private fun showRows(tab: ListPanel.Tab, rows: List<ListPanel.Row>, empty: String, onClear: () -> Unit) {
        list.show(tab, rows, empty, ::showTab, onClear, colors, keyboard.unit, prefs.leftHanded)
    }

    private fun showTab(tab: ListPanel.Tab) {
        clipboardTab = tab == ListPanel.Tab.CLIPBOARD
        if (clipboardTab) showClipboard() else showBuffer()
    }

    private fun time(millis: Long): String = DateFormat.getTimeFormat(this).format(millis)

    // Lists

    /**
     * Opens [key]'s panel or card, or closes it when it is open already. The Style and Translate halves share one
     * card: tapping the other half keeps it and moves its notch.
     */
    private fun open(key: KeyboardView.Key) {
        if (openList == key) return closeList()
        val was = openList
        openList = key
        if (was != null) hide(was, animated = true, keepCard = isCard(key))
        when (key) {
            KeyboardView.Key.CLIPBOARD -> {
                // Opened, everything in it is seen: the badge counts only what comes after.
                prefs.historySeenAt = System.currentTimeMillis()
                shownClipAt = max(shownClipAt, clip?.copiedAt ?: 0L)
                placeList()
                showTab(if (clipboardTab) ListPanel.Tab.CLIPBOARD else ListPanel.Tab.HISTORY)
                list.visibility = View.VISIBLE
                list.open()
            }
            KeyboardView.Key.EMOJI -> {
                placeList()
                emoji.visibility = View.VISIBLE
                emoji.open()
            }
            else -> showCard(key)
        }
        render()
    }

    private fun closeList(animated: Boolean = true) {
        if (!::list.isInitialized) return
        val was = openList ?: return
        openList = null
        hide(was, animated, keepCard = false)
        render()
    }

    private fun hide(key: KeyboardView.Key, animated: Boolean, keepCard: Boolean) {
        when (key) {
            KeyboardView.Key.CLIPBOARD -> if (animated) list.close { if (openList != key) list.visibility = View.GONE } else list.visibility = View.GONE
            KeyboardView.Key.EMOJI -> if (animated) emoji.close { if (openList != key) emoji.visibility = View.GONE } else emoji.visibility = View.GONE
            else -> if (!keepCard) card.close(animated)
        }
    }

    private fun isCard(key: KeyboardView.Key) = key == KeyboardView.Key.STYLE || key == KeyboardView.Key.TRANSLATE

    /** The Style or Translate list in the floating card under the capsule. */
    private fun showCard(key: KeyboardView.Key) {
        val frame = RectF().also(keyboard::cardFrame)
        if (key == KeyboardView.Key.STYLE) {
            val app = currentInputEditorInfo?.packageName.orEmpty()
            val current = prefs.styleFor(app)
            val items = Style.entries.map { style ->
                val hint = when (style) {
                    Style.OFF -> null
                    Style.MESSAGE -> getString(R.string.style_hint_message)
                    Style.FORMAL -> getString(R.string.style_hint_formal)
                }
                ListCard.Item(styleName(this, style), hint, style == current) {
                    prefs.rememberStyle(app, style)
                    // A dictation going on takes it too, not only the next one.
                    Dictation.setStyle(style, prefs.translateTo)
                    closeList()
                }
            }
            val pinned = ListCard.Pinned("gear-six", getString(R.string.style_settings)) {
                closeList(animated = false)
                openApp("STYLES")
            }
            card.show(items, pinned, frame, keyboard.notchX(key), keyboard, colors, keyboard.unit)
        } else {
            val current = prefs.translateTo
            val items = listOf(ListCard.Item(getString(R.string.translate_off), null, current.isEmpty()) { pickLanguage("") }) +
                prefs.translationChoices().map { tag ->
                    ListCard.Item(languageName(tag), tag.uppercase(Locale.ROOT), tag == current) { pickLanguage(tag) }
                }
            val pinned = ListCard.Pinned("plus", getString(R.string.add_language)) {
                closeList(animated = false)
                openApp("TRANSLATION_LANGUAGES")
            }
            card.show(items, pinned, frame, keyboard.notchX(key), keyboard, colors, keyboard.unit)
        }
    }

    private fun placeList() {
        if (!::list.isInitialized) return
        emoji.unit = keyboard.unit
        val params = list.layoutParams as FrameLayout.LayoutParams
        val top = keyboard.middleTop.toInt()
        val height = (keyboard.middleBottom - keyboard.middleTop).toInt()
        // On a wide screen the lists keep to the keys' centred band; their background runs on to the edges.
        val side = keyboard.side.toInt()
        if (list.paddingLeft != side) {
            list.setPadding(side, 0, side, 0)
            emoji.setPadding(side, 0, side, 0)
        }
        if (params.topMargin != top || params.height != height) {
            params.topMargin = top
            params.height = height
            list.layoutParams = params
            emoji.layoutParams = FrameLayout.LayoutParams(params)
        }
    }

    private fun pickLanguage(tag: String) {
        prefs.translateTo = tag
        Dictation.setStyle(prefs.styleFor(currentInputEditorInfo?.packageName.orEmpty()), tag)
        closeList()
    }

    /** The History tab: the dictations, newest first, each with its status. */
    private fun showBuffer() {
        val field = fieldText()
        // A dictation still being recorded or transcribed shows once it has its text.
        val rows = BufferStore.all().filter { !it.status.active }.sortedByDescending { it.id }.map { entry -> bufferRow(entry, field) }
        // Clear keeps a dictation still going, as the buffer screen in the app does.
        showRows(ListPanel.Tab.HISTORY, rows, getString(R.string.buffer_empty)) {
            BufferStore.clearDone()
            showBuffer()
        }
    }

    /** A History row: a status dot or check, then the time, the app and the status. */
    private fun bufferRow(entry: BufferEntry, field: String?): ListPanel.Row {
        val status = if (entry.hasText && field != null && entry.status != BufferStatus.INSERTED &&
            AlreadyInField.check(entry.text!!, field)
        ) {
            BufferStatus.ALREADY_IN_FIELD
        } else {
            entry.status
        }
        val raw = entry.id in showRaw && entry.raw != null
        val text = when {
            raw -> entry.raw!!
            entry.hasText -> entry.text!!
            else -> getString(R.string.not_transcribed_yet, entry.durationMs / 60_000, entry.durationMs / 1000 % 60)
        }
        // A kept recording goes to the model from here: never transcribed, or once more.
        val canSend = BufferStore.audioFile(entry) != null && !entry.status.active && Dictation.state == Dictation.State.IDLE
        val send = {
            closeList()
            Dictation.retry(entry.id)
        }
        val main = when {
            entry.hasText -> if (status != BufferStatus.ALREADY_IN_FIELD && field != null) ListPanel.Action(getString(R.string.insert)) { insertEntry(entry) } else null
            canSend -> ListPanel.Action(getString(if (status == BufferStatus.FAILED) R.string.retry else R.string.transcribe)) { send() }
            else -> null
        }
        val more = ArrayList<ListPanel.MenuItem>()
        if (entry.hasText && entry.raw != null && entry.raw != entry.text) {
            more += ListPanel.MenuItem("file-text", getString(if (raw) R.string.show_final else R.string.show_raw)) {
                if (!showRaw.add(entry.id)) showRaw.remove(entry.id)
                showBuffer()
            }
        }
        if (entry.hasText && canSend) more += ListPanel.MenuItem("arrow-counter-clockwise", getString(R.string.retry)) { send() }
        val (mark, label) = when (status) {
            BufferStatus.RECORDING -> ListPanel.Mark.Dot(colors.accent) to R.string.status_recording
            BufferStatus.TRANSCRIBING -> ListPanel.Mark.Dot(colors.accent) to R.string.status_transcribing
            // The title already says it is not transcribed yet: no label for it here.
            BufferStatus.KEPT -> ListPanel.Mark.Dot(colors.muted) to null
            BufferStatus.FAILED -> ListPanel.Mark.Dot(colors.muted) to R.string.status_not_transcribed_failed
            BufferStatus.READY -> ListPanel.Mark.Dot(colors.amber) to R.string.status_not_inserted
            // The tick says it went in: no word for it.
            BufferStatus.INSERTED -> ListPanel.Mark.Done to null
            BufferStatus.ALREADY_IN_FIELD -> ListPanel.Mark.Done to R.string.status_already
        }
        // What happened first, then when and where.
        val meta = listOfNotNull(label?.let(::getString), time(entry.id), appLabel(this, entry.app)).joinToString(" · ")
        return ListPanel.Row(
            text = text,
            meta = meta,
            muted = !entry.hasText,
            mark = mark,
            main = main,
            copy = if (entry.hasText) ({ copy(text) }) else null,
            delete = if (!entry.status.active) ({ BufferStore.delete(entry.id) }) else null,
            more = more,
        )
    }

    /** Records on into a kept recording, for the field in front; the keyboard shows it recording. */
    private fun continueKept(entry: BufferEntry) {
        closeList()
        note = null
        val info = currentInputEditorInfo
        val error = Dictation.continueKept(entry.id, info?.packageName.orEmpty(), signature(info))
        if (error != null) showNote(error)
    }

    /** Inserts a buffer row, unless it is already in the field. */
    private fun insertEntry(entry: BufferEntry) {
        val text = entry.text ?: return
        val field = fieldText()
        if (field != null && AlreadyInField.check(text, field)) {
            showNote(getString(R.string.status_already))
            closeList()
            return
        }
        if (insert(text)) {
            if (incognito) BufferStore.delete(entry.id) else BufferStore.update(entry.id) { it.copy(status = BufferStatus.INSERTED) }
            DebugLog.add("buffer: inserted a row, ${text.length} chars")
        }
        insertHere = null
        closeList()
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
        showNote(getString(R.string.copied))
    }

    /** A kept image back on the clipboard, as the field's paste would take it. */
    private fun copyImage(item: ClipboardHistory.Item) {
        val file = item.image ?: return
        clipboard.setPrimaryClip(ClipData.newUri(contentResolver, getString(R.string.clip_image), FileProvider.getUriForFile(this, clipsAuthority(), file)))
        showNote(getString(R.string.copied))
    }

    /**
     * The newest row saved for this field and not inserted yet, or else the dictation waiting for its field, which
     * goes into any field this way; never inserted by itself.
     */
    private fun findInsertHere() {
        val signature = signature(currentInputEditorInfo)
        insertHere = signature?.let { sig ->
            BufferStore.all().filter { it.status == BufferStatus.READY && it.field?.let { f -> sameField(f, sig) } == true }.maxByOrNull { it.id }
                ?: Dictation.waitingId?.let(BufferStore::get)
        }
        // The field's text is asked from the app only when something waits to go in.
        val row = insertHere ?: return
        if (fieldText()?.let { AlreadyInField.check(row.text.orEmpty(), it) } == true) insertHere = null
    }

    // Rendering

    private fun render() {
        if (!::keyboard.isInitialized) return
        // Recording from the touch, but shown as Listening only a moment after the mic's first sound:
        // the person waits for it, the first word is not cut, a second tap meanwhile does nothing.
        val state = Dictation.state.takeUnless { it == Dictation.State.RECORDING && !Dictation.listeningShown } ?: Dictation.State.IDLE
        val (status, kind) = when (state) {
            Dictation.State.RECORDING -> getString(if (Dictation.continued) R.string.continuing else R.string.listening) to KeyboardView.StatusKind.ACTIVE
            Dictation.State.PAUSED -> getString(R.string.paused) to KeyboardView.StatusKind.ACTIVE
            Dictation.State.PROCESSING -> getString(
                when {
                    Dictation.slow && Dictation.slowNetwork -> R.string.processing_slow_network
                    Dictation.slow -> R.string.processing_slow
                    Dictation.sending -> R.string.connecting
                    else -> R.string.processing
                },
            ) to KeyboardView.StatusKind.ACTIVE
            Dictation.State.IDLE -> when {
                // A step to take before dictating (sign in, allow the microphone, agree to changed Terms, get Tide, a
                // phone to verify) is the accent chip that opens it. Red is only a dictation that failed, with Retry.
                note?.let(::planChip) != null -> planChip(note!!)!! to KeyboardView.StatusKind.ACTION
                // A local note (Copied, Already in this field, the bin's question) reads in ink; the mic's own reason
                // stays the grey hint it already was.
                note != null -> note!! to when {
                    note == getString(R.string.no_account) || note == getString(R.string.no_mic_permission) || note == getString(R.string.agree_new_terms) ->
                        KeyboardView.StatusKind.ACTION
                    // A dictation that could not start, or ×'s question: a state, in ink.
                    note == micDisabledReason?.let(::getString) -> KeyboardView.StatusKind.HINT
                    else -> KeyboardView.StatusKind.ACTIVE
                }
                Dictation.message?.let(::planChip) != null -> planChip(Dictation.message!!)!! to KeyboardView.StatusKind.ACTION
                Dictation.message == getString(R.string.agree_new_terms) -> Dictation.message!! to KeyboardView.StatusKind.ACTION
                Dictation.message != null -> Dictation.message!! to messageKind(Dictation.message!!)
                micDisabledReason != null -> getString(micDisabledReason!!) to KeyboardView.StatusKind.HINT
                prefs.termsPending -> getString(R.string.agree_new_terms) to KeyboardView.StatusKind.ACTION
                Account.freeUsedUp -> usedUpChip() to KeyboardView.StatusKind.ACTION
                else -> getString(R.string.tap_to_talk) to KeyboardView.StatusKind.HINT
            }
        }
        val app = currentInputEditorInfo?.packageName.orEmpty()
        // Asked whether to let it go, the cut-off recording still offers Retry, so × can answer.
        val cutOff = cutOff()?.takeIf { (note == null || note == getString(R.string.dismiss_again)) && insertHere == null }
        val style = prefs.styleFor(app)
        val translateTo = prefs.translateTo
        val offer = if (Dictation.state == Dictation.State.IDLE && note == null && Dictation.message == null) pasteOffer() else null
        val preview = offer?.image?.let { ClipboardHistory.image(it)?.thumbnail }
        keyboard.model = KeyboardView.Model(
            state = state,
            status = status,
            statusKind = kind,
            liveWords = if (state == Dictation.State.RECORDING) Dictation.liveWords else "",
            style = styleName(this, style),
            styleOn = style != Style.OFF,
            translate = if (translateTo.isEmpty()) getString(R.string.translate_off) else languageName(translateTo),
            translateOn = translateTo.isNotEmpty(),
            micEnabled = micDisabledReason == null && !Account.freeUsedUp,
            // A chip that asks for a step first (sign in, allow the microphone, Terms, Tide, verify): the mic looks off.
            micBlocked = state == Dictation.State.IDLE && kind == KeyboardView.StatusKind.ACTION,
            canRetry = Dictation.retryId != null || cutOff != null,
            enterAction = editorAction() ?: EditorInfo.IME_ACTION_NONE,
            insertHere = Dictation.state == Dictation.State.IDLE && insertHere != null && note == null,
            continueKept = cutOff?.let { getString(R.string.continue_cut_off, it.durationMs / 60_000, it.durationMs / 1000 % 60) },
            paste = offer?.let {
                when {
                    // With its preview in the chip the label says only "Paste".
                    it.image != null -> if (preview != null) "" else getString(R.string.clip_image)
                    it.sensitive -> ""
                    else -> it.text.take(PASTE_PREVIEW_CHARS)
                }
            },
            pasteImage = preview,
            open = openList,
            bufferCount = BufferStore.all().count {
                it.id > prefs.historySeenAt && (it.status == BufferStatus.READY || it.status == BufferStatus.KEPT || it.status == BufferStatus.FAILED)
            } +
                (if ((clip?.copiedAt ?: 0L) > max(shownClipAt, pastedClipAt)) 1 else 0),
            autoSend = prefs.autoSend,
            sendOnce = sendOnce,
            clearAsked = note == getString(R.string.clear_field_again),
            // "Waiting for network…" keeps its own look (the amber arc), apart from "Connecting…" and "Processing…".
            network = state == Dictation.State.PROCESSING && Dictation.slow && Dictation.slowNetwork,
        )
    }

    /**
     * How a dictation's closing message reads: a failure with Retry in red, "Nothing heard" as a grey note,
     * the rest (Stopped, kept…) in ink.
     */
    private fun messageKind(message: String): KeyboardView.StatusKind = when {
        message == getString(R.string.cancelled_kept) -> KeyboardView.StatusKind.ACTIVE
        Dictation.retryId != null -> KeyboardView.StatusKind.ERROR
        message == getString(R.string.nothing_heard) -> KeyboardView.StatusKind.NOTE
        else -> KeyboardView.StatusKind.ACTIVE
    }

    private fun showNote(text: String) {
        note = text
        render()
    }

    private fun keepScreenOn(on: Boolean) {
        val window = window?.window ?: return
        if (on && prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Field helpers

    private fun editorAction(): Int? {
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val none = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        return if (none) null else action
    }

    private fun fieldText(): String? =
        currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()

    private fun showPicker() = getSystemService(InputMethodManager::class.java).showInputMethodPicker()

    private fun isPassword(info: EditorInfo): Boolean {
        val type = info.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    companion object {
        /** When the floating switch button asked for this keyboard (uptime), so it shows itself once it starts. */
        @Volatile
        var showRequestedAt = 0L

        /** Our keyboard's id, as Android names input methods. */
        fun id(context: Context): String = ComponentName(context, KeyboardService::class.java).flattenToShortString()

        /** The keyboard selected now. */
        fun current(context: Context): String? =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)

        /**
         * The keyboard used before [current], as Android records it: its input method history, most recent first, the
         * list the system's own switch to the previous input method reads.
         */
        fun previousKeyboard(context: Context, current: String): String? {
            val history = runCatching { Settings.Secure.getString(context.contentResolver, INPUT_METHOD_HISTORY) }.getOrNull() ?: return null
            val enabled = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.id }
            return history.split(':').map { it.substringBefore(';') }.firstOrNull { it != current && it in enabled }
        }

        /** Settings.Secure.INPUT_METHODS_SUBTYPE_HISTORY, hidden in the SDK but readable by apps: "id;subtype:id;subtype". */
        private const val INPUT_METHOD_HISTORY = "input_methods_subtype_history"

        /**
         * Only the next dictation is sent right after it goes in. Kept here, not in the keyboard, which the system ends
         * when it switches to another one; a dictation may go in after that. Main thread only.
         */
        private var sendOnce = false
        private const val SHOW_AFTER_SWITCH_MS = 2_000L
        private const val SHOW_CHECK_MS = 600L

        /** This keyboard is on screen, known at once: the window list the dot service reads may show it only later. Main thread only. */
        var windowShown = false
            private set

        /** How far below its place the keyboard's content starts rising as the keyboard comes up. */
        private const val RISE_DP = 24f

        /** How long the keys take to rise and fade in as the keyboard comes up. */
        private const val RISE_MS = 200L

        /** How long after its planned end a stalled rise is put right. */
        private const val RISE_SETTLE_MS = 150L

        /** How long without a new field before the person counts as having left the app. */
        private const val LEAVE_MS = 1_000L

        /** How long this keyboard comes back to an app it was left in. */
        private const val RETURN_MS = 5 * 60_000L

        /** The apps this keyboard was left in, each with the time (uptime) until it comes back there. Main thread only. */
        val returnTo = HashMap<String, Long>()

        /** How long after a copy the panel offers to paste it. */
        private const val PASTE_OFFER_MS = 3 * 60_000L
        private const val PASTE_PREVIEW_CHARS = 60

        /** A copied image bigger than this is not kept. */
        private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

        private const val SEPARATOR_CONTEXT = 100

        /** The start of a dictation a field must show to have taken it, changed or cut short. */
        private const val LANDED_PROBE = 12
        private const val WORD_CONTEXT = 64

        /** How far the clear key reaches on each side of the cursor when the app does not share its text. */
        private const val CLEAR_CONTEXT = 100_000
        /** How long the bin's question waits for the second tap. */
        private const val CLEAR_CONFIRM_MS = 3_000L

        /** Field signature: app package + field id + field name + hint + input type. */
        /**
         * Whether two signatures are the same field: the app, the view and the field name. The hint and the
         * input type are left out, because an app may change them on the same field while it is in use.
         */
        fun sameField(a: String, b: String?): Boolean = b != null && a.split('|').take(3) == b.split('|').take(3)

        fun signature(info: EditorInfo?): String? {
            if (info == null || info.packageName.isNullOrEmpty()) return null
            // No text field: some chat apps drop the field while the keyboard stays. A dictation
            // started then is tied to no field, else it would wait for a field that never comes back.
            if (info.inputType == InputType.TYPE_NULL) return null
            return listOf(info.packageName, info.fieldId, info.fieldName, info.hintText, info.inputType).joinToString("|")
        }
    }
}
