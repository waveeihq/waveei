package app.waveei.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.waveei.android.DotService
import app.waveei.android.Learning
import app.waveei.android.MainActivity
import app.waveei.android.Prefs
import app.waveei.android.R
import app.waveei.core.style.Style
import app.waveei.core.text.Separator

/**
 * One searchable row: the group it is shown under, the words a search finds it by (its title, description and any
 * more), and how it is drawn.
 */
class SearchItem(@StringRes val group: Int, private val words: List<String>, val content: @Composable () -> Unit) {
    fun matches(query: String) = query.isBlank() || words.any { it.contains(query.trim(), ignoreCase = true) }
}

/** Items in their groups, each group a card under its label; nothing found says so. */
@Composable
fun SearchResults(items: List<SearchItem>, query: String) {
    val shown = items.filter { it.matches(query) }
    shown.groupBy { it.group }.forEach { (group, rows) ->
        Group(stringResource(group)) {
            rows.forEachIndexed { i, item ->
                if (i > 0) Divider()
                item.content()
            }
        }
    }
    if (shown.isEmpty()) EmptyState("magnifying-glass", stringResource(R.string.nothing_found))
}

/** Settings: Keyboard, Dictation and Dots, search at the top. */
@Composable
fun SettingsScreen(activity: MainActivity, nav: Navigator) {
    val extra = remember { nav.takeExtra() }
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    // Opened at a group (the dot's menu: the Dots): scrolled to it once it is laid out.
    val focusGroup = (extra as? Extra.Group)?.group
    var focusY by remember { mutableIntStateOf(-1) }
    LaunchedEffect(focusY) { if (focusY > 0) scroll.scrollTo(focusY) }
    val items = settingsItems(activity, nav)
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.settings), nav::back)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll),
        ) {
            SearchField(
                query,
                { query = it },
                stringResource(R.string.search_settings),
                Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                    .fillMaxWidth(),
            )
            val shown = items.filter { it.matches(query) }
            shown.groupBy { it.group }.forEach { (group, rows) ->
                Column(
                    if (group == focusGroup && focusY < 0) Modifier.onGloballyPositioned { focusY = it.positionInParent().y.toInt() } else Modifier,
                ) {
                    Group(stringResource(group)) {
                        rows.forEachIndexed { i, item ->
                            if (i > 0) Divider()
                            item.content()
                        }
                    }
                }
            }
            if (shown.isEmpty()) EmptyState("magnifying-glass", stringResource(R.string.nothing_found))
            BottomSpace()
        }
    }
}

/**
 * Settings' rows, for Settings and Home's search. The states are held here, so a row that depends on another (the
 * Dots under their master) follows it at once.
 */
@Composable
fun settingsItems(activity: MainActivity, nav: Navigator): List<SearchItem> {
    val prefs = activity.prefs
    @Suppress("UNUSED_VARIABLE") val refresh = activity.refresh
    val serviceOn = activity.dotServiceOn()
    var floatingOn by rememberPref({ prefs.floatingOn }) {
        prefs.floatingOn = it
        DotService.instance?.refresh()
    }
    // Everything under the master works only with it and the Accessibility service on.
    val dotsLive = floatingOn && serviceOn
    val switchOn = activity.floating(MainActivity.Floating.SWITCH)
    val micOn = activity.floating(MainActivity.Floating.MIC)

    var theme by rememberPref({ prefs.theme }) {
        prefs.theme = it
        activity.theme = it
    }
    var leftHanded by rememberPref({ prefs.leftHanded }) { prefs.leftHanded = it }
    var screenshotOffer by rememberPref({ prefs.screenshotOffer }) { prefs.screenshotOffer = it }
    val photosGranted = activity.photosGranted()
    var switchBack by rememberPref({ prefs.switchBackKeyboard }) { prefs.switchBackKeyboard = it }
    var separator by rememberPref({ prefs.separator }) { prefs.separator = it }
    var keepScreenOn by rememberPref({ prefs.keepScreenOn }) { prefs.keepScreenOn = it }
    var autoPause by rememberPref({ prefs.autoPause }) { prefs.autoPause = it }
    var liveWords by rememberPref({ prefs.showLiveWords }) { prefs.showLiveWords = it }
    var oneTap by rememberPref({ prefs.dotsOneTap }) {
        prefs.dotsOneTap = it
        DotService.instance?.refresh()
    }
    var staysDot by rememberPref({ prefs.dotsStayDot }) {
        prefs.dotsStayDot = it
        DotService.instance?.refresh()
    }
    var transparency by rememberPref({ prefs.dotTransparency }) {
        prefs.dotTransparency = it
        DotService.instance?.refresh()
    }

    val previousOne = stringResource(R.string.previous_one)
    val keyboards = remember(previousOne) { listOf("" to previousOne) + activity.otherKeyboards() }

    fun item(@StringRes group: Int, @StringRes title: Int, @StringRes description: Int, vararg more: String, content: @Composable () -> Unit) =
        SearchItem(group, listOf(activity.getString(title), activity.getString(description)) + more, content)

    return listOf(
        item(R.string.group_keyboard, R.string.theme, R.string.theme_description) {
            SegmentedRow(
                stringResource(R.string.theme),
                stringResource(R.string.theme_description),
                listOf(Prefs.Theme.SYSTEM to stringResource(R.string.system), Prefs.Theme.LIGHT to stringResource(R.string.light), Prefs.Theme.DARK to stringResource(R.string.dark)),
                theme,
            ) { theme = it }
        },
        item(R.string.group_keyboard, R.string.left_handed, R.string.left_handed_description) {
            SwitchRow(stringResource(R.string.left_handed), stringResource(R.string.left_handed_description), leftHanded) { leftHanded = it }
        },
        item(R.string.group_keyboard, R.string.screenshot_offer, R.string.screenshot_offer_description) {
            SwitchRow(
                stringResource(R.string.screenshot_offer),
                stringResource(if (screenshotOffer && !photosGranted) R.string.photos_needed else R.string.screenshot_offer_description),
                screenshotOffer,
                descriptionColor = if (screenshotOffer && !photosGranted) appColors.acc else appColors.ink2,
            ) {
                // The switch always turns it off; turned on again, it asks for the photos again.
                screenshotOffer = it
                if (it) activity.requestPhotos()
            }
        },
        item(R.string.group_keyboard, R.string.switch_back, R.string.switch_back_description) {
            ChoiceRow(stringResource(R.string.switch_back), stringResource(R.string.switch_back_description), keyboards, switchBack) { switchBack = it }
        },
        item(R.string.group_dictation, R.string.speech_language, R.string.speech_language_description) { SpeechLanguageRow(activity, nav) },
        item(R.string.group_dictation, R.string.separator, R.string.separator_description, activity.getString(R.string.separator_search)) {
            ChoiceRow(
                stringResource(R.string.separator),
                stringResource(R.string.separator_description),
                listOf(Separator.NONE to stringResource(R.string.nothing), Separator.SPACE to stringResource(R.string.space_option), Separator.NEW_LINE to stringResource(R.string.new_line), Separator.NEW_PARAGRAPH to stringResource(R.string.new_paragraph)),
                separator,
            ) { separator = it }
        },
        item(R.string.group_dictation, R.string.keep_screen_on, R.string.while_recording) {
            SwitchRow(stringResource(R.string.keep_screen_on), stringResource(R.string.while_recording), keepScreenOn) { keepScreenOn = it }
        },
        item(R.string.group_dictation, R.string.auto_pause, R.string.auto_pause_description) {
            SwitchRow(stringResource(R.string.auto_pause), stringResource(R.string.auto_pause_description), autoPause) { autoPause = it }
        },
        item(R.string.group_dictation, R.string.live_words, R.string.live_words_description) {
            // Tide's: greyed without it, saying so.
            val tide = activity.tide()
            SwitchRow(stringResource(R.string.live_words), stringResource(if (tide) R.string.live_words_description else R.string.with_tide), liveWords && tide, enabled = tide) { liveWords = it }
        },
        item(R.string.group_dots, R.string.floating_icons, R.string.floating_all_description, activity.getString(R.string.accessibility_service)) {
            var disclosure by remember { mutableStateOf(false) }
            // With the Accessibility service off the dots cannot work: a tap asks for it first (the disclosure).
            // Without the service nothing floats, so the switch shows off until it is on.
            SwitchRow(
                stringResource(R.string.floating_icons),
                stringResource(if (serviceOn) R.string.floating_all_description else R.string.turn_on_accessibility),
                floatingOn && serviceOn,
                descriptionColor = if (serviceOn) appColors.ink2 else appColors.acc,
            ) { value -> if (!serviceOn) disclosure = true else floatingOn = value }
            if (disclosure) {
                DotDisclosure(onAgree = {
                    floatingOn = true
                    activity.openAccessibilitySettings()
                }) { disclosure = false }
            }
        },
        item(R.string.group_dots, R.string.switch_button, R.string.switch_button_description) {
            SwitchRow(stringResource(R.string.switch_button), stringResource(R.string.switch_button_description), switchOn, enabled = dotsLive) {
                activity.setFloating(MainActivity.Floating.SWITCH, it)
            }
        },
        item(R.string.group_dots, R.string.floating_mic, R.string.floating_mic_description) {
            SwitchRow(stringResource(R.string.floating_mic), stringResource(R.string.floating_mic_description), micOn, enabled = dotsLive) {
                activity.setFloating(MainActivity.Floating.MIC, it)
            }
        },
        item(R.string.group_dots, R.string.switch_button_tap, R.string.tap_description) {
            SegmentedRow(
                stringResource(R.string.switch_button_tap),
                stringResource(R.string.tap_description),
                listOf(false to stringResource(R.string.two_taps), true to stringResource(R.string.one_tap)),
                oneTap,
                enabled = dotsLive,
            ) { oneTap = it }
        },
        item(R.string.group_dots, R.string.stays_dot, R.string.stays_dot_description) {
            SwitchRow(stringResource(R.string.stays_dot), stringResource(R.string.stays_dot_description), staysDot, enabled = dotsLive) { staysDot = it }
        },
        item(R.string.group_dots, R.string.dot_transparency, R.string.dot_transparency_description) {
            SegmentedRow(
                stringResource(R.string.dot_transparency),
                stringResource(R.string.dot_transparency_description),
                TRANSPARENCIES.map { it to "$it%" },
                transparency,
                enabled = dotsLive,
            ) { transparency = it }
        },
    )
}

private val TRANSPARENCIES = listOf(0, 20, 40, 60, 75)

/** A style's name in the app's language. */
fun styleName(context: android.content.Context, style: Style): String = context.getString(
    when (style) {
        Style.OFF -> R.string.style_off
        Style.MESSAGE -> R.string.style_message
        Style.FORMAL -> R.string.style_formal
    },
)

/**
 * Styles and translation: the default style and Remember per app, then the languages Translate offers; last, as it can
 * run long, each app's own style, which only counts while that switch is on (greyed, with a note saying so, while off).
 */
@Composable
fun StylesScreen(activity: MainActivity, nav: Navigator) {
    val prefs = activity.prefs
    var style by rememberPref({ prefs.style }) { prefs.style = it }
    var perApp by rememberPref({ prefs.rememberStylePerApp }) { prefs.rememberStylePerApp = it }
    // An app set back to Default stays listed until the screen is left.
    var apps by remember { mutableStateOf<Map<String, Style?>>(prefs.appStyles()) }
    val styles = Style.entries.map { it to styleName(activity, it) }
    Page(Screen.STYLES, nav) {
        Group(stringResource(R.string.styles)) {
            ChoiceRow(stringResource(R.string.default_style), stringResource(R.string.default_style_description), styles, style) { style = it }
            Divider()
            SwitchRow(stringResource(R.string.per_app), stringResource(R.string.per_app_description), perApp) { perApp = it }
        }
        Group(stringResource(R.string.translation)) {
            TranslationLanguagesRow(activity, nav)
        }
        if (apps.isNotEmpty()) {
            // Each app's style only counts while styles are remembered per app.
            Group(stringResource(R.string.group_apps)) {
                apps.entries.map { (app, value) -> Triple(app, appLabel(activity, app) ?: app, value) }.sortedBy { it.second.lowercase() }
                    .forEachIndexed { i, (app, label, value) ->
                        if (i > 0) Divider()
                        ChoiceRow<Style?>(
                            label,
                            null,
                            listOf<Pair<Style?, String>>(null to stringResource(R.string.style_default_value)) + styles,
                            value,
                            enabled = perApp,
                        ) {
                            prefs.setAppStyle(app, it)
                            apps = apps + (app to it)
                        }
                    }
            }
            if (!perApp) Note(stringResource(R.string.per_app_off_note))
        }
    }
}

/** Words: Vocabulary and Text replacement, with how many each holds. */
@Composable
fun WordsScreen(activity: MainActivity, nav: Navigator) {
    @Suppress("UNUSED_VARIABLE") val refresh = activity.refresh
    Page(Screen.WORDS, nav) {
        Group {
            VocabularyRow(activity, nav)
            Divider()
            SnippetsRow(activity, nav)
        }
    }
}

@Composable
fun VocabularyRow(activity: MainActivity, nav: Navigator) =
    NavRow(stringResource(R.string.vocabulary), stringResource(R.string.vocabulary_description), value = activity.prefs.vocabulary.size.toString()) { nav.go(Screen.VOCABULARY) }

@Composable
fun SnippetsRow(activity: MainActivity, nav: Navigator) =
    NavRow(stringResource(R.string.snippets), stringResource(R.string.snippets_description), value = activity.prefs.snippets.size.toString()) { nav.go(Screen.SNIPPETS) }

/**
 * The vocabulary: names and terms sent to the model as hints. The words the person corrects in dictations join it by
 * themselves; here they can be added by hand or removed.
 */
@Composable
fun VocabularyScreen(activity: MainActivity, nav: Navigator) {
    // Words corrected in a dictation still being watched join at once.
    LaunchedEffect(Unit) { Learning.commit() }
    var terms by rememberPref({ activity.prefs.vocabulary }) { activity.prefs.vocabulary = it }
    var draft by rememberSaveable { mutableStateOf("") }
    val add = {
        val term = draft.trim()
        if (term.isNotEmpty() && terms.none { it.equals(term, ignoreCase = true) }) terms = (terms + term).takeLast(Learning.MAX_WORDS)
        draft = ""
    }
    Page(Screen.VOCABULARY, nav) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Field(
                draft,
                { draft = it },
                stringResource(R.string.term_hint),
                Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            // As tall as the field beside it.
            PillButton(stringResource(R.string.add), draft.isNotBlank(), modifier = Modifier.height(50.dp), onClick = add)
        }
        Note(stringResource(R.string.vocabulary_note), Modifier.padding(bottom = 4.dp))
        if (terms.isEmpty()) {
            EmptyState("book-open-text", stringResource(R.string.no_terms), stringResource(R.string.no_terms_description))
        } else {
            Group {
                terms.sortedBy { it.lowercase() }.forEachIndexed { i, term ->
                    if (i > 0) Divider()
                    SettingRow(term) { TextAction(stringResource(R.string.remove)) { terms = terms - term } }
                }
            }
        }
    }
}

/** The snippets editor: a spoken trigger becomes a saved text. */
@Composable
fun SnippetsScreen(activity: MainActivity, nav: Navigator) {
    var pairs by rememberPref({ activity.prefs.snippets }) { activity.prefs.snippets = it }
    var trigger by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    Page(Screen.SNIPPETS, nav) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Labels that stay over the fields, as the sign-in dialog's: a placeholder goes once you type.
            Field(trigger, { trigger = it }, "", Modifier.fillMaxWidth(), label = stringResource(R.string.when_i_say))
            Field(text, { text = it }, "", Modifier.fillMaxWidth(), label = stringResource(R.string.replace_with), singleLine = false, minHeight = 78.dp)
            // The form's submit: full width and 48 dp, as the setup's actions.
            PillButton(
                stringResource(R.string.save_snippet),
                trigger.isNotBlank() && text.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                val key = trigger.trim()
                pairs = pairs.filterNot { it.substringBefore(Prefs.PAIR_SEPARATOR).equals(key, ignoreCase = true) } +
                    (key + Prefs.PAIR_SEPARATOR + text.trim())
                trigger = ""
                text = ""
            }
        }
        if (pairs.isEmpty()) {
            EmptyState("arrow-bend-down-left", stringResource(R.string.no_snippets), stringResource(R.string.no_snippets_description))
        } else {
            Group(stringResource(R.string.saved)) {
                pairs.forEachIndexed { i, pair ->
                    if (i > 0) Divider()
                    SettingRow(pair.substringBefore(Prefs.PAIR_SEPARATOR), pair.substringAfter(Prefs.PAIR_SEPARATOR)) {
                        TextAction(stringResource(R.string.remove)) { pairs = pairs - pair }
                    }
                }
            }
        }
    }
}
