package app.waveei.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CorrectionsTest {
    private val said = "Погоди, а вот Nation, да, он отправляет заметки два раза в день?"

    @Test
    fun aMisheardWordFixedInTheFieldIsOffered() {
        assertEquals(listOf("Notion"), Corrections.of(said, "Погоди, а вот Notion, да, он отправляет заметки два раза в день?"))
        // Another alphabet: an English name the model wrote in Cyrillic.
        assertEquals(listOf("Notion"), Corrections.of("Погоди, а вот Ноушн, да, он отправляет", "Погоди, а вот Notion, да, он отправляет"))
        assertEquals(listOf("Ноушн" to "Notion"), Corrections.fixes("Погоди, а вот Ноушн, да, он отправляет", "Погоди, а вот Notion, да, он отправляет"))
        // Two heard words for one term, and the first and last word.
        assertEquals(listOf("Wi-Fi"), Corrections.of("Включи вай фай на кухне", "Включи Wi-Fi на кухне"))
        assertEquals(listOf("Notion"), Corrections.of("Nation отправляет два раза", "Notion отправляет два раза"))
        assertEquals(listOf("Notion"), Corrections.of("два раза отправляет Nation", "два раза отправляет Notion и всё"))
        assertEquals(listOf("Kubernetes"), Corrections.of("подними кластер на Kubernetis сегодня", "подними кластер на Kubernetes сегодня"))
    }

    @Test
    fun theDictationAmongOtherTextIsFound() {
        val field = "Привет! Погоди, а вот Notion, да, он отправляет заметки два раза в день? Жду ответа"
        assertTrue(Corrections.holds(said, field))
        assertEquals(listOf("Notion"), Corrections.of(said, field))
    }

    @Test
    fun rewordingsCaseAndPunctuationAreNotOffered() {
        assertEquals(emptyList(), Corrections.of(said, said))
        assertEquals(emptyList(), Corrections.of(said, "Погоди, а вот nation да он отправляет заметки два раза в день"))
        assertEquals(emptyList(), Corrections.of(said, "Погоди, а вот Nation, да, он шлёт заметки два раза в день?"))
        // Another ending of the same word is grammar, not a misheard word.
        assertEquals(emptyList(), Corrections.of(said, "Погоди, а вот Nation, да, он отправил заметки два раза в день?"))
        // Words added or taken away are not corrections.
        assertEquals(emptyList(), Corrections.of(said, "Погоди, а вот Nation, да, он отправляет заметки два раза в день? Ну и ладно"))
        assertEquals(emptyList(), Corrections.of(said, "Погоди, а вот Nation, он отправляет заметки два раза в день?"))
        // The last word deleted and new text typed after it.
        assertEquals(emptyList(), Corrections.of("два раза отправляет Nation", "два раза отправляет Привет"))
    }

    @Test
    fun aClearedOrOtherFieldHoldsNoDictation() {
        assertFalse(Corrections.holds(said, ""))
        assertFalse(Corrections.holds(said, "Совсем другой текст про погоду"))
        assertEquals(emptyList(), Corrections.of(said, "Совсем другой текст"))
        // Too short to tell an edit from new text.
        assertFalse(Corrections.holds("Nation", "Notion"))
    }
}
