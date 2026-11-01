package app.waveei.core.audio

/** A minimal WAV container around 16-bit mono PCM. */
object Wav {
    fun fromPcm(pcm: ByteArray, sampleRate: Int, length: Int = pcm.size): ByteArray {
        val out = ByteArray(HEADER + length)
        fun text(at: Int, value: String) = value.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun int(at: Int, value: Int) = repeat(4) { out[at + it] = (value shr (8 * it)).toByte() }
        fun short(at: Int, value: Int) = repeat(2) { out[at + it] = (value shr (8 * it)).toByte() }
        text(0, "RIFF"); int(4, 36 + length); text(8, "WAVE")
        text(12, "fmt "); int(16, 16); short(20, 1); short(22, 1)
        int(24, sampleRate); int(28, sampleRate * 2); short(32, 2); short(34, 16)
        text(36, "data"); int(40, length)
        pcm.copyInto(out, HEADER, 0, length)
        return out
    }

    private const val HEADER = 44
}
