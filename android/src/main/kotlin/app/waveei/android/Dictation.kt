package app.waveei.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.waveei.core.audio.Wav
import app.waveei.core.api.Api
import app.waveei.core.api.FreeTier
import app.waveei.core.api.Plan
import app.waveei.core.api.Text
import app.waveei.core.api.Piece as PieceBody
import app.waveei.core.buffer.BufferEntry
import app.waveei.core.buffer.BufferStatus
import app.waveei.core.style.Style
import app.waveei.core.text.Snippets
import app.waveei.core.text.glueDrafts
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64

/**
 * The dictation flow shared by the keyboard and the dot: recording, pause, the model
 * with its style and delivery into a field or the buffer. One dictation at a time.
 * All public methods are called on the main thread.
 *
 * Only the user's own stop sends a recording to the model. Anything else that breaks it off (the screen
 * locks, the system takes the microphone) pauses it: it is kept, and the user continues or stops it.
 */
object Dictation {
    enum class State {
        IDLE, RECORDING, PAUSED, PROCESSING;

        /** A recording under way, running or paused. */
        val open: Boolean get() = this == RECORDING || this == PAUSED
    }

    /** Something that can put text into the focused field: the keyboard, later the dot. */
    interface Target {
        /**
         * Inserts [text] at the cursor; false when there is no field to insert into, when [app]
         * is given and another app is in front, or when [field] (a field signature) is given, the target can
         * tell fields apart and another field has the focus. [id] is the dictation's, when one goes in by itself.
         */
        fun insert(text: String, app: String? = null, field: String? = null, id: Long? = null): Boolean

        /** The field asks for no personalised learning: do not keep the text after insertion. */
        val incognito: Boolean

        /** The field in front as this target tells it apart, for [Learning]; null where it cannot. */
        val learningField: Learning.Field? get() = null

        /** Whether this target can tell if [field], a field signature, is the one with the focus. */
        fun knowsField(field: String): Boolean

        /**
         * This target is in front and sees that another field than [field] of [app] has the focus: no target may
         * insert there, though one that cannot tell the fields apart would.
         */
        fun seesOtherField(app: String?, field: String?): Boolean = false
    }

    interface Observer {
        fun onDictationChanged()
    }

    var state = State.IDLE
        private set

    /** The last outcome or error, shown in the status line until the next dictation. */
    var message: String? = null
        private set

    /** Live words while speaking, when that setting is on. */
    var liveWords: String = ""
        private set

    /** The dictation records on into a kept recording from the buffer. */
    var continued = false
        private set

    /** The row Retry acts on, after a failure. */
    var retryId: Long? = null
        private set

    /** The text takes longer than usual to come after stop; the status line says so. */
    var slow = false
        private set

    /** While [slow]: the recording is still on its way (no network, or a call still sending), not waiting for the answer. */
    var slowNetwork = false
        private set

    /** Processing, and the recording still on its way to the server (no network yet, or still sending): Connecting. */
    var sending = false
        private set

    /**
     * What of the dictation being processed has not reached the server yet: 1 for the rest after the stop until it is
     * cut into pieces, and 1 for each piece sent from then on until its audio is all sent, or found to hold no voice.
     */
    @Volatile
    private var unsent = AtomicInteger()

    /** While processing, looks every [SEND_CHECK_MS] whether the recording has reached the server, until the text comes. */
    private val watchSending = object : Runnable {
        override fun run() {
            if (state != State.PROCESSING) return
            val now = !isOnline() || Server.uploading.get() > 0 || unsent.get() > 0
            if (now != sending) {
                sending = now
                notifyChanged()
            }
            main.postDelayed(this, SEND_CHECK_MS)
        }
    }

    /** Marks the wait slow, then looks again every [SLOW_CHECK_MS] whether it is the network's, until the text comes. */
    private val markSlow = object : Runnable {
        override fun run() {
            if (state != State.PROCESSING) return
            val network = !isOnline() || Server.uploading.get() > 0
            if (!slow || network != slowNetwork) {
                DebugLog.add("dictation: slower than usual, ${if (network) "still sending" else "waiting for the answer"}, ${(SystemClock.elapsedRealtime() - stoppedAt) / 1000} s after stop")
                slow = true
                slowNetwork = network
                notifyChanged()
            }
            main.postDelayed(this, SLOW_CHECK_MS)
        }
    }

    private lateinit var app: Context
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val control = Executors.newSingleThreadExecutor()
    private val network = Executors.newCachedThreadPool()

    /** Pieces of a long recording go out a few at a time, so a long Retry does not flood the key. */
    private val pieceWork = Executors.newFixedThreadPool(PARALLEL_PIECES)

    /** Drafts for the live words, apart from the pieces so they never hold up the text that goes in. */
    private val draftWork = Executors.newFixedThreadPool(PARALLEL_PIECES)
    private val observers = LinkedHashSet<Observer>()
    private val targets = ArrayList<Target>()

    /** A finished dictation whose app was left before it was ready: it goes in once the person is back there. */
    private class Waiting(val id: Long, val text: String, val app: String, val field: String?, val until: Long)
    private var waiting: Waiting? = null

    /**
     * A finished dictation waits for its app; the dot keeps showing that something is on its way. Taken from the
     * buffer (our keyboard's Insert here), it waits no more.
     */
    val isWaiting: Boolean
        get() = waiting.let { it != null && SystemClock.elapsedRealtime() < it.until && BufferStore.get(it.id)?.status == BufferStatus.READY }

    /** The buffer row of the dictation waiting for its field, which our keyboard offers in any field. */
    val waitingId: Long? get() = waiting?.id?.takeIf { isWaiting }

    /** The waiting dictation was made outside any field: it goes in only by Insert here. */
    val waitsForAnyField: Boolean get() = isWaiting && waiting?.field == null

    /** The dictation being recorded or processed: its id is its start time. */
    var current: Long? = null
        private set

    /**
     * Our keyboard's input starts, restarts included: in a browser or a Compose app another field starts input as a
     * restart, and the chats of one app share their field's id. A dictation goes into a field by itself only if no
     * input started since it began; otherwise our keyboard offers it as Insert here.
     */
    private var inputs = 0L

    /** The dictation started last, and [inputs] as it started. */
    private var startedId: Long? = null
    private var startedInputs = 0L

    /** Our keyboard's input started, in another field or in the same one again. */
    fun inputStarted() {
        inputs++
    }

    /** No input of our keyboard started since dictation [id] began: the field in front is still the one it was made in. */
    private fun sameInput(id: Long) = id == startedId && inputs == startedInputs

    /** The dictation is a kept recording sent again: Cancel stops the attempt, never the audio. */
    private var retried = false
    private var capture: AudioCapture? = null
    /** Style and translation of the current dictation; the dot can change them while recording. */
    var style = Style.OFF
        private set
    var translateTo: String? = null
        private set
    private var segmentStartedAt = 0L
    private var recordedBefore = 0L
    private var stoppedAt = 0L
    @Volatile private var silentMs = 0L
    @Volatile private var heardVoice = false

    /**
     * A long dictation goes out piece by piece while it records, each piece cut at a quiet moment, so after stop
     * only the last few minutes are left to wait for, however long the dictation was.
     */
    private class Piece(val from: Long, val to: Long, val result: Future<Text?>, val shape: Shape)
    private val pieces = ArrayList<Piece>()

    /** The calls sent after stop: × stops them, as it does the pieces sent while recording. */
    private val calls = CopyOnWriteArrayList<Future<*>>()

    /**
     * Each go of a dictation at the model has its own number. What a go given up still brings back (×, then Retry of
     * the same recording) is dropped, never taken for the answer of the go after it.
     */
    private val attempt = AtomicInteger()

    /**
     * The style or translation asked for did not come back (the words went in instead): the text is not sent by itself,
     * as the person reading it may not read its language.
     */
    var styleDropped = false
        private set

    /**
     * The pieces of a failed dictation the model did answer, by its buffer id: its Retry sends only the rest, so a
     * long recording on a slow network is not uploaded, and its free minutes not counted, all over again.
     */
    private class Done(val from: Long, val to: Long, val shape: Shape, val text: Text?)
    private val done = HashMap<Long, List<Done>>()

    /**
     * With live words: while it records, every few seconds of it also go out as a draft, only for the live words.
     * The text that goes in comes as without them, from the whole recording.
     */
    @Volatile private var drafting = false
    private val drafts = ArrayList<Future<Text?>>()
    @Volatile private var draftFrom = 0L
    @Volatile private var recordedBytes = 0L

    /** The keyboard shows the recording as Listening once the mic's first sound came in. */
    var listeningShown = true
        private set
    @Volatile private var micReady = true
    private var tappedAt = 0L
    private val showListening = Runnable {
        listeningShown = true
        notifyChanged()
    }
    @Volatile private var pieceFrom = 0L

    fun init(context: Context) {
        app = context.applicationContext
        prefs = Prefs(app)
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = interrupt("screen locked")
        }, IntentFilter(Intent.ACTION_SCREEN_OFF))
        // The buffer is the queue, the network the signal: once online, failed sends go again by themselves.
        app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    resent.clear()
                    resendFailed()
                }
            }
        })
    }

    /**
     * The dictation going on is a failed one sent again by itself: its text goes to the buffer, never straight into
     * a field, which by now may be another chat of the same app.
     */
    private var resending = false

    /** Failed sends already tried again on this connection: each gets one more go per connection. */
    private val resent = HashSet<Long>()

    /** The user stopped these, so they go to the model again by themselves, the oldest first, one at a time. */
    private fun resendFailed() {
        if (state != State.IDLE) return
        val row = BufferStore.all()
            .filter { it.status == BufferStatus.FAILED && it.id !in resent && BufferStore.audioFile(it) != null }
            .minByOrNull { it.id } ?: return
        resent += row.id
        DebugLog.add("dictation: online, sending a failed recording again")
        // Set before the retry, which may end at once and clear it.
        resending = true
        retry(row.id)
    }

    fun addObserver(observer: Observer) {
        observers += observer
    }

    fun removeObserver(observer: Observer) {
        observers -= observer
    }

    /** A [preferred] target (the visible keyboard) is tried before the others (the dot). */
    fun addTarget(target: Target, preferred: Boolean = true) {
        if (target in targets) return
        if (preferred) targets.add(0, target) else targets.add(target)
    }

    fun removeTarget(target: Target) {
        targets -= target
    }

    /** Recording time of the current dictation, pauses excluded. */
    fun elapsedMs(): Long = recordedBefore + if (state == State.RECORDING) SystemClock.elapsedRealtime() - segmentStartedAt else 0

    /**
     * Changed Terms not agreed to yet stop dictation on the free plan. Tide goes on: it was paid for under the Terms it
     * was bought with.
     */
    private val termsStop: Boolean get() = prefs.termsPending && Account.plan != Plan.TIDE

    /** Starts a dictation for a field; returns a short reason if it cannot start. */
    fun start(appPackage: String, field: String?): String? {
        if (state != State.IDLE) return null
        styleDropped = false
        if (termsStop) return app.getString(R.string.agree_new_terms)
        if (!Server.ready) return app.getString(R.string.no_account)
        // Asked again at once as well: Tide bought a moment ago dictates on the next tap.
        if (Account.freeUsedUp) return app.getString(R.string.free_used_up).also { refreshPlan() }
        // Asked again at once: bought a moment ago, the next tap dictates.
        if (Account.plan == Plan.NONE) return app.getString(R.string.tide_needed).also { refreshPlan() }
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return app.getString(R.string.no_mic_permission)
        }
        val id = System.currentTimeMillis()
        val file = audioOf(id)
        val online = isOnline()
        tappedAt = SystemClock.elapsedRealtime()
        micReady = false
        listeningShown = false
        main.removeCallbacks(showListening)
        val audio = capture(file)
        // Audio first, network second.
        if (!audio.start()) {
            listeningShown = true
            return app.getString(R.string.mic_failed)
        }
        if (online) warm()

        // A new dictation ends the wait of the last one: that text stays in the buffer.
        waiting = null
        current = id
        startedId = id
        startedInputs = inputs
        capture = audio
        useStyleOf(appPackage)
        recordedBefore = 0
        segmentStartedAt = SystemClock.elapsedRealtime()
        silentMs = 0
        heardVoice = false
        // Live words are Tide's.
        drafting = prefs.showLiveWords && Account.tide
        recordedBytes = 0
        pieceFrom = 0
        draftFrom = 0
        message = null
        liveWords = ""
        retryId = null
        // A new dictation answers the keyboard's question about a cut-off one: that one stays in the buffer.
        BufferStore.addClearingInterrupted(BufferEntry(id, BufferStatus.RECORDING, app = appPackage, field = field, audio = file.name))
        DebugLog.add("dictation: started${if (online) "" else " offline"}, style ${style.name}${if (drafting) ", live words" else ""}${this.translateTo?.let { ", to $it" } ?: ""}")
        setState(State.RECORDING)
        return null
    }

    /**
     * Records on into a kept recording that was never transcribed, for the field in front; the user's stop
     * then sends all of it. Returns a short reason if it cannot start.
     */
    fun continueKept(id: Long, appPackage: String, field: String?): String? {
        if (state != State.IDLE) return null
        val entry = BufferStore.get(id)?.takeIf { !it.hasText && !it.status.active } ?: return null
        val file = BufferStore.audioFile(entry) ?: return null
        if (termsStop) return app.getString(R.string.agree_new_terms)
        if (!Server.ready) return app.getString(R.string.no_account)
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return app.getString(R.string.no_mic_permission)
        }
        // What was kept goes out in pieces now, as if sent while it was first recorded.
        val kept = split(file, 0L)
        done.remove(id)
        val length = file.length()
        val audio = capture(file)
        if (!audio.start()) return app.getString(R.string.mic_failed)

        waiting = null
        current = id
        startedId = id
        startedInputs = inputs
        capture = audio
        listeningShown = true
        useStyleOf(appPackage)
        recordedBefore = AudioCapture.durationMs(length)
        segmentStartedAt = SystemClock.elapsedRealtime()
        silentMs = 0
        // Never dropped for silence: what was kept is the user's.
        heardVoice = true
        recordedBytes = length
        pieceFrom = length
        message = null
        liveWords = ""
        retryId = null
        continued = true
        BufferStore.update(id) { it.copy(status = BufferStatus.RECORDING, app = appPackage, field = field, error = null, interrupted = false) }
        DebugLog.add("dictation: continued a kept ${recordedBefore / 1000} s recording, style ${style.name}")
        setState(State.RECORDING)
        kept.forEach { (from, to) -> sendPiece(from, to) }
        return null
    }

    fun setStyle(value: Style, language: String?) {
        if (!state.open) return
        style = value
        translateTo = language?.takeIf { it.isNotEmpty() }
        notifyChanged()
    }

    /** Drops the note of the last dictation, unless one is going on or its Retry is still offered. */
    fun forgetMessage() {
        if (state != State.IDLE || retryId != null || message == null) return
        message = null
        notifyChanged()
    }

    fun pause() {
        if (state != State.RECORDING) return
        val audio = capture ?: return
        capture = null
        recordedBefore = elapsedMs()
        setState(State.PAUSED)
        control.execute { audio.stop() }
        DebugLog.add("dictation: paused at ${recordedBefore / 1000} s")
    }

    fun resume() {
        if (state != State.PAUSED) return
        val id = current ?: return
        val file = audioOf(id)
        val audio = capture(file)
        if (!audio.start()) {
            message = app.getString(R.string.mic_failed)
            return notifyChanged()
        }
        capture = audio
        segmentStartedAt = SystemClock.elapsedRealtime()
        silentMs = 0
        listeningShown = true
        BufferStore.update(id) { it.copy(status = BufferStatus.RECORDING) }
        setState(State.RECORDING)
        DebugLog.add("dictation: resumed")
    }

    fun stop() {
        if (!state.open) return
        val id = current ?: return
        val audio = capture
        recordedBefore = elapsedMs()
        capture = null
        stoppedAt = SystemClock.elapsedRealtime()
        BufferStore.update(id) { it.copy(status = BufferStatus.TRANSCRIBING, durationMs = recordedBefore) }
        setState(State.PROCESSING)
        // Recording on a moment after stop, so a last word said while tapping goes in whole.
        val tail = if (audio != null) RECORD_TAIL_MS else 0L
        DebugLog.add("dictation: stopped after ${recordedBefore / 1000} s${if (tail > 0) ", recording on $tail ms" else ""}")
        val mine = attempt.get()
        // Joining the capture thread can take up to one chunk; keep it off the main thread.
        control.execute {
            if (tail > 0) Thread.sleep(tail)
            audio?.stop()
            // Cancelled meanwhile: kept as it is, nothing sent.
            main.post { if (current == id && attempt.get() == mine) transcribe(id) }
        }
    }

    fun cancel() {
        if (state == State.IDLE) return
        val id = current
        val audio = capture
        control.execute { audio?.stop() }
        if (continued) {
            // A kept recording stays kept, with what was added to it.
            val duration = elapsedMs()
            id?.let { BufferStore.update(it) { entry -> entry.copy(status = BufferStatus.KEPT, durationMs = duration) } }
        } else if (retried || state == State.PROCESSING) {
            // Stopped and on its way to the model, it is the user's recording: kept, with Retry offered.
            id?.let { BufferStore.update(it) { entry -> entry.copy(status = BufferStatus.KEPT) } }
            if (state == State.PROCESSING) {
                clear()
                retryId = id
                message = app.getString(R.string.cancelled_kept)
                DebugLog.add("dictation: cancelled while transcribed, kept")
                return setState(State.IDLE)
            }
        } else if (state == State.PAUSED || heardVoice) {
            // Not finished by the record button, the recording is the user's to finish: × keeps it in History, to
            // record on into it or send later. Only one with no voice in it at all goes.
            val duration = elapsedMs()
            id?.let { BufferStore.update(it) { entry -> entry.copy(status = BufferStatus.KEPT, durationMs = duration) } }
            clear()
            message = app.getString(R.string.saved_to_buffer)
            DebugLog.add("dictation: cancelled at ${duration / 1000} s, kept")
            return setState(State.IDLE)
        } else {
            id?.let { control.execute { main.post { BufferStore.delete(it) } } }
        }
        clear()
        DebugLog.add("dictation: cancelled")
        setState(State.IDLE)
    }

    /**
     * The keyboard's × while Retry is offered: the failed recording is let go. It stays in History, kept, and is not
     * sent again by itself.
     */
    fun dismiss() {
        if (state != State.IDLE) return
        retryId?.let { id -> BufferStore.update(id) { if (it.status == BufferStatus.FAILED) it.copy(status = BufferStatus.KEPT) else it } }
        retryId = null
        message = app.getString(R.string.saved_to_buffer)
        DebugLog.add("dictation: Retry let go, kept in History")
        notifyChanged()
    }

    /** Transcribes a kept recording again: the keyboard button, a buffer row, the dot menu. */
    fun retry(row: Long? = retryId) {
        val id = row ?: return
        if (state != State.IDLE) return
        val entry = BufferStore.get(id) ?: return
        if (BufferStore.audioFile(entry) == null) return
        current = id
        // Retry is tapped in front of the field it goes into: inputs started before it (while the first try failed)
        // no longer make it Insert here.
        startedId = id
        startedInputs = inputs
        retried = true
        message = null
        retryId = null
        useStyleOf(entry.app)
        stoppedAt = SystemClock.elapsedRealtime()
        BufferStore.update(id) { it.copy(status = BufferStatus.TRANSCRIBING, error = null, interrupted = false) }
        setState(State.PROCESSING)
        DebugLog.add("dictation: retry of a ${entry.durationMs / 1000} s recording")
        transcribe(id)
    }

    /** The kept audio to the model, within the overall time limit after stop. */
    private fun transcribe(id: Long) {
        val entry = BufferStore.get(id) ?: return finishIdle()
        val file = BufferStore.audioFile(entry)
        if (file == null || file.length() == 0L) {
            BufferStore.delete(id)
            message = app.getString(R.string.nothing_heard)
            DebugLog.add("dictation: nothing heard, no audio")
            return finishIdle()
        }
        if (termsStop) return failed(id, app.getString(R.string.agree_new_terms), BufferStatus.KEPT)
        if (!Server.ready) return failed(id, app.getString(R.string.no_account), BufferStatus.KEPT)
        Trace.current?.recording(id, file)
        send(id, file)
    }

    /**
     * The kept audio to the server, a call per piece that transcribes and shapes it. Pieces sent while recording are
     * waited for; what is left after them goes now, cut into pieces if it is long.
     */
    private fun send(id: Long, file: File) {
        val shape = Shape(style, translateTo)
        val stopped = stoppedAt
        // A piece sent while recording with the style or translation changed since goes again in this one, not mixed in.
        val (sent, changed) = pieces.toList().partition { it.shape == shape }
        changed.forEach { it.result.cancel(true) }
        if (changed.isNotEmpty()) DebugLog.add("dictation: ${changed.size} piece${if (changed.size == 1) "" else "s"} sent before the style changed, sent again")
        // A Retry with the same style and translation takes the pieces answered before as they are.
        val reused = done.remove(id).orEmpty().filter { it.shape == shape }.sortedBy { it.from }
        val left = unsent
        val mine = attempt.get()
        network.execute {
            val budget = { (TOTAL_LIMIT_MS - (SystemClock.elapsedRealtime() - stopped)).coerceAtLeast(MIN_CALL_MS) }
            // In order: the pieces sent while recording and the ones answered before, as they are; the gaps between them
            // sent now.
            val all = ArrayList<Piece>()
            fun send(from: Long, to: Long) = split(file, from, to).forEach { (a, b) ->
                val onSent = counted(left)
                val call = pieceWork.submit(Callable { transcribePiece(file, a, b, shape, budget, onSent) })
                calls += call
                // Given up while the calls were made: × may have missed this one.
                if (attempt.get() != mine) call.cancel(true)
                all += Piece(a, b, call, shape)
            }
            val have = sent + reused.map { Piece(it.from, it.to, CompletableFuture.completedFuture(it.text), shape) }
            var cursor = 0L
            for (piece in have.sortedBy { it.from }) {
                if (piece.from < cursor) continue
                send(cursor, piece.from)
                all += piece
                cursor = piece.to
            }
            send(cursor, file.length())
            // All of the rest is cut and counted: from here only its pieces are still to send.
            left.decrementAndGet()
            if (reused.isNotEmpty()) DebugLog.add("dictation: ${reused.size} piece${if (reused.size == 1) "" else "s"} answered before, not sent again")
            val parts = all.map { it.result }
            // A piece sent while recording that failed gets one more try now; one that fails after stop is final, and
            // a go given up is tried no more.
            val results = parts.mapIndexed { i, part ->
                runCatching { part.get() }.recoverCatching { error ->
                    // Offline it would only wait out the tries, piece after piece: kept now, sent when the network is back.
                    if (attempt.get() != mine || !isOnline()) throw error
                    val piece = all[i].takeIf { it in sent } ?: throw (error.cause ?: error)
                    DebugLog.add("dictation: piece ${i + 1} failed while recording, once more")
                    transcribePiece(file, piece.from, piece.to, piece.shape, budget, counted(left))
                }
            }
            main.post {
                if (current != id || attempt.get() != mine) return@post
                DebugLog.add(
                    "dictation: ${AudioCapture.durationMs(file.length()) / 1000} s in ${parts.size} piece${if (parts.size == 1) "" else "s"}" +
                        (if (sent.isEmpty()) "" else ", ${sent.size} sent while recording") + ", +${SystemClock.elapsedRealtime() - stopped} ms after stop",
                )
                val error = results.firstNotNullOfOrNull { it.exceptionOrNull() }
                if (error != null) {
                    DebugLog.add("dictation: failed: ${(error as? Server.Failure)?.let { "HTTP ${it.code} ${it.kind.orEmpty()}" } ?: error.javaClass.simpleName}")
                    done[id] = all.zip(results).filter { it.second.isSuccess }.map { (piece, result) -> Done(piece.from, piece.to, piece.shape, result.getOrNull()) }
                    return@post callFailed(id, error)
                }
                val texts = results.mapNotNull { it.getOrNull() }.filter { it.text.isNotEmpty() }
                if (texts.isEmpty()) return@post nothingHeard(id)
                val raw = join(texts.map { it.text })
                // The server keeps the styled text of a piece only if it holds to what was said; otherwise the words go in.
                val styled = texts.count { it.styled != null }
                when {
                    styled == texts.size -> {
                        styleDropped = false
                        deliver(id, raw, finish(join(texts.map { it.styled!! }, sentences = shape.style == Style.MESSAGE)), styled = true)
                    }
                    else -> {
                        // Off with no translation is no style to the person: its clean-up skipped needs no note.
                        styleDropped = shape.style != Style.OFF || shape.translateTo != null
                        if (styleDropped) message = app.getString(R.string.style_skipped)
                        DebugLog.add("dictation: style kept in $styled of ${texts.size} pieces")
                        deliver(id, raw, finish(join(texts.map { it.styled ?: it.text })), styled = styled > 0, keepMessage = true)
                    }
                }
            }
        }
    }

    /** The style and the translation a piece is sent with: Off and no translation still asks for the light clean-up. */
    private data class Shape(val style: Style, val translateTo: String?)

    /**
     * One piece of a dictation, or a draft for the live words: null when it holds no voice. [sent] once its audio is
     * all on the server, or when it holds none to send.
     */
    private fun transcribePiece(file: File, from: Long, to: Long, shape: Shape?, budget: () -> Long, sent: () -> Unit = {}): Text? {
        val read = CompactAudio.read(file, from, to)
        // A tap, a held press or a breath alone is no speech: sent, the model makes words up ("Давайте начинать.").
        val voiceMs = CompactAudio.voicedChunks(read, VOICE_LEVEL) * CHUNK_MS
        val durationMs = AudioCapture.durationMs(read.size.toLong())
        if (shape != null) DebugLog.add("dictation: piece of $durationMs ms, voice $voiceMs ms")
        if (voiceMs < MIN_VOICE_MS) {
            sent()
            return null
        }
        // The length sent stays the recorded one: minutes count what was said, not the slowed copy.
        val (audio, mime) = upload(if (shape != null && durationMs < STRETCH_UNDER_MS) CompactAudio.stretched(read, STRETCH_SPEED) else read)
        val piece = PieceBody(
            audio = Base64.encode(audio),
            mimeType = mime,
            durationMs = durationMs,
            style = shape?.style?.name,
            translateTo = shape?.translateTo?.let(::englishName),
            languages = prefs.speechLanguageName(),
            vocabulary = prefs.vocabulary.take(Learning.MAX_WORDS),
        )
        return retrying(if (shape == null) "draft" else "piece") {
            if (shape == null) Server.draft(piece, budget()) else Server.transcribe(piece, budget(), sent)
        }
    }

    /** The upload and its type: lossless FLAC, the one format; WAV only if this phone cannot encode FLAC. */
    private fun upload(pcm: ByteArray): Pair<ByteArray, String> =
        CompactAudio.flac(pcm)?.let { it to "audio/flac" } ?: (Wav.fromPcm(pcm, AudioCapture.SAMPLE_RATE) to "audio/wav")

    /**
     * Tries [call] again on a failure that may pass (the network, the server, a rate limit), 1, 2, then 4 s apart, so a
     * busy model is not pressed harder, starting no try later than [RETRY_LIMIT_MS] after the first; a refused request
     * or a blocked answer is not paid for again, and with no network at all the recording is kept for its return.
     */
    private fun <T> retrying(what: String, call: () -> T): T {
        val until = SystemClock.elapsedRealtime() + RETRY_LIMIT_MS
        var tries = 0
        while (true) {
            try {
                return call()
            } catch (e: Exception) {
                // Cancelled, or the same answer again: no more tries.
                val wait = RETRY_WAIT_MS shl tries.coerceAtMost(2)
                if (Thread.currentThread().isInterrupted || (e as? Server.Failure)?.final == true || !isOnline() ||
                    SystemClock.elapsedRealtime() + wait > until
                ) {
                    throw e
                }
                DebugLog.add("$what: failed (${(e as? Server.Failure)?.code ?: e.javaClass.simpleName}), trying again (${++tries})")
                Thread.sleep(wait)
            }
        }
    }

    private fun capture(file: File) =
        AudioCapture(file, { _, length ->
            recordedBytes += length
            if (!micReady) {
                micReady = true
                main.post(::onMicReady)
            }
        }, ::onLevel) { reason -> main.post { interrupt(reason) } }

    /** The mic's first sound of a new dictation: the keyboard shows Listening from now. */
    private fun onMicReady() {
        if (state != State.RECORDING || listeningShown) return
        main.post(showListening)
    }

    /** Broken off by anything but the user: paused and kept, nothing goes to the model. */
    private fun interrupt(reason: String) {
        if (state != State.RECORDING) return
        DebugLog.add("dictation: $reason, pausing")
        pause()
    }

    /** Sends the piece recorded since the last one, while the dictation goes on. */
    private fun sendPiece(from: Long, to: Long) {
        val id = current ?: return
        if (!state.open) return
        if (!Server.ready) return
        val file = audioOf(id)
        val shape = Shape(style, translateTo)
        val result = pieceWork.submit(Callable { transcribePiece(file, from, to, shape, { PIECE_LIMIT_MS }) })
        pieces += Piece(from, to, result, shape)
        DebugLog.add("dictation: piece ${pieces.size} sent while recording, ${AudioCapture.durationMs(to - from) / 1000} s")
    }

    /**
     * A few seconds just recorded, as a draft for the live words. It starts [DRAFT_OVERLAP_MS] inside the piece before,
     * so a word cut at the join is heard whole; the words said twice are dropped when the drafts are joined.
     */
    private fun sendDraft(from: Long, to: Long) {
        val id = current ?: return
        if (!state.open) return
        if (!Server.ready) return
        val file = audioOf(id)
        val start = (from - DRAFT_OVERLAP_MS / CHUNK_MS * AudioCapture.CHUNK_BYTES).coerceAtLeast(0L)
        drafts += draftWork.submit(Callable { transcribePiece(file, start, to, null, { DRAFT_LIMIT_MS }).also { main.post { showDrafts(id) } } })
    }

    /** The drafts back so far, in order up to the first still on its way, as the live words. */
    private fun showDrafts(id: Long) {
        if (current != id || !drafting || state == State.PROCESSING) return
        liveWords = glueDrafts(drafts.takeWhile { it.isDone }.mapNotNull { runCatching { it.get() }.getOrNull()?.text })
        notifyChanged()
    }

    /** The recording from byte [from] to [end], as byte ranges of pieces. */
    private fun split(file: File, from: Long, end: Long = file.length()): List<Pair<Long, Long>> {
        if (end <= from) return emptyList()
        val chunk = AudioCapture.CHUNK_BYTES.toLong()
        return CompactAudio.pieces(CompactAudio.levels(file, from, end), PIECE_CHUNKS, SILENCE_LEVEL)
            .map { from + it.first * chunk to minOf(from + (it.last + 1) * chunk, end) }
    }

    /**
     * The pieces' texts in one. [sentences]: Message leaves each piece without its last full stop, so where a piece ends
     * on a word and the next starts a sentence, the stop goes back in between.
     */
    private fun join(texts: List<String>, sentences: Boolean = false): String =
        texts.map { it.trim() }.filter { it.isNotEmpty() }.reduceOrNull { joined, next ->
            val stop = sentences && joined.last().isLetterOrDigit() && next.first().isUpperCase()
            joined + (if (stop) ". " else " ") + next
        }.orEmpty()

    /** A failed transcription call: the recording is kept untranscribed for Retry, with the error. */
    private fun callFailed(id: Long, error: Throwable) {
        // A request the model refuses fails the same way on every connection: kept for Retry, not sent again by itself.
        val status = if ((error as? Server.Failure)?.final == true) BufferStatus.KEPT else BufferStatus.FAILED
        failed(id, app.getString(R.string.failed_kept, short(error)), status)
    }

    private fun finish(text: String): String {
        val pairs = prefs.snippets.mapNotNull {
            val parts = it.split(Prefs.PAIR_SEPARATOR, limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        return Snippets.apply(text, pairs)
    }

    /** Into the field if there is one, otherwise into the buffer. */
    private fun deliver(
        id: Long,
        raw: String,
        text: String,
        styled: Boolean,
        keepMessage: Boolean = false,
    ) {
        val entry = BufferStore.get(id)
        Trace.current?.text("dictation: raw", raw)
        if (text != raw) Trace.current?.text("dictation: final", text)
        val other = targets.firstOrNull { it.seesOtherField(entry?.app, entry?.field) }
        if (other != null) DebugLog.add("dictation: ${other.javaClass.simpleName} sees another field in front")
        // Our keyboard's input started again since the dictation began: the field in front may be another chat.
        val moved = !resending && !sameInput(id)
        if (moved) DebugLog.add("dictation: the keyboard's input started again, offered as Insert here")
        val target = if (resending || other != null || moved) null
        else targets.firstOrNull { it.insert(text, entry?.app, entry?.field, id) }
        if (!keepMessage) message = null
        if (target != null) {
            if (target.incognito) {
                BufferStore.delete(id)
            } else {
                BufferStore.update(id) { it.copy(status = BufferStatus.INSERTED, raw = raw, text = text, error = null) }
                BufferStore.dropAudio(id)
                entry?.app?.takeIf { it.isNotEmpty() }?.let { Learning.inserted(text, it, learningField()) }
            }
            DebugLog.add("dictation: inserted ${text.length} chars${if (styled) " (styled)" else ""} +${SystemClock.elapsedRealtime() - stoppedAt} ms after stop")
        } else {
            BufferStore.update(id) { it.copy(status = BufferStatus.READY, raw = raw, text = text, error = null) }
            BufferStore.dropAudio(id)
            val from = entry?.app?.takeIf { it.isNotEmpty() && !resending }
            if (from != null) {
                // Its field no longer told apart, it goes in only by Insert here, in any field.
                waiting = Waiting(id, text, from, entry.field.takeUnless { moved }, SystemClock.elapsedRealtime() + WAIT_FOR_FIELD_MS)
                DebugLog.add("dictation: not inserted, saved to buffer; ${if (moved) "offered as Insert here" else "goes in on return"}")
            } else {
                message = app.getString(R.string.saved_to_buffer)
                DebugLog.add("dictation: no field, saved to buffer")
            }
        }
        finishIdle()
    }

    /**
     * A field has appeared: the waiting dictation goes in only if it is the very field it was made in, checked
     * by a target that can tell fields apart. Any other field gets it by Insert here, or from the buffer.
     */
    fun deliverWaiting() {
        val wait = waiting ?: return
        if (state != State.IDLE) return
        // Too late, or already taken from the buffer: the wait ends there.
        if (!isWaiting) {
            waiting = null
            notifyChanged()
            return
        }
        // It goes back by itself only into the very field it was made in: our keyboard's input not started again since,
        // and the dot's field the same element. Anywhere else our keyboard offers it as Insert here.
        val field = wait.field ?: return
        if (!sameInput(wait.id)) {
            waiting = Waiting(wait.id, wait.text, wait.app, null, wait.until)
            DebugLog.add("dictation: the keyboard's input started again, the waiting text is offered as Insert here")
            return notifyChanged()
        }
        if (targets.any { it.seesOtherField(wait.app, field) }) return
        val target = targets.firstOrNull { (it.knowsField(field) || !field.startsWith(DotService.FIELD_PREFIX)) && it.insert(wait.text, wait.app, field, wait.id) }
            ?: return
        endWait(wait, target, "back in its app")
    }

    /** The field a dictation just went into, as every target can tell it: it has the focus now. */
    private fun learningField() = Learning.Field(
        targets.firstNotNullOfOrNull { it.learningField?.node },
        targets.firstNotNullOfOrNull { it.learningField?.signature },
    )

    private fun endWait(wait: Waiting, target: Target, how: String) {
        waiting = null
        if (target.incognito) {
            BufferStore.delete(wait.id)
        } else {
            BufferStore.update(wait.id) { it.copy(status = BufferStatus.INSERTED) }
            Learning.inserted(wait.text, wait.app, learningField())
        }
        DebugLog.add("dictation: $how, inserted ${wait.text.length} chars")
        notifyChanged()
    }

    /** No words came back: a new recording goes, but one the user kept (a retry, a continued one) stays kept. */
    private fun nothingHeard(id: Long) {
        DebugLog.add("dictation: nothing heard")
        if (retried || continued) return failed(id, app.getString(R.string.failed_kept, app.getString(R.string.nothing_heard)), BufferStatus.KEPT)
        BufferStore.delete(id)
        message = app.getString(R.string.nothing_heard)
        finishIdle()
    }

    private fun failed(id: Long, text: String, status: BufferStatus = BufferStatus.FAILED) {
        // Sent again by itself only once, on the next connection; a Retry that failed too, the person's own or that
        // one, leaves it kept: it goes again only when the person taps Retry.
        resent += id
        val kept = if (retried) BufferStatus.KEPT else status
        BufferStore.update(id) { it.copy(status = kept, error = text) }
        message = text
        finishIdle(retry = id)
    }

    /** Back to idle; [retry] is the recording Retry sends again, if any. */
    private fun finishIdle(retry: Long? = null) {
        clear()
        retryId = retry
        setState(State.IDLE)
        resendFailed()
    }

    private fun clear() {
        // The go ends: its calls still on their way stop, and what they bring back is dropped. Neither is a failure,
        // so nothing of it is sent again.
        attempt.incrementAndGet()
        pieces.forEach { it.result.cancel(true) }
        drafts.forEach { it.cancel(true) }
        calls.forEach { it.cancel(true) }
        calls.clear()
        current = null
        pieces.clear()
        drafts.clear()
        drafting = false
        capture = null
        liveWords = ""
        retryId = null
        continued = false
        retried = false
        resending = false
    }

    private fun onLevel(level: Float) {
        if (level > SILENCE_LEVEL) {
            silentMs = 0
            heardVoice = true
        } else {
            silentMs += CHUNK_MS
        }
        pieceFrom = cut(pieceFrom, level, PIECE_CHUNKS, ::sendPiece)
        if (drafting) draftFrom = cut(draftFrom, level, DRAFT_CHUNKS, ::sendDraft)
        val autoPause = heardVoice && silentMs >= AUTO_PAUSE_MS && prefs.autoPause
        // No voice at all from the start: a recording begun by mistake, dropped rather than kept running.
        val unheard = !heardVoice && silentMs >= NO_VOICE_CANCEL_MS
        main.post {
            if (unheard && state == State.RECORDING) {
                DebugLog.add("dictation: no voice for ${silentMs / 1000} s, dropped")
                message = app.getString(R.string.nothing_heard)
                cancel()
                return@post
            }
            if (autoPause && state == State.RECORDING) {
                DebugLog.add("dictation: auto-pause after silence")
                pause()
            }
        }
    }

    /** Where the next piece of at most [chunks] starts: here, with the one since [from] sent, if it is cut now. */
    private fun cut(from: Long, level: Float, chunks: Int, send: (Long, Long) -> Unit): Long {
        val to = recordedBytes
        if (!CompactAudio.cutsHere(((to - from) / AudioCapture.CHUNK_BYTES).toInt(), level, chunks, SILENCE_LEVEL)) return from
        main.post { send(from, to) }
        return to
    }

    /** The style and translation set for [app] now. */
    private fun useStyleOf(app: String) {
        style = prefs.styleFor(app)
        translateTo = prefs.translateTo.takeIf { it.isNotEmpty() }
    }

    private fun audioOf(id: Long) = File(BufferStore.audioDir, "$id.pcm")

    private fun setState(value: State) {
        state = value
        slow = false
        slowNetwork = false
        main.removeCallbacks(markSlow)
        main.removeCallbacks(watchSending)
        // Connecting from the stop until the recording is all sent; then Processing.
        sending = value == State.PROCESSING
        if (value == State.PROCESSING) {
            unsent = AtomicInteger(1)
            main.postDelayed(watchSending, SEND_CHECK_MS)
            val audioMs = current?.let(BufferStore::get)?.durationMs ?: 0L
            main.postDelayed(markSlow, SLOW_MS + audioMs / SLOW_AUDIO_DIVISOR)
        }
        RecordingService.follow(app, value)
        notifyChanged()
    }

    private fun notifyChanged() = observers.toList().forEach { it.onDictationChanged() }

    /** One more of [count] now; the call it gives makes it one less, the first time only: an upload may be written again. */
    private fun counted(count: AtomicInteger): () -> Unit {
        count.incrementAndGet()
        val done = AtomicBoolean()
        return { if (done.compareAndSet(false, true)) count.decrementAndGet() }
    }

    /**
     * While the person speaks: the sign-in token, the connection to the server and the server's own to the model get
     * ready. After an idle while each takes its time, and the stop would wait for all of them.
     */
    private fun warm() {
        network.execute {
            val started = SystemClock.elapsedRealtime()
            val result = runCatching { Server.plan() }
            DebugLog.add("dictation: warmed up in ${SystemClock.elapsedRealtime() - started} ms${result.exceptionOrNull()?.let { ", ${it.javaClass.simpleName}" } ?: ""}")
        }
    }

    /** Asks the server for the plan, as the app opens and after a purchase; [done] runs on the network thread. */
    fun refreshPlan(done: () -> Unit = {}) {
        if (!Server.ready) return
        network.execute {
            runCatching { Server.plan() }.onFailure { DebugLog.add("account: plan unknown: ${it.javaClass.simpleName}") }
            DebugLog.add("account: plan ${Account.plan}")
            Sync.pull()
            done()
        }
    }

    private fun isOnline(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun short(error: Throwable): String = when (error) {
        is Server.Failure -> when {
            error.code == FreeTier.USED_UP -> app.getString(R.string.free_used_up)
            error.kind == Api.DAY_USED_UP -> app.getString(R.string.tide_day_used_up)
            // Before the plan: App Check's refusal of a free call comes with the same 403 as "Tide needed".
            error.kind == Api.UNVERIFIED -> app.getString(R.string.error_unverified)
            error.code == Plan.NEEDED -> app.getString(R.string.tide_needed)
            error.kind == Api.NO_TEXT -> app.getString(R.string.nothing_heard)
            // For people there are two causes only: their internet (below) or our server, whatever failed behind it.
            else -> app.getString(R.string.error_server)
        }
        // Their internet: none, or too slow. Never a class name or a raw message; the log has them.
        is java.io.InterruptedIOException -> app.getString(if (isOnline()) R.string.no_answer_in_time else R.string.no_network)
        is java.io.IOException -> app.getString(R.string.no_network)
        // Never a class name or a raw message in the line people read; the log has them.
        else -> app.getString(R.string.error_unknown)
    }

    private const val CHUNK_MS = 100L
    private const val PARALLEL_PIECES = 4

    /** A long recording goes out in pieces of about two minutes, each cut at a quiet moment. */
    private const val PIECE_CHUNKS = (120_000L / CHUNK_MS).toInt()

    /** With live words, a draft every two seconds or so of speech. */
    private const val DRAFT_CHUNKS = (2_000L / CHUNK_MS).toInt()

    /** Each draft also takes this much of the audio before it, so a word cut at the join is heard whole. */
    private const val DRAFT_OVERLAP_MS = 500L

    /** Quieter than this is a pause: where pieces are cut, and what auto-pause and the no-voice drop count. */
    private const val SILENCE_LEVEL = 0.12f

    /**
     * What counts as voice for [MIN_VOICE_MS]: speech on a phone is 0.55 to 0.95 on this scale (-52 to -12 dBFS), while
     * [SILENCE_LEVEL] lets a held press with nothing said through as voice.
     */
    private const val VOICE_LEVEL = 0.4f

    /** A piece with less voice than this is taken as no speech and never sent. */
    private const val MIN_VOICE_MS = 300L

    /** The mic records on this long after stop, so a last word said while tapping is not cut. */
    private const val RECORD_TAIL_MS = 500L

    /** Silence after speech before Auto-pause pauses. */
    private const val AUTO_PAUSE_MS = 4_000L

    /** A recording with no voice at all this long was begun by mistake: dropped. */
    private const val NO_VOICE_CANCEL_MS = 60_000L

    /** How long a finished dictation waits for the app it was made in. */
    private const val WAIT_FOR_FIELD_MS = 10 * 60_000L

    /** After stop, the pieces left get this long in all, but each call at least [MIN_CALL_MS]. */
    private const val TOTAL_LIMIT_MS = 15_000L
    private const val MIN_CALL_MS = 5_000L

    /** A failed call is tried again a second apart, for this long. */
    private const val RETRY_LIMIT_MS = 10_000L
    private const val RETRY_WAIT_MS = 1_000L

    /** A piece sent while recording has time: nobody waits for it yet. */
    private const val PIECE_LIMIT_MS = 60_000L

    /** A draft late by this much is no use as live words. */
    private const val DRAFT_LIMIT_MS = 10_000L

    /**
     * After stop the text usually comes in 1.5 s plus 40 ms per second of speech; past this, plus a second per 20 s
     * of speech, the wait is said to be longer than usual.
     */
    private const val SLOW_MS = 4_000L
    private const val SLOW_AUDIO_DIVISOR = 20
    private const val SLOW_CHECK_MS = 1_000L
    private const val SEND_CHECK_MS = 150L

    /**
     * A piece under this length goes this much slower, its pitch kept: in a short one fast speech was misheard more
     * often, and slowed it was misheard less (one A/B round, 2026-10-31). Drafts go as recorded.
     */
    private const val STRETCH_UNDER_MS = 30_000L
    private const val STRETCH_SPEED = 0.85f
}
