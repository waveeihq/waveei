package app.waveei.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pieces of a long recording and the FLAC file for the upload. */
class CompactAudioTest {
    @Test
    fun longRecordingsAreCutAtQuietChunksOrAtHalfAgain() {
        val loud = 0.5f
        val quiet = 0.05f
        // Quiet at 12 (after the piece length of 10), then no quiet at all: a hard cut at 15 chunks.
        val levels = FloatArray(40) { if (it == 3 || it == 12) quiet else loud }
        assertEquals(listOf(0..12, 13..27, 28..39), CompactAudio.pieces(levels, 10, 0.12f))
        assertEquals(listOf(0..4), CompactAudio.pieces(FloatArray(5) { loud }, 10, 0.12f))
    }

    private val streamInfo = ByteArray(34) { it.toByte() }
    private val frames = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 1, 2, 3)
    private val marker = "fLaC".toByteArray()

    @Test
    fun streamInfoAloneGetsTheMarkerAndALastBlockHeader() {
        val file = CompactAudio.flacFile(streamInfo, frames)
        assertArrayEquals(marker + byteArrayOf(0x80.toByte(), 0, 0, 34) + streamInfo + frames, file)
    }

    @Test
    fun aWholeHeaderOrOneInsideTheFramesIsKeptAsItIs() {
        val header = marker + byteArrayOf(0x80.toByte(), 0, 0, 34) + streamInfo
        assertArrayEquals(header + frames, CompactAudio.flacFile(header, frames))
        assertArrayEquals(header + frames, CompactAudio.flacFile(null, header + frames))
    }

    @Test
    fun anythingElseIsNoFlacFile() {
        assertThrows(IllegalStateException::class.java) { CompactAudio.flacFile(ByteArray(10), frames) }
        assertThrows(IllegalStateException::class.java) { CompactAudio.flacFile(streamInfo, byteArrayOf(1, 2, 3)) }
        assertThrows(IllegalStateException::class.java) { CompactAudio.flacFile(null, frames) }
    }
}
