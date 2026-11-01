package app.waveei.android

import app.waveei.core.api.Piece
import java.io.File
import java.util.ServiceLoader

/**
 * A developer build's view into what a dictation went through: its recording, each call to the server with its
 * answer, the text that came of it, and every line of the event log. A release build has no trace ([current] is
 * null), so none of this is kept or leaves the phone; a developer build adds one as a Java service
 * (META-INF/services/app.waveei.android.Trace) that sends them to the Waveei server's developer log.
 */
interface Trace {
    fun line(stamped: String) {}

    fun recording(id: Long, file: File) {}

    fun call(path: String, piece: Piece, answer: String) {}

    fun text(label: String, value: String) {}

    /** Sends what is pending now, for a crash about to end the process. */
    fun flush() {}

    companion object {
        val current: Trace? by lazy { ServiceLoader.load(Trace::class.java, Trace::class.java.classLoader).firstOrNull() }
    }
}
