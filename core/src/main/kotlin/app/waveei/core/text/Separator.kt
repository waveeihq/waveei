package app.waveei.core.text

/** What goes between the text already in the field and the next dictation; it is put right after a dictation. */
enum class Separator { NONE, SPACE, NEW_LINE, NEW_PARAGRAPH }

/** Indent used at the start of a new paragraph. */
const val PARAGRAPH_INDENT = "\t"

/**
 * Returns the text to insert before the next dictation, given the text left of the cursor.
 * Nothing is added at the start of an empty field, and whitespace already present is reused,
 * so a separator is never doubled. A new paragraph is a line break and a tab.
 */
fun separatorBefore(textBeforeCursor: String, separator: Separator): String {
    if (textBeforeCursor.isEmpty()) return ""
    val last = textBeforeCursor.last()
    return when (separator) {
        Separator.NONE -> ""
        Separator.SPACE -> if (last.isWhitespace()) "" else " "
        Separator.NEW_LINE -> if (last == '\n') "" else "\n"
        Separator.NEW_PARAGRAPH -> {
            val lastLine = textBeforeCursor.substringAfterLast('\n')
            when {
                last != '\n' && lastLine.isNotBlank() -> "\n" + PARAGRAPH_INDENT
                // The cursor is already on an empty or indented line.
                lastLine.isEmpty() -> PARAGRAPH_INDENT
                else -> ""
            }
        }
    }
}

/**
 * Returns the text to put right after a dictation, given the text right of the cursor, so the next one can
 * follow at once: a space, a new line or paragraph is ready; with [Separator.NONE] nothing is.
 * Nothing is added where the field already goes on with the same.
 */
fun separatorAfter(textAfterCursor: String, separator: Separator): String {
    val next = textAfterCursor.firstOrNull()
    return when (separator) {
        Separator.NONE -> ""
        Separator.SPACE -> if (next != null && next.isWhitespace()) "" else " "
        Separator.NEW_LINE -> if (next == '\n') "" else "\n"
        Separator.NEW_PARAGRAPH -> if (next == '\n') "" else "\n" + PARAGRAPH_INDENT
    }
}
