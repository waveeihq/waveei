package app.waveei.android

import android.os.Build
import android.os.SystemClock
import app.waveei.core.api.Api
import app.waveei.core.api.FailureAnswer
import app.waveei.core.api.FreeTier
import app.waveei.core.api.LogBatch
import app.waveei.core.api.Piece
import app.waveei.core.api.StatsBatch
import app.waveei.core.api.Text
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Waveei server ([Api]): every call carries the sign-in and the App Check token when one is at hand; the calls
 * that count free minutes also carry the phone's coded id, unless the account has Tide. Blocking: call off the main thread.
 */
object Server {
    /** A failed call; [final] when trying it again would fail the same way: a refused request, a blocked answer. */
    class Failure(
        message: String,
        val code: Int = 0,
        /** One of [Api.BLOCKED], [Api.LOOPED], [Api.NO_TEXT], [Api.UNVERIFIED], [Api.DAY_USED_UP], or null. */
        val kind: String? = null,
        val final: Boolean = code in 400..499 && code != 408 && code != 429 || kind == Api.DAY_USED_UP,
    ) : IOException(message)

    /** Dictation can go through the server: signed in to Tide, or, while the free tier is on, without an account. */
    val ready: Boolean get() = Account.ready

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // No limit between bytes, either way: a long piece can take the model longer than any gap limit to answer,
            // and a slow network can stall an upload for longer than OkHttp's 10 s default without losing it. Each
            // dictation call has its own whole-call timeout instead.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                // No token yet (a slow refresh, no network): a failure worth trying again, not a refusal by the server.
                val token = Account.token() ?: throw IOException("no sign-in token")
                val request = chain.request().newBuilder().header("Authorization", "Bearer $token").header(Api.BUILD, BuildConfig.VERSION_CODE.toString())
                Account.appCheckToken()?.let { request.header(APP_CHECK, it) }
                if (!Account.tide && chain.request().url.encodedPath in COUNTED) Account.deviceId?.let { request.header(FreeTier.DEVICE, it) }
                chain.proceed(request.build())
            }
            .build()
    }

    /** A piece of a dictation: its words and, when asked for, the shaped text; [onSent] once its body is written out. */
    fun transcribe(piece: Piece, timeoutMs: Long, onSent: () -> Unit = {}): Text = piece(Api.TRANSCRIBE, piece, timeoutMs, onSent)

    /** A few seconds for the live words: the words only. */
    fun draft(piece: Piece, timeoutMs: Long): Text = piece(Api.DRAFT, piece, timeoutMs)

    /** The plan and the free minutes left, also getting the way to the model ready; read into [Account]. */
    fun plan() {
        short(Request.Builder().url(url(Api.PLAN)).build()).execute().use {
            Account.note(it)
            Account.noteBilling(it)
        }
    }

    /** To the server's log: a message, lines of the event log, or both; the HTTP status. */
    fun log(message: String? = null, lines: String? = null): Int {
        val batch = LogBatch("${BuildConfig.VERSION_NAME} Android ${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}", message, lines)
        val body = Api.json.encodeToString(LogBatch.serializer(), batch).toRequestBody(JSON)
        return short(Request.Builder().url(url(Api.LOG)).post(body).build()).execute().use { it.code }
    }

    /** A day of usage statistics, without the sign-in: only App Check's token goes with it. The HTTP status. */
    fun stats(batch: StatsBatch): Int {
        val body = Api.json.encodeToString(StatsBatch.serializer(), batch).toRequestBody(JSON)
        val request = Request.Builder().url(url(Api.STATS)).post(body)
        Account.appCheckToken()?.let { request.header(APP_CHECK, it) }
        return open.newCall(request.build()).execute().use { it.code }
    }

    /** Tide's settings as the server keeps them for the account, and when they were stored; null when it keeps none. */
    fun settings(): Pair<String, Long>? = short(Request.Builder().url(url(Api.SETTINGS)).build()).execute().use {
        if (it.code == 204) return null
        if (!it.isSuccessful) throw Failure("HTTP ${it.code}", it.code)
        it.body.string() to (it.header(Api.SETTINGS_AT)?.toLongOrNull() ?: 0)
    }

    /** Replaces the account's settings on the server with [json]; when they were stored. */
    fun putSettings(json: String): Long =
        short(Request.Builder().url(url(Api.SETTINGS)).put(json.toRequestBody(JSON)).build()).execute().use {
            if (!it.isSuccessful) throw Failure("HTTP ${it.code}", it.code)
            it.header(Api.SETTINGS_AT)?.toLongOrNull() ?: 0
        }

    /** The same connections without the sign-in, for the calls that must not tell who sent them. */
    private val open: OkHttpClient by lazy { client.newBuilder().apply { interceptors().clear() }.callTimeout(SHORT_CALL_S, TimeUnit.SECONDS).build() }

    /** An empty POST to [path]: the HTTP status and, for a failure, its kind ([FailureAnswer]). */
    fun post(path: String): Pair<Int, String?> =
        short(Request.Builder().url(url(path)).post(ByteArray(0).toRequestBody()).build()).execute().use {
            it.code to if (it.isSuccessful) null else runCatching { Api.json.decodeFromString(FailureAnswer.serializer(), it.body.string()).error.kind }.getOrNull()
        }

    /** A GET of [path]: the body of a successful answer, null otherwise. */
    fun get(path: String): String? =
        short(Request.Builder().url(url(path)).build()).execute().use { if (it.isSuccessful) it.body.string() else null }

    /** A call that is not a dictation's: done within [SHORT_CALL_S] whatever the network does, never hanging on. */
    private fun short(request: Request) = client.newCall(request).apply { timeout().timeout(SHORT_CALL_S, TimeUnit.SECONDS) }

    /** Calls still connecting or sending their body: a slow wait with one of them is the network's, not the model's. */
    val uploading = AtomicInteger()

    fun url(path: String) = BuildConfig.SERVER_URL + path

    private fun piece(path: String, piece: Piece, timeoutMs: Long, onSent: () -> Unit = {}): Text {
        val body = Api.json.encodeToString(Piece.serializer(), piece)
        val upload = Upload(body.toRequestBody(JSON), onSent)
        val call = client.newCall(Request.Builder().url(url(path)).post(upload).build())
        // [timeoutMs] plus time to upload the body at a slow 1 Mbit/s and for the model to hear the audio.
        call.timeout().timeout(timeoutMs.coerceIn(1_000, 600_000) + body.length / UPLOAD_BYTES_PER_MS + piece.durationMs / 10, TimeUnit.MILLISECONDS)
        val sent = SystemClock.elapsedRealtime()
        val size = body.length / 1024
        val response = try {
            call.execute()
        } catch (e: IOException) {
            DebugLog.add("net: $path ${size} KB failed after ${SystemClock.elapsedRealtime() - sent} ms: ${e.javaClass.simpleName}")
            throw e
        } finally {
            upload.done()
        }
        return response.use {
            Account.note(response)
            val text = response.body.string()
            Trace.current?.call(path, piece, text)
            // Each call on its own, to tell a slow network from a slow model.
            DebugLog.add("net: $path ${size} KB, HTTP ${response.code} in ${SystemClock.elapsedRealtime() - sent} ms")
            if (!response.isSuccessful) throw failure(response, text)
            runCatching { Api.json.decodeFromString(Text.serializer(), text) }.getOrNull() ?: throw Failure("not an answer", kind = Api.NO_TEXT)
        }
    }

    private fun failure(response: Response, text: String): Failure {
        val error = runCatching { Api.json.decodeFromString(FailureAnswer.serializer(), text).error }.getOrNull()
        return Failure(error?.message ?: "HTTP ${response.code}", response.code, error?.kind)
    }

    /** A call's body, counted in [uploading] from the call's start until it is all written out, then [onSent]. */
    private class Upload(private val body: RequestBody, private val onSent: () -> Unit) : RequestBody() {
        private val counted = AtomicBoolean(true).also { uploading.incrementAndGet() }

        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun writeTo(sink: BufferedSink) {
            body.writeTo(sink)
            sink.flush()
            done()
            onSent()
        }

        fun done() {
            if (counted.compareAndSet(true, false)) uploading.decrementAndGet()
        }
    }

    private const val APP_CHECK = "X-Firebase-AppCheck"

    /** The calls the free tier counts, or answers with the minutes left. */
    private val COUNTED = setOf(Api.TRANSCRIBE, Api.DRAFT, Api.PLAN)
    private val JSON = "application/json".toMediaType()
    private const val UPLOAD_BYTES_PER_MS = 125
    private const val SHORT_CALL_S = 30L
}
