package app.waveei.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

class WordDeleteTest {
    @Test
    fun deletesTheLastWordWithTrailingSpaces() {
        assertEquals(5, wordDeleteLength("see you  "))
        assertEquals(3, wordDeleteLength("see you"))
        assertEquals(1, wordDeleteLength("see you!"))
        assertEquals(3, wordDeleteLength("ok?! "))
        assertEquals(2, wordDeleteLength("  "))
        assertEquals(0, wordDeleteLength(""))
        assertEquals(6, wordDeleteLength("привет"))
    }
}
