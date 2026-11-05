package app.waveei.core.tank

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassTest {
    @Test
    fun uprightAndStillKeepsTheEdgeFlat() {
        val glass = Glass(mulberry32(1))
        repeat(600) { glass.step(1 / 60.0, 0.0, Glass.EARTH, 379.0) }
        for (j in 0 until Glass.SAMPLES) {
            assertEquals(0f, glass.edge[j])
            assertEquals(0f, glass.far[j])
        }
        assertEquals(0.0, glass.stir)
    }

    /**
     * Reference values (first taken from the design page, since 2026-11-05 Glass's own, the edge keeping level with
     * the ground) for one input, Math.random as [mulberry32] seeded 12345: upright for half a second, a 20° roll over 0.3 s, a 6 m/s² jolt sideways for four
     * frames at 1.5 s, then held, at 60 frames a second on a 379 dp card.
     */
    @Test
    fun matchesTheDesignPage() {
        val glass = Glass(mulberry32(12345))
        val pick = intArrayOf(0, 8, 16, 24, 32)
        for (f in 0..240) {
            val t = f / 60.0
            val roll = if (t < 0.5) 0.0 else min(1.0, (t - 0.5) / 0.3) * 20 * PI / 180
            var x = -sin(roll) * Glass.EARTH
            if (f in 90 until 94) x += 6
            glass.step(if (f == 0) 0.0 else 1 / 60.0, x, cos(roll) * Glass.EARTH, 379.0)
            val want = PAGE[f] ?: continue
            for ((k, j) in pick.withIndex()) {
                near(want.e[k], glass.edge[j].toDouble(), 1e-3, "e[$j] at frame $f")
                near(want.b[k], glass.far[j].toDouble(), 1e-3, "b[$j] at frame $f")
            }
            near(want.stir, glass.stir, 1e-6, "stir at frame $f")
            near(want.ct, glass.causticTime, 1e-9, "ct at frame $f")
            assertEquals(want.n, glass.count, "bubbles at frame $f")
            for (i in want.bubbles.indices) near(want.bubbles[i], glass.bubbles[i].toDouble(), 1e-3, "bubble data $i at frame $f")
        }
    }

    private class Page(val e: DoubleArray, val b: DoubleArray, val stir: Double, val ct: Double, val n: Int, val bubbles: DoubleArray)

    private fun near(want: Double, got: Double, by: Double, what: String) =
        assertTrue(abs(want - got) <= by, "$what: want $want, got $got")

    /** A seeded Math.random stand-in, so every run draws the same bubbles. */
    private fun mulberry32(start: Int): () -> Double {
        var seed = start
        return {
            seed += 0x6D2B79F5
            var t = (seed xor (seed ushr 15)) * (1 or seed)
            t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
            ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    private companion object {
        val PAGE = mapOf(
            30 to Page(
                doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0),
                doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0),
                0.0, 1.4262499999999987, 5,
                doubleArrayOf(370.1544494628906, 25.8233699798584, 1.7231491804122925, 0.9453803300857544, 130.67552185058594, 9.368398666381836, 2.202873945236206, 0.9990479350090027),
            ),
            60 to Page(
                doubleArrayOf(-19.399822235107422, -4.465917110443115, 2.6356568336486816, 15.506919860839844, 37.31851577758789),
                doubleArrayOf(-15.24305248260498, -3.4184508323669434, 2.0915651321411133, 11.894883155822754, 32.353912353515625),
                0.7935067349684579, 1.4747830650139746, 6,
                doubleArrayOf(372.1822814941406, 30.284196853637695, 1.7231491804122925, 0.9453803300857544, 146.2628173828125, 14.952585220336914, 2.202873945236206, 0.9990479350090027),
            ),
        )
    }
}
