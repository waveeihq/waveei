package app.waveei.core.api

/** The caller's plan, the same on the server and in the app. */
object Plan {
    /** Sent back by the server with its answers: [TIDE], [FREE] or [NONE]. */
    const val HEADER = "X-Plan"

    const val TIDE = "tide"
    const val FREE = "free"

    /** Signed in, with no Tide and the free tier off: dictation needs Tide. */
    const val NONE = "none"

    /** The server's answer to a dictation on [NONE]. */
    const val NEEDED = 403

    /** Sent with the plan when Tide's last payment failed: Stripe's status, [PAST_DUE] or [UNPAID]. */
    const val BILLING = "X-Billing"

    /** Stripe is retrying the card; Tide stays on meanwhile. */
    const val PAST_DUE = "past_due"

    /** Stripe gave up retrying; Tide is off until the card is updated and the invoice paid. */
    const val UNPAID = "unpaid"

    /** Sent with the plan as "no" when Tide is cancelled and runs to the period's end: the app offers Resubscribe. */
    const val RENEWS = "X-Renews"

    /** Sent with the plan: Tide's payment page, so a new price or plan needs no new build of the app. */
    const val LINK = "X-Tide-Link"
}
