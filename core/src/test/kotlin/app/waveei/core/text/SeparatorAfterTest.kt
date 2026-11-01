package app.waveei.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

class SeparatorAfterTest {
    @Test
    fun aSpaceIsReadyUnlessNothingIsChosen() {
        assertEquals("", separatorAfter("", Separator.NONE))
        assertEquals(" ", separatorAfter("", Separator.SPACE))
        assertEquals(" ", separatorAfter("next", Separator.SPACE))
    }

    @Test
    fun aNewParagraphIsReadyRightAfterTheDictation() {
        assertEquals("\n" + PARAGRAPH_INDENT, separatorAfter("", Separator.NEW_PARAGRAPH))
        assertEquals("\n", separatorAfter("", Separator.NEW_LINE))
    }

    @Test
    fun nothingIsDoubled() {
        assertEquals("", separatorAfter(" rest", Separator.SPACE))
        assertEquals("", separatorAfter("\nrest", Separator.NEW_LINE))
        assertEquals("", separatorAfter("\nrest", Separator.NEW_PARAGRAPH))
    }

    @Test
    fun theNextDictationAddsNothingBeforeItself() {
        val first = "Hello" + separatorAfter("", Separator.NEW_PARAGRAPH)
        assertEquals("", separatorBefore(first, Separator.NEW_PARAGRAPH))
        assertEquals("", separatorBefore("Hello" + separatorAfter("", Separator.SPACE), Separator.SPACE))
    }
}
