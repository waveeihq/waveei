package app.waveei.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class WavTest {
    @Test
    fun headerDescribesMonoPcm() {
        val wav = Wav.fromPcm(ByteArray(10), sampleRate = 16_000)
        assertEquals(54, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("data", wav.decodeToString(36, 40))
        assertEquals(10, wav[40].toInt())
    }
}
