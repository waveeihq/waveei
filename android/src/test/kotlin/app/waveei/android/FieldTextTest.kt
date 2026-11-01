package app.waveei.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading another app's field before inserting into it through Accessibility. */
class FieldTextTest {
    @Test
    fun aHintOrABlankValueIsAnEmptyField() {
        assertEquals("", FieldText.readable("Message", showingHint = true))
        assertEquals("", FieldText.readable("\n", showingHint = false))
        assertEquals("", FieldText.readable(null, showingHint = false))
        assertEquals("Hi", FieldText.readable("Hi", showingHint = false))
    }

    @Test
    fun aValidSelectionIsKept() {
        assertEquals(3 to 3, FieldText.insertAt(10, 3, 3))
        assertEquals(2 to 5, FieldText.insertAt(10, 5, 2))
        assertEquals(0 to 4, FieldText.insertAt(10, 0, 4))
    }

    @Test
    fun anUnknownCursorGoesToTheEnd() {
        assertEquals(10 to 10, FieldText.insertAt(10, -1, -1))
        assertEquals(10 to 10, FieldText.insertAt(10, 3, 12))
        assertEquals(10 to 10, FieldText.insertAt(10, 0, 0))
        assertEquals(0 to 0, FieldText.insertAt(0, 0, 0))
    }

    @Test
    fun aWriteLandsDespiteTrimmedTrailingSpace() {
        assertTrue(FieldText.landed("Hello there ", "Hello there"))
        assertTrue(FieldText.landed("Hello", "Hello"))
        assertFalse(FieldText.landed("Hello there", "Hello"))
    }

    @Test
    fun aPasteLandsOnlyWhenTheFieldChangedAndHoldsTheText() {
        assertTrue(FieldText.pasted("Hi", "Hi, see you at five", ", see you at five"))
        assertFalse(FieldText.pasted("Hi", "Hi", "Hi"))
        assertFalse(FieldText.pasted("Hi", "Hi!", " see you"))
    }
}
