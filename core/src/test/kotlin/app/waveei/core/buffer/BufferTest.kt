package app.waveei.core.buffer

import kotlin.test.Test
import kotlin.test.assertEquals

class BufferTest {
    private fun text(id: Long) = BufferEntry(id, BufferStatus.INSERTED, text = "t$id")
    private fun audio(id: Long) = BufferEntry(id, BufferStatus.KEPT, audio = "$id.pcm")

    @Test
    fun keepsTheNewestRowsOfEachKind() {
        val entries = (1L..7L).map(::text) + (11L..13L).map(::audio)
        val (kept, dropped) = Buffer.trim(entries, recordings = 2, transcripts = 5)
        assertEquals(listOf(13L, 12L, 7L, 6L, 5L, 4L, 3L), kept.map { it.id })
        assertEquals(listOf(11L, 2L, 1L), dropped.map { it.id })
    }

    @Test
    fun neverDropsActiveRows() {
        val entries = listOf(BufferEntry(1, BufferStatus.RECORDING, audio = "1.pcm"), audio(2), audio(3))
        val (kept, dropped) = Buffer.trim(entries, recordings = 1, transcripts = 1)
        assertEquals(listOf(3L, 1L), kept.map { it.id })
        assertEquals(listOf(2L), dropped.map { it.id })
    }

    @Test
    fun roundTrips() {
        val entries = listOf(text(1), audio(2).copy(field = "app|id", durationMs = 1200))
        assertEquals(entries, Buffer.decode(Buffer.encode(entries)))
        assertEquals(emptyList(), Buffer.decode("broken"))
    }
}
