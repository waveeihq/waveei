package app.waveei.android

import android.content.Context
import android.content.SharedPreferences
import android.icu.util.ULocale
import android.os.LocaleList
import app.waveei.core.api.Languages
import app.waveei.core.style.Style
import app.waveei.core.text.Separator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Settings stored on the phone. SharedPreferences, because the keyboard reads them
 * synchronously on its hot path.
 */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    enum class Theme { SYSTEM, LIGHT, DARK }

    // Keyboard
    var theme by enum("theme", Theme.SYSTEM)
    var leftHanded by boolean("left_handed", false)

    /** The copy time of the clip the keyboard last pasted from its offer, or whose offer was dismissed. */
    var pastedClipAt by long("pasted_clip_at", 0L)

    /** When History was last opened: what was in it then is seen, and the badge no longer counts it. */
    var historySeenAt by long("history_seen_at", 0L)

    /** The keyboard offers a screenshot taken a moment ago, as it offers a fresh copy; needs access to photos. */
    var screenshotOffer by boolean("screenshot_offer", false)

    /** Input method id to switch back to; empty means the one the user came from. */
    var switchBackKeyboard by string("switch_back_keyboard")

    // Dictation
    var separator by enum("separator", Separator.SPACE)
    var autoSend by boolean("auto_send", false)

    var keepScreenOn by boolean("keep_screen_on", true)
    var autoPause by boolean("auto_pause", false)
    var showLiveWords by boolean("live_words", false)

    // Styles
    var style by enum("style", Style.OFF)
    var rememberStylePerApp by boolean("style_per_app", false)

    /** Target language tag for translation; empty means off. */
    var translateTo by string("translate_to")

    /** Language tags offered in the translate list; empty means the phone's languages plus English. */
    var translationLanguages by list("translation_languages")

    /** The languages offered for translation: the chosen ones, else the phone's and English. */
    fun translationChoices(): List<String> = translationLanguages.ifEmpty {
        val phone = LocaleList.getDefault()
        ((0 until phone.size()).map { languageTag(phone[it]) } + "en").distinct()
    }

    // Words
    /** Names and terms sent with every dictation; the words the person corrects in dictations join it by themselves. */
    var vocabulary by list("vocabulary")

    /** The languages spoken, as language tags (ru, en); empty means Auto: the model detects them and keeps a mix. */
    var speechLanguages by list("speech_languages")

    /** [speechLanguages] by their English names sent with each dictation ("Russian or English"); null for Auto. */
    fun speechLanguageName(): String? = speechLanguages.takeIf { it.isNotEmpty() }
        ?.joinToString(" or ", transform = ::englishName)

    /** Snippets as "trigger\u001Ftext" pairs. */
    var snippets by list("snippet_pairs")

    // Floating icons, drawn by the Accessibility service
    /** All floating icons at once: off, none shows and the service stops acting in other apps; each icon keeps its own switch. */
    var floatingOn by boolean("floating_on", true)

    /** The floating mic at the screen edge. */
    var dotEnabled by boolean("dot", false)

    /** A button over another keyboard that switches to this one; on by default. */
    var switchIcon by boolean("switch_icon", true)

    /** The recording pill while a dictation goes on with the keyboard hidden; on by default. */
    var recordingIcon by boolean("recording_icon", true)

    /** How see-through every icon is while it is a dot, in percent: 40 until changed, so a resting dot is see-through. */
    var dotTransparency by int("dot_transparency", 40)

    /** A tap on a dot acts at once (dictate, switch, stop) instead of first opening it. */
    var dotsOneTap by boolean("dots_one_tap", true)

    /**
     * The event log also goes to the Waveei server as it happens ([DebugLog.share]), and the usage statistics are
     * counted ([Stats]); off unless the person chose Share (setup or Home › Privacy).
     */
    var shareEvents by boolean(SHARE_EVENTS, false)

    /** Every icon is always a dot, never opening into its full form, and a tap acts at once. */
    var dotsStayDot by boolean("dots_stay_dot", false)

    /**
     * Where the dot rests: its top in pixels and the centre of its round end in pixels (-1 until it is first
     * dragged). [dotRight] is the screen edge it rests on until then; it seeds [dotX].
     */
    var dotY by int("dot_y", -1)
    var dotX by int("dot_x", -1)
    var dotRight by boolean("dot_right", true)

    /** Where the keyboard switch button rests, kept apart from the dot so it can sit clear of send buttons. */
    var switchY by int("switch_y", -1)
    var switchX by int("switch_x", -1)
    var switchRight by boolean("switch_right", true)

    // Buffer
    var bufferRecordings by int("buffer_recordings", 5)
    var bufferTranscripts by int("buffer_transcripts", 5)

    var onboardingDone by boolean("onboarding_done", false)

    /** The version of the Terms the person agreed to ([MainActivity.TERMS_VERSION]); 0 before any. */
    var termsAccepted by int("terms_accepted", 0)

    /** Terms changed since the person agreed (asked on Home): until they agree, nothing is dictated. */
    val termsPending: Boolean get() = onboardingDone && termsAccepted < MainActivity.TERMS_VERSION

    /** The microphone was asked for once; after a second refusal only the system settings can grant it. */
    var micAsked by boolean("mic_asked", false)

    /** Photos were asked for once, as the microphone. */
    var photosAsked by boolean("photos_asked", false)

    /** The app looks for an update by itself when opened, now and then, and with it closed ([UpdateCheckJob]). */
    var autoUpdateCheck by boolean("auto_update_check", true)

    /** When the app last looked for an update by itself, so it does not look at every start. */
    var lastUpdateCheck by long("last_update_check", 0L)

    /** The newest version a notification has told of: each is told once ([UpdateCheckJob]). */
    var notifiedVersion by long("notified_version", 0L)

    /** Notifications were asked for once, when a check first found a new version. */
    var notificationsAsked by boolean("notifications_asked", false)

    /** Setup has just ended: Home shows the usage guide once, then clears it. */
    var guidePending by boolean("guide_pending", false)

    /** Accessibility was opened from setup while Android kept Waveei's switch restricted: the first of its steps is done. */
    var restrictedSeen by boolean("restricted_seen", false)

    /** The first update's guide (unknown sources, Play Protect) was followed once; later updates go straight on. */
    var updateGuideSeen by boolean("update_guide_seen", false)

    /** The style kept for each app (style_app_<package>), by package. */
    fun appStyles(): Map<String, Style> = prefs.all.mapNotNull { (key, value) ->
        val app = key.removePrefix(APP_STYLE).takeIf { key.startsWith(APP_STYLE) } ?: return@mapNotNull null
        (value as? String)?.let { app to Style.fromName(it) }
    }.toMap()

    /** Sets an app's own style; null takes it back to the default style. */
    fun setAppStyle(app: String, value: Style?) {
        if (value == null) prefs.edit().remove(APP_STYLE + app).apply() else prefs.edit().putString(APP_STYLE + app, value.name).apply()
    }

    fun styleFor(app: String): Style =
        if (rememberStylePerApp) prefs.getString("style_app_$app", null)?.let { Style.fromName(it) } ?: style else style

    /** A style picked on the keyboard: this app's own while remembered per app, otherwise the default. */
    fun rememberStyle(app: String, value: Style) {
        if (rememberStylePerApp && app.isNotEmpty()) prefs.edit().putString("style_app_$app", value.name).apply() else style = value
    }

    /**
     * The settings Tide keeps in step across the account's phones, as a JSON object: what the person picks, their
     * vocabulary and text replacements; never this phone's own keyboards, permissions, places on the screen or sharing.
     */
    fun synced(): String = buildJsonObject {
        prefs.all.toSortedMap().forEach { (key, value) ->
            if (!syncs(key)) return@forEach
            when (value) {
                is Boolean -> put(key, value)
                is Int -> put(key, value)
                is String -> put(key, value)
            }
        }
    }.toString()

    /** Takes [json], a copy from [synced]: each synced setting it lacks goes back to its default. */
    fun applySynced(json: String) {
        val values = Json.parseToJsonElement(json).jsonObject
        val edit = prefs.edit()
        prefs.all.keys.filter { syncs(it) && it !in values }.forEach { edit.remove(it) }
        values.forEach { (key, element) ->
            val value = element as? JsonPrimitive ?: return@forEach
            when (kind(key)) {
                TEXT -> if (value.isString) edit.putString(key, value.content)
                SWITCH -> if (!value.isString) value.booleanOrNull?.let { edit.putBoolean(key, it) }
                NUMBER -> if (!value.isString) value.intOrNull?.let { edit.putInt(key, it) }
            }
        }
        edit.commit()
    }

    /**
     * [mine] and [theirs], two copies from [synced] grown apart from [base], as one: what was changed here wins, the
     * rest is theirs. In the vocabulary and the snippets, what either side added stays and what either took out goes.
     */
    fun mergeSynced(base: String, mine: String, theirs: String): String {
        val was = Json.parseToJsonElement(base).jsonObject
        val now = Json.parseToJsonElement(mine).jsonObject
        val merged = Json.parseToJsonElement(theirs).jsonObject.toMutableMap()
        for (key in was.keys + now.keys) {
            if (now[key] == was[key]) continue
            if (key in MERGED_LISTS) {
                val added = items(now[key]) - items(was[key]).toSet()
                val taken = items(was[key]) - items(now[key]).toSet()
                var list = (items(merged[key]) - taken.toSet() + added).distinct()
                if (key == VOCABULARY) list = list.takeLast(Learning.MAX_WORDS)
                if (list.isEmpty()) merged.remove(key) else merged[key] = JsonPrimitive(list.joinToString(LIST_SEPARATOR))
            } else {
                now[key]?.let { merged[key] = it } ?: merged.remove(key)
            }
        }
        return JsonObject(merged).toString()
    }

    private fun items(value: JsonElement?): List<String> =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty().split(LIST_SEPARATOR).filter { it.isNotEmpty() }

    /** [act] after a synced setting changed. The listener returned must stay referenced: Android holds it weakly. */
    fun onSyncedChange(act: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != null && syncs(key)) act() }.also(prefs::registerOnSharedPreferenceChangeListener)

    private fun syncs(key: String) = kind(key) != null

    /** A synced key's kind; a style kept for an app is text. */
    private fun kind(key: String): Int? = SYNCED[key] ?: TEXT.takeIf { key.startsWith(APP_STYLE) && key.length > APP_STYLE.length }

    /** Everything but the person's own choice about what leaves the phone: deleting data never turns sharing on or off. */
    fun clearAll() {
        val share = shareEvents
        prefs.edit().clear().apply()
        shareEvents = share
    }

    /** [act] after one of [keys] changed. The listener returned must stay referenced: Android holds it weakly. */
    fun onChange(vararg keys: String, act: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key in keys) act() }.also(prefs::registerOnSharedPreferenceChangeListener)

    private fun string(key: String) = Property({ prefs.getString(key, "").orEmpty() }) {
        prefs.edit().putString(key, it.trim()).apply()
    }

    private fun boolean(key: String, default: Boolean) = Property({ prefs.getBoolean(key, default) }) {
        prefs.edit().putBoolean(key, it).apply()
    }

    private fun int(key: String, default: Int) = Property({ prefs.getInt(key, default) }) {
        prefs.edit().putInt(key, it).apply()
    }

    private fun long(key: String, default: Long) = Property({ prefs.getLong(key, default) }) {
        prefs.edit().putLong(key, it).apply()
    }

    private fun list(key: String) = Property({
        prefs.getString(key, "").orEmpty().split(LIST_SEPARATOR).filter { it.isNotEmpty() }
    }) { value -> prefs.edit().putString(key, value.joinToString(LIST_SEPARATOR)).apply() }

    private inline fun <reified E : Enum<E>> enum(key: String, default: E) = Property({
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
    }) { prefs.edit().putString(key, it.name).apply() }

    /**
     * From now on each change of a setting the person picks goes to the event log ("ui: set theme DARK"): only the
     * keys in [LOGGED], never one that holds their words, an app's name or a place on the screen. This [Prefs] must
     * stay referenced: Android holds the listener weakly.
     */
    fun logChanges() = prefs.registerOnSharedPreferenceChangeListener(changes)

    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key in LOGGED) DebugLog.add("ui: set $key ${p.all[key]?.toString()?.replace(LIST_SEPARATOR, ",")}")
    }

    class Property<T>(private val read: () -> T, private val write: (T) -> Unit) {
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = read()
        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) = write(value)
    }

    companion object {
        const val LIST_SEPARATOR = "\u001E"

        const val SHARE_EVENTS = "share_events"

        const val PAIR_SEPARATOR = "\u001F"

        private const val APP_STYLE = "style_app_"

        /** See [synced]: each key with its kind, so a copy never puts a value of another kind under it. */
        private val SYNCED = mapOf(
            "theme" to TEXT, "separator" to TEXT, "style" to TEXT, "translate_to" to TEXT, "translation_languages" to TEXT,
            "speech_languages" to TEXT, "vocabulary" to TEXT, "snippet_pairs" to TEXT,
            "left_handed" to SWITCH, "screenshot_offer" to SWITCH, "auto_send" to SWITCH, "keep_screen_on" to SWITCH,
            "auto_pause" to SWITCH, "live_words" to SWITCH, "style_per_app" to SWITCH, "dots_one_tap" to SWITCH,
            "dots_stay_dot" to SWITCH, "auto_update_check" to SWITCH,
            "dot_transparency" to NUMBER, "buffer_recordings" to NUMBER, "buffer_transcripts" to NUMBER,
        )
        private const val VOCABULARY = "vocabulary"
        private val MERGED_LISTS = setOf(VOCABULARY, "snippet_pairs")
        private const val TEXT = 0
        private const val SWITCH = 1
        private const val NUMBER = 2

        private val LOGGED = setOf(
            "theme", "left_handed", "screenshot_offer", "separator", "auto_send", "keep_screen_on", "auto_pause",
            "live_words", "style", "style_per_app", "translate_to", "translation_languages", "speech_languages",
            "floating_on", "dot", "switch_icon", "recording_icon", "dot_transparency", "dots_one_tap", "dots_stay_dot",
            SHARE_EVENTS, "buffer_recordings", "buffer_transcripts", "auto_update_check",
        )
    }
}

/** A phone language as the app's lists tag it: its language alone, Chinese with its script (zh-Hans, zh-Hant). */
fun languageTag(locale: Locale): String {
    // By the tag, not Locale.language: on Android 13 and 14 that still says iw, in and ji for he, id and yi.
    val language = locale.toLanguageTag().substringBefore('-')
    if (language != "zh") return language
    // Taiwan and Hong Kong write Traditional, the rest Simplified, unless the locale names its script.
    return "zh-" + ULocale.addLikelySubtags(ULocale.forLocale(locale)).script.ifEmpty { "Hans" }
}

/** A language's English name as a piece tells the model: the list's own (Chinese (Traditional)), else the phone's. */
fun englishName(tag: String): String = Languages.ALL.firstOrNull { it.first == tag }?.second ?: Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH)
