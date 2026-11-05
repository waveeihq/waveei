package app.waveei.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Records mono 16 kHz PCM. Every chunk is appended to the [journal] file first and then handed to
 * [onChunk] on the capture thread. [onLevel] gets the voice level 0..1 per chunk.
 */
class AudioCapture(
    private val journal: File,
    private val onChunk: (ByteArray, Int) -> Unit,
    private val onLevel: (Float) -> Unit = {},
    /** The recording broke off by itself: the microphone failed or the system took it, for a call or another app. */
    private val onInterrupted: (String) -> Unit = {},
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Starts recording; the caller has checked the microphone permission. Returns false if the mic is unavailable. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(minBuffer, CHUNK_BYTES * 4),
            )
        }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        // Registered before the capture starts, as Android asks, so a silencing at the start is heard too.
        record.registerAudioRecordingCallback({ it.run() }, object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                if (configs.any { it.clientAudioSessionId == record.audioSessionId && it.isClientSilenced }) {
                    onInterrupted("the system took the microphone")
                }
            }
        })
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return false
        }
        running = true
        thread = Thread({ loop(record) }, "audio-capture").apply { start() }
        return true
    }

    /** Stops recording and returns after the last chunk has been delivered. */
    fun stop() {
        running = false
        thread?.join()
        thread = null
    }

    private fun loop(record: AudioRecord) {
        val chunk = ByteArray(CHUNK_BYTES)
        // The newest 32 ms, for the water: the chunk is read in quarters and the level follows each quarter,
        // so it moves 40 times a second instead of jumping 10 times.
        val window = ByteArray(VoiceLevel.WINDOW_BYTES)
        try {
            journal.parentFile?.mkdirs()
            // Unbuffered: a long dictation reads its pieces from the file while it records.
            FileOutputStream(journal, true).use { out ->
                var failed = false
                while (running && !failed) {
                    var filled = 0
                    while (running && filled < chunk.size) {
                        val read = record.read(chunk, filled, minOf(chunk.size / PIECES, chunk.size - filled))
                        if (read < 0) {
                            DebugLog.add("audio: read error $read")
                            onInterrupted("the microphone failed")
                            failed = true
                            break
                        }
                        if (read == 0) continue
                        System.arraycopy(window, read, window, 0, window.size - read)
                        System.arraycopy(chunk, filled, window, window.size - read, read)
                        filled += read
                        VoiceLevel.level = level(window, window.size)
                    }
                    if (filled == 0) continue
                    out.write(chunk, 0, filled)
                    onChunk(chunk, filled)
                    onLevel(level(chunk, filled))
                }
            }
        } catch (e: java.io.IOException) {
            // The phone's storage full: paused and kept with what was written, never the keyboard's process brought down.
            DebugLog.add("audio: the recording could not be written: ${e.javaClass.simpleName}")
            onInterrupted("the phone's storage is full")
        } finally {
            record.stop()
            record.release()
            VoiceLevel.level = 0f
        }
    }

    companion object {
        /** The recording: raw 16-bit little-endian mono PCM. */
        const val SAMPLE_RATE = 16_000

        /** 100 ms of audio. */
        const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2

        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** Reads per chunk; the waves follow each one. */
        private const val PIECES = 4

        /** Below this the level is silence, at the top it is a loud voice close to the mic. */
        private const val FLOOR_DB = -52.0
        private const val CEILING_DB = -12.0

        fun durationMs(bytes: Long): Long = bytes * 1000 / (SAMPLE_RATE * 2)

        /** RMS of 16-bit little-endian samples mapped from dBFS to 0..1. */
        fun level(pcm: ByteArray, length: Int, offset: Int = 0): Float {
            var sum = 0.0
            val samples = length / 2
            if (samples == 0) return 0f
            for (i in 0 until samples) {
                val sample = (pcm[offset + 2 * i].toInt() and 0xFF) or (pcm[offset + 2 * i + 1].toInt() shl 8)
                sum += sample.toDouble() * sample
            }
            val rms = sqrt(sum / samples) / 32768.0
            if (rms <= 0) return 0f
            val db = 20 * log10(rms)
            return ((db - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
