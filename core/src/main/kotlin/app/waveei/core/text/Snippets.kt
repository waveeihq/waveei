package app.waveei.core.text

/**
 * Replaces each trigger phrase with its exact saved text. Runs last, so the saved text
 * is never rewritten. Matching ignores case and a trailing period the model may have added.
 */
object Snippets {
    fun apply(text: String, snippets: List<Pair<String, String>>): String {
        var result = text
        for ((trigger, saved) in snippets) {
            if (trigger.isBlank()) continue
            val pattern = Regex(
                "(?<![\\p{L}\\p{N}])" + Regex.escape(trigger.trim()) + "(?![\\p{L}\\p{N}])\\.?",
                setOf(RegexOption.IGNORE_CASE),
            )
            result = pattern.replace(result) { saved }
        }
        return result
    }
}
