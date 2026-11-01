package app.waveei.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import app.waveei.core.api.Api
import app.waveei.core.api.StatsBatch
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Usage statistics: how the app is used, as counts a day, never who uses it nor what they said. Each line of the event
 * log counts under its kind, its numbers and names left out ("dictation: piece of # ms, voice # ms"); each key of the
 * keyboard and each tap on a dot count under their own; a crash or an ANR adds its trace. Once a day the day's counts
 * go to the server ([Api.STATS]) with the app's build and Android's version only: no account, no id.
 *
 * Counted only with Share app reports on ([Prefs.shareEvents]), the person's consent, the same in the EU and the UK.
 * Turned off, what was counted and not yet sent is deleted.
 */
object Stats {
    private var store: SharedPreferences? = null
    private val counts = HashMap<String, Long>()
    private val faults = ArrayList<String>()
    private var day = ""
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var saving = false

    /** A later try of [send] is waiting; read and set on [worker] only. */
    private var retrying = false

    /** Counting and sending: [Prefs.shareEvents]. */
    @Volatile
    var on = false
        private set

    fun init(context: Context, prefs: Prefs) {
        val s = context.getSharedPreferences("stats", Context.MODE_PRIVATE)
        synchronized(this) {
            store = s
            day = s.getString(DAY, null) ?: today()
            s.all.forEach { (key, value) -> if (key.startsWith(COUNT) && value is Long) counts[key.removePrefix(COUNT)] = value }
            s.getString(FAULTS, null)?.let { faults += it.split(FAULT_SEPARATOR).filter(String::isNotEmpty) }
        }
        update(prefs)
        choices = prefs.onChange(Prefs.SHARE_EVENTS) { update(prefs) }
        // After App Check has had time for its token.
        worker.schedule(::send, FIRST_SEND_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /** Held: Android keeps the listener only weakly. */
    private var choices: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** On or off as the person chose; off deletes what was not sent. */
    private fun update(prefs: Prefs) {
        val now = prefs.shareEvents
        val s = store ?: return
        if (now && !s.getBoolean(INSTALLED, false)) {
            s.edit().putBoolean(INSTALLED, true).apply()
            on = true
            count("app: installed")
        }
        on = now
        if (!now) clear()
    }

    /** Deletes what was counted and not sent yet: sharing turned off, or the app's data deleted. */
    fun clear() {
        val s = store ?: return
        synchronized(this) {
            counts.clear()
            faults.clear()
            s.edit().clear().putBoolean(INSTALLED, s.getBoolean(INSTALLED, false)).apply()
        }
    }

    /** One more of [line]'s kind: the line as the event log has it, or a kind of its own ("key: mic"). */
    fun count(line: String) {
        if (!on) return
        val kind = kind(line)
        synchronized(this) {
            rollOver()
            // A day's kinds are bounded: past the limit, a new one counts as other.
            val key = if (kind in counts || counts.size < StatsBatch.MAX_KINDS - 1) kind else OTHER
            counts[key] = (counts[key] ?: 0) + 1
            if (!saving) {
                saving = true
                worker.schedule({ save() }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** A crash's trace, kept at once: the process is about to end. */
    fun fault(trace: String) {
        if (!on) return
        synchronized(this) {
            rollOver()
            if (faults.size < StatsBatch.MAX_FAULTS) faults += trace.take(StatsBatch.MAX_FAULT).replace(FAULT_SEPARATOR, " ")
            store?.edit()?.putString(FAULTS, faults.joinToString(FAULT_SEPARATOR))?.commit()
        }
        save(now = true)
    }

    /** A new day: the day before becomes a batch to send. Called holding the lock. */
    private fun rollOver() {
        val today = today()
        if (today == day) return
        val s = store ?: return
        if (counts.isNotEmpty() || faults.isNotEmpty()) {
            val batch = StatsBatch(day, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT, counts.toMap(), faults.toList())
            val edit = s.edit().putString(BATCH + day, Api.json.encodeToString(StatsBatch.serializer(), batch))
            // At most a week waits for the network; the oldest go first.
            s.all.keys.filter { it.startsWith(BATCH) }.sorted().dropLast(MAX_WAITING - 1).forEach { edit.remove(it) }
            s.all.keys.filter { it.startsWith(COUNT) }.forEach { edit.remove(it) }
            edit.remove(FAULTS).apply()
        }
        counts.clear()
        faults.clear()
        day = today
        s.edit().putString(DAY, day).apply()
        worker.execute(::send)
    }

    private fun save(now: Boolean = false) {
        val s = store ?: return
        synchronized(this) {
            saving = false
            val edit = s.edit().putString(DAY, day)
            counts.forEach { (kind, n) -> edit.putLong(COUNT + kind, n) }
            if (now) edit.commit() else edit.apply()
        }
    }

    /**
     * The days counted and not yet sent, oldest first: each is deleted once the server took it or found it malformed;
     * any other failure (no network, no App Check token yet) leaves it, and the rest, for a try an hour later.
     * Runs on [worker] only.
     */
    private fun send() {
        // Unit tests run the app too: their counts never leave.
        if (Build.FINGERPRINT == "robolectric" || !on) return
        synchronized(this) { rollOver() }
        val s = store ?: return
        s.all.filterKeys { it.startsWith(BATCH) }.toSortedMap().forEach { (key, json) ->
            val batch = runCatching { Api.json.decodeFromString(StatsBatch.serializer(), json as String) }.getOrNull()
            val code = if (batch == null) 400 else runCatching { Server.stats(batch) }.getOrDefault(0)
            if (code in 200..299 || code == 400 || code == 413) s.edit().remove(key).apply()
            else {
                if (!retrying) {
                    retrying = true
                    worker.schedule({ retrying = false; send() }, RETRY_MS, TimeUnit.MILLISECONDS)
                }
                return
            }
        }
    }

    /** [line]'s kind: its first line, numbers as #, quoted text left out; an HTTP status stays. */
    internal fun kind(line: String): String {
        val first = line.lineSequence().first().replace(QUOTED, "\"…\"")
        return NUMBER.replace(first) { if (first.startsWith("HTTP ", it.range.first - 5)) it.value else "#" }.take(StatsBatch.MAX_KIND)
    }

    private fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

    /** A number, not part of a name (v1, H264). */
    private val NUMBER = Regex("(?<![A-Za-z])\\d+(\\.\\d+)?")
    private val QUOTED = Regex("\"[^\"]*\"")
    private const val DAY = "day"
    private const val COUNT = "c:"
    private const val BATCH = "batch:"
    private const val FAULTS = "faults"
    private const val INSTALLED = "installed"
    private const val FAULT_SEPARATOR = "\u001E"
    private const val OTHER = "other"
    private const val MAX_WAITING = 7
    private const val SAVE_DELAY_MS = 5_000L
    private const val FIRST_SEND_DELAY_MS = 30_000L
    private const val RETRY_MS = 60 * 60_000L
}
