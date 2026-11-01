package app.waveei.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetsTest {
    @Test
    fun replacesTriggersWithSavedText() {
        val snippets = listOf("my address" to "221B Baker Street, London", "sig" to "— R. $5")
        assertEquals("Send it to 221B Baker Street, London", Snippets.apply("Send it to my address.", snippets))
        assertEquals("Thanks — R. $5", Snippets.apply("Thanks sig", snippets))
        assertEquals("design is fine", Snippets.apply("design is fine", snippets))
    }
}
