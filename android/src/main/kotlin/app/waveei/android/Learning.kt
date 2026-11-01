package app.waveei.android

import android.content.Context
import android.os.SystemClock
import app.waveei.core.text.Corrections

/**
 * Learns the words the person corrects in a dictation after it went in, as keyboards learn a typed word: each goes
 * straight into the vocabulary, with nothing to confirm, and the word it put right leaves it, so a wrong one learned
 * before is replaced by correcting it again. The field is looked at while it is edited (the accessibility
 * service's text events, our keyboard coming back to it); its text stays in memory, only the corrected words are kept.
 */
object Learning {
    private class Watch(val text: String, val app: String, val until: Long) {
        var fixes = emptyList<Pair<String, String>>()
    }

    private lateinit var prefs: Prefs
    private var watch: Watch? = null

    fun init(context: Context) {
        prefs = Prefs(context.applicationContext)
    }

    /** A dictation went into a field of [app]: its edits are looked at from now on. */
    fun inserted(text: String, app: String) {
        commit()
        watch = Watch(text, app, SystemClock.elapsedRealtime() + WATCH_MS)
    }

    /** Whether a dictation in [app] is being watched. */
    fun watches(app: String): Boolean = watch?.app == app

    /** A field of [app] holds [text] now. The dictation gone from it (sent, cleared) ends the watch. */
    fun seen(app: String, text: String) {
        val current = watch ?: return
        if (app != current.app) return
        if (SystemClock.elapsedRealtime() > current.until) return commit()
        current.fixes = Corrections.heldFixes(current.text, text) ?: return commit()
    }

    /**
     * Ends the watch: the words corrected by then go into the vocabulary as its newest, the words they put right leave
     * it, and the oldest drop out past [MAX_WORDS].
     */
    fun commit() {
        val current = watch ?: return
        watch = null
        if (current.fixes.isEmpty()) return
        val gone = current.fixes.flatMap { listOf(it.first, it.second) }.map { it.lowercase() }.toSet()
        val new = current.fixes.map { it.second }.distinct()
        prefs.vocabulary = (prefs.vocabulary.filter { it.lowercase() !in gone } + new).takeLast(MAX_WORDS)
        DebugLog.add("vocabulary: learned ${new.size} words")
        Trace.current?.text("learned", current.fixes.joinToString { "\"${it.second}\" for \"${it.first}\"" })
    }

    /** How long after the insertion an edit still counts as a correction of the dictation. */
    private const val WATCH_MS = 10 * 60_000L

    /** The vocabulary goes with every dictation, so it stays short. */
    const val MAX_WORDS = 100
}
