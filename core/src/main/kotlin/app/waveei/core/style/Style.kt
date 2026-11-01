package app.waveei.core.style

/** How the transcript is shaped before it goes in. A fixed set; the server holds the wording of each. */
enum class Style(val title: String) {
    /** The words as said, only hesitations and slips taken out. */
    OFF("Off"),

    /** Reads as if the person typed it on a phone keyboard. The flagship style. */
    MESSAGE("Message"),

    /** Polite, complete sentences; keeps every fact. */
    FORMAL("Formal"),
    ;

    companion object {
        fun fromName(name: String?): Style = entries.firstOrNull { it.name == name } ?: OFF
    }
}
