package app.waveei.android.ui

import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.SweepGradient

/**
 * The busy arc's ends as soft as its sides: a blur widens the stroke's sides, but its ends stop short, so the colour
 * fades in over the first and last [FADE] degrees of the arc. Kept while the arc only turns.
 */
class ArcFade {
    private val matrix = Matrix()
    private var shader: SweepGradient? = null
    private var forX = Float.NaN
    private var forY = Float.NaN
    private var forSweep = Float.NaN
    private var forColor = 0

    /** For an arc of [sweep] degrees from [start] around ([cx], [cy]); the paint's alpha still applies. */
    fun shader(cx: Float, cy: Float, start: Float, sweep: Float, color: Int): Shader {
        val opaque = color or (0xFF shl 24)
        if (shader == null || cx != forX || cy != forY || sweep != forSweep || opaque != forColor) {
            forX = cx
            forY = cy
            forSweep = sweep
            forColor = opaque
            val clear = opaque and 0xFFFFFF
            val end = (sweep / 360f).coerceAtMost(1f)
            val fade = minOf(FADE, sweep / 3f) / 360f
            shader = SweepGradient(
                cx, cy,
                intArrayOf(clear, opaque, opaque, clear, clear),
                floatArrayOf(0f, fade, end - fade, end, 1f),
            )
        }
        matrix.setRotate(start, cx, cy)
        return shader!!.also { it.setLocalMatrix(matrix) }
    }

    private companion object {
        const val FADE = 30f
    }
}
