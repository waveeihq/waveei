package app.waveei.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import java.text.NumberFormat
import java.util.Currency
import java.util.Date
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.waveei.android.Account
import app.waveei.android.BufferStore
import app.waveei.android.rawDiffers
import app.waveei.android.rowText
import app.waveei.android.DebugLog
import app.waveei.android.Diagnostics
import app.waveei.android.Dictation
import app.waveei.android.MainActivity
import app.waveei.android.BuildConfig
import app.waveei.android.R
import app.waveei.android.Updater
import app.waveei.core.api.LogBatch
import app.waveei.core.buffer.BufferEntry
import app.waveei.core.buffer.BufferStatus
import app.waveei.core.api.FreeTier

/** History: recent dictations as rows by day, one main action each and the rest in ⋯; what is kept, under them. */
@Composable
fun BufferScreen(activity: MainActivity, nav: Navigator) {
    val entries = rememberBufferEntries()
    val raw = remember { mutableListOf<Long>().toMutableStateList() }
    val open = remember { mutableListOf<Long>().toMutableStateList() }
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Pair<BufferEntry, Rect>?>(null) }
    Page(Screen.BUFFER, nav, action = { TextAction(stringResource(R.string.clear), enabled = entries.isNotEmpty()) { confirm = true } }) {
        // As every other empty list: a glyph, a line and what will show here.
        if (entries.isEmpty()) EmptyState("clock-counter-clockwise", stringResource(R.string.no_dictations), stringResource(R.string.buffer_empty))
        val today = stringResource(R.string.today)
        entries.groupBy { day(context, it.id, today) }.forEach { (day, rows) ->
            Group(day) {
                rows.forEachIndexed { i, entry ->
                    if (i > 0) Divider()
                    HistoryRow(
                        entry,
                        showRaw = entry.id in raw,
                        expanded = entry.id in open,
                        onExpand = { if (!open.remove(entry.id)) open += entry.id },
                        onMenu = { menu = entry to it },
                        context = context,
                    )
                }
            }
        }
        KeepGroup(activity)
    }
    menu?.let { (entry, anchor) ->
        val showRaw = entry.id in raw
        val items = buildList {
            // On every text row, greyed where the edited text is the original as it was said.
            if (entry.hasText) {
                add(MenuItem("file-text", stringResource(if (showRaw) R.string.show_final else R.string.show_raw), enabled = entry.rawDiffers) { if (!raw.remove(entry.id)) raw += entry.id })
            }
            if (canRetry(entry)) add(MenuItem("arrow-counter-clockwise", stringResource(R.string.retry)) { Dictation.retry(entry.id) })
            add(MenuItem("trash", stringResource(R.string.delete), destructive = true) { BufferStore.delete(entry.id) })
        }
        VeilMenu(anchor, items) { menu = null }
    }
    if (confirm) {
        ConfirmDialog(stringResource(R.string.clear_buffer_question), stringResource(R.string.clear_buffer_warning), stringResource(R.string.clear), onConfirm = BufferStore::clearDone) { confirm = false }
    }
}

/** Recordings and transcripts kept. */
@Composable
fun KeepGroup(activity: MainActivity) {
    Group(stringResource(R.string.group_keep)) {
        RecordingsKeptRow(activity)
        Divider()
        TranscriptsKeptRow(activity)
    }
    Note(stringResource(R.string.older_deleted))
}

@Composable
fun RecordingsKeptRow(activity: MainActivity) {
    var recordings by rememberPref({ activity.prefs.bufferRecordings }) { activity.prefs.bufferRecordings = it }
    SegmentedRow(stringResource(R.string.recordings_kept), null, CAPACITIES, recordings) { recordings = it }
}

@Composable
fun TranscriptsKeptRow(activity: MainActivity) {
    var transcripts by rememberPref({ activity.prefs.bufferTranscripts }) { activity.prefs.bufferTranscripts = it }
    SegmentedRow(stringResource(R.string.transcripts_kept), null, CAPACITIES, transcripts) { transcripts = it }
}

private val CAPACITIES = listOf(1, 3, 5, 10, 20).map { it to it.toString() }

/** "Today", or the date of an older day. */
private fun day(context: Context, time: Long, today: String) =
    if (DateUtils.isToday(time)) today else DateFormat.getMediumDateFormat(context).format(time)

/** Kept audio can be sent again once nothing else is going on and dictation is allowed. */
private fun canRetry(entry: BufferEntry) = retryBlock(entry) == null

/** Why a row's audio cannot be sent again now, in words for the row; null when it can. */
private fun retryBlock(entry: BufferEntry): Int? = when {
    entry.status.active -> R.string.retry_busy
    // With the free tier no sign-in is needed, only the anonymous account, which comes with the connection.
    !Account.ready -> if (FreeTier.ON) R.string.retry_no_account else R.string.retry_sign_in
    Dictation.state != Dictation.State.IDLE -> R.string.retry_busy
    BufferStore.audioFile(entry) == null -> R.string.retry_gone
    else -> null
}

/** A History row: the text (a tap shows all of it), the meta line, the main action and ⋯. */
@Composable
private fun HistoryRow(entry: BufferEntry, showRaw: Boolean, expanded: Boolean, onExpand: () -> Unit, onMenu: (Rect) -> Unit, context: Context) {
    val c = appColors
    val text = entry.rowText(context, showRaw)
    var anchor by remember { mutableStateOf(Rect.Zero) }
    val words: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.clickable(enabled = entry.hasText, onClick = onExpand)) {
            val style = type(16f, 24f, Fonts.MEDIUM)
            // In ink with or without text: only the pill says whether it can be sent now.
            if (expanded) SelectionContainer { Text(text, style = style, color = c.ink) } else OneLine(text, style, c.ink)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusMark(entry.status)
                // A kept recording's title says it is not transcribed yet; the meta line does not say it again.
                val meta = listOfNotNull(
                    DateFormat.getTimeFormat(context).format(entry.id),
                    appLabel(context, entry.app),
                    statusLabel(entry.status),
                ).joinToString(" · ")
                // A large font wraps the line between words rather than cutting its last word off.
                if (LocalDensity.current.fontScale >= LARGE_FONT) Text(meta, style = type(14f, 20f), color = c.ink2) else OneLine(meta, type(14f, 20f), c.ink2)
            }
            entry.error?.takeIf { !entry.hasText }?.let { error ->
                Text(error, style = type(14f, 20f), color = c.red, modifier = Modifier.padding(top = 2.dp))
            }
            // A grey pill never stands without its reason.
            retryBlock(entry)?.takeIf { !entry.hasText }?.let { reason ->
                Text(stringResource(reason), style = type(14f, 20f), color = c.ink2, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    val actions: @Composable () -> Unit = {
        Trailing {
            when {
                entry.hasText -> PillButton(stringResource(R.string.copy), kind = PillKind.TONAL, icon = "copy") { copy(context, text) }
                // No text and no recording left to send: no pill, the line under the row says why.
                !entry.status.active && BufferStore.audioFile(entry) == null -> {}
                entry.status == BufferStatus.FAILED -> PillButton(stringResource(R.string.retry), canRetry(entry), PillKind.TONAL, icon = "arrow-counter-clockwise") { Dictation.retry(entry.id) }
                else -> PillButton(stringResource(R.string.transcribe), canRetry(entry), PillKind.TONAL) { Dictation.retry(entry.id) }
            }
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .onGloballyPositioned { anchor = it.boundsInRoot() }
                    .clip(CircleShape)
                    .clickable(onClickLabel = stringResource(R.string.kb_more)) { onMenu(anchor) },
                contentAlignment = Alignment.Center,
            ) { Glyph("dots-three", 20.dp, c.icon, description = stringResource(R.string.kb_more)) }
        }
    }
    val row = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    if (LocalDensity.current.fontScale >= LARGE_FONT) {
        // A large font: the words get the whole width, the pill and ⋯ a line of their own under them.
        Column(row) {
            words(Modifier.fillMaxWidth())
            Box(Modifier.align(Alignment.End).padding(top = 8.dp)) { actions() }
        }
    } else {
        Row(row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            words(Modifier.weight(1f))
            actions()
        }
    }
}

/** The row's status: a green tick once inserted, else a dot (accent while busy, amber waiting, grey without text). */
@Composable
private fun StatusMark(status: BufferStatus) {
    val c = appColors
    when (status) {
        BufferStatus.INSERTED, BufferStatus.ALREADY_IN_FIELD -> Glyph("check", 12.dp, c.green)
        BufferStatus.READY -> StatusDot(c.amber)
        BufferStatus.KEPT, BufferStatus.FAILED -> StatusDot(c.off)
        BufferStatus.RECORDING, BufferStatus.TRANSCRIBING -> StatusDot(c.acc)
    }
}

/** The row's status in words; none for a kept recording, whose title already says it is not transcribed. */
@Composable
private fun statusLabel(status: BufferStatus): String? = when (status) {
    BufferStatus.RECORDING -> R.string.status_recording
    BufferStatus.TRANSCRIBING -> R.string.status_transcribing
    BufferStatus.KEPT -> null
    BufferStatus.FAILED -> R.string.status_not_transcribed_failed
    BufferStatus.READY -> R.string.status_not_inserted
    BufferStatus.INSERTED -> R.string.status_inserted
    BufferStatus.ALREADY_IN_FIELD -> R.string.status_already
}?.let { stringResource(it) }

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dictation", text))
}

/** An app's name from its package; null for none or one no longer installed. */
fun appLabel(context: Context, packageName: String): String? {
    if (packageName.isEmpty()) return null
    val pm = context.packageManager
    return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrNull()
}

/** Account: who is signed in, Waveei Tide, sign out and delete; signed out, sign in; waiting, send again. */
@Composable
fun AccountScreen(activity: MainActivity, nav: Navigator) {
    @Suppress("UNUSED_VARIABLE") val refresh = activity.refresh
    var signIn by remember { mutableStateOf(false) }
    val signedIn = activity.signedIn()
    val waiting = activity.accountWaiting()
    Page(Screen.ACCOUNT, nav) {
        when {
            signedIn -> IdentityCard("user-circle", stringResource(R.string.signed_in), Account.email)
            waiting -> IdentityCard("envelope", Account.email, stringResource(R.string.waiting_description))
            else -> IdentityCard("user-circle", stringResource(R.string.not_signed_in), freeDescription())
        }
        Group {
            TideRow(activity, nav)
        }
        Group {
            when {
                signedIn -> SettingRow(stringResource(R.string.sign_out), onClick = activity::signOut) { Glyph("sign-out", 20.dp, appColors.icon) }
                waiting -> {
                    SettingRow(stringResource(R.string.send_again), stringResource(R.string.send_again_description)) {
                        TextAction(stringResource(R.string.send), onClick = activity::sendConfirmation)
                    }
                    Divider()
                    SettingRow(stringResource(R.string.sign_out), onClick = activity::signOut) { Glyph("sign-out", 20.dp, appColors.icon) }
                }
                else -> NavRow(stringResource(R.string.sign_in)) { signIn = true }
            }
            // Any account the server knows, the free tier's anonymous one too, can be deleted.
            if (signedIn || waiting || Account.uid.isNotEmpty()) {
                Divider()
                DeleteAccountRow(activity, nav)
            }
        }
    }
    if (signIn) AccountDialog(activity) { signIn = false }
}

/**
 * Delete account, after a dialog that says what deleting does and what it leaves. With Tide still to refund or to
 * cancel, the server deletes nothing and the plan page opens.
 */
@Composable
fun DeleteAccountRow(activity: MainActivity, nav: Navigator) {
    var open by remember { mutableStateOf(false) }
    SettingRow(stringResource(R.string.delete_account), stringResource(R.string.delete_account_description), onClick = { open = true }, titleColor = appColors.red)
    // What deleting does, the one line in red; under it, in a lighter red, the list of what follows, one short line each
    // and only what applies to this person: what the server deletes with it, the Tide lines while there is Tide (the
    // cancel line only while it would renew), the free minutes while the free tier is on, and what stays on the phone.
    if (open) DialogCard({ open = false }) {
        val c = appColors
        // Destructive, like the row that opens it and the red Delete pill: the title is red too, day and night.
        DialogTitle(stringResource(R.string.delete_account_title), color = c.red)
        Text(stringResource(R.string.delete_account_gone), style = type(16f, 24f, Fonts.MEDIUM), color = c.red)
        val tide = activity.tide()
        val lines = listOfNotNull(
            R.string.delete_account_server,
            R.string.delete_account_refund.takeIf { tide },
            R.string.delete_account_tide.takeIf { tide && !activity.tideEnding() },
            R.string.delete_account_minutes.takeIf { FreeTier.ON },
            R.string.delete_account_phone,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("•", style = type(14f, 20f), color = c.redSoft)
                    Text(stringResource(it), style = type(14f, 20f), color = c.redSoft)
                }
            }
        }
        DialogButtons(stringResource(R.string.cancel), stringResource(R.string.delete), destructive = true, onDismiss = { open = false }) {
            open = false
            activity.deleteAccount { nav.go(Screen.PLAN) }
        }
    }
}

@Composable
fun TideRow(activity: MainActivity, nav: Navigator) = NavRow(
    stringResource(R.string.pro),
    stringResource(R.string.plan),
    // As on Home: nothing while all is well, only a failed payment.
    value = if (activity.paymentFailed()) stringResource(R.string.payment_failed) else null,
) { nav.go(Screen.PLAN) }

/** The card at the top of Account and About: a 56 dp tile and two lines. */
@Composable
private fun IdentityCard(glyph: String?, title: String, subtitle: String, subtitleColor: Color = appColors.ink2, tile: @Composable (() -> Unit)? = null) {
    val c = appColors
    Row(
        Modifier
            // The first card of a screen 16 dp under the bar, with the groups' 16 dp corners, as every other screen.
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.box)
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (tile != null) {
            tile()
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(c.tonal),
                contentAlignment = Alignment.Center,
            ) { if (glyph != null) Glyph(glyph, 28.dp, c.acc) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type(22f, 28f, Fonts.TITLE), color = c.ink)
            Text(subtitle, style = type(14f, 20f), color = subtitleColor)
        }
    }
}

/** Signed out: dictation needs no account while the free tier is on; its limit is said at setup, and once used up only on the keyboard. */
@Composable
fun freeDescription(): String = when {
    !FreeTier.ON -> stringResource(R.string.sign_in_description)
    else -> stringResource(R.string.sign_in_optional)
}

@Composable
fun FeedbackRow(nav: Navigator) = NavRow(stringResource(R.string.feedback), stringResource(R.string.feedback_description)) { nav.go(Screen.FEEDBACK) }

/**
 * Feedback: a problem or a word for Waveei, to the Waveei server. The event log goes with it only when the person
 * turns Attach the log on; a recording or a dictated text never goes. A form, as Text replacement: the field, the one
 * switch, what is sent, then Send full width and 48 dp, as that form's Save.
 */
@Composable
fun FeedbackScreen(activity: MainActivity, nav: Navigator) {
    var message by rememberSaveable { mutableStateOf("") }
    var attach by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    Page(Screen.FEEDBACK, nav) {
        Field(
            message,
            { message = it.take(LogBatch.MAX_MESSAGE) },
            stringResource(R.string.feedback_placeholder),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            singleLine = false,
            minHeight = 140.dp,
        )
        Group {
            SwitchRow(stringResource(R.string.attach_log), stringResource(R.string.attach_log_description), attach) { attach = it }
        }
        Note(stringResource(R.string.feedback_note))
        PillButton(
            stringResource(R.string.send),
            message.isNotBlank() && !sending,
            icon = "paper-plane-tilt",
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 24.dp)
                .fillMaxWidth()
                .height(48.dp),
        ) {
            sending = true
            activity.sendFeedback(message.trim(), attach) { sent ->
                sending = false
                if (sent) nav.back()
            }
        }
    }
}

/**
 * Privacy: first the promise, the same lock line as the first setup step; then whether app reports go to Waveei and the
 * privacy policy; apart, last, deleting everything the app keeps on the phone, red only in its one word.
 */
@Composable
fun PrivacyScreen(activity: MainActivity, nav: Navigator) {
    Page(Screen.PRIVACY, nav) {
        InfoLine(stringResource(R.string.leaves_phone), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Group {
            ShareEventsRow(activity)
            Divider()
            PrivacyPolicyRow(activity)
        }
        Group {
            DeleteAllRow(activity)
        }
    }
}

@Composable
fun ShareEventsRow(activity: MainActivity) {
    var share by rememberPref({ activity.prefs.shareEvents }) {
        activity.prefs.shareEvents = it
        DebugLog.share = it
        if (it) Thread { Diagnostics.logPhone(activity) }.start()
    }
    SwitchRow(stringResource(R.string.share_events), stringResource(R.string.share_events_description), share) { share = it }
}

@Composable
fun DeleteAllRow(activity: MainActivity) {
    var confirm by remember { mutableStateOf(false) }
    // One look for every delete row: the red title, no second Delete.
    SettingRow(stringResource(R.string.delete_all), stringResource(R.string.delete_all_description), onClick = { confirm = true }, titleColor = appColors.red)
    if (confirm) {
        ConfirmDialog(stringResource(R.string.delete_all_question), stringResource(R.string.delete_all_warning), stringResource(R.string.delete), onConfirm = activity::deleteAllData) { confirm = false }
    }
}

/**
 * Tide and its payment: what it gives, the plan and the account it belongs to. Subscribe opens the payment page;
 * Restore asks the server for the account's plan again.
 */
@Composable
fun PlanScreen(activity: MainActivity, nav: Navigator) {
    val refresh = activity.refresh
    var billing by remember { mutableStateOf<Account.Billing?>(null) }
    LaunchedEffect(refresh) { billing = if (activity.signedIn()) Account.billing() else null }
    PlanPage(activity, nav, TideState.of(activity), billing)
}

/** What the plan page shows of Tide, as the activity knows it; a test can set each state by hand. */
class TideState(
    val signedIn: Boolean,
    val tide: Boolean,
    val ending: Boolean,
    val paymentFailed: Boolean,
    val email: String,
    val subscribeLink: String?,
) {
    companion object {
        fun of(activity: MainActivity) = TideState(
            activity.signedIn(), activity.tide(), activity.tideEnding(), activity.paymentFailed(), Account.email, activity.subscribeLink(),
        )
    }
}

@Composable
fun PlanPage(activity: MainActivity, nav: Navigator, state: TideState, billing: Account.Billing?) {
    var signIn by remember { mutableStateOf(false) }
    val date = { s: Long -> DateFormat.getMediumDateFormat(activity).format(Date(s * 1000)) }
    Page(Screen.PLAN, nav) {
        // As Account and About: where Tide stands in a card on top, and right under it the one thing it asks for now.
        PlanCard(state, billing, date)
        // The subscription is still there when a payment failed, even once Stripe has turned Tide off (unpaid): its
        // card is updated at Link, where the subscription lives, and a second Subscribe would only make two.
        val subscribed = state.tide || state.paymentFailed
        // Before buying, what Tide adds to the card's no weekly limit, and what never stays with us.
        if (!subscribed) {
            Group {
                SettingRow(stringResource(R.string.tide_live), icon = "waveform")
                Divider()
                SettingRow(stringResource(R.string.tide_sync), icon = "device-mobile")
            }
            InfoLine(stringResource(R.string.leaves_phone), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        }
        when {
            state.paymentFailed -> Group {
                SettingRow(billing?.card ?: stringResource(R.string.payment_failed), stringResource(R.string.payment_failed_description), onClick = { activity.openPayment(MainActivity.LINK) }) {
                    // Filled, the one thing to do here; Update on the card row while Tide is fine stays tonal.
                    PillButton(stringResource(R.string.update_card)) { activity.openPayment(MainActivity.LINK) }
                }
            }
            !state.tide -> Group(stringResource(R.string.plan)) {
                // Without an account Subscribe signs in first, as the purchase is linked to the account; signed in, it
                // opens the payment link. An account waiting for its email to be confirmed goes to Account, which says so.
                val link = state.subscribeLink
                val subscribe = link != null || !state.signedIn
                SettingRow(stringResource(R.string.tide_price), stringResource(R.string.price_description)) {
                    PillButton(stringResource(R.string.subscribe), subscribe) {
                        when {
                            link != null -> activity.openPayment(link)
                            activity.accountWaiting() -> nav.go(Screen.ACCOUNT)
                            else -> signIn = true
                        }
                    }
                }
                if (state.signedIn) {
                    Divider()
                    SettingRow(stringResource(R.string.restore_purchase), stringResource(R.string.restore_description), onClick = activity::refreshPlan)
                }
                Divider()
                ManageRow(activity)
            }
            // Cancelled Tide runs to the end of the month paid for, and until then it can renew again.
            state.ending -> Group {
                val end = billing?.periodEnd
                if (end != null) {
                    SettingRow(stringResource(R.string.renewal_off), stringResource(R.string.renewal_off_description, date(end))) {
                        PillButton(stringResource(R.string.resubscribe), true, onClick = activity::resubscribeTide)
                    }
                } else {
                    ConfirmRow(
                        stringResource(R.string.resubscribe),
                        stringResource(R.string.resubscribe_description),
                        stringResource(R.string.resubscribe_confirm),
                        stringResource(R.string.resubscribe_confirm_text),
                        stringResource(R.string.resubscribe),
                        destructive = false,
                        onConfirm = activity::resubscribeTide,
                    )
                }
            }
        }
        if (subscribed) {
            // Like a billing page: the card and the invoices, read from Stripe on opening.
            billing?.let { BillingGroups(activity, it, showCard = !state.paymentFailed, date = date) }
            Group(stringResource(R.string.subscription)) {
                if (state.tide && !state.ending) {
                    ConfirmRow(
                        stringResource(R.string.cancel_subscription),
                        stringResource(R.string.cancel_subscription_description),
                        stringResource(R.string.cancel_subscription_confirm),
                        stringResource(R.string.cancel_subscription_confirm_text),
                        stringResource(R.string.cancel_subscription),
                        dismiss = stringResource(R.string.keep_tide),
                        onConfirm = activity::cancelTide,
                    )
                    Divider()
                }
                ManageRow(activity)
            }
        }
        Group(stringResource(R.string.your_account)) {
            if (state.signedIn || activity.accountWaiting()) NavRow(state.email) { nav.go(Screen.ACCOUNT) } else NavRow(stringResource(R.string.sign_in)) { signIn = true }
        }
        // Last on the page, out of the way, and both always there while subscribed, so neither seems to vanish: ours,
        // Cancel current period, works in the first 14 days of a subscription (with the invoices known, with its last
        // day; without them only the server can tell, so it stays on) and after them stays in place, greyed, with why.
        // Below it Link's, always open: Link sells Tide as merchant of record, and after the 14 days it decides.
        val refundUntil = billing?.invoices?.singleOrNull()?.takeIf { it.paid && it.total > 0 }
            ?.let { it.date + REFUND_DAYS * DAY_S }?.takeIf { it * 1000 > System.currentTimeMillis() }
        val refundHere = state.tide && (billing == null || refundUntil != null)
        if (subscribed) {
            Group {
                ConfirmRow(
                    stringResource(R.string.refund),
                    when {
                        !refundHere -> stringResource(R.string.refund_late)
                        refundUntil != null -> stringResource(R.string.refund_until, date(refundUntil))
                        else -> stringResource(R.string.refund_description)
                    },
                    stringResource(R.string.refund_confirm),
                    stringResource(R.string.refund_confirm_text),
                    stringResource(R.string.refund),
                    enabled = refundHere,
                    dismiss = stringResource(R.string.keep_tide),
                    onConfirm = activity::refundTide,
                )
                Divider()
                ActionRow(stringResource(R.string.refund_at_link), stringResource(R.string.refund_at_link_description), "arrow-square-out") {
                    activity.openPayment(MainActivity.LINK_REFUNDS)
                }
            }
        }
    }
    if (signIn) AccountDialog(activity) { signIn = false }
}

/** The plan's card on top: Tide on or off, and when it renews or ends, or that its payment failed. */
@Composable
private fun PlanCard(state: TideState, billing: Account.Billing?, date: (Long) -> String) {
    val end = billing?.periodEnd
    val subtitle = when {
        !state.signedIn -> stringResource(if (FreeTier.ON) R.string.pro_description else R.string.sign_in_description)
        state.paymentFailed -> stringResource(R.string.payment_failed)
        !state.tide -> stringResource(if (FreeTier.ON) R.string.pro_description else R.string.tide_needed)
        end != null && billing.renews -> stringResource(R.string.renews_on, date(end))
        end != null -> stringResource(R.string.ends_on, date(end))
        state.ending -> stringResource(R.string.cancel_subscription_description)
        else -> stringResource(R.string.pro_monthly)
    }
    IdentityCard(
        "waveei",
        stringResource(if (state.tide) R.string.tide_on else R.string.tide_off),
        subtitle,
        subtitleColor = if (state.paymentFailed) appColors.red else appColors.ink2,
    )
}

@Composable
private fun ManageRow(activity: MainActivity) =
    ActionRow(stringResource(R.string.manage_subscription), stringResource(R.string.manage_description), "arrow-square-out") {
        activity.openPayment(MainActivity.LINK)
    }

@Composable
private fun BillingGroups(activity: MainActivity, billing: Account.Billing, showCard: Boolean, date: (Long) -> String) {
    billing.card?.takeIf { showCard }?.let { card ->
        Group(stringResource(R.string.payment)) {
            val update: () -> Unit = { activity.openPayment(MainActivity.LINK) }
            SettingRow(card, billing.cardExpires?.let { stringResource(R.string.expires, it) }, onClick = update) {
                PillButton(stringResource(R.string.update), kind = PillKind.TONAL, onClick = update)
            }
        }
    }
    if (billing.invoices.isNotEmpty()) {
        // The latest few; the rest of the year a tap away, so the page does not run on for a long subscription.
        var all by remember { mutableStateOf(false) }
        Group(stringResource(R.string.invoices)) {
            billing.invoices.take(if (all) billing.invoices.size else INVOICES_SHOWN).forEachIndexed { k, invoice ->
                if (k > 0) Divider()
                val status = stringResource(if (invoice.paid) R.string.invoice_paid else R.string.invoice_not_paid)
                val open: (() -> Unit)? = invoice.pdf?.let { pdf -> { activity.openPayment(pdf) } }
                SettingRow(
                    date(invoice.date),
                    "${money(invoice.total, invoice.currency)} · $status",
                    onClick = open,
                    descriptionColor = if (invoice.paid) appColors.ink2 else appColors.red,
                ) {
                    if (open != null) PillButton(stringResource(R.string.receipt), kind = PillKind.TONAL, onClick = open)
                }
            }
            if (billing.invoices.size > INVOICES_SHOWN) {
                Divider()
                SettingRow(stringResource(if (all) R.string.show_fewer else R.string.show_all), onClick = { all = !all }, titleColor = appColors.acc) {
                    Caret(all, 18.dp, appColors.chev, CARET_MS)
                }
            }
        }
    }
}

private const val INVOICES_SHOWN = 3
private const val CARET_MS = 200
private const val REFUND_DAYS = 14L
private const val DAY_S = 86_400L

/** [cents] in [currency] as people read it, with the currency's own decimals. */
private fun money(cents: Long, currency: String): String = runCatching {
    val code = Currency.getInstance(currency.uppercase())
    NumberFormat.getCurrencyInstance().apply { this.currency = code }.format(cents / Math.pow(10.0, code.defaultFractionDigits.toDouble()))
}.getOrDefault("$cents $currency")

/** About: the app with its version, its updates in all their states, the terms, its source and licenses. */
@Composable
fun AboutScreen(activity: MainActivity, nav: Navigator) {
    // Read here, not inside the row: the row skips recomposition while its arguments stay the same.
    val install = Updater.install
    Page(Screen.ABOUT, nav) {
        IdentityCard(null, activity.applicationInfo.loadLabel(activity.packageManager).toString(), stringResource(R.string.version, activity.versionName())) {
            AppIcon()
        }
        // Always there: the check, the found build's install under it, and whether to check by itself.
        Group(stringResource(R.string.updates)) {
            UpdateRow(activity, install)
            Divider()
            AutoCheckRow(activity)
        }
        Group {
            TermsRow(activity)
            Divider()
            SourceRow(activity)
            Divider()
            LicensesRow(nav)
        }
    }
}

@Composable
fun AutoCheckRow(activity: MainActivity) {
    var autoCheck by rememberPref({ activity.prefs.autoUpdateCheck }) { activity.prefs.autoUpdateCheck = it }
    SwitchRow(stringResource(R.string.auto_update_check), stringResource(R.string.auto_update_check_description), autoCheck) { autoCheck = it }
}

/**
 * The update in About: Check, which once it finds a newer build only names it, with no button; and under it a row of
 * its own for that build, stage by stage: Install, then its download with no more taps and the system's install
 * prompt; or the error with Retry. [install] is read by the caller, as the row skips recomposition while its arguments
 * stay the same.
 */
@Composable
fun UpdateRow(activity: MainActivity, install: Updater.Install? = Updater.install) {
    val found = activity.available
    if (found != null) {
        SettingRow(stringResource(R.string.check_updates), stringResource(R.string.version, found.name))
    } else {
        SettingRow(
            stringResource(R.string.check_updates),
            activity.updateStatus ?: stringResource(R.string.checks_latest),
            onClick = activity::checkForUpdate,
        ) { PillButton(stringResource(R.string.check), kind = PillKind.TONAL, onClick = activity::checkForUpdate) }
    }
    // A download and its error are the app's, not the screen's: they stay after Android makes the screen anew (a turn,
    // dark mode), which forgets the found build.
    if (found == null && install == null) return
    Divider()
    // The version is in the check's row above.
    when (install) {
        is Updater.Install.Downloading -> Column {
            // Eased between the reports, as Material's progress indicators are, so the line moves smoothly between
            // network bursts; empty until the size is known.
            val known = install.total > 0
            val progress by animateFloatAsState(
                if (known) install.read.toFloat() / install.total else 0f,
                ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "download",
            )
            SettingRow(
                stringResource(R.string.downloading),
                // In steps of 10: a download of a few MB needs no finer count.
                if (known) stringResource(R.string.percent, (progress * 100).toInt() / PERCENT_STEP * PERCENT_STEP) else null,
            )
            ProgressLine(progress, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
        }
        Updater.Install.Installing -> SettingRow(stringResource(R.string.install_update), stringResource(R.string.installing))
        // Retry as everywhere in a list: the tonal pill with its arrow.
        // The title and Retry say it all; the reason stays in the log. Only No connection is said: the person can fix it.
        is Updater.Install.Failed -> {
            val offline = stringResource(R.string.update_no_network)
            SettingRow(stringResource(R.string.update_failed), install.message.takeIf { it == offline }, onClick = activity::installUpdate, descriptionColor = appColors.red) {
                PillButton(stringResource(R.string.retry), kind = PillKind.TONAL, icon = "arrow-counter-clockwise", onClick = activity::installUpdate)
            }
        }
        null -> SettingRow(stringResource(R.string.install_update), onClick = activity::installUpdate) {
            PillButton(stringResource(R.string.install), onClick = activity::installUpdate)
        }
    }
}

@Composable
fun PrivacyPolicyRow(activity: MainActivity) =
    ActionRow(stringResource(R.string.privacy_policy), stringResource(R.string.privacy_policy_description), "arrow-square-out") { activity.openUrl(MainActivity.PRIVACY_POLICY) }

@Composable
fun TermsRow(activity: MainActivity) =
    ActionRow(stringResource(R.string.terms), stringResource(R.string.terms_description), "arrow-square-out") { activity.openUrl(MainActivity.TERMS) }

@Composable
fun SourceRow(activity: MainActivity) =
    ActionRow(stringResource(R.string.source_code), stringResource(R.string.source_code_description), "arrow-square-out") { activity.openUrl(BuildConfig.SOURCE_URL) }

@Composable
fun LicensesRow(nav: Navigator) = NavRow(stringResource(R.string.licenses), stringResource(R.string.licenses_description)) { nav.go(Screen.LICENSES) }

/**
 * The app's own launcher icon, round: its tile and waves at the size the launcher shows them (the middle 72 of 108),
 * cut to a circle, with the dots' shadow.
 */
@Composable
private fun AppIcon() {
    Box(
        Modifier
            .size(ICON_SIZE)
            .dropShadow(CircleShape, Shadow(radius = 2.dp, color = ICON_SHADOW, offset = DpOffset(0.dp, 1.dp)))
            .dropShadow(CircleShape, Shadow(radius = 12.dp, color = ICON_SHADOW, offset = DpOffset(0.dp, 4.dp)))
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val full = ICON_SIZE * ICON_VIEWPORT / ICON_VISIBLE
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(full))
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(full))
    }
}

private val ICON_SIZE = 56.dp
private const val ICON_VIEWPORT = 108f
private const val ICON_VISIBLE = 72f
private val ICON_SHADOW = Color(11, 40, 90, (0.22f * 255).toInt())

@Composable
fun LicensesScreen(nav: Navigator) {
    val libraries = listOf(
        "Kotlin, kotlinx.serialization" to APACHE,
        "Jetpack Compose, AndroidX" to APACHE,
        "OkHttp, Okio" to APACHE,
        "AndroidX Media3 (Sonic), Guava" to APACHE,
        "Firebase App Check" to APACHE,
        "Firebase Authentication, Google Play services" to "Android SDK License",
        "Play Integrity" to "Play Integrity API Terms of Service",
        stringResource(R.string.license_inter).split(" — ").let { it.first() to it.last() },
        stringResource(R.string.license_phosphor).split(" — ").let { it.first() to it.last() },
    )
    val context = LocalContext.current
    // The licences' own texts go with every copy of the app, as they ask; a tap shows one.
    var shown by rememberSaveable { mutableStateOf<String?>(null) }
    Page(Screen.LICENSES, nav) {
        Group {
            libraries.forEachIndexed { i, (name, license) ->
                if (i > 0) Divider()
                SettingRow(name, license)
            }
        }
        Group(stringResource(R.string.license_texts)) {
            LICENSE_TEXTS.forEachIndexed { i, (name, file) ->
                if (i > 0) Divider()
                SettingRow(name, onClick = { shown = file.takeUnless { it == shown } })
                if (shown == file) {
                    val text = remember(file) { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
                    SelectionContainer {
                        Text(
                            text,
                            style = type(11f, 16f).copy(fontFamily = FontFamily.Monospace),
                            color = appColors.ink2,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

private const val APACHE = "Apache License 2.0"

/** The licence texts in the app's assets (assets/licenses), by name. */
private val LICENSE_TEXTS = listOf(
    APACHE to "apache-2.0.txt",
    "SIL Open Font License 1.1" to "inter-OFL.txt",
    "MIT License" to "phosphor-MIT.txt",
)

private const val PERCENT_STEP = 10

/**
 * The rows of the screens under Home, for Home's search: Account, History, Words, Styles, Privacy, Feedback and
 * About, each under its screen's name.
 */
@Composable
fun homeItems(activity: MainActivity, nav: Navigator): List<SearchItem> {
    fun item(@androidx.annotation.StringRes group: Int, vararg words: Int, content: @Composable () -> Unit) =
        SearchItem(group, words.map { activity.getString(it) }, content)
    return listOf(
        item(R.string.group_account, R.string.pro, R.string.plan, R.string.group_account, R.string.sign_in) { TideRow(activity, nav) },
        item(R.string.buffer_title, R.string.recordings_kept, R.string.recordings_kept_search) { RecordingsKeptRow(activity) },
        item(R.string.buffer_title, R.string.transcripts_kept, R.string.transcripts_kept_search) { TranscriptsKeptRow(activity) },
        item(R.string.words, R.string.vocabulary, R.string.vocabulary_description) { VocabularyRow(activity, nav) },
        item(R.string.words, R.string.snippets, R.string.snippets_description) { SnippetsRow(activity, nav) },
        item(R.string.styles_translation, R.string.styles, R.string.styles_translation, R.string.translation, R.string.styles_screen_description, R.string.default_style, R.string.per_app) {
            NavRow(stringResource(R.string.styles_translation), stringResource(R.string.styles_screen_description), value = styleName(activity, activity.prefs.style)) { nav.go(Screen.STYLES) }
        },
        item(R.string.styles_translation, R.string.translation_languages, R.string.translation_languages_description, R.string.translation) { TranslationLanguagesRow(activity, nav) },
        item(R.string.privacy, R.string.share_events, R.string.share_events_description, R.string.telemetry) { ShareEventsRow(activity) },
        item(R.string.privacy, R.string.privacy_policy, R.string.privacy_policy_description) { PrivacyPolicyRow(activity) },
        item(R.string.privacy, R.string.delete_all, R.string.delete_all_description) { DeleteAllRow(activity) },
        item(R.string.feedback, R.string.feedback, R.string.feedback_description, R.string.report_problem) { FeedbackRow(nav) },
        item(R.string.about, R.string.check_updates, R.string.updates, R.string.install_update) { UpdateRow(activity) },
        item(R.string.about, R.string.auto_update_check, R.string.auto_update_check_description) { AutoCheckRow(activity) },
        item(R.string.about, R.string.terms, R.string.terms_description) { TermsRow(activity) },
        item(R.string.about, R.string.source_code, R.string.source_code_description) { SourceRow(activity) },
        item(R.string.about, R.string.licenses, R.string.licenses_description) { LicensesRow(nav) },
    )
}
