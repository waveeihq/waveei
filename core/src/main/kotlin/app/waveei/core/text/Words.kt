package app.waveei.core.text

/**
 * Splits [text] into lower-cased words made of letters, digits and combining marks.
 * Every other character separates words.
 */
fun words(text: String): List<String> {
    val result = ArrayList<String>()
    val word = StringBuilder()
    for (char in text) {
        if (char.isWordPart()) {
            word.append(char.lowercaseChar())
        } else if (word.isNotEmpty()) {
            result += word.toString()
            word.clear()
        }
    }
    if (word.isNotEmpty()) result += word.toString()
    return result
}

fun Char.isWordPart(): Boolean =
    isLetterOrDigit() ||
        category == CharCategory.NON_SPACING_MARK ||
        category == CharCategory.COMBINING_SPACING_MARK

/**
 * How many characters a word-wise backspace removes from the end of [before]: trailing spaces,
 * then the last word, or the last run of other characters (punctuation, emoji).
 */
fun wordDeleteLength(before: CharSequence): Int {
    var i = before.length
    while (i > 0 && before[i - 1].isWhitespace()) i--
    if (i == 0) return before.length
    val word = before[i - 1].isWordPart()
    while (i > 0 && !before[i - 1].isWhitespace() && before[i - 1].isWordPart() == word) i--
    return before.length - i
}
