package app.waveei.android.ui

import kotlin.math.exp
import kotlin.math.pow

/**
 * The voice as the water shows it, for the keyboard's mic and the record dot alike. The meter's level (0..1 over
 * −52..−12 dBFS) loses its noise floor first, so a street does not hold the water up between words; what is left
 * lifts the water ([swell]: rising in about 0.4 s, falling in about 1.4 s) and the waves and the water's
 * brightness ([level]). Every value is a constant below, to tune on a device.
 */
class VoiceEnvelope {
    private val times = LongArray(WINDOW)
    private val values = FloatArray(WINDOW)
    private var count = 0
    private var index = 0

    /** The noise floor: the quietest level of the last [FLOOR_WINDOW_MS], smoothed. */
    var floor = FLOOR_START
        private set

    /** The voice above the floor, 0..1, shaped by [CURVE]: what the water's height follows. */
    var swell = 0f
        private set

    /** The voice above the floor, 0..1, quicker: what the waves and the brightness follow. */
    var level = 0f
        private set

    /** A new recording: the floor starts from a quiet room again. */
    fun reset() {
        count = 0
        index = 0
        floor = FLOOR_START
        swell = 0f
        level = 0f
    }

    /**
     * One frame: [input] at [time], [dt] ms after the last. [instant] (Remove animations) follows the voice at once;
     * [fixedFloor] keeps the floor at its start, for the design's still frames.
     */
    fun step(input: Float, time: Long, dt: Float, instant: Boolean = false, fixedFloor: Boolean = false) {
        index = (index + 1) % WINDOW
        times[index] = time
        values[index] = input
        if (count < WINDOW) count++
        var low = input
        for (i in 0 until count) {
            val j = (index - i + WINDOW) % WINDOW
            if (time - times[j] > FLOOR_WINDOW_MS) break
            if (values[j] < low) low = values[j]
        }
        floor = when {
            fixedFloor -> FLOOR_START
            instant -> low
            else -> floor + (low - floor) * (1f - exp(-dt / FLOOR_MS))
        }.coerceIn(0f, FLOOR_MAX)
        val v = ((input - floor - GATE) / (TOP - floor)).coerceIn(0f, 1f)
        val shaped = v.pow(CURVE)
        if (instant) {
            swell = shaped
            level = v
        } else {
            swell += (shaped - swell) * (1f - exp(-dt / if (shaped > swell) SWELL_ATTACK_MS else SWELL_RELEASE_MS))
            level += (v - level) * (1f - exp(-dt / if (v > level) LEVEL_ATTACK_MS else LEVEL_RELEASE_MS))
        }
    }

    companion object {
        const val WINDOW = 160
        /** The floor is the quietest level over this long (ms), smoothed over [FLOOR_MS]. */
        const val FLOOR_WINDOW_MS = 1200L
        const val FLOOR_MS = 300f
        /** Where the floor starts, about −46 dBFS (a quiet room), and the highest it may go, about −28 dBFS. */
        const val FLOOR_START = 0.15f
        const val FLOOR_MAX = 0.6f
        /** Just above the floor still counts as silence; the voice is full at [TOP] (about −18 dBFS). */
        const val GATE = 0.04f
        const val TOP = 0.85f
        /**
         * v^1.25: with the water's wider range (0.34 → 0.86) normal speech stays where it was (about half the ball) and
         * only a loud voice reaches the top.
         */
        const val CURVE = 1.25f
        /** The height, unhurried: τ 180 ms up (90 % in about 0.4 s), τ 600 ms down (90 % in about 1.4 s). */
        const val SWELL_ATTACK_MS = 180f
        const val SWELL_RELEASE_MS = 600f
        /** The waves and the brightness: τ 120 ms up, 450 ms down. */
        const val LEVEL_ATTACK_MS = 120f
        const val LEVEL_RELEASE_MS = 450f
    }
}
