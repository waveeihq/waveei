package app.waveei.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.waveei.android.MainActivity
import app.waveei.android.R
import app.waveei.core.api.Languages
import java.text.Collator
import java.util.Locale

/** A language's name in the app's language; where the phone has none, the model's English name. */
fun languageName(tag: String): String =
    displayName(tag, Locale.getDefault()) ?: Languages.ALL.firstOrNull { it.first == tag }?.second ?: tag

/** A language's name in itself (Deutsch, 日本語), if the phone has one. */
private fun nativeName(tag: String): String? = displayName(tag, Locale.forLanguageTag(tag))

/**
 * Android gives the bare code for a language it has no name for in [locale]: that counts as none. Chinese is named
 * with its script, as it is two entries.
 */
private fun displayName(tag: String, locale: Locale): String? {
    val language = Locale.forLanguageTag(tag)
    return language.getDisplayLanguage(locale)
        .takeIf { it.isNotBlank() && !it.equals(tag.substringBefore('-'), ignoreCase = true) }
        ?.let { if (language.language == "zh" && language.script.isNotEmpty()) language.getDisplayName(locale) else it }
        ?.replaceFirstChar { it.titlecase(locale) }
}

@Composable
fun SpeechLanguageRow(activity: MainActivity, nav: Navigator) = NavRow(
    stringResource(R.string.speech_language),
    stringResource(R.string.speech_language_description),
    value = activity.prefs.speechLanguages.joinToString(", ") { languageName(it) }.ifEmpty { stringResource(R.string.auto) },
) { nav.go(Screen.SPEECH_LANGUAGES) }

@Composable
fun TranslationLanguagesRow(activity: MainActivity, nav: Navigator) = NavRow(
    stringResource(R.string.translation_languages),
    stringResource(R.string.translation_languages_description),
    value = activity.prefs.translationChoices().joinToString(", ") { languageName(it) },
) { nav.go(Screen.TRANSLATION_LANGUAGES) }

/** The languages spoken: none picked is Auto, and the model finds them, a mix included. */
@Composable
fun SpeechLanguagesScreen(activity: MainActivity, nav: Navigator) {
    var chosen by rememberPref({ activity.prefs.speechLanguages }) { activity.prefs.speechLanguages = it }
    LanguagesPage(Screen.SPEECH_LANGUAGES, nav, chosen, onChange = { chosen = it }) {
        CheckRow(stringResource(R.string.auto), stringResource(R.string.auto_description), chosen.isEmpty()) { if (it) chosen = emptyList() }
    }
}

/** The languages Translate offers; none picked is the phone's languages and English. */
@Composable
fun TranslationLanguagesScreen(activity: MainActivity, nav: Navigator) {
    val prefs = activity.prefs
    var chosen by rememberPref({ prefs.translationChoices() }) { prefs.translationLanguages = it }
    LanguagesPage(Screen.TRANSLATION_LANGUAGES, nav, chosen, onChange = { chosen = it })
}

/**
 * A language list to pick from: the search, the chosen ones in the order they were picked, then every language the
 * model knows, A to Z in the app's language. A search finds a language by its name in the app's language, in English
 * or in itself, or by its code. The search sits on top, as Settings'; while it runs the Chosen group steps aside and the
 * matches keep their ticks. Nothing found says what else a search can try.
 */
@Composable
private fun LanguagesPage(screen: Screen, nav: Navigator, chosen: List<String>, onChange: (List<String>) -> Unit, first: (@Composable () -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember(Locale.getDefault()) {
        val collator = Collator.getInstance(Locale.getDefault())
        Languages.ALL.map { (tag, english) -> Language(tag, languageName(tag), nativeName(tag), english) }.sortedWith(compareBy(collator) { it.name })
    }
    fun toggle(tag: String, on: Boolean) = onChange(if (on) chosen + tag else chosen - tag)
    Page(screen, nav) {
        SearchField(
            query,
            { query = it },
            stringResource(R.string.search_languages),
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                .fillMaxWidth(),
        )
        if (query.isBlank()) {
            Group(stringResource(R.string.chosen)) {
                first?.let {
                    it()
                    if (chosen.isNotEmpty()) Divider()
                }
                chosen.forEachIndexed { i, tag ->
                    if (i > 0) Divider()
                    val language = all.firstOrNull { it.tag == tag } ?: Language(tag, languageName(tag), nativeName(tag), tag)
                    CheckRow(language.name, language.native?.takeIf { it != language.name }, true) { toggle(tag, it) }
                }
            }
        }
        val shown = all.filter { it.matches(query) && (query.isNotBlank() || it.tag !in chosen) }
        if (shown.isNotEmpty()) {
            Group(stringResource(R.string.all_languages)) {
                shown.forEachIndexed { i, language ->
                    if (i > 0) Divider()
                    CheckRow(language.name, language.native?.takeIf { it != language.name }, language.tag in chosen) { toggle(language.tag, it) }
                }
            }
        } else if (query.isNotBlank()) {
            EmptyState("magnifying-glass", stringResource(R.string.nothing_found), stringResource(R.string.language_search_hint))
        }
    }
}

private class Language(val tag: String, val name: String, val native: String?, val english: String) {
    fun matches(query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || tag.equals(q, ignoreCase = true) || listOfNotNull(name, native, english).any { it.contains(q, ignoreCase = true) }
    }
}
