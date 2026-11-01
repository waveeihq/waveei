package app.waveei.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The app's calls to the Waveei server, the same on both sides. Every call but [STATS] carries the person's sign-in
 * (Firebase ID token, anonymous for the free tier). A dictation goes as its pieces: each piece's audio with the
 * person's own choices, never a prompt; the server words the request to the model itself.
 */
object Api {
    /** A piece of a dictation: its words and, with a style or a translation, the shaped text. POST [Piece], answers [Text]. */
    const val TRANSCRIBE = "/v1/transcribe"

    /**
     * A few seconds of a dictation still going on, for the live words: the words only. POST [Piece], answers [Text].
     * Tide only: [Plan.NEEDED] otherwise.
     */
    const val DRAFT = "/v1/draft"

    /**
     * The plan in the answer's headers ([Plan.HEADER], [FreeTier.LEFT_MS], [Plan.BILLING], [Plan.RENEWS]). Sent as a
     * dictation starts, it also gets the way to the model ready while the person speaks. GET.
     */
    const val PLAN = "/v1/plan"

    /** Waveei Tide ends at the end of the period paid for. POST. */
    const val TIDE_CANCEL = "/v1/tide/cancel"

    /** A cancelled Tide that still runs renews again. POST. */
    const val TIDE_RESUME = "/v1/tide/resume"

    /** Within 14 days of subscribing: the unused days refunded and Tide ended at once; 409 later. POST. */
    const val TIDE_REFUND = "/v1/tide/refund"

    /** Tide's renewal, card and invoices. GET, answers JSON. */
    const val TIDE_BILLING = "/v1/tide/billing"

    /** The account and everything the server keeps of it. POST. */
    const val ACCOUNT_DELETE = "/v1/account/delete"

    /**
     * Tide's settings, kept in step across the account's phones: a JSON object of the settings the person picks, their
     * vocabulary and text replacements. GET answers the stored copy (204 when there is none), PUT replaces it; both
     * tell when it was stored in [SETTINGS_AT]. Tide only: [Plan.NEEDED] otherwise.
     */
    const val SETTINGS = "/v1/settings"

    /** When the stored settings were written, in ms since the epoch, by the server's clock. */
    const val SETTINGS_AT = "X-Settings-At"

    /** The most a settings copy may be, in bytes. */
    const val MAX_SETTINGS = 64 * 1024

    /**
     * The server's log, where an app sends what it chooses to: a message the person wrote, lines of its event log, or
     * both. POST [LogBatch]. The server keeps it in its log without the account, for 30 days.
     */
    const val LOG = "/v1/log"

    /**
     * Usage statistics: a day's counts of what an app did, and its crash traces. POST [StatsBatch], without the
     * sign-in: only App Check's token goes with it. The server keeps them without anything that tells who sent them.
     */
    const val STATS = "/v1/stats"

    /** Every call but [STATS] carries the app's build, its version code, so the server's log tells builds apart. */
    const val BUILD = "X-Build"

    /** The answer's kind of failure when Tide's dictation for today is used up: 429, and no use trying again today. */
    const val DAY_USED_UP = "day_used_up"

    /** The answer's kind of failure when an account is not deleted: Tide is within its 14 days, refund it first. */
    const val REFUND_FIRST = "refund_first"

    /** Tide would renew: cancel it first. */
    const val CANCEL_FIRST = "cancel_first"

    /** App Check could not vouch for the app on this phone: the free tier needs it, Tide does not. */
    const val UNVERIFIED = "unverified"

    /** The answer's kind of failure ([Failure.kind]): the model's filter stopped it, every try would fail the same. */
    const val BLOCKED = "blocked"

    /** The model ran out of room, often repeating itself; another try usually comes out whole. */
    const val LOOPED = "looped"

    /** The model answered something that is not a transcript. */
    const val NO_TEXT = "no_text"

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
}

/** A piece of audio and what the person chose for it. */
@Serializable
data class Piece(
    /** The audio, base64. */
    val audio: String,
    /** audio/flac or audio/wav. */
    val mimeType: String,
    /** The recording's length before any speed-up: what the free tier counts. */
    val durationMs: Long,
    /** A [app.waveei.core.style.Style] name; null for the words alone, as said. Not sent for a draft. */
    val style: String? = null,
    /** The language to translate into, in English (German); null for no translation. */
    val translateTo: String? = null,
    /** The languages the person speaks, in English (Russian, English); null when not set. */
    val languages: String? = null,
    /** The person's own spellings of names and terms. */
    val vocabulary: List<String> = emptyList(),
)

/** The words of a piece and, when a style or a translation was asked for, the shaped text. */
@Serializable
data class Text(val text: String, val styled: String? = null)

/** What an app sends to the server's log. */
@Serializable
data class LogBatch(
    /** The app's version, Android's and the phone's model. */
    val app: String,
    /** A message the person wrote, from the Feedback screen. */
    val message: String? = null,
    /** Lines of the event log, one event a line. */
    val lines: String? = null,
) {
    companion object {
        const val MAX_MESSAGE = 4_000
        const val MAX_LINES = 64 * 1024
    }
}

/** A day of usage statistics from one app: how it was used, never by whom. */
@Serializable
data class StatsBatch(
    /** The day counted, yyyy-MM-dd in UTC. */
    val day: String,
    /** The app's build (its version code). */
    val build: Long,
    /** Android's SDK level. */
    val android: Int,
    /** How many times each kind of event happened that day ("ui: open settings", "key: mic"). */
    val counts: Map<String, Long>,
    /** That day's crash traces: exception kinds and places, never their messages. */
    val faults: List<String> = emptyList(),
) {
    companion object {
        const val MAX_KINDS = 500
        const val MAX_KIND = 100
        const val MAX_FAULTS = 5
        const val MAX_FAULT = 12_000
    }
}

/** A failed call's answer: `{"error": {...}}`, with the HTTP status as [code]. */
@Serializable
data class Failure(val code: Int, val message: String, val kind: String? = null)

@Serializable
data class FailureAnswer(val error: Failure)
