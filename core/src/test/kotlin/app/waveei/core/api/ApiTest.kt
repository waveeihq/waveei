package app.waveei.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {
    @Test
    fun aPieceLeavesOutWhatWasNotChosen() {
        val body = Api.json.encodeToString(Piece.serializer(), Piece("AQ==", "audio/flac", 1_000))
        assertTrue("style" !in body && "translateTo" !in body)
        assertEquals(Piece("AQ==", "audio/flac", 1_000), Api.json.decodeFromString(Piece.serializer(), body))
    }

    @Test
    fun answersReadTheSameOnBothSides() {
        assertEquals(Text("hi there", "Hi there"), Api.json.decodeFromString(Text.serializer(), """{"text":"hi there","styled":"Hi there"}"""))
        assertEquals(Text("hi"), Api.json.decodeFromString(Text.serializer(), """{"text":"hi"}"""))
        val failure = Api.json.decodeFromString(FailureAnswer.serializer(), """{"error":{"code":422,"message":"Blocked by the model","kind":"blocked"}}""")
        assertEquals(Api.BLOCKED, failure.error.kind)
    }
}
