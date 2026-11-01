package app.waveei.android.ui

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** What a touch on a floating dot turns out to be: a tap, a flick away, a menu, a drag, or a brush or slow slide that does nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class DotViewTest {
    private val calls = mutableListOf<String>()

    private val actions = object : DotView.Actions {
        override fun onTap() { calls += "tap" }
        override fun onMenu() { calls += "menu" }
        override fun onMenuLeft() { calls += "menu left" }
        override fun onItem(item: DotView.Item) { calls += item.name.lowercase() }
        override fun onDrag(dx: Float, dy: Float) = Unit
        override fun onDragEnd(flungRight: Boolean?, speed: Float) { calls += "drag" }
        override fun onSwipeAway(toRight: Boolean, speed: Float) { calls += if (toRight) "swipe right" else "swipe left" }
        override fun onTouchStart() = Unit
        override fun onTouchEnd() = Unit
    }

    private fun dot(right: Boolean) = DotView(ApplicationProvider.getApplicationContext(), actions).apply {
        rightEdge = right
        measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        layout(0, 0, measuredWidth, measuredHeight)
    }

    /** A finger from the dot's middle, moved by [dx], [dy] dp and lifted after [ms]. */
    private fun DotView.touch(dx: Float, dy: Float, ms: Long) {
        val density = resources.displayMetrics.density
        val x = width / 2f
        val y = height / 2f
        val time = SystemClock.uptimeMillis()
        onTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        onTouchEvent(MotionEvent.obtain(time, time + ms / 2, MotionEvent.ACTION_MOVE, x + dx * density / 2, y + dy * density / 2, 0))
        onTouchEvent(MotionEvent.obtain(time, time + ms, MotionEvent.ACTION_UP, x + dx * density, y + dy * density, 0))
    }

    @Test
    fun aQuickFlickSendsItToTheEdgeItPointsTo() {
        dot(right = true).touch(60f, 5f, 40)
        dot(right = false).touch(-60f, 5f, 40)
        // A dot on the right half flicked left goes left.
        dot(right = true).touch(-40f, 8f, 30)
        assertEquals(listOf("swipe right", "swipe left", "swipe left"), calls)
    }

    @Test
    fun aSlowSideSlideBeforeTheHoldDoesNothing() {
        // Slow slides as real fingers make them: the dot does not move, follow or fly before the hold.
        dot(right = true).touch(20f, 2f, 800)
        dot(right = true).touch(-26f, 13f, 856)
        dot(right = true).touch(60f, 5f, 400)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun brushesAndUpOrDownSlidesDoNothing() {
        dot(right = true).touch(10f, 60f, 150)
        dot(right = true).touch(20f, 40f, 300)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aTapStillTaps() {
        dot(right = true).touch(0f, 0f, 100)
        assertEquals(listOf("tap"), calls)
    }

    @Test
    fun aTouchMovedPastTheTouchSlopIsNoTap() {
        // A tap moves less than the system's touch slop; this one moves 2 dp more on the way.
        val view = dot(right = true)
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop / view.resources.displayMetrics.density
        view.touch(0f, 2 * (slop + 2f), 100)
        assertEquals(emptyList<String>(), calls)
    }

    /** A finger held still on a dot on the screen past the long-press time, then moved by [dx] dp and lifted. */
    private fun hold(dx: Float) {
        val view = dot(right = true)
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        val density = view.resources.displayMetrics.density
        val time = SystemClock.uptimeMillis()
        val hold = MENU_MS + 100L
        view.onTouchEvent(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 20f, 20f, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(hold))
        if (dx != 0f) view.onTouchEvent(MotionEvent.obtain(time, time + hold, MotionEvent.ACTION_MOVE, 20f + dx * density, 20f, 0))
        view.onTouchEvent(MotionEvent.obtain(time, time + hold + 400, MotionEvent.ACTION_UP, 20f + dx * density, 20f, 0))
    }

    @Test
    fun heldStillAndLetGoItOpensTheMenu() {
        hold(0f)
        assertEquals(listOf("menu"), calls)
    }

    @Test
    fun heldStillTheMenuOpensAndMovedOnItIsDragged() {
        hold(-40f)
        assertEquals(listOf("menu", "menu left", "drag"), calls)
    }

    @Test
    fun eachMenuItemIsAnAccessibilityAction() {
        val view = dot(right = true)
        view.items = listOf(DotView.Item.PAUSE, DotView.Item.CANCEL)
        assertTrue(view.performAccessibilityAction(DotView.Item.CANCEL.label, null))
        assertEquals(listOf("cancel"), calls)
    }
}
