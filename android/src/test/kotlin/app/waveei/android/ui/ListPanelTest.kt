package app.waveei.android.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import app.waveei.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** The Clipboard | History panel's motion: delete, clear and insert wait for their animation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ListPanelTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val panel = ListPanel(activity)
    private val unit = activity.resources.displayMetrics.density
    private val calls = ArrayList<String>()

    @Before
    fun attach() {
        activity.setContentView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (208 * unit).toInt()))
        show()
    }

    private fun show(tab: ListPanel.Tab = ListPanel.Tab.CLIPBOARD, rows: List<ListPanel.Row> = listOf(row("a"), row("b"))) {
        panel.show(tab, rows, "empty", { calls += "tab $it" }, { calls += "clear" }, KeyboardColors.DAY, unit, false)
        idle(0)
    }

    private fun row(text: String) = ListPanel.Row(
        text, "12:00",
        main = ListPanel.Action("Insert") { calls += "insert $text" },
        copy = {},
        delete = { calls += "delete $text" },
    )

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun all(view: View = panel): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    private fun described(text: String) = all().filter { it.contentDescription == text }

    private fun labelled(text: String) = all().filter { it is TextView && it.text.toString() == text }

    @Test
    fun deleteRunsOnceTheRowHasFolded() {
        val bin = described(activity.getString(R.string.delete)).first()
        val row = bin.parent as View
        bin.performClick()
        bin.performClick()
        assertTrue("the row folds first", calls.isEmpty())
        idle(1_000)
        assertEquals("deleted once, folded", listOf("delete a"), calls)
        assertEquals(0, row.height)
    }

    @Test
    fun clearWaitsForTheRowsToFade() {
        val clear = described(activity.getString(R.string.clear)).single()
        clear.performClick()
        idle(500)
        assertEquals(activity.getString(R.string.tap_again_to_clear), clear.contentDescription)
        assertTrue("the pill grew", clear.width > (100 * unit).toInt())
        clear.performClick()
        assertTrue("the rows fade first", calls.isEmpty())
        idle(1_000)
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun clearAsksAgainAfterThreeSeconds() {
        val clear = described(activity.getString(R.string.clear)).single()
        clear.performClick()
        idle(3_500)
        assertEquals(activity.getString(R.string.clear), clear.contentDescription)
        clear.performClick()
        idle(1_000)
        assertTrue("one tap after the timeout only asks", calls.isEmpty())
    }

    @Test
    fun insertGoesInAfterThePressAndTheCloseWaits() {
        val insert = labelled("Insert").first()
        insert.performClick()
        assertTrue("not at once", calls.isEmpty())
        idle(100)
        assertEquals(listOf("insert a"), calls)
        panel.close { calls += "closed" }
        assertTrue("the close waits for the pill's release", panel.closeDelayMs in 1..60)
        idle(1_000)
        assertEquals(listOf("insert a", "closed"), calls)
    }

    @Test
    fun anotherTabSlidesTheListsIn() {
        panel.show(ListPanel.Tab.HISTORY, listOf(row("c")), "empty", {}, {}, KeyboardColors.DAY, unit, false)
        val text = labelled("c").single()
        // The new list starts away and see-through, then lands.
        val card = generateSequence(text as View) { it.parent as? View }.firstOrNull { it.alpha < 1f || it.translationX != 0f }
        assertNotNull("it slides in", card)
        idle(1_000)
        assertEquals(1f, card!!.alpha)
        assertEquals(0f, card.translationX)
    }
}
