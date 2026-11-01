package app.waveei.core.field

import app.waveei.core.text.words
import kotlin.math.ceil

/**
 * The cheap local "already in this field" check.
 *
 * Both texts are reduced to lower-cased words of letters and digits. A short dictation must
 * occur in the field as a whole word sequence. A long dictation is cut into windows of
 * [WINDOW] words; about [SAMPLE_SHARE] of them, spread evenly, are checked, and the dictation
 * counts as present when at least [ACCEPT_SHARE] of the checked windows occur in the field.
 * Even spacing instead of random picks keeps the result the same on every platform.
 */
object AlreadyInField {
    private const val SHORT_WORDS = 40
    private const val WINDOW = 8
    private const val SAMPLE_SHARE = 0.2
    private const val MIN_SAMPLES = 3
    private const val ACCEPT_SHARE = 0.8

    fun check(dictation: String, fieldText: String): Boolean {
        val dictated = words(dictation)
        if (dictated.isEmpty()) return false
        val field = phrase(words(fieldText))
        if (dictated.size <= SHORT_WORDS) return field.contains(phrase(dictated))

        val windowCount = (dictated.size + WINDOW - 1) / WINDOW
        val samples = maxOf(MIN_SAMPLES, ceil(windowCount * SAMPLE_SHARE).toInt()).coerceAtMost(windowCount)
        var matched = 0
        for (i in 0 until samples) {
            val window = i * windowCount / samples
            // The last window is moved back so that it is always full.
            val start = minOf(window * WINDOW, dictated.size - WINDOW)
            if (field.contains(phrase(dictated.subList(start, start + WINDOW)))) matched++
        }
        return matched >= samples * ACCEPT_SHARE
    }

    /** Words joined with single spaces and padded with spaces, so that `contains` respects word boundaries. */
    private fun phrase(words: List<String>): String = words.joinToString(" ", prefix = " ", postfix = " ")
}
