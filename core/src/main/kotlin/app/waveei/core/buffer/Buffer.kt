package app.waveei.core.buffer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Row status in the buffer. */
enum class BufferStatus {
    /** Being recorded right now. */
    RECORDING,

    /** Audio kept, not transcribed yet (recovered, offline, or paused before transcription). */
    KEPT,
    TRANSCRIBING,
    READY,
    FAILED,
    INSERTED,
    ALREADY_IN_FIELD,
    ;

    val active: Boolean get() = this == RECORDING || this == TRANSCRIBING
}

/** One dictation in the buffer: the app's own clipboard, never the system one. */
@Serializable
data class BufferEntry(
    /** Creation time in milliseconds, also the identity of the row. */
    val id: Long,
    val status: BufferStatus,
    /** Package of the app the dictation was made in. */
    val app: String = "",
    /** Field signature, for "Insert here?". */
    val field: String? = null,
    /** The transcript as the model returned it. */
    val raw: String? = null,
    /** The text that is inserted: styled when a style was applied, otherwise the raw transcript. */
    val text: String? = null,
    /** File name of the kept audio, if any. */
    val audio: String? = null,
    val durationMs: Long = 0,
    /** Short reason of the last failure, shown in the row. */
    val error: String? = null,
    /** Broken off by a crash or a kill, not by the user: the keyboard asks what to do with it. */
    val interrupted: Boolean = false,
) {
    val hasText: Boolean get() = !text.isNullOrEmpty()
}

/** Capacity rules and storage format of the buffer. */
object Buffer {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Keeps at most [recordings] rows without text and [transcripts] rows with text, newest first.
     * Active rows are never dropped. Returns the kept rows and the dropped ones (their audio is deleted by the caller).
     */
    fun trim(
        entries: List<BufferEntry>,
        recordings: Int,
        transcripts: Int,
    ): Pair<List<BufferEntry>, List<BufferEntry>> {
        var audioRows = 0
        var textRows = 0
        val kept = ArrayList<BufferEntry>()
        val dropped = ArrayList<BufferEntry>()
        for (entry in entries.sortedByDescending { it.id }) {
            val fits = when {
                entry.status.active -> true
                entry.hasText -> ++textRows <= transcripts
                else -> ++audioRows <= recordings
            }
            if (fits) kept += entry else dropped += entry
        }
        return kept to dropped
    }

    fun encode(entries: List<BufferEntry>): String = json.encodeToString(entries)

    fun decode(text: String): List<BufferEntry> = runCatching { json.decodeFromString<List<BufferEntry>>(text) }.getOrDefault(emptyList())
}
