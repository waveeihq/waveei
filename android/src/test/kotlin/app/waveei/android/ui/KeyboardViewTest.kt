package app.waveei.android.ui

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import app.waveei.android.Dictation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** How the keyboard panel reacts to touches and to TalkBack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class KeyboardViewTest {
    private val calls = ArrayList<String>()

    private val actions = object : KeyboardView.Actions {
        override fun onMicDown() { calls += "micDown" }
        override fun onMicTap() { calls += "micTap" }
        override fun onCancel() { calls += "cancel" }
        override fun onPause() { calls += "pause" }
        override fun onRetry() { calls += "retry" }
        override fun onBackspace(word: Boolean) { calls += "backspace" }
        override fun onSpace() { calls += "space" }
        override fun onEnter() { calls += "enter" }
        override fun onSwitch() { calls += "switch" }
        override fun onSwitchLong() { calls += "switchLong" }
        override fun onStyle() { calls += "style" }
        override fun onTranslate() { calls += "translate" }
        override fun onStatus() { calls += "status" }
        override fun onInsertHere() { calls += "insertHere" }
        override fun onContinueKept() { calls += "continue" }
        override fun onPaste() { calls += "paste" }
        override fun onPasteDismiss() { calls += "dismiss" }
        override fun onClipboard() { calls += "clipboard" }
        override fun onEmoji() { calls += "emoji" }
        override fun onHide() { calls += "hide" }
        override fun onSettings() { calls += "settings" }
        override fun onVocabulary() { calls += "vocabulary" }
        override fun onAutoSend() { calls += "autoSend" }
        override fun onAutoSendLong() { calls += "autoSendLong" }
        override fun onClearField() { calls += "clearField" }
        override fun onCloseList() { calls += "closeList" }
    }

    private fun keyboard(
        state: Dictation.State = Dictation.State.IDLE,
        leftHanded: Boolean = false,
        canRetry: Boolean = false,
    ) =
        KeyboardView(ApplicationProvider.getApplicationContext(), actions).apply {
            this.leftHanded = leftHanded
            model = KeyboardView.Model(state = state, canRetry = canRetry)
            val width = (411 * resources.displayMetrics.density).toInt()
            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            layout(0, 0, measuredWidth, measuredHeight)
        }

    private fun KeyboardView.tap(key: KeyboardView.Key) {
        val r = bounds(key) ?: error("$key is not shown")
        val time = SystemClock.uptimeMillis()
        onTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0))
        onTouchEvent(MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0))
    }

    private fun KeyboardView.talkBack(key: KeyboardView.Key, action: Int) =
        accessibilityNodeProvider!!.performAction(key.ordinal, action, null)

    @Test
    fun holdingTheMicIsPushToTalk() {
        val view = keyboard()
        val r = view.bounds(KeyboardView.Key.MIC)!!
        val time = SystemClock.uptimeMillis()
        view.onTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, r.centerX(), r.centerY(), 0))
        view.onTouchEvent(MotionEvent.obtain(time, time + 900, MotionEvent.ACTION_UP, r.centerX(), r.centerY(), 0))
        assertEquals(listOf("micDown", "micTap"), calls)
    }

    @Test
    fun aTapStartsAndDoesNotStopOnRelease() {
        keyboard().tap(KeyboardView.Key.MIC)
        assertEquals(listOf("micDown"), calls)
    }

    @Test
    fun aTapWhileRecordingOrPausedStops() {
        keyboard(Dictation.State.RECORDING).tap(KeyboardView.Key.MIC)
        keyboard(Dictation.State.PAUSED).tap(KeyboardView.Key.MIC)
        assertEquals(listOf("micTap", "micTap"), calls)
    }

    @Test
    fun startThenStopWithTwoTaps() {
        val view = keyboard()
        view.tap(KeyboardView.Key.MIC)
        view.model = view.model.copy(state = Dictation.State.RECORDING)
        view.tap(KeyboardView.Key.MIC)
        assertEquals(listOf("micDown", "micTap"), calls)
    }

    @Test
    fun aDisabledMicDoesNotStart() {
        val view = keyboard()
        view.model = view.model.copy(micEnabled = false)
        view.tap(KeyboardView.Key.MIC)
        assertEquals(listOf("micTap"), calls)
    }

    @Test
    fun pauseAndResumeAreOneKey() {
        keyboard(Dictation.State.RECORDING).tap(KeyboardView.Key.PAUSE)
        keyboard(Dictation.State.PAUSED).tap(KeyboardView.Key.PAUSE)
        // Idle, the key does nothing.
        keyboard().tap(KeyboardView.Key.PAUSE)
        assertEquals(listOf("pause", "pause"), calls)
    }

    @Test
    fun retryTakesThePlaceOfPause() {
        val idle = keyboard(canRetry = true)
        assertNull(idle.bounds(KeyboardView.Key.PAUSE))
        idle.tap(KeyboardView.Key.RETRY)
        val recording = keyboard(Dictation.State.RECORDING, canRetry = true)
        assertNull(recording.bounds(KeyboardView.Key.RETRY))
        recording.tap(KeyboardView.Key.PAUSE)
        assertEquals(listOf("retry", "pause"), calls)
    }

    @Test
    fun cancelActsOnlyOnADictation() {
        keyboard().tap(KeyboardView.Key.CANCEL)
        keyboard(Dictation.State.PROCESSING).tap(KeyboardView.Key.CANCEL)
        assertEquals(listOf("cancel"), calls)
    }

    @Test
    fun enterIsInTheMicRow() {
        for (left in listOf(false, true)) {
            val view = keyboard(leftHanded = left)
            val mic = view.bounds(KeyboardView.Key.MIC)!!
            val enter = view.bounds(KeyboardView.Key.ENTER)!!
            assertTrue("Enter is centred with the mic", abs(mic.centerY() - enter.centerY()) < 1f)
            assertTrue("Enter is on the outer side", if (left) enter.centerX() < mic.centerX() else enter.centerX() > mic.centerX())
            view.tap(KeyboardView.Key.ENTER)
        }
        assertEquals(listOf("enter", "enter"), calls)
    }

    @Test
    fun thePasteOfferTakesTheTopRow() {
        val view = keyboard()
        view.model = view.model.copy(paste = "Copied text")
        assertNull("the toolbar steps aside", view.bounds(KeyboardView.Key.SEND))
        val clipboard = view.bounds(KeyboardView.Key.CLIPBOARD)!!
        val paste = view.bounds(KeyboardView.Key.PASTE)!!
        val close = view.bounds(KeyboardView.Key.PASTE_CLOSE)!!
        assertTrue("the clipboard key stays on the left", clipboard.centerX() < paste.centerX())
        assertTrue("× is at the right end of the pill", close.centerX() > paste.centerX())
        view.tap(KeyboardView.Key.PASTE)
        view.tap(KeyboardView.Key.PASTE_CLOSE)
        view.tap(KeyboardView.Key.CLIPBOARD)
        assertEquals(listOf("paste", "dismiss", "clipboard"), calls)
    }

    @Test
    fun insertHereAndContinueTakeTheStatusLine() {
        val view = keyboard()
        view.model = view.model.copy(paste = "Copied text", insertHere = true)
        assertNull("no paste offer over Insert here", view.bounds(KeyboardView.Key.PASTE))
        view.tap(KeyboardView.Key.STATUS)
        view.model = view.model.copy(insertHere = false, paste = null, continueKept = "Continue recording 0:42")
        view.tap(KeyboardView.Key.STATUS)
        view.model = view.model.copy(continueKept = null)
        view.tap(KeyboardView.Key.STATUS)
        assertEquals(listOf("insertHere", "continue", "status"), calls)
    }

    @Test
    fun holdsHaveTalkBackActions() {
        val view = keyboard()
        view.talkBack(KeyboardView.Key.STATUS, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        view.talkBack(KeyboardView.Key.SEND, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        view.talkBack(KeyboardView.Key.SWITCH, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        view.talkBack(KeyboardView.Key.GRABBER, AccessibilityNodeInfo.ACTION_CLICK)
        view.model = view.model.copy(paste = "Copied text")
        view.talkBack(KeyboardView.Key.PASTE, AccessibilityNodeInfo.ACTION_LONG_CLICK)
        assertEquals(listOf("clipboard", "autoSendLong", "switchLong", "hide", "clipboard"), calls)
    }

    @Test
    fun theEditingKeysAreUnderTheHand() {
        for (left in listOf(false, true)) {
            val view = keyboard(leftHanded = left)
            val mic = view.bounds(KeyboardView.Key.MIC)!!
            val enter = view.bounds(KeyboardView.Key.ENTER)!!
            val backspace = view.bounds(KeyboardView.Key.BACKSPACE)!!
            assertTrue("Backspace is right above Enter", abs(backspace.centerX() - enter.centerX()) < 1f && backspace.bottom <= enter.top + 1f)
            val hand = { key: KeyboardView.Key -> view.bounds(key)!!.centerX().let { if (left) view.width - it else it } > mic.centerX() }
            for (key in listOf(KeyboardView.Key.CANCEL, KeyboardView.Key.ENTER, KeyboardView.Key.BACKSPACE, KeyboardView.Key.SWITCH, KeyboardView.Key.SEND)) {
                assertTrue("$key is on the hand's side", hand(key))
            }
            assertTrue("The switch is beside the space bar", abs(view.bounds(KeyboardView.Key.SWITCH)!!.centerY() - view.bounds(KeyboardView.Key.SPACE)!!.centerY()) < 1f)
            view.tap(KeyboardView.Key.BACKSPACE)
            view.tap(KeyboardView.Key.SWITCH)
        }
        assertEquals(listOf("backspace", "switch", "backspace", "switch"), calls)
    }

    @Test
    fun theMicRowKeepsItsOrderAroundTheMic() {
        val view = keyboard()
        val order = listOf(KeyboardView.Key.SETTINGS, KeyboardView.Key.PAUSE, KeyboardView.Key.MIC, KeyboardView.Key.CANCEL, KeyboardView.Key.ENTER)
            .map { view.bounds(it)!!.centerX() }
        assertEquals(order.sorted(), order)
        val enter = view.bounds(KeyboardView.Key.ENTER)!!
        assertEquals("Enter is round like the other keys", enter.width(), enter.height(), 1f)
    }

    @Test
    fun theOuterKeysOpenTheApp() {
        val view = keyboard()
        view.tap(KeyboardView.Key.SETTINGS)
        view.tap(KeyboardView.Key.VOCABULARY)
        view.tap(KeyboardView.Key.SEND)
        view.tap(KeyboardView.Key.CLEAR)
        assertEquals(listOf("settings", "vocabulary", "autoSend", "clearField"), calls)
    }

    @Test
    fun clipboardAndEmojiAreOppositeTheHand() {
        for (left in listOf(false, true)) {
            val view = keyboard(leftHanded = left)
            val mic = view.bounds(KeyboardView.Key.MIC)!!
            for (key in listOf(KeyboardView.Key.CLIPBOARD, KeyboardView.Key.EMOJI)) {
                val x = view.bounds(key)!!.centerX().let { if (left) view.width - it else it }
                assertTrue("$key is opposite the hand", x < mic.centerX())
            }
            view.tap(KeyboardView.Key.CLIPBOARD)
            view.tap(KeyboardView.Key.EMOJI)
        }
        assertEquals(listOf("clipboard", "emoji", "clipboard", "emoji"), calls)
    }

    @Test
    fun theCapsuleIsSymmetricAboutTheMicForEitherHand() {
        for (left in listOf(false, true)) {
            val view = keyboard(leftHanded = left)
            val mic = view.bounds(KeyboardView.Key.MIC)!!
            val style = view.bounds(KeyboardView.Key.STYLE)!!
            val translate = view.bounds(KeyboardView.Key.TRANSLATE)!!
            assertEquals(style.width(), translate.width(), 1f)
            assertEquals(mic.centerX(), (style.left + translate.right) / 2, 1f)
            assertTrue("Style stays first", style.centerX() < translate.centerX())
            view.tap(KeyboardView.Key.STYLE)
            view.tap(KeyboardView.Key.TRANSLATE)
        }
        assertEquals(listOf("style", "translate", "style", "translate"), calls)
    }

    @Test
    fun anOpenCardClosesOnATouchElsewhere() {
        val view = keyboard()
        view.model = view.model.copy(open = KeyboardView.Key.STYLE)
        view.tap(KeyboardView.Key.SPACE)
        view.tap(KeyboardView.Key.TRANSLATE)
        assertEquals(listOf("closeList", "translate"), calls)
    }

    @Test
    fun anOpenPanelCoversTheKeyboardUnderTheTopRow() {
        for (open in listOf(KeyboardView.Key.CLIPBOARD, KeyboardView.Key.EMOJI)) {
            val view = keyboard()
            val space = view.bounds(KeyboardView.Key.SPACE)!!
            view.model = view.model.copy(open = open)
            for (key in listOf(KeyboardView.Key.STATUS, KeyboardView.Key.MIC, KeyboardView.Key.ENTER, KeyboardView.Key.CLEAR, KeyboardView.Key.SPACE, KeyboardView.Key.SWITCH, KeyboardView.Key.VOCABULARY)) assertNull("$key under $open", view.bounds(key))
            for (key in listOf(KeyboardView.Key.CLIPBOARD, KeyboardView.Key.EMOJI, KeyboardView.Key.SEND)) assertNotNull("$key over $open", view.bounds(key))
            assertTrue("the panel lies under the top row", view.middleTop >= view.bounds(KeyboardView.Key.CLIPBOARD)!!.bottom)
            assertTrue("the panel reaches past the bottom row", view.middleBottom >= space.bottom)
        }
    }

    @Test
    fun keysDoNotOverlap() {
        for (left in listOf(false, true)) for (canRetry in listOf(false, true)) for (paste in listOf(null, "Copied text")) {
            val view = keyboard(leftHanded = left, canRetry = canRetry)
            view.model = view.model.copy(paste = paste)
            val keys = KeyboardView.Key.entries.mapNotNull { key -> view.bounds(key)?.let { key to it } }
            for ((a, ra) in keys) for ((b, rb) in keys) {
                if (a < b) assertTrue("$a overlaps $b (left $left)", !android.graphics.RectF.intersects(ra, rb))
            }
        }
    }
}
