package app.waveei.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import app.waveei.core.buffer.Buffer
import app.waveei.core.buffer.BufferEntry
import app.waveei.core.buffer.BufferStatus
import java.io.File

/**
 * The buffer on disk: one JSON index plus one PCM file per kept recording.
 * Nothing here is sent anywhere unless the user taps an action. Listeners are called on the main thread.
 */
object BufferStore {
    private lateinit var index: AtomicFile
    lateinit var audioDir: File
        private set
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var entries: List<BufferEntry> = emptyList()

    fun init(context: Context) {
        // Written whole or not at all: a write cut off by the process dying leaves the last good copy.
        index = AtomicFile(File(context.filesDir, "buffer.json"))
        audioDir = File(context.filesDir, "audio").apply { mkdirs() }
        prefs = Prefs(context)
        entries = if (index.baseFile.exists()) Buffer.decode(index.readFully().decodeToString()) else emptyList()
        recover(File(context.filesDir, "journal"))
    }

    @Synchronized
    fun all(): List<BufferEntry> = entries

    @Synchronized
    fun get(id: Long): BufferEntry? = entries.firstOrNull { it.id == id }

    fun audioFile(entry: BufferEntry): File? = entry.audio?.let { File(audioDir, it) }?.takeIf { it.exists() }

    fun add(entry: BufferEntry) = change { it.filterNot { e -> e.id == entry.id } + entry }

    fun update(id: Long, transform: (BufferEntry) -> BufferEntry) = change { list ->
        list.map { if (it.id == id) transform(it) else it }
    }

    fun delete(id: Long) {
        get(id)?.let { audioFile(it)?.delete() }
        change { list -> list.filterNot { it.id == id } }
    }

    /** Drops the audio of a row whose transcript has been delivered. */
    fun dropAudio(id: Long) {
        get(id)?.let { audioFile(it)?.delete() }
        update(id) { it.copy(audio = null) }
    }

    /** Deletes every row not being recorded or transcribed right now, with its audio. */
    fun clearDone() {
        all().filterNot { it.status.active }.forEach { audioFile(it)?.delete() }
        change { list -> list.filter { it.status.active } }
    }

    fun clear() {
        audioDir.listFiles()?.forEach { it.delete() }
        change { emptyList() }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun change(transform: (List<BufferEntry>) -> List<BufferEntry>) {
        val dropped: List<BufferEntry>
        synchronized(this) {
            val (kept, removed) = Buffer.trim(transform(entries), prefs.bufferRecordings, prefs.bufferTranscripts)
            entries = kept
            dropped = removed
            val out = index.startWrite()
            runCatching { out.write(Buffer.encode(kept).encodeToByteArray()) }
                .onSuccess { index.finishWrite(out) }
                .onFailure {
                    index.failWrite(out)
                    DebugLog.add("buffer: not saved: ${it.javaClass.simpleName}")
                }
        }
        dropped.forEach { entry -> entry.audio?.let { File(audioDir, it).delete() } }
        main.post { listeners.toList().forEach { it() } }
    }

    /**
     * After a crash or a kill: rows that were being recorded or transcribed become "recording kept",
     * and audio files without a row are added as such, so nothing is deleted.
     */
    private fun recover(oldJournal: File) {
        oldJournal.listFiles()?.forEach { it.renameTo(File(audioDir, it.name)) }
        oldJournal.delete()
        val known = entries.mapNotNull { it.audio }.toSet()
        val orphans = audioDir.listFiles().orEmpty().filter { it.name !in known && it.length() > 0 }
        val stale = entries.any { it.status.active }
        if (orphans.isEmpty() && !stale) return
        change { list ->
            list.map { if (it.status.active) it.copy(status = BufferStatus.KEPT, interrupted = true) else it } +
                orphans.map { file ->
                    BufferEntry(
                        id = file.lastModified(),
                        status = BufferStatus.KEPT,
                        audio = file.name,
                        durationMs = AudioCapture.durationMs(file.length()),
                        interrupted = true,
                    )
                }
        }
        DebugLog.add("buffer: recovered ${orphans.size} recordings, ${if (stale) "reset" else "no"} unfinished rows")
    }
}
