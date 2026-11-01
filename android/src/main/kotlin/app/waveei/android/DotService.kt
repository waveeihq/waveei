package app.waveei.android

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.DecelerateInterpolator
import app.waveei.android.ui.DotColors
import app.waveei.android.ui.DotMenu
import app.waveei.android.ui.DotView
import app.waveei.core.text.separatorAfter
import app.waveei.core.text.separatorBefore
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating icons: an accessibility overlay that starts and stops dictation over any app, inserts the text
 * into the focused field through Accessibility, and switches to the voice keyboard. It reads only the focused
 * field, and only to insert at the cursor.
 *
 * There are two dots, each its own window with its own place, shrinking, taps and menu: the switch dot, over any
 * keyboard, and the record dot, which dictates on its own when turned on and shows a dictation started on the
 * keyboard after the keyboard is gone, whatever the switches say.
 */
class DotService : AccessibilityService(), Dictation.Observer, Dictation.Target {
    internal enum class Icon(val tag: String) { SWITCH("switch"), RECORD("record") }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var overlay: WindowManager
    private val switcher = Floating(Icon.SWITCH)
    private val recorder = Floating(Icon.RECORD)
    private val floating = listOf(switcher, recorder)

    private var otherKeyboard = false
    private var ourKeyboard = false

    /** The other keyboard seen last, for switching back from ours. */
    private var lastOtherKeyboard: String? = null
    private var lastState = Dictation.State.IDLE

    /** The dictation going on was started by the record dot itself, not by the keyboard. */
    private var micDictation = false

    /** The dictation the record dot started, and whether it is starting one right now. */
    private var micDictationId: Long? = null
    private var micStarting = false

    /**
     * The dictation started last, the dot's or the keyboard's, and the very field element it began in: the text goes
     * in by itself only there. The chats of one app share their field's id and hint, never the element.
     */
    private var startedId: Long? = null
    private var startedField: AccessibilityNodeInfo? = null

    /** The last tap in any app (uptime), and the app. */
    private var clickedAt = 0L
    private var clickedApp: String? = null

    /** The field that had the focus right before a switch: some chat apps drop the focus with the keyboard. */
    private var fieldBeforeSwitch: AccessibilityNodeInfo? = null

    /** The app that was in front at the switch, to find its field again when the one above is gone. */
    private var appBeforeSwitch: String? = null
    private var showUntil = 0L

    /**
     * Some apps leave the field without a keyboard after the switch; a click on the field brings it up. Looked at
     * often, so the click comes as soon as ours is gone with no other in its place.
     */
    private val showKeyboard: Runnable = Runnable {
        val ime = runCatching { getWindows().firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrNull()
        // Ours may still be on its way out after the switch: looked at again a moment later.
        val other = ime?.root?.packageName?.let { it.toString() != packageName } ?: false
        // Switched to ours, which asks to show itself as it starts (KeyboardService.onStartInput) and is up before
        // the window list shows it: a click on the field then would show it a second time in the middle of its slide in.
        val toOurs = KeyboardService.current(this) == KeyboardService.id(this)
        val oursComing = toOurs && KeyboardService.showRequestedAt == 0L && !KeyboardService.windowShown
        if ((ime != null && !other || oursComing) && SystemClock.uptimeMillis() < showUntil) {
            main.postDelayed(showKeyboard, SHOW_CHECK_MS)
            return@Runnable
        }
        val before = fieldBeforeSwitch
        val app = appBeforeSwitch
        fieldBeforeSwitch = null
        appBeforeSwitch = null
        if (ime != null || toOurs && KeyboardService.windowShown) return@Runnable
        // The field is looked for in every window: the focus may sit outside the active one. Some chat apps also
        // rebuild their field when the keyboard goes, so the field kept from before may be gone: then the app's own is found.
        var how = "focused"
        val field = focusedField() ?: runCatching {
            getWindows().firstNotNullOfOrNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { node -> node.isEditable } }
        }.getOrNull()?.also { how = "focused in another window" }
            ?: before?.takeIf { it.stillField() }?.also { how = "kept from before" }
            ?: app?.let { fieldIn(it, before) }?.also { how = "found in the app" }
        if (field == null) {
            return@Runnable DebugLog.add("dot: the keyboard stayed hidden after the switch, no field found in ${DebugLog.name(app, "an app")} (kept from before: ${before != null})")
        }
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val clicked = field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        DebugLog.add("dot: the keyboard stayed hidden after the switch, clicked the field ($how) ${describe(field)}: $clicked")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        overlay = getSystemService(WindowManager::class.java)
        Dictation.addObserver(this)
        registerReceiver(lockChanged, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        DebugLog.add("dot: service connected")
        refresh()
    }

    /** The dots never show over the lock screen: looked at again when the screen locks or unlocks. */
    private val lockChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DebugLog.add("dot: service stopped")
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        Dictation.removeObserver(this)
        Dictation.removeTarget(this)
        runCatching { unregisterReceiver(lockChanged) }
        main.removeCallbacks(recheckKeyboards)
        main.removeCallbacks(switchSettled)
        for (f in floating) f.remove()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::prefs.isInitialized) return
        // A tap in an app (its send button, say): a text the dot inserted that is gone after it is not pasted again.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            clickedAt = SystemClock.uptimeMillis()
            clickedApp = event.packageName?.toString()
            return
        }
        // Any keyboard's edits of a field: a word corrected in the last dictation is offered for the vocabulary.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            if (!event.isPassword) event.packageName?.toString()?.takeIf(Learning::watches)?.let { Learning.seen(it, event.text.joinToString("")) }
            return
        }
        // Back in the app a finished dictation was made in: it goes into the field there, whichever keyboard is up.
        Dictation.deliverWaiting()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (prefs.floatingOn) returnToApp(event.packageName?.toString().takeIf { event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED })
            checkKeyboards()
            // A rising keyboard's window may join the list only after the event: looked at once more a moment later.
            main.removeCallbacks(recheckKeyboards)
            main.postDelayed(recheckKeyboards, KEYBOARD_RECHECK_MS)
        }
    }

    private val recheckKeyboards = Runnable { checkKeyboards() }

    /**
     * Which keyboard is up, if any: the switch button shows over either, and while one goes and another, already
     * chosen, comes up: no keyboard is up for a second or two then, whoever switched.
     */
    private fun checkKeyboards() {
        val imeVisible = runCatching { getWindows().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)
        val current = KeyboardService.current(this)
        val ours = current == KeyboardService.id(this)
        if (imeVisible && !ours) lastOtherKeyboard = current
        val now = SystemClock.uptimeMillis()
        if (imeVisible) {
            shownKeyboard = current
        } else if (ourKeyboard || otherKeyboard) {
            keyboardGoneAt = now
            main.removeCallbacks(switchSettled)
            main.postDelayed(switchSettled, SWITCH_GAP_MS)
        }
        val nowSwitching = !imeVisible && shownKeyboard != current && now - keyboardGoneAt < SWITCH_GAP_MS
        if (ourKeyboard != (imeVisible && ours) || otherKeyboard != (imeVisible && !ours) || switching != nowSwitching) {
            ourKeyboard = imeVisible && ours
            otherKeyboard = imeVisible && !ours
            switching = nowSwitching
            render()
        }
    }

    private var shownKeyboard: String? = null
    private var keyboardGoneAt = 0L
    private var switching = false
    private val switchSettled = Runnable { checkKeyboards() }

    /** Our keyboard has just come up or gone: the record dot must not flash while the window list catches up. */
    fun keyboardShownOrHidden() {
        checkKeyboards()
        render()
    }

    /** Adds or removes each dot's window after a settings change; text goes into fields with the dots off too. */
    fun refresh() {
        if (!::prefs.isInitialized) return
        Dictation.addTarget(this, preferred = false)
        for (f in floating) if (enabled(f.icon)) f.add() else f.remove()
        render()
    }

    /** The record dot's window is always there: a dictation going on with the keyboard hidden is never out of sight. */
    private fun enabled(icon: Icon): Boolean = when (icon) {
        Icon.SWITCH -> prefs.floatingOn && prefs.switchIcon
        Icon.RECORD -> true
    }

    private fun render() {
        if (!::prefs.isInitialized) return
        if (Dictation.state == Dictation.State.IDLE && !Dictation.isWaiting) micDictation = false
        // A finished dictation waiting for its app still shows as on its way; one made outside any field is
        // offered by our keyboard's Insert here instead, so the dot is free.
        val state = if (Dictation.isWaiting && !Dictation.waitsForAnyField) Dictation.State.PROCESSING else Dictation.state
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // The keyboard knows it is up before the window list shows it, and while the list drops its window for a moment.
        val ours = ourKeyboard || KeyboardService.windowShown
        switcher.show(switchMode(ours || switching, otherKeyboard).takeUnless { locked })
        recorder.view?.muted = Account.freeUsedUp
        // Keep screen on while recording holds for a dictation from the dot too, as it does in the keyboard.
        recorder.view?.keepScreenOn = micDictation && prefs.keepScreenOn &&
            Dictation.state.open
        recorder.show(recordMode(state, micDictation, ours, prefs.floatingOn && prefs.dotEnabled).takeUnless { locked })
    }

    /** What a dot's menu offers now, its accessibility actions too. */
    private fun items(icon: Icon, mode: DotView.Mode?): List<DotView.Item> =
        menuItems(icon, mode, keyboardDictation = !micDictation, transcribing = Dictation.state == Dictation.State.PROCESSING)

    /**
     * One floating dot: its own window, place, shrinking, resting and menu. It acts only for its own [icon]: the
     * switch dot switches, the record dot dictates, or brings our keyboard back to a dictation started there.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        switcher.replace()
        recorder.replace()
    }

    private inner class Floating(val icon: Icon) : DotView.Actions {
        var view: DotView? = null
            private set
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // No limits: the window's room for the shadow may reach past the screen edge (DotView.overhang).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )

        /**
         * Both dots alike: it shows up as a dot, a tap opens it (a little bigger), and it shrinks back only once
         * left untouched for 3 s, see-through then unless a dictation is going on.
         */
        private var open = false
        private var resting = false

        /** Its menu, while open, in a window of its own; and what it offers. */
        private var menu: DotMenu? = null
        /** Whether the open menu is to the dot's right. */
        private var menuRight = false
        private var menuItems: List<DotView.Item> = emptyList()
        private val menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Only the card takes touches; one anywhere else closes it and still reaches the app below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        /** A touch outside closed the menu at this time: if it was on the dot, it only closed the menu. */
        private var menuLeftAt = 0L

        /** The touch going on came while the menu was open: it closes the menu and does nothing else. */
        private var swallowing = false

        /** A bubble is up: the dot stays for it even when nothing else would show it, as a plain dot. */
        private var lingering = false

        /** Just hidden with Hide the dot: it dissolves and only says how to bring it back. */
        private var hidden = false

        /** What the dot is shown for; null while it is up only for its bubble, when a touch does nothing. */
        private var showing: DotView.Mode? = null

        /** Swiped away to a screen edge (the right one when [tuckedRight]), half hidden, until a tap brings it back. */
        private var tucked = false
        private var tuckedRight = true

        /** Slides the window between where it was and where it rests now, so a swipe sends the icon flying. */
        private var flyer: ValueAnimator? = null
        private var touched = false

        /** When a tap last acted: a second tap right after it is the same tap, not a new command. */
        private var actedAt = 0L

        /** What it showed last, so the log says each change once. */
        private var lastShown = ""
        private val rest = Runnable {
            // A dot showing a dictation going on never rests: it stays big and opaque until the dictation ends.
            if (view?.mode in BUSY || menu != null) return@Runnable
            resting = true
            open = false
            render()
        }

        /** Awake again, full and opaque; it rests 3 s from now unless touched again or its menu is open. */
        private fun wake() {
            resting = false
            main.removeCallbacks(rest)
            if (!touched && menu == null) main.postDelayed(rest, REST_MS)
        }
        private val hideBubble = Runnable {
            view?.bubble = null
            main.postDelayed(endLinger, BUBBLE_FADE_MS)
        }

        /** The bubble is gone: the dot shows only what it is for again, and a hidden one goes. */
        private val endLinger = Runnable {
            lingering = false
            hidden = false
            refresh()
        }

        private fun log(text: String) = DebugLog.add("dot ${icon.tag}: $text")

        fun add() {
            if (view != null) return
            val added = DotView(this@DotService, this)
            if (icon == Icon.SWITCH) added.mode = DotView.Mode.SWITCH
            added.meter = { VoiceLevel.level }
            place(added)
            added.tucked = tucked
            added.settleTuck()
            runCatching { overlay.addView(added, params) }
                .onSuccess {
                    view = added
                    added.appear(from = 0f)
                    // Already visible, so show() will not wake it: without this a new icon never rests.
                    wake()
                    log("overlay added")
                }
                .onFailure { log("could not add the overlay: ${it.javaClass.simpleName}") }
        }

        fun remove() {
            val gone = view ?: return
            view = null
            flyer?.cancel()
            removeMenu()
            lingering = false
            hidden = false
            main.removeCallbacks(rest)
            main.removeCallbacks(hideBubble)
            main.removeCallbacks(endLinger)
            runCatching { overlay.removeView(gone) }
                .onSuccess { log("overlay removed") }
                .onFailure { log("could not remove the overlay: ${it.javaClass.simpleName}") }
        }

        /** The record dot keeps its place under the dot's keys. */
        private var savedX: Int
            get() = if (icon == Icon.SWITCH) prefs.switchX else prefs.dotX
            set(value) {
                if (icon == Icon.SWITCH) prefs.switchX = value else prefs.dotX = value
            }

        private var savedY: Int
            get() = if (icon == Icon.SWITCH) prefs.switchY else prefs.dotY
            set(value) {
                if (icon == Icon.SWITCH) prefs.switchY = value else prefs.dotY = value
            }

        private val savedRight: Boolean
            get() = if (icon == Icon.SWITCH) prefs.switchRight else prefs.dotRight

        /**
         * Puts the window where this dot rests: a height and the centre of its round end, anchored by the end
         * nearer the screen edge so a bubble grows away from that edge.
         */
        private fun place(view: DotView) {
            val metrics = resources.displayMetrics
            val slot = view.slot.toInt()
            val margin = view.margin.toInt()
            val overhang = view.overhang.toInt()
            val centre = savedX.takeIf { it >= 0 } ?: edgeCentre(savedRight, slot, margin - overhang, metrics.widthPixels)
            val (rests, x) = windowX(centre, slot, margin, metrics.widthPixels, overhang)
            val right = if (tucked) tuckedRight else rests
            params.gravity = Gravity.TOP or if (right) Gravity.END else Gravity.START
            params.x = if (tucked) -overhang else x
            // Kept on the screen it is on now: a place saved on a taller screen (before turning the phone) is brought in.
            val y = savedY.takeIf { it >= 0 } ?: (metrics.heightPixels * defaultY(icon)).toInt()
            params.y = y.coerceIn(0, (metrics.heightPixels - slot).coerceAtLeast(0))
            view.rightEdge = right
        }

        /** The screen turned or unfolded: the dot is placed again on it. */
        fun replace() {
            val view = view ?: return
            place(view)
            runCatching { overlay.updateViewLayout(view, params) }
        }

        fun show(wanted: DotView.Mode?) {
            val view = view ?: return
            view.colors = DotColors.resolve(this@DotService, prefs.theme)
            // While its bubble is up the dot stays, as a plain dot; hidden with Hide the dot it shows only the bubble.
            val mode = wanted ?: view.mode.takeIf { lingering && view.shown }?.let { if (it in BUSY) DotView.Mode.IDLE else it }
            // Back from Hide the dot: turned on again, or a dictation to show.
            if (wanted != null && view.vanished && (!lingering || wanted in BUSY)) {
                hidden = false
                view.appear()
            }
            showing = wanted.takeUnless { view.vanished || lingering && hidden }
            // Up only for its bubble, or hidden with Hide the dot, it offers nothing.
            val items = items(icon, showing)
            // A menu for a state that is over closes: its items would do something else now.
            if (menu != null && items != menuItems) dismissMenu()
            view.items = items
            if (mode == null) {
                // It dissolves (see DotView.hide), and is back as a dot.
                if (view.shown) view.hide()
                open = false
                main.removeCallbacks(rest)
            } else {
                if (!view.shown) {
                    wake()
                    // Up for a dictation going on (the keyboard folded while recording): open, as if tapped.
                    if (!tucked && mode in BUSY) open = true
                    view.appear()
                }
                // Into a dictation going on it wakes and opens, so a dictation never shows as a small dot; back from
                // one, it rests after the auto-hide time from now.
                if ((view.mode in BUSY) != (mode in BUSY)) {
                    wake()
                    if (!tucked && mode in BUSY) open = true
                }
                view.mode = mode
            }
            // Always a dot opens only in what a tap does, never in size.
            view.open = open && !prefs.dotsStayDot
            view.opacity = if (resting) 1f - prefs.dotTransparency / 100f else 1f
            // Resting and its see-through look come and go every few seconds: only what the dot is goes to the log.
            val shown = if (mode == null) "nothing" else "${mode.name.lowercase()}${if (view.open) ", open" else ""}"
            if (shown != lastShown) {
                lastShown = shown
                log("shows $shown")
            }
        }

        /** A line beside the dot for 2.5 s; the dot stays for it, also when the dictation it told of is over. */
        fun bubble(text: String) {
            val view = view ?: return
            view.bubble = text
            lingering = view.shown
            main.removeCallbacks(hideBubble)
            main.removeCallbacks(endLinger)
            main.postDelayed(hideBubble, BUBBLE_MS)
        }

        /** A dictation going on that the keyboard started, not this dot. */
        private fun followsKeyboard(view: DotView) = icon == Icon.RECORD && view.mode in BUSY && !micDictation

        // DotView.Actions

        override fun onTap() {
            Stats.count("dot ${icon.tag}: tap")
            val view = view ?: return
            if (swallowing) return
            if (showing == null) return log("tapped while up only for its bubble, nothing to do")
            // A tucked dot only comes back; the next tap acts.
            if (tucked) return tuck(view, false)
            // A tap on a dot opens it; set to two taps that is all it does, set to one tap it acts at once too.
            // A tap never closes it: only the rest after 3 s does.
            if (!open) {
                open = true
                if (!prefs.dotsOneTap) return render()
            }
            val now = SystemClock.uptimeMillis()
            if (now - actedAt < DEBOUNCE_MS) return log("second tap right after the last one, ignored")
            actedAt = now
            // Each dot acts only for itself: the switch dot switches; the record dot brings our keyboard back to a
            // dictation started there, and records and stops its own. Nothing else answers a tap.
            val mode = view.mode
            when {
                icon == Icon.SWITCH -> if (ourKeyboard) switchBack() else switchToVoiceKeyboard(this)
                followsKeyboard(view) -> switchToVoiceKeyboard(this)
                mode == DotView.Mode.RECORDING -> Dictation.stop()
                // Paused, as on the keyboard: it records on; the next tap sends.
                mode == DotView.Mode.PAUSED -> Dictation.resume()
                // Its last dictation still waits for its field: a tap starts a new one, which ends that wait.
                mode == DotView.Mode.IDLE || Dictation.isWaiting && Dictation.state == Dictation.State.IDLE -> start()
                else -> log("tapped while ${mode.name.lowercase()}, nothing to do")
            }
            render()
        }

        override fun onMenu() {
            Stats.count("dot ${icon.tag}: hold")
            val view = view ?: return
            if (swallowing) return
            val items = items(icon, showing)
            if (items.isEmpty()) return log("held while ${view.mode.name.lowercase()}, no menu")
            openMenu(view, items)
        }

        override fun onMenuLeft() {
            log("moved on from the menu")
            dismissMenu()
        }

        override fun menuToRight(): Boolean? = if (menu != null) menuRight else null

        override fun onMenuSlide(rawX: Float, rawY: Float) {
            menu?.slideTo(rawX, rawY)
        }

        override fun onMenuLift(rawX: Float, rawY: Float) {
            val open = menu ?: return
            if (!open.liftAt(rawX, rawY)) log("slid to the menu, let go off its rows")
        }

        override fun onItem(item: DotView.Item) {
            val view = view ?: return
            if (item !in items(icon, showing)) return
            act(item)
            render()
        }

        /** The menu beside the dot, on the side away from its edge, centred on it and kept on the screen. */
        private fun openMenu(view: DotView, items: List<DotView.Item>) {
            removeMenu()
            val opened = DotMenu(this@DotService, items, DotColors.resolve(this@DotService, prefs.theme), ::picked, ::closed)
            opened.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val metrics = resources.displayMetrics
            val centre = view.dotCentre()
            val windowLeft = if (view.rightEdge) metrics.widthPixels - params.x - view.width else params.x
            val x = windowLeft + centre.x
            val y = params.y + centre.y
            val room = DotMenu.SHADOW_ROOM * metrics.density
            val gap = DotMenu.GAP * metrics.density
            val fromRight = x > metrics.widthPixels / 2
            val left = if (fromRight) x - gap + room - opened.measuredWidth else x + gap - room
            menuParams.x = left.toInt().coerceIn(0, (metrics.widthPixels - opened.measuredWidth).coerceAtLeast(0))
            menuParams.y = (y - opened.measuredHeight / 2).toInt().coerceIn(0, (metrics.heightPixels - opened.measuredHeight).coerceAtLeast(0))
            runCatching { overlay.addView(opened, menuParams) }
                .onSuccess {
                    menu = opened
                    menuRight = !fromRight
                    menuItems = items
                    view.held = true
                    wake()
                    opened.open(fromRight, (y - menuParams.y - room).coerceAtLeast(0f))
                    log("menu: ${items.joinToString { it.name.lowercase() }}")
                }
                .onFailure { log("could not add the menu: ${it.javaClass.simpleName}") }
        }

        /** An item tapped, or null for a touch outside the menu. */
        private fun picked(item: DotView.Item?) {
            menu = null
            // Hide the dot keeps it at the size it had until it has dissolved (hideDot).
            if (item != DotView.Item.HIDE) view?.held = false
            if (item == null) menuLeftAt = SystemClock.uptimeMillis() else act(item)
            wake()
            render()
        }

        private fun closed(gone: DotMenu) {
            runCatching { overlay.removeView(gone) }
        }

        /** Closes the menu with its fade, nothing picked. */
        private fun dismissMenu() {
            val open = menu ?: return
            menu = null
            view?.held = false
            open.dismiss()
            wake()
        }

        /** Takes the menu's window away at once. */
        private fun removeMenu() {
            val open = menu ?: return
            menu = null
            view?.held = false
            runCatching { overlay.removeView(open) }
        }

        /** What a menu item does: the same as the keyboard's keys for it, or the dot's own setting. */
        private fun act(item: DotView.Item) {
            log("menu: ${item.name.lowercase()}")
            when (item) {
                DotView.Item.SHOW_KEYBOARD -> switchToVoiceKeyboard(this)
                DotView.Item.PAUSE -> Dictation.pause()
                DotView.Item.RESUME -> Dictation.resume()
                DotView.Item.CANCEL -> Dictation.cancel()
                DotView.Item.HIDE -> hideDot()
                DotView.Item.SETTINGS -> runCatching {
                    startActivity(
                        Intent(this@DotService, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_SCREEN, SETTINGS_SCREEN)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { log("could not open the settings: ${it.javaClass.simpleName}") }
            }
        }

        /**
         * Turns this dot's own switch off. Once the menu has closed the dot dissolves, and where it was a bubble
         * says how to bring it back; the window goes with the bubble.
         */
        private fun hideDot() {
            if (icon == Icon.SWITCH) prefs.switchIcon = false else prefs.dotEnabled = false
            lingering = true
            hidden = true
            main.postDelayed({
                view?.vanish()
                view?.held = false
            }, MENU_CLOSE_MS)
            main.postDelayed({ bubble(getString(R.string.dot_hidden_hint)) }, MENU_CLOSE_MS + VANISH_MS)
        }

        override fun onSwipeAway(toRight: Boolean, speed: Float) {
            dragFrom = null
            if (swallowing) return
            val view = view ?: return
            open = false
            when {
                !tucked -> {
                    tuckedRight = toRight
                    tuck(view, true, speed)
                }
                // Tucked already: a swipe away from its edge brings it back, one towards it changes nothing.
                toRight != tuckedRight -> tuck(view, false, speed)
                else -> {
                    fly(view, speed)
                    render()
                }
            }
        }

        private fun tuck(view: DotView, away: Boolean, speed: Float = 0f) {
            tucked = away
            log(if (away) "tucked away at the ${if (tuckedRight) "right" else "left"} edge" else "back from the edge")
            fly(view, speed)
            render()
        }

        /**
         * Places the icon where it rests now, gliding there from where it is: quick at first, slowing down at the end.
         * Thrown at [speed] pixels a second, it leaves at that speed, as if it kept the finger's momentum.
         * The dot slides half past the edge only once its window is there, and out of it before the window leaves,
         * so it is never cut off in the middle of the screen.
         */
        private fun fly(view: DotView, speed: Float) {
            val screen = resources.displayMetrics.widthPixels
            val from = if (view.rightEdge) screen - params.x - view.width else params.x
            val leaving = view.tucked && !tucked
            if (leaving) view.tucked = false
            val wasRight = view.rightEdge
            place(view)
            val right = view.rightEdge
            val to = if (right) screen - params.x - view.width else params.x
            // Still sliding out of the edge it is tucked at, it is drawn from that edge until the window moves.
            if (leaving) view.rightEdge = wasRight
            flyer?.cancel()
            flyer = ValueAnimator.ofInt(from, to).apply {
                // This easing starts at 4 times the average speed over the flight.
                duration = if (speed > 0f) (4_000 * abs(to - from) / speed).toLong().coerceIn(FLY_MIN_MS, FLY_MS) else FLY_MS
                if (leaving) startDelay = TUCK_MS
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener {
                    view.rightEdge = right
                    val left = it.animatedValue as Int
                    params.x = if (right) screen - left - view.width else left
                    runCatching { overlay.updateViewLayout(view, params) }
                }
                if (tucked) addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled && tucked) view.tucked = true
                    }
                })
                start()
            }
        }

        /** Where the window was when the drag started; it follows the finger's whole way from there. */
        private var dragFrom: Point? = null

        override fun onDrag(dx: Float, dy: Float) {
            val view = view ?: return
            if (swallowing) return
            // Pulled out of the edge where it is tucked: it comes out right there, under the finger.
            if (tucked) {
                tucked = false
                view.tucked = false
                view.settleTuck()
                log("pulled out of the edge")
            }
            flyer?.cancel()
            val from = dragFrom ?: Point(params.x, params.y).also { dragFrom = it }
            // Kept whole on the screen: never past an edge, only the room for its shadow may be, at the sides.
            val metrics = resources.displayMetrics
            val overhang = view.overhang.toInt()
            params.x = (from.x + if (view.rightEdge) -dx else dx).roundToInt()
                .coerceIn(-overhang, (metrics.widthPixels - view.width + overhang).coerceAtLeast(-overhang))
            params.y = (from.y + dy).roundToInt().coerceIn(0, (metrics.heightPixels - view.height).coerceAtLeast(0))
            runCatching { overlay.updateViewLayout(view, params) }
        }

        // Every dot shows fully while a finger is on it, and starts its 3 s to rest again once it leaves.
        override fun onTouchStart() {
            // With the menu open a touch on the dot only closes the menu: the menu's window may see it first.
            swallowing = menu != null || SystemClock.uptimeMillis() - menuLeftAt < MENU_GRACE_MS
            dismissMenu()
            touched = true
            wake()
            render()
        }

        override fun onTouchEnd() {
            touched = false
            wake()
            render()
        }

        override fun onDragEnd(flungRight: Boolean?, speed: Float) {
            dragFrom = null
            val view = view ?: return
            if (swallowing) return
            // Stays exactly where it was dropped: nothing snaps to an edge.
            val screen = resources.displayMetrics.widthPixels
            val windowLeft = if (view.rightEdge) screen - params.x - view.width else params.x
            val left = windowLeft + view.iconLeft().toInt()
            val centre = restingCentre(left, left + view.iconWidth().toInt(), view.slot.toInt(), screen)
            log("moved to $centre, ${params.y}")
            savedX = centre
            savedY = params.y
            // Thrown: it flies on from here and tucks away at that edge; pulled out, it comes back to this place.
            if (flungRight != null) {
                open = false
                tuckedRight = flungRight
                return tuck(view, true, speed)
            }
            place(view)
            runCatching { overlay.updateViewLayout(view, params) }
            render()
        }
    }

    // Dictation

    private fun start() {
        // A dictation from the keyboard, or a resend, is going on: it is not the record dot's to take over.
        if (Dictation.state != Dictation.State.IDLE) return recorder.bubble(getString(R.string.already_recording))
        val field = focusedField()
        if (field?.isPassword == true) return recorder.bubble(getString(R.string.password_field))
        val app = field?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString().orEmpty()
        // Set before the start, whose first news would otherwise show it as the keyboard's dictation for a moment.
        micDictation = true
        micStarting = true
        val error = Dictation.start(app, field?.let(::signature), prefs.styleFor(app), prefs.translateTo)
        micStarting = false
        micDictationId = Dictation.current
        if (error != null) {
            micDictation = false
            return recorder.bubble(error)
        }
        micDictation = true
        render()
    }

    /** To our keyboard; [from] is the dot that asked, which tells when it cannot. */
    private fun switchToVoiceKeyboard(from: Floating = switcher) {
        KeyboardService.showRequestedAt = SystemClock.uptimeMillis()
        rememberField()
        val switched = switchTo(KeyboardService.id(this))
        if (!switched) {
            KeyboardService.showRequestedAt = 0L
            return from.bubble(getString(R.string.enable_keyboard_first))
        }
        checkKeyboardShown()
    }

    /**
     * Whether [app] still has a window on screen, null when unknown. Its windows count, not the active one: a touch on a
     * dot, a keyboard or the notification shade makes those active for a moment while the app stays in front.
     */
    fun showsApp(app: String): Boolean? = runCatching {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.map { it.root?.packageName?.toString() }
        // A window whose content could not be read may be that app's: unknown, not gone.
        if (app in apps) true else if (null in apps) null else false
    }.getOrNull()

    /** The windows on screen for the log: type and app, as the leave was decided on them. */
    fun describeWindows(): String = runCatching {
        windows.joinToString { "${it.type}:${DebugLog.name(it.root?.packageName ?: it.title, "window")}" }
    }.getOrDefault("unknown")

    /** The app last looked at for a return, so each app in front is logged once. */
    private var lastSeenApp: String? = null

    private fun switchTo(id: String): Boolean = runCatching { softKeyboardController.switchToInputMethod(id) }.getOrDefault(false)

    /**
     * Back in an app on the list (KeyboardService.returnTo), within its 5 minutes: our keyboard again. The app is the
     * one whose window just came to the front ([shown], from the event), else the one with the focused field or the
     * active window, which right after a switch of apps may still be the one left. Each app looked at is logged once.
     */
    private fun returnToApp(shown: String?) {
        val returnTo = KeyboardService.returnTo
        if (returnTo.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        returnTo.values.removeAll { it < now }
        val candidates = listOfNotNull(shown, focusedField()?.packageName?.toString(), rootInActiveWindow?.packageName?.toString())
        val app = candidates.firstOrNull { it in returnTo }
        if (app == null) {
            val seen = candidates.firstOrNull()
            if (seen != null && seen != lastSeenApp && returnTo.isNotEmpty()) DebugLog.add("dot: in ${DebugLog.name(seen, "an app")}, our keyboard returns to ${DebugLog.name(returnTo.keys, "${returnTo.size} apps")}")
            lastSeenApp = seen
            return
        }
        lastSeenApp = app
        returnTo.remove(app)
        val current = KeyboardService.current(this)
        if (current == KeyboardService.id(this)) return
        // Shown at once only in place of a keyboard already up: with none up, it waits for the field like any keyboard.
        if (runCatching { getWindows().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)) {
            KeyboardService.showRequestedAt = SystemClock.uptimeMillis()
        }
        val switched = switchTo(KeyboardService.id(this))
        DebugLog.add("dot: back in ${DebugLog.name(app, "an app")}, our keyboard returns: ${if (switched) "done" else "failed"}")
        if (!switched) KeyboardService.showRequestedAt = 0L
    }

    /** After a keyboard switch: if no keyboard shows a moment later, a click on the field brings one up. */
    fun checkKeyboardShown() {
        fieldBeforeSwitch = fieldBeforeSwitch ?: focusedField()
        appBeforeSwitch = appBeforeSwitch ?: fieldBeforeSwitch?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString()
        main.removeCallbacks(showKeyboard)
        showUntil = SystemClock.uptimeMillis() + SHOW_WAIT_MS
        main.postDelayed(showKeyboard, SHOW_CHECK_MS)
    }

    /**
     * Auto-send where the field has no send action: presses the send button next to the field, found by its label,
     * while the field still holds [text]. The app may turn the button on, or put it in place of another (a like, a
     * voice message), only once it sees the text: the first look waits a frame, and it is looked for a few times.
     */
    fun pressSend(app: String, text: String) {
        main.postDelayed({ trySend(app, text, SEND_TRIES) }, SEND_FIRST_MS)
    }

    private fun trySend(app: String, text: String, tries: Int) {
        val field = focusedField()?.takeIf { it.packageName?.toString() == app && currentText(it).contains(text.trim()) }
            ?: return DebugLog.add("dot: the text is not in the field of ${DebugLog.name(app, "an app")}, not sent")
        val button = sendButton(field)
        if (button != null) {
            DebugLog.add("dot: pressed send in ${DebugLog.name(app, "an app")}: ${button.performAction(AccessibilityNodeInfo.ACTION_CLICK)}")
        } else if (tries > 1) {
            main.postDelayed({ trySend(app, text, tries - 1) }, SEND_RETRY_MS)
        } else {
            DebugLog.add("dot: no send button in ${DebugLog.name(app, "an app")}, not sent")
            Trace.current?.let { trace ->
                val labels = appRoots(app).flatMap { nodes(it) }.filter { it.isClickable && it.isVisibleToUser }
                    .mapNotNull { it.contentDescription?.toString() }.distinct().take(SEND_LOG_LABELS).toList()
                trace.text("buttons", labels.toString())
            }
        }
    }

    private fun rememberField() {
        fieldBeforeSwitch = focusedField()
        appBeforeSwitch = fieldBeforeSwitch?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString()
    }

    /** The text field of [app] when none has the focus: the one with [like]'s view id, else the lowest visible one. */
    private fun fieldIn(app: String, like: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val fields = appRoots(app).flatMap { nodes(it) }.filter { it.isEditable && it.isVisibleToUser }.toList()
        val id = like?.viewIdResourceName
        return fields.firstOrNull { id != null && it.viewIdResourceName == id }
            ?: fields.maxByOrNull { android.graphics.Rect().also(it::getBoundsInScreen).bottom }
    }

    /** The visible, enabled button labelled "send", or its clickable parent, nearest to [field] in the field's window. */
    private fun sendButton(field: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val root = field.window?.root ?: generateSequence(field) { it.parent }.last()
        val at = android.graphics.Rect().also(field::getBoundsInScreen)
        return nodes(root)
            .filter { it.isVisibleToUser && !it.isEditable && isSendLabel(it.contentDescription) }
            .mapNotNull { node -> generateSequence(node) { it.parent }.take(SEND_PARENTS).firstOrNull { it.isClickable } }
            .filter { it.isEnabled }
            .minByOrNull { gap(at, android.graphics.Rect().also(it::getBoundsInScreen)) }
    }

    /** How far apart two places on screen are, squared; 0 when they touch or overlap. */
    private fun gap(a: android.graphics.Rect, b: android.graphics.Rect): Long {
        val x = maxOf(0, b.left - a.right, a.left - b.right).toLong()
        val y = maxOf(0, b.top - a.bottom, a.top - b.bottom).toLong()
        return x * x + y * y
    }

    private fun appRoots(app: String): Sequence<AccessibilityNodeInfo> =
        runCatching { getWindows() }.getOrDefault(emptyList()).asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() == app }

    private fun nodes(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root)
        for (i in 0 until root.childCount) root.getChild(i)?.let { yieldAll(nodes(it)) }
    }

    /**
     * From our keyboard back to the one set in the settings, else the one used before it as Android records it, else
     * the other one seen last.
     * The keyboard's own switch key comes here too: some chat apps keep the other
     * keyboard hidden after the keyboard switches itself, but show it after this switch.
     */
    fun switchBack(): Boolean {
        val target = prefs.switchBackKeyboard.ifEmpty { null } ?: KeyboardService.previousKeyboard(this, KeyboardService.id(this))
            ?: lastOtherKeyboard ?: return false
        // Taken before the switch: after it, the field may no longer have the focus.
        rememberField()
        val switched = switchTo(target)
        DebugLog.add("dot: to ${DebugLog.name(target, "another keyboard")}: ${if (switched) "done" else "failed"}")
        if (!switched) {
            switcher.bubble(getString(R.string.switch_failed))
            return false
        }
        checkKeyboardShown()
        return true
    }

    override fun onDictationChanged() {
        val state = Dictation.state
        if (state == Dictation.State.RECORDING && lastState == Dictation.State.IDLE) {
            startedId = Dictation.current
            startedField = fieldFor(startedId?.let(BufferStore::get)?.app)
        }
        // A dictation started elsewhere (the keyboard) is not the dot's own, even while its last one still waits for its field.
        if (state != Dictation.State.IDLE && !micStarting && Dictation.current != micDictationId) micDictation = false
        if (state == Dictation.State.IDLE && lastState != Dictation.State.IDLE) {
            // Shown only by a dot in sight, which then stays for it; a successful insert has no message.
            Dictation.message?.let { recorder.bubble(it) }
            // The dot's own dictation made outside any field waits: our keyboard offers it as Insert here whenever it
            // comes up next. The dot never brings the keyboard up for it, nor goes looking for a field.
        }
        lastState = state
        render()
    }

    // Dictation.Target: insertion through Accessibility when our keyboard is not the one in front

    override fun knowsField(field: String): Boolean = field.startsWith(FIELD_PREFIX)

    // A dictation goes in only into the very element it began in, whichever keyboard is up; the app is checked as well.
    override fun insert(text: String, app: String?, field: String?, id: Long?): Boolean {
        val node = fieldFor(app)
            ?: return false.also { DebugLog.add("dot: no field of ${DebugLog.name(app, "an app")} found, in front: ${DebugLog.name(rootInActiveWindow?.packageName, "another app")}") }
        if (node.isPassword) return false
        if (app != null && node.packageName?.toString() != app) return false.also { DebugLog.add("dot: the field is in another app") }
        if (id != null && (id != startedId || node != startedField)) {
            DebugLog.add("dot: not the field the dictation began in, not inserting")
            return false
        }
        // Some editors read their text and cursor only once they have the focus.
        if (!node.isFocused) {
            DebugLog.add("dot: no field has the focus, using the input field of ${DebugLog.name(app, "an app")}")
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        node.refresh()
        val current = currentText(node)
        val (start, end) = FieldText.insertAt(current.length, node.textSelectionStart, node.textSelectionEnd)
        val insertion = separatorBefore(current.substring(0, start), prefs.separator) + text +
            separatorAfter(current.substring(end), prefs.separator)
        val whole = current.substring(0, start) + insertion + current.substring(end)
        val written = setText(node, whole, start + insertion.length) ||
            // The editor reshaped the text (emoji, spacing) but it is there: pasting would add it twice.
            FieldText.pasted(current, currentText(node), insertion)
        if (!written && !paste(node, current, insertion, start, end)) return false
        DebugLog.add("dot: inserted into ${describe(node)}")
        val insertedAt = SystemClock.uptimeMillis()
        main.postDelayed({ checkKept(node, insertion, insertedAt) }, KEPT_CHECK_MS)
        return true
    }

    /**
     * A moment after an insertion: a field that took the text and then dropped it gets it pasted. Only that very field,
     * and not after a tap in its app: the person may have sent the text, or gone to another chat.
     */
    private fun checkKept(node: AccessibilityNodeInfo, insertion: String, insertedAt: Long) {
        val app = node.packageName?.toString() ?: return
        if (!node.stillField()) return DebugLog.add("dot: the field is gone after the insertion")
        if (clickedApp == app && clickedAt >= insertedAt) return
        val now = currentText(node)
        if (now.contains(insertion.trim())) return
        DebugLog.add("dot: ${describe(node)} dropped the inserted text, pasting it")
        paste(node, now, insertion, now.length, now.length)
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        return "${node.className?.toString()?.substringAfterLast('.')} ${DebugLog.name(node.viewIdResourceName, "field")} at ${bounds.toShortString()}, " +
            "focused ${node.isFocused}, ${currentText(node).length} chars"
    }

    override val incognito: Boolean get() = false

    /** The field as the dot can tell it apart: its app, view id, hint and input type. */
    private fun signature(node: AccessibilityNodeInfo): String =
        listOf(FIELD_PREFIX + node.packageName, node.viewIdResourceName, node.hintText, node.inputType).joinToString("|")

    /**
     * A node kept from before, read again, still a text field on screen: some chat apps rebuild their views,
     * and a kept node may then stand for another one, their menu button.
     */
    private fun AccessibilityNodeInfo.stillField(): Boolean = refresh() && isEditable && isVisibleToUser

    private fun focusedField(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    /**
     * The field a dictation goes into: the focused one, else the input field of [app] if it is in front. Some chat apps
     * drop the focus while no keyboard is up; away from that app, the text waits for Insert here.
     */
    private fun fieldFor(app: String?): AccessibilityNodeInfo? =
        focusedField() ?: app?.takeIf { it == rootInActiveWindow?.packageName?.toString() }?.let { fieldIn(it, null) }

    private fun currentText(field: AccessibilityNodeInfo): String = FieldText.readable(field.text, field.isShowingHintText)

    /**
     * Sets the whole text, waits until the field shows it, then moves the cursor. Compose fields show a write a
     * few frames late, and a cursor move sent before that makes them rebuild from the old text; some editors
     * accept the action and ignore it, so the write is tried twice.
     */
    private fun setText(field: AccessibilityNodeInfo, value: String, cursor: Int): Boolean {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        val landed = (1..2).any {
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) && waitFor(field) { FieldText.landed(value, it) }
        }
        if (!landed) {
            DebugLog.add("dot: the field ignored the inserted text")
            return false
        }
        select(field, cursor, cursor)
        return true
    }

    /**
     * The fallback when a field refuses a whole-text write: paste [insertion] over start..end through the clipboard.
     * Some Compose fields offer paste only as a custom action. The user's own clip comes back 30 s later, unless
     * something else was copied meanwhile. It can be read only while our keyboard is the selected one: with another,
     * no paste, or the person's clip (a code, a number) would be lost.
     */
    private fun paste(field: AccessibilityNodeInfo, before: String, insertion: String, start: Int, end: Int): Boolean {
        if (KeyboardService.current(this) != KeyboardService.id(this)) {
            return false.also { DebugLog.add("dot: the clipboard cannot be read with another keyboard, no paste") }
        }
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return false
        val previous = runCatching { clipboard.primaryClip }.getOrNull()
        val ours = ClipData.newPlainText("dictation", insertion)
        // Keeps the dictated text out of the system's clipboard preview.
        ours.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(ours)
        select(field, start, end)
        val action = field.actionList.firstOrNull { it.id != AccessibilityNodeInfo.ACTION_PASTE && it.label?.contains("paste", ignoreCase = true) == true }
            ?.id ?: AccessibilityNodeInfo.ACTION_PASTE
        val done = field.performAction(action) && waitFor(field) { FieldText.pasted(before, it, insertion) }
        DebugLog.add(if (done) "dot: inserted by paste" else "dot: paste did not land either")
        main.postDelayed({
            val now = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()
            if (now != insertion) return@postDelayed
            if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
        }, if (done) CLIP_RESTORE_MS else 0L)
        return done
    }

    private fun select(field: AccessibilityNodeInfo, start: Int, end: Int) {
        field.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            },
        )
    }

    /** Re-reads the field every 25 ms for up to 400 ms until [done] holds for its text. */
    private fun waitFor(field: AccessibilityNodeInfo, done: (String) -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + LAND_WAIT_MS
        while (true) {
            field.refresh()
            if (done(currentText(field))) return true
            if (SystemClock.uptimeMillis() >= until) return false
            SystemClock.sleep(LAND_POLL_MS)
        }
    }


    companion object {
        /** Marks the dot's field signatures, which only the dot can check. */
        const val FIELD_PREFIX = "dot:"

        /** The switch dot: over any keyboard, switching both ways. */
        internal fun switchMode(ourKeyboard: Boolean, otherKeyboard: Boolean): DotView.Mode? =
            DotView.Mode.SWITCH.takeIf { ourKeyboard || otherKeyboard }

        /**
         * The record dot. Its own dictation it shows wherever the user is; one started on the keyboard it shows
         * while it records, is paused or transcribed with the keyboard gone, whatever the switches say, so a dictation
         * is never out of sight. Turned on ([idleOn]) it waits as an idle dot, stepping aside over our own keyboard,
         * which has every control.
         */
        internal fun recordMode(
            state: Dictation.State,
            ownDictation: Boolean,
            ourKeyboard: Boolean,
            idleOn: Boolean,
        ): DotView.Mode? {
            val busy = busyMode(state)
            return when {
                busy != null && (ownDictation || !ourKeyboard) -> busy
                ourKeyboard -> null
                idleOn -> DotView.Mode.IDLE
                else -> null
            }
        }

        private fun busyMode(state: Dictation.State): DotView.Mode? = when (state) {
            Dictation.State.RECORDING -> DotView.Mode.RECORDING
            Dictation.State.PAUSED -> DotView.Mode.PAUSED
            Dictation.State.PROCESSING -> DotView.Mode.PROCESSING
            Dictation.State.IDLE -> null
        }

        /**
         * A dot's menu: at rest the dot's own switch and the settings; over a dictation what the keyboard's keys do,
         * with Show keyboard for one started there. A finished text waiting for its field has nothing to cancel.
         */
        internal fun menuItems(icon: Icon, mode: DotView.Mode?, keyboardDictation: Boolean, transcribing: Boolean): List<DotView.Item> {
            val keyboard = listOfNotNull(DotView.Item.SHOW_KEYBOARD.takeIf { keyboardDictation })
            return when {
                icon == Icon.SWITCH || mode == DotView.Mode.IDLE -> listOf(DotView.Item.HIDE, DotView.Item.SETTINGS)
                mode == DotView.Mode.RECORDING -> keyboard + DotView.Item.PAUSE + DotView.Item.CANCEL
                mode == DotView.Mode.PAUSED -> keyboard + DotView.Item.RESUME + DotView.Item.CANCEL
                mode == DotView.Mode.PROCESSING && transcribing -> listOf(DotView.Item.CANCEL)
                else -> emptyList()
            }
        }

        /** Where each dot first rests, as a share of the screen height: apart, so neither hides the other; the record dot on top. */
        internal fun defaultY(icon: Icon): Float = when (icon) {
            Icon.RECORD -> 0.3f
            Icon.SWITCH -> 0.4f
        }

        /**
         * The window's side and its offset from that side, for an icon whose round end ([slot] wide) is centred
         * at [centre]: anchored by the end nearer the screen edge, and never past an edge by more than [overhang]
         * (the window's room for the shadow, which may reach past it).
         */
        internal fun windowX(centre: Int, slot: Int, margin: Int, screen: Int, overhang: Int = 0): Pair<Boolean, Int> {
            val right = centre > screen / 2
            val x = if (right) screen - centre - slot / 2 - margin else centre - slot / 2 - margin
            return right to x.coerceIn(-overhang, (screen - slot - margin * 2 + overhang).coerceAtLeast(-overhang))
        }

        /**
         * Where an icon dropped with its shape between [left] and [right] rests: the centre of its round end on
         * the side of the screen it was dropped on, so placing it there again does not move it.
         */
        internal fun restingCentre(left: Int, right: Int, slot: Int, screen: Int): Int =
            if ((left + right) / 2 > screen / 2) right - slot / 2 else left + slot / 2

        /** The first resting place of an icon never dragged: the edge it rests on. */
        internal fun edgeCentre(right: Boolean, slot: Int, margin: Int, screen: Int): Int =
            if (right) screen - margin - slot / 2 else margin + slot / 2

        /**
         * Whether a button's whole label is "send" or "send message", in English, Russian or Ukrainian: "Send a like",
         * "Send voice message" or "Resend" are other buttons.
         */
        internal fun isSendLabel(label: CharSequence?): Boolean =
            label?.toString()?.trim()?.lowercase(Locale.ROOT)?.replace(SPACES, " ") in SEND_LABELS

        private val SEND_LABELS = setOf("send", "send message", "отправить", "отправить сообщение", "надіслати", "надіслати повідомлення")
        private val SPACES = Regex("\\s+")

        /** The first look for the send button waits a frame, for the app to take the text in. */
        private const val SEND_FIRST_MS = 16L
        private const val SEND_TRIES = 6
        private const val SEND_RETRY_MS = 150L
        private const val SEND_PARENTS = 4
        private const val SEND_LOG_LABELS = 12
        private const val BUBBLE_MS = 2_500L

        /** How long the bubble takes to fade out, as DotView's own. */
        private const val BUBBLE_FADE_MS = 200L

        /** A dot rests this long after the last touch. */
        private const val REST_MS = 3_000L

        /** The menu's fade out and, after Hide the dot, the dot's own, as DotMenu's and DotView's. */
        private const val MENU_CLOSE_MS = 140L
        private const val VANISH_MS = 160L

        /** A touch on the dot this soon after one outside closed the menu is that same touch. */
        private const val MENU_GRACE_MS = 400L

        /** The app's Settings at the Dots group, as MainActivity names it. */
        private const val SETTINGS_SCREEN = "SETTINGS_DOTS"

        /** How long a swiped icon takes to fly to the edge, or back from it; a fast throw takes no less than the least. */
        private const val FLY_MS = 320L
        private const val FLY_MIN_MS = 120L

        /** How long the dot takes to slide out of the edge, as DotView's own fade, before its window flies home. */
        private const val TUCK_MS = 180L

        /** What a dot shows while a dictation goes on. */
        private val BUSY = setOf(DotView.Mode.RECORDING, DotView.Mode.PAUSED, DotView.Mode.PROCESSING)

        /** A second tap this soon after one that acted is the same tap. */
        private const val DEBOUNCE_MS = 450L
        private const val SHOW_CHECK_MS = 100L
        private const val SHOW_WAIT_MS = 1_500L
        private const val KEYBOARD_RECHECK_MS = 300L
        private const val SWITCH_GAP_MS = 2_500L
        private const val LAND_WAIT_MS = 400L
        private const val LAND_POLL_MS = 25L
        private const val CLIP_RESTORE_MS = 30_000L
        private const val KEPT_CHECK_MS = 1_000L

        /** The running service, if the user turned it on. */
        var instance: DotService? = null
            private set
    }
}
