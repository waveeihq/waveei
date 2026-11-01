package app.waveei.android

import android.app.Application

class App : Application() {
    /** Held for the process's life: it logs the settings the person changes. */
    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        prefs = Prefs(this).apply { logChanges() }
        DebugLog.share = prefs.shareEvents
        Stats.init(this, prefs)
        DebugLog.add("app: started")
        Diagnostics.catchCrashes()
        Account.init(this)
        Sync.init(this)
        Thread {
            Diagnostics.exitReasons(this)
            if (DebugLog.share) Diagnostics.logPhone(this)
        }.start()
        BufferStore.init(this)
        Learning.init(this)
        Dictation.init(this)
        UpdateCheckJob.schedule(this)
    }
}
