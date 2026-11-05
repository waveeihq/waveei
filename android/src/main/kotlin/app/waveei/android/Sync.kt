package app.waveei.android

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Tide's settings sync: the settings the person picks, their vocabulary and text replacements ([Prefs.synced]), kept
 * on the server under the account, so each phone signed in to it has the same. The last change wins: a phone sends
 * its own a few seconds after one, and takes the server's when it has none of its own waiting and the server's is
 * newer. When both changed since they last agreed (a phone used only through the keyboard reads the server's copy
 * only when it sends), each keeps the other's changes ([Prefs.mergeSynced]). A phone new to the account takes the
 * account's settings, when there are any.
 */
object Sync {
    private lateinit var prefs: Prefs
    private lateinit var store: SharedPreferences
    private lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener
    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var pending: ScheduledFuture<*>? = null

    fun init(context: Context) {
        prefs = Prefs(context)
        store = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
        listener = prefs.onSyncedChange {
            pending?.cancel(false)
            pending = worker.schedule({ run(::push) }, PUSH_DELAY_S, TimeUnit.SECONDS)
        }
    }

    /** As the plan is read: this phone's change goes up, or the server's newer copy comes down. */
    fun pull() = worker.execute {
        run {
            val stored = Server.settings()
            val known = store.getLong(AT, 0)
            when {
                stored == null -> push()
                known == 0L || !changed() && stored.second > known -> take(stored)
                changed() -> push()
            }
        }
    }

    /** After the data on this phone is deleted: the account's settings come back as on a new phone. */
    fun forget() {
        store.edit().clear().apply()
    }

    private fun take(stored: Pair<String, Long>) {
        prefs.applySynced(stored.first)
        remember(stored.second, prefs.synced())
        DebugLog.add("sync: settings taken")
    }

    private fun push() {
        var copy = prefs.synced()
        val agreed = store.getString(AGREED, null)
        if (copy == agreed) return
        val stored = Server.settings()
        if (stored != null && stored.second > store.getLong(AT, 0)) {
            if (agreed == null) return take(stored)
            prefs.applySynced(prefs.mergeSynced(agreed, copy, stored.first))
            copy = prefs.synced()
            DebugLog.add("sync: settings merged")
        }
        remember(Server.putSettings(copy), copy)
        DebugLog.add("sync: settings sent")
    }

    /** This phone's settings differ from the copy last agreed with the server. */
    private fun changed() = prefs.synced() != store.getString(AGREED, null)

    /** The copy agreed with the server, stored there at [at]. */
    private fun remember(at: Long, copy: String) {
        store.edit().putString(UID, Account.uid).putLong(AT, at).putString(AGREED, copy).apply()
    }

    /** Only with Tide, and for the account the copy was agreed with: another account starts as a new phone. */
    private fun run(work: () -> Unit) {
        if (!Account.tide) return
        if (store.getString(UID, null) != Account.uid) forget()
        runCatching(work).onFailure { DebugLog.add("sync: failed: ${it.javaClass.simpleName}") }
    }

    private const val UID = "uid"
    private const val AT = "at"
    private const val AGREED = "agreed"
    private const val PUSH_DELAY_S = 5L
}
