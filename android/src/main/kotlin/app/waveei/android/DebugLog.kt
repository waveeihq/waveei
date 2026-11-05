package app.waveei.android

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The app's event log: what happened and how long it took, never what the person said, typed or copied. Kept on the
 * phone in a private file, the newest 256 KB, which the person can attach to a message on the Feedback screen; there an
 * app, another keyboard or a field ([name]) is only its kind. Each line counts in the usage statistics ([Stats]) by its
 * kind. With Share app reports on ([share]) the same lines, the names in them included, also go to the server's log
 * ([app.waveei.core.api.Api.LOG]), a few seconds' worth at a time.
 */
object DebugLog {
    private const val MAX_BYTES = 256 * 1024
    private var file: File? = null

    /** Share app reports: the person's own choice, off unless they chose Share. Turned off, what waits to go is dropped. */
    @Volatile
    var share = false
        set(value) {
            field = value
            if (!value) synchronized(pending) { pending.setLength(0) }
        }

    private val pending = StringBuilder()
    private val sender = Executors.newSingleThreadScheduledExecutor()

    fun init(context: Context) = synchronized(this) {
        if (file == null) file = File(context.filesDir, "log.txt")
    }

    fun add(line: String) {
        // A developer build's trace keeps the names everywhere; otherwise only what is shared has them.
        val hasName = OPEN in line
        val keepsNames = !hasName || Trace.current != null
        val plain = when {
            !hasName -> line
            keepsNames -> NAMED.replace(line) { it.groupValues[2] }
            else -> NAMED.replace(line) { it.groupValues[1] }
        }
        Log.i("waveei", plain)
        val now = System.currentTimeMillis()
        val stamped = stamp(now, plain)
        synchronized(this) {
            val log = file ?: return
            runCatching {
                log.appendText(stamped)
                if (log.length() > MAX_BYTES) {
                    val text = log.readText()
                    log.writeText(text.substring(text.indexOf('\n', text.length / 2) + 1))
                }
            }
        }
        Stats.count(plain)
        Trace.current?.line(stamped)
        if (share) synchronized(pending) {
            if (pending.isEmpty()) sender.schedule(::send, SEND_DELAY_MS, TimeUnit.MILLISECONDS)
            if (pending.length < MAX_PENDING) pending.append(if (keepsNames) stamped else stamp(now, NAMED.replace(line) { it.groupValues[2] }))
        }
    }

    private fun stamp(time: Long, line: String) = String.format(Locale.ROOT, "%tF %<tT %s%n", time, line)

    /** Sends what is pending now and waits a little for it, for a crash about to end the process. */
    fun flush() {
        if (!share) return
        Thread(::send).apply { start() }.join(FLUSH_WAIT_MS)
    }

    private fun send() {
        // Unit tests run the app too: their lines never leave.
        if (Build.FINGERPRINT == "robolectric") return
        // Sharing turned off meanwhile: nothing goes.
        if (!share) return synchronized(pending) { pending.setLength(0) }
        // They go with the account's sign-in, as every call to the server; until there is an account, they wait.
        if (!Account.exists) return synchronized(pending) { if (pending.isNotEmpty()) sender.schedule(::send, RETRY_DELAY_MS, TimeUnit.MILLISECONDS) }
        val batch = synchronized(pending) { pending.toString().also { pending.setLength(0) } }
        if (batch.isEmpty()) return
        runCatching { Server.log(lines = batch) }.onFailure {
            Log.w("waveei", "events not sent: $it")
            // Offline, the batch waits for the next try.
            if (share) synchronized(pending) {
                if (pending.isEmpty()) sender.schedule(::send, RETRY_DELAY_MS, TimeUnit.MILLISECONDS)
                if (pending.length + batch.length <= MAX_PENDING) pending.insert(0, batch)
            }
        }
    }

    /**
     * An app, another keyboard or a field named in a line: the name goes only with Share app reports and to a
     * developer build's [Trace]; the event log on the phone and the usage statistics say only [kind].
     */
    fun name(value: Any?, kind: String): String = "$OPEN$kind$MIDDLE$value$CLOSE"

    /** The whole log, oldest first. */
    fun text(): String = synchronized(this) {
        file?.takeIf { it.exists() }?.readText().orEmpty()
    }

    /** The newest whole lines that fit in [maxChars], oldest first. */
    fun newest(maxChars: Int): String {
        val text = text()
        if (text.length <= maxChars) return text
        val from = text.length - maxChars
        return text.substring(text.indexOf('\n', from).let { if (it < 0) text.length else it + 1 })
    }

    /** The log on the phone and what waits to go to the server. */
    fun clear() {
        synchronized(this) { file?.delete() }
        synchronized(pending) { pending.setLength(0) }
    }

    /** A [name] in a line: its kind, then the name itself, between control characters no line otherwise has. */
    private const val OPEN = '\u0001'
    private const val MIDDLE = '\u0002'
    private const val CLOSE = '\u0003'
    private val NAMED = Regex("$OPEN([^$MIDDLE]*)$MIDDLE([^$CLOSE]*)$CLOSE")

    private const val SEND_DELAY_MS = 3_000L
    private const val RETRY_DELAY_MS = 30_000L
    private const val FLUSH_WAIT_MS = 2_000L
    private const val MAX_PENDING = 48 * 1024
}
