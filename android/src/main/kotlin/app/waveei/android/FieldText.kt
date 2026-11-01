package app.waveei.android

/**
 * The rules for reading another app's text field through Accessibility before inserting into it.
 * Pure functions, so the edge cases are tested without a phone.
 */
object FieldText {
    /** The field's real text: a shown hint is not text, and a blank value is an empty field (web fields report "\n"). */
    fun readable(text: CharSequence?, showingHint: Boolean): String {
        if (showingHint) return ""
        val value = text?.toString().orEmpty()
        return if (value.isBlank()) "" else value
    }

    /**
     * Where new text goes, as start and end of the range it replaces. An invalid selection, and a cursor reported
     * at 0 in a field that has text (many apps report that when they do not know), put it at the end.
     */
    fun insertAt(length: Int, selectionStart: Int, selectionEnd: Int): Pair<Int, Int> {
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        if (start < 0 || end > length) return length to length
        if (start == 0 && end == 0 && length > 0) return length to length
        return start to end
    }

    /** Whether a whole-field write landed: the same text, give or take trailing whitespace the editor trims. */
    fun landed(expected: String, actual: String): Boolean = actual == expected || actual.trimEnd() == expected.trimEnd()

    /** Whether a paste landed: the field changed and now holds the pasted text. */
    fun pasted(before: String, after: String, insertion: String): Boolean = after != before && after.contains(insertion.trim())
}
