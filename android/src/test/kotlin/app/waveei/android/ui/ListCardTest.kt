package app.waveei.android.ui

import android.app.Activity
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** The Style card under a finger held from Style: the row under it lights, and letting go there picks it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ListCardTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val actions = object : KeyboardView.Actions {
        override fun onMicDown() = Unit
        override fun onMicTap() = Unit
        override fun onCancel() = Unit
        override fun onPause() = Unit
        override fun onRetry() = Unit
        override fun onBackspace(word: Boolean) = Unit
        override fun onSpace() = Unit
        override fun onEnter() = Unit
        override fun onSwitch() = Unit
        override fun onSwitchLong() = Unit
        override fun onStyle() = Unit
        override fun onTranslate() = Unit
        override fun onStatus() = Unit
        override fun onInsertHere() = Unit
        override fun onPaste() = Unit
        override fun onPasteDismiss() = Unit
        override fun onClipboard() = Unit
        override fun onEmoji() = Unit
        override fun onHide() = Unit
        override fun onSettings() = Unit
        override fun onVocabulary() = Unit
        override fun onAutoSend() = Unit
        override fun onClearField() = Unit
    }
    private val root = FrameLayout(activity)
    private val keyboard = KeyboardView(activity, actions)
    private val card = ListCard(activity)
    private val picked = ArrayList<String>()

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun open() {
        root.addView(keyboard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(card, FrameLayout.LayoutParams(0, 0))
        activity.setContentView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        idle(100)
        val items = listOf("Off", "Message", "Formal").map { name -> ListCard.Item(name, null, name == "Message") { picked += name } }
        card.show(items, ListCard.Pinned("gear-six", "Style settings") { picked += "settings" }, RectF().also(keyboard::cardFrame), keyboard.notchX(KeyboardView.Key.STYLE), keyboard, KeyboardColors.DAY, keyboard.unit)
        idle(400)
    }

    private fun all(view: View = card): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    /** The row holding [label], and its centre on the screen. */
    private fun row(label: String): Pair<View, Pair<Float, Float>> {
        val row = all().first { it is TextView && it.text.toString() == label }.parent as View
        val at = IntArray(2).also(row::getLocationOnScreen)
        return row to ((at[0] + row.width / 2f) to (at[1] + row.height / 2f))
    }

    @Test
    fun slideLightsTheRowUnderTheFingerAndLetGoPicksIt() {
        open()
        val (off, offAt) = row("Off")
        val (formal, formalAt) = row("Formal")
        card.slideTo(offAt.first, offAt.second)
        assertTrue("Off lights under the finger", off.isPressed)
        card.slideTo(formalAt.first, formalAt.second)
        assertFalse("Off goes out as the finger leaves it", off.isPressed)
        assertTrue("Formal lights", formal.isPressed)
        assertTrue(card.liftAt(formalAt.first, formalAt.second))
        assertEquals(listOf("Formal"), picked)
        assertFalse(formal.isPressed)
    }

    @Test
    fun letGoOffTheRowsPicksNothing() {
        open()
        val (_, at) = row("Off")
        card.slideTo(at.first, at.second)
        assertFalse(card.liftAt(at.first, at.second - 400 * activity.resources.displayMetrics.density))
        assertTrue(picked.isEmpty())
        assertTrue("the card stays open", card.shown)
    }

    @Test
    fun theSettingsRowUnderTheHairlineIsPickedToo() {
        open()
        val (_, at) = row("Style settings")
        assertTrue(card.liftAt(at.first, at.second))
        assertEquals(listOf("settings"), picked)
    }
}
