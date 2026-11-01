package app.waveei.core.text

/**
 * Live-words drafts as one text. Each draft is a few seconds cut by time, so the model may end one with a full
 * stop where the speaker went on: when the next draft starts with a small letter, that full stop is dropped.
 * A word cut at a hyphen is joined up with its end, or left out when the next draft says it whole.
 * Each draft's audio also overlaps the one before it, so the words it says again at its start are dropped.
 */
fun glueDrafts(drafts: List<String>): String = drafts.map { it.trim() }.filter { it.isNotEmpty() }.fold("") { text, draft ->
    val next = dropRepeated(text, draft)
    when {
        next.isEmpty() -> text
        text.isEmpty() -> next
        !next.first().isLowerCase() -> "$text $next"
        text.endsWith('-') -> {
            val cut = text.substringAfterLast(' ').dropLast(1)
            if (cut.isNotEmpty() && next.startsWith(cut, ignoreCase = true)) "${text.dropLast(cut.length + 1).trimEnd()} $next".trimStart() else text + next
        }
        else -> "${text.trimEnd('.')} $next"
    }
}

/**
 * The draft without the words it says again from the end of [before], up to six, maybe after one other word
 * such as the cut end of a word; or without a first word that is only the end of the last word of [before].
 */
internal fun dropRepeated(before: String, draft: String): String {
    val last = before.split(' ').filter { it.isNotEmpty() }.map(::norm)
    val words = draft.trim().split(' ').filter { it.isNotEmpty() }
    for (skip in 0..1) for (k in minOf(6, words.size - skip, last.size) downTo if (skip == 0) 1 else 2) {
        if (words.subList(skip, skip + k).map(::norm) == last.takeLast(k)) return words.drop(skip + k).joinToString(" ")
    }
    val first = words.firstOrNull()?.let(::norm).orEmpty()
    if (first.length >= 3 && last.isNotEmpty() && last.last() != first && last.last().endsWith(first)) return words.drop(1).joinToString(" ")
    return words.joinToString(" ")
}

private fun norm(word: String) = word.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() || it == '-' }
