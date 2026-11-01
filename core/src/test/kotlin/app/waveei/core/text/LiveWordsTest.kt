package app.waveei.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveWordsTest {
    @Test
    fun aFullStopAtACutGoesWhenTheNextDraftGoesOn() {
        assertEquals("будут идти. Ну вот и всё.", glueDrafts(listOf("будут идти. Ну.", "вот и всё.")))
        assertEquals("ты как будто должен, э ты как будто", glueDrafts(listOf("ты как будто должен, э...", "ты как будто")))
    }

    @Test
    fun aSentenceThatEndedStaysEnded() {
        assertEquals("Не работает. Я смахиваю B, да?", glueDrafts(listOf(" Не работает. ", "", "Я смахиваю B, да?")))
        assertEquals("сделать всё-таки? но типа", glueDrafts(listOf("сделать всё-таки?", "но типа")))
    }

    @Test
    fun aWordCutAtAHyphenIsJoinedUp() {
        assertEquals("эти суп-титры идут", glueDrafts(listOf("эти суп-", "титры идут")))
        assertEquals("прошлые слова. Чего", glueDrafts(listOf("прошлые сл-", "слова. Чего")))
        assertEquals("кто-то", glueDrafts(listOf("кто-", "то")))
    }

    @Test
    fun wordsHeardAgainInTheOverlapAreDropped() {
        assertEquals("Ну вот, сейчас мы проверим, как это работает.", glueDrafts(listOf("Ну вот, сейчас мы.", "Мы проверим, как", "как это работает.")))
        assertEquals("ну вот сейчас мы проверим", glueDrafts(listOf("ну вот сейчас", "час мы проверим")))
        assertEquals("сказал это то самое", glueDrafts(listOf("сказал это", "то самое")))
        assertEquals("и вот он и я пошёл", glueDrafts(listOf("и вот он", "и я пошёл")))
        assertEquals("он сказал", glueDrafts(listOf("он сказал", "сказал.")))
    }
}
