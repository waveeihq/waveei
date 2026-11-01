package app.waveei.core.text

/**
 * The words a person corrected in a dictation after it went in: the dictated text against the field's text later.
 * A word put in place of one or two dictated words that look alike (a near spelling, or another alphabet, as
 * "Ноушн" for "Notion") is one the model got wrong, so it is worth a place in the vocabulary; a rewording or another
 * ending of the same word (отправляет, отправил) is not.
 */
object Corrections {
    /** Whether [field] still holds most of [dictated], so its differences are edits of the dictation. */
    fun holds(dictated: String, field: String): Boolean {
        val said = tokens(dictated)
        return said.size >= MIN_WORDS && matches(said, tokens(field)).size >= said.size * HELD
    }

    /** The words of [field] put in place of words of [dictated]; empty when the field no longer holds it. */
    fun of(dictated: String, field: String): List<String> = fixes(dictated, field).map { it.second }.distinct()

    /** Each correction as the dictated word or two and what was put in their place. */
    fun fixes(dictated: String, field: String): List<Pair<String, String>> = heldFixes(dictated, field).orEmpty()

    /** [fixes], or null when [field] no longer [holds] the dictation: both in one pass. */
    fun heldFixes(dictated: String, field: String): List<Pair<String, String>>? {
        val said = tokens(dictated)
        val now = tokens(field)
        val pairs = matches(said, now)
        if (said.size < MIN_WORDS || pairs.size < said.size * HELD) return null
        val found = ArrayList<Pair<String, String>>()
        // At either end the word next to the dictation may be new text, so only a near spelling counts there.
        fun gap(old: List<Token>, new: List<Token>, end: Boolean = false) {
            if (old.size !in 1..2 || new.size !in 1..2) return
            val term = new.joinToString(" ") { it.text }
            if (term.any { it.isLetter() } && alike(old.joinToString("") { it.key }, new.joinToString("") { it.key }, term, !end)) {
                found += old.joinToString(" ") { it.text } to term
            }
        }
        val first = pairs.first()
        gap(said.subList(0, first.first), now.subList(maxOf(0, first.second - first.first), first.second), end = true)
        pairs.zipWithNext { a, b -> gap(said.subList(a.first + 1, b.first), now.subList(a.second + 1, b.second)) }
        val last = pairs.last()
        val tail = said.size - last.first - 1
        gap(said.subList(last.first + 1, said.size), now.subList(last.second + 1, minOf(now.size, last.second + 1 + tail)), end = true)
        return found.distinct()
    }

    private val SPACE = Regex("\\s+")

    /** A word as written, without the punctuation around it, and its letters and digits in lower case to compare. */
    private class Token(val text: String, val key: String)

    private fun tokens(text: String): List<Token> = text.split(SPACE).mapNotNull { raw ->
        val key = words(raw).joinToString("")
        if (key.isEmpty()) null else Token(raw.trim { !it.isWordPart() }, key)
    }

    /** The longest run of the same words in both, in order, as index pairs. */
    private fun matches(a: List<Token>, b: List<Token>): List<Pair<Int, Int>> {
        if (a.isEmpty() || b.isEmpty() || a.size.toLong() * b.size > MAX_CELLS) return emptyList()
        val length = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            length[i][j] = if (a[i].key == b[j].key) length[i + 1][j + 1] + 1 else maxOf(length[i + 1][j], length[i][j + 1])
        }
        val pairs = ArrayList<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i].key == b[j].key -> { pairs += i to j; i++; j++ }
                length[i + 1][j] >= length[i][j + 1] -> i++
                else -> j++
            }
        }
        return pairs
    }

    /**
     * Another spelling of the same sound: written in another alphabet (when [anyScript]), or at most half its letters
     * changed. In the same alphabet a word that keeps most of its start is another ending, unless it is a name or a
     * term ([term] with a capital or a digit, as Kubernetes for Kubernetis).
     */
    private fun alike(old: String, new: String, term: String, anyScript: Boolean): Boolean {
        if (old == new || new.length < 2) return false
        if (script(old) != script(new)) return anyScript
        if (distance(old, new) * 2 > maxOf(old.length, new.length)) return false
        if (term.any { it.isUpperCase() || it.isDigit() }) return true
        return old.commonPrefixWith(new).length * 5 < minOf(old.length, new.length) * 3
    }

    private fun script(word: String): Character.UnicodeScript? =
        word.firstOrNull { it.isLetter() }?.let { Character.UnicodeScript.of(it.code) }

    private fun distance(a: String, b: String): Int {
        var row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1)
            next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(row[j + 1] + 1, next[j] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
            row = next
        }
        return row[b.length]
    }

    /** A dictation this short gives no context to tell an edit of it from new text. */
    private const val MIN_WORDS = 3

    /** The share of the dictated words the field must still hold. */
    private const val HELD = 0.6

    /** The comparison's table is kept small: it runs as the person types. */
    private const val MAX_CELLS = 400_000L
}
