package app.waveei.android

/** How loud the voice is while recording, 0..1, for the keyboard's and the dot's water; no audio is kept here. */
object VoiceLevel {
    /** The newest window's level. */
    @Volatile
    var level = 0f
        internal set

    /** The bytes of audio one level looks at: 512 samples, 32 ms at 16 kHz. */
    const val WINDOW_BYTES = 1024
}
