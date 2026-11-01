package app.waveei.android

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prepares a recording for the upload: pieces cut at quiet moments, then FLAC.
 * The model bills by audio length, so the format is for upload speed only.
 */
object CompactAudio {
    /** How many chunks of [pcm] are loud enough to be a voice. */
    fun voicedChunks(pcm: ByteArray, voiceLevel: Float): Int {
        val chunk = AudioCapture.CHUNK_BYTES
        return (0 until (pcm.size + chunk - 1) / chunk).count { i ->
            val part = pcm.copyOfRange(i * chunk, minOf((i + 1) * chunk, pcm.size))
            AudioCapture.level(part, part.size) > voiceLevel
        }
    }

    /**
     * Whether a piece of a long recording ends after this chunk: at the first quiet chunk once it is [pieceChunks]
     * long, or at half as long again if no quiet comes. The phone and Retry cut the same way.
     */
    fun cutsHere(length: Int, level: Float, pieceChunks: Int, quietLevel: Float): Boolean =
        length >= pieceChunks && level <= quietLevel || length >= pieceChunks * 3 / 2

    /** A recording's chunk levels split into pieces, as chunk index ranges; the last piece is what is left. */
    fun pieces(levels: FloatArray, pieceChunks: Int, quietLevel: Float): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in levels.indices) {
            if (cutsHere(i - start + 1, levels[i], pieceChunks, quietLevel)) {
                out += start..i
                start = i + 1
            }
        }
        if (start < levels.size) out += start until levels.size
        return out
    }

    /** The voice level of every chunk of [file] from [from] to [to], read a chunk at a time. */
    fun levels(file: File, from: Long, to: Long): FloatArray {
        val chunk = AudioCapture.CHUNK_BYTES
        val count = ((to - from + chunk - 1) / chunk).toInt()
        val levels = FloatArray(count)
        val buffer = ByteArray(chunk)
        RandomAccessFile(file, "r").use { input ->
            input.seek(from)
            for (i in 0 until count) {
                val length = minOf(chunk.toLong(), to - from - i.toLong() * chunk).toInt()
                input.readFully(buffer, 0, length)
                levels[i] = AudioCapture.level(buffer, length)
            }
        }
        return levels
    }

    /** Bytes [from] to [to] of [file]. */
    fun read(file: File, from: Long, to: Long): ByteArray = RandomAccessFile(file, "r").use { input ->
        ByteArray((to - from).toInt()).also {
            input.seek(from)
            input.readFully(it)
        }
    }

    /**
     * 16 kHz mono PCM as a FLAC file: lossless, so the model hears what WAV gives it, at about half the size.
     * Null if the phone cannot encode it or the file does not come out whole, and the caller sends WAV.
     */
    fun flac(pcm: ByteArray): ByteArray? {
        var codec: MediaCodec? = null
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_FLAC, AudioCapture.SAMPLE_RATE, 1)
            format.setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, FLAC_LEVEL)
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var header: ByteArray? = null
            val frames = ByteArrayOutputStream()
            encode(codec, pcm, { format -> if (header == null) header = format.getByteBuffer("csd-0")?.let(::bytes) }) { buffer, info ->
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) header = bytes(buffer) else frames.write(bytes(buffer))
            }
            flacFile(header, frames.toByteArray())
        } catch (e: Exception) {
            DebugLog.add("audio: FLAC failed, sending WAV: ${e.javaClass.simpleName}")
            null
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
        }
    }

    /**
     * The encoder's stream header and frames as one file: the header is the whole "fLaC" start, or STREAMINFO
     * alone, which then gets the marker and its block header, or none when the frames carry it themselves.
     * Anything else is no FLAC file the model can read.
     */
    internal fun flacFile(header: ByteArray?, frames: ByteArray): ByteArray {
        if (header == null) return frames.also { check(it.size > 8 && it.copyOf(4).contentEquals(FLAC_MARKER)) { "no stream header" } }
        val start = when {
            header.size >= 8 && header.copyOf(4).contentEquals(FLAC_MARKER) -> header
            header.size == STREAMINFO_BYTES -> FLAC_MARKER + byteArrayOf(0x80.toByte(), 0, 0, STREAMINFO_BYTES.toByte()) + header
            else -> error("unknown stream header of ${header.size} bytes")
        }
        // Frames start with the sync code 0xFFF8; without it the file holds no audio the model can find.
        check(frames.size >= 2 && frames[0] == 0xFF.toByte() && frames[1].toInt() and 0xFE == 0xF8) { "no audio frames" }
        return start + frames
    }

    /** [pcm] played at [speed] (below 1 slower), the voice's pitch kept: Sonic, as ExoPlayer plays at another speed. */
    @OptIn(UnstableApi::class)
    fun stretched(pcm: ByteArray, speed: Float): ByteArray {
        val sonic = SonicAudioProcessor().apply {
            setSpeed(speed)
            configure(AudioProcessor.AudioFormat(AudioCapture.SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT))
            flush(AudioProcessor.StreamMetadata.DEFAULT)
        }
        sonic.queueInput(ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder()))
        sonic.queueEndOfStream()
        val out = ByteArrayOutputStream((pcm.size / speed).toInt() + 1024)
        while (!sonic.isEnded) out.write(bytes(sonic.output))
        return out.toByteArray()
    }

    private fun bytes(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

    private fun encode(codec: MediaCodec, pcm: ByteArray, formatChanged: (MediaFormat) -> Unit, output: (ByteBuffer, MediaCodec.BufferInfo) -> Unit) {
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + ENCODE_LIMIT_MS
        var offset = 0
        var inputDone = false
        while (true) {
            check(SystemClock.elapsedRealtime() < deadline) { "encoding took too long" }
            // Nothing waits while the codec has work: the encoder takes small frames, and a wait per frame
            // would add up to seconds on a long recording.
            var fed = false
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(0)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index)!!
                    buffer.clear()
                    val size = minOf(buffer.capacity(), pcm.size - offset) and 1.inv()
                    buffer.put(pcm, offset, size)
                    val timeUs = offset / 2 * 1_000_000L / AudioCapture.SAMPLE_RATE
                    offset += size
                    inputDone = offset >= pcm.size - 1
                    codec.queueInputBuffer(index, 0, size, timeUs, if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    fed = true
                }
            }
            // Every frame that is ready is taken; the short wait is only for when nothing moved.
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (fed) 0 else WAIT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    formatChanged(codec.outputFormat)
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    if (info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        output(buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                } else {
                    break
                }
                fed = true
            }
        }
    }

    private const val ENCODE_LIMIT_MS = 5_000L

    /** libFLAC's default: near the smallest file for speech, still fast. */
    private const val FLAC_LEVEL = 5
    private val FLAC_MARKER = "fLaC".toByteArray(Charsets.US_ASCII)
    private const val STREAMINFO_BYTES = 34
    private const val WAIT_US = 10_000L
}
