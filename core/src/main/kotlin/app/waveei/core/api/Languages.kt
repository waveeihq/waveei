package app.waveei.core.api

/**
 * Every language the speech model is documented to support, by its tag and its English name (the source's order):
 * the app's language lists, and on the server the languages a piece may name. Chinese is two entries, by its script:
 * the model writes the one it is told.
 */
object Languages {
    val ALL = listOf(
        "af" to "Afrikaans", "sq" to "Albanian", "am" to "Amharic", "ar" to "Arabic", "hy" to "Armenian",
        "as" to "Assamese", "az" to "Azerbaijani", "eu" to "Basque", "be" to "Belarusian", "bn" to "Bengali",
        "bs" to "Bosnian", "bg" to "Bulgarian", "ca" to "Catalan", "ceb" to "Cebuano",
        "zh-Hans" to "Chinese (Simplified)",
        "zh-Hant" to "Chinese (Traditional)", "co" to "Corsican", "hr" to "Croatian", "cs" to "Czech",
        "da" to "Danish", "dv" to "Dhivehi", "nl" to "Dutch", "en" to "English", "eo" to "Esperanto", "et" to "Estonian",
        "fil" to "Filipino (Tagalog)", "fi" to "Finnish", "fr" to "French", "fy" to "Frisian", "gl" to "Galician",
        "ka" to "Georgian", "de" to "German", "el" to "Greek", "gu" to "Gujarati", "ht" to "Haitian Creole",
        "ha" to "Hausa", "haw" to "Hawaiian", "he" to "Hebrew", "hi" to "Hindi", "hmn" to "Hmong", "hu" to "Hungarian",
        "is" to "Icelandic", "ig" to "Igbo", "id" to "Indonesian", "ga" to "Irish", "it" to "Italian", "ja" to "Japanese",
        "jv" to "Javanese", "kn" to "Kannada", "kk" to "Kazakh", "km" to "Khmer", "ko" to "Korean", "kri" to "Krio",
        "ku" to "Kurdish", "ky" to "Kyrgyz", "lo" to "Lao", "la" to "Latin", "lv" to "Latvian", "lt" to "Lithuanian",
        "lb" to "Luxembourgish", "mk" to "Macedonian", "mg" to "Malagasy", "ms" to "Malay", "ml" to "Malayalam",
        "mt" to "Maltese", "mi" to "Maori", "mr" to "Marathi", "mni-Mtei" to "Meiteilon (Manipuri)", "mn" to "Mongolian",
        "my" to "Myanmar (Burmese)", "ne" to "Nepali", "no" to "Norwegian", "ny" to "Nyanja (Chichewa)",
        "or" to "Odia (Oriya)", "ps" to "Pashto", "fa" to "Persian", "pl" to "Polish", "pt" to "Portuguese",
        "pa" to "Punjabi", "ro" to "Romanian", "ru" to "Russian", "sm" to "Samoan", "gd" to "Scots Gaelic",
        "sr" to "Serbian", "st" to "Sesotho", "sn" to "Shona", "sd" to "Sindhi", "si" to "Sinhala (Sinhalese)",
        "sk" to "Slovak", "sl" to "Slovenian", "so" to "Somali", "es" to "Spanish", "su" to "Sundanese",
        "sw" to "Swahili", "sv" to "Swedish", "tg" to "Tajik", "ta" to "Tamil", "te" to "Telugu", "th" to "Thai",
        "tr" to "Turkish", "uk" to "Ukrainian", "ur" to "Urdu", "ug" to "Uyghur", "uz" to "Uzbek", "vi" to "Vietnamese",
        "cy" to "Welsh", "xh" to "Xhosa", "yi" to "Yiddish", "yo" to "Yoruba", "zu" to "Zulu",
    )
}
