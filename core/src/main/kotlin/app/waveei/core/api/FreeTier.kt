package app.waveei.core.api

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.IsoFields

/**
 * The free tier, the same on the server and in the app: minutes of dictation a week without an account.
 * The week is an ISO week in UTC, so it starts on Monday at 00:00 UTC for everyone.
 */
object FreeTier {
    /** On: dictation needs no account, within the week's minutes, which the server sets. */
    const val ON = true

    /**
     * Sent by the app outside Tide with each transcription, draft and plan call: a hash of the phone's id for this app,
     * which stays through a reinstall or cleared data, so the week's minutes count by phone as well as by account.
     */
    const val DEVICE = "X-Device-Id"

    /** Sent back by the server to a free user: the minutes left this week, in ms. */
    const val LEFT_MS = "X-Free-Left-Ms"

    /** The server's answer once the week's free minutes are used up. */
    const val USED_UP = 402

    /** The week of [epochMs], such as 2026-W43. */
    fun week(epochMs: Long): String {
        val day = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
        return "%d-W%02d".format(day.get(IsoFields.WEEK_BASED_YEAR), day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }
}
