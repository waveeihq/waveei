package app.waveei.android

import app.waveei.android.Dictation.State
import app.waveei.android.ui.DotView.Item
import app.waveei.android.ui.DotView.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each of the two floating dots shows on its own: the switch dot over any keyboard, the record dot its own
 * dictations and the keyboard's once the keyboard is gone; the menu each offers; and that a dropped dot stays
 * where it was dropped.
 */
class FloatingIconsTest {
    private fun record(state: State = State.IDLE, own: Boolean = false, ours: Boolean = false, on: Boolean = true) =
        DotService.recordMode(state, own, ours, on)

    @Test
    fun theRecordDotWaitsIdleWhenTurnedOn() {
        assertEquals(Mode.IDLE, record())
        assertEquals(null, record(on = false))
    }

    @Test
    fun theRecordDotShowsItsOwnDictationOverOurKeyboardToo() {
        assertEquals(Mode.RECORDING, record(State.RECORDING, own = true))
        assertEquals(Mode.PAUSED, record(State.PAUSED, own = true))
        assertEquals(Mode.PROCESSING, record(State.PROCESSING, own = true))
        assertEquals(Mode.RECORDING, record(State.RECORDING, own = true, ours = true))
    }

    @Test
    fun theRecordDotStepsAsideOverOurKeyboard() {
        assertEquals(null, record(ours = true))
    }

    @Test
    fun theRecordDotFollowsTheKeyboardsDictationOnceTheKeyboardIsGoneEvenTurnedOff() {
        assertEquals(Mode.RECORDING, record(State.RECORDING, on = false))
        // Paused it stays now, so Resume is one tap away.
        assertEquals(Mode.PAUSED, record(State.PAUSED, on = false))
        assertEquals(Mode.PROCESSING, record(State.PROCESSING, on = false))
        assertEquals(null, record(State.IDLE, on = false))
        // Our keyboard shows its own dictation.
        assertEquals(null, record(State.RECORDING, ours = true))
        assertEquals(null, record(State.PROCESSING, ours = true))
    }

    @Test
    fun theSwitchDotShowsOverAKeyboardBothWays() {
        assertEquals(Mode.SWITCH, DotService.switchMode(ourKeyboard = false, otherKeyboard = true))
        assertEquals(Mode.SWITCH, DotService.switchMode(ourKeyboard = true, otherKeyboard = false))
        assertEquals(null, DotService.switchMode(ourKeyboard = false, otherKeyboard = false))
    }

    private fun menu(icon: DotService.Icon, mode: Mode?, keyboard: Boolean = false, transcribing: Boolean = true) =
        DotService.menuItems(icon, mode, keyboard, transcribing)

    @Test
    fun atRestTheMenuHidesTheDotOrOpensTheSettings() {
        assertEquals(listOf(Item.HIDE, Item.SETTINGS), menu(DotService.Icon.SWITCH, Mode.SWITCH))
        assertEquals(listOf(Item.HIDE, Item.SETTINGS), menu(DotService.Icon.RECORD, Mode.IDLE))
    }

    @Test
    fun overADictationTheMenuDoesWhatTheKeyboardsKeysDo() {
        assertEquals(listOf(Item.SHOW_KEYBOARD, Item.PAUSE, Item.CANCEL), menu(DotService.Icon.RECORD, Mode.RECORDING, keyboard = true))
        assertEquals(listOf(Item.SHOW_KEYBOARD, Item.RESUME, Item.CANCEL), menu(DotService.Icon.RECORD, Mode.PAUSED, keyboard = true))
        // The dot's own dictation has no keyboard to show.
        assertEquals(listOf(Item.PAUSE, Item.CANCEL), menu(DotService.Icon.RECORD, Mode.RECORDING))
        assertEquals(listOf(Item.CANCEL), menu(DotService.Icon.RECORD, Mode.PROCESSING, keyboard = true))
        // A finished text waiting for its field has nothing to cancel.
        assertEquals(emptyList<Item>(), menu(DotService.Icon.RECORD, Mode.PROCESSING, transcribing = false))
        assertEquals(emptyList<Item>(), menu(DotService.Icon.RECORD, null))
    }

    @Test
    fun theDotsFirstRestApart() {
        val heights = DotService.Icon.entries.map { DotService.defaultY(it) }
        assertEquals(heights.size, heights.distinct().size)
    }

    @Test
    fun aDroppedIconStaysWhereItWasDropped() {
        val screen = 1080
        val slot = 156
        val margin = 12
        // A dot anywhere on the screen, in either half and against either edge, and a wide pill on the right.
        for ((left, width) in listOf(300 to slot, 700 to slot, margin to slot, screen - slot - margin to slot, 500 to 520)) {
            val centre = DotService.restingCentre(left, left + width, slot, screen)
            val (right, x) = DotService.windowX(centre, slot, margin, screen)
            val placedLeft = if (right) screen - x - margin - width else x + margin
            assertEquals(left, placedLeft)
        }
    }

    @Test
    fun anIconNeverGoesPastAnEdge() {
        val (_, x) = DotService.windowX(-50, 156, 12, 1080)
        assertEquals(0, x)
        val (right, fromRight) = DotService.windowX(5000, 156, 12, 1080)
        assertTrue(right)
        assertEquals(0, fromRight)
    }

    @Test
    fun onlyTheShadowsRoomReachesPastTheEdge() {
        // A dot window with 30 px of room round its slot, 18 of them for the shadow: the dot rests 12 px from the edge.
        assertEquals(DotService.windowX(DotService.edgeCentre(true, 96, 12, 1080), 96, 30, 1080, 18), true to -18)
        assertEquals(DotService.windowX(DotService.edgeCentre(false, 96, 12, 1080), 96, 30, 1080, 18), false to -18)
        assertEquals(DotService.windowX(-50, 96, 30, 1080, 18), false to -18)
        assertEquals(DotService.windowX(5000, 96, 30, 1080, 18), true to -18)
    }

    @Test
    fun anIconNotYetDraggedStartsAtTheEdgeItRestedOn() {
        assertEquals(DotService.windowX(DotService.edgeCentre(true, 156, 12, 1080), 156, 12, 1080), true to 0)
        assertEquals(DotService.windowX(DotService.edgeCentre(false, 156, 12, 1080), 156, 12, 1080), false to 0)
    }
}
