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
     * Reference values for one input, Math.random as [mulberry32] seeded 12345: upright for half a second, a 20° roll over 0.3 s, a 6 m/s² jolt sideways for four
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
                doubleArrayOf(-4.284427165985107, -0.9538592100143433, 0.5605971813201904, 3.3335938453674316, 9.001394271850586),
                doubleArrayOf(-3.3847885131835938, -0.7201341986656189, 0.43881189823150635, 2.559060573577881, 7.867445945739746),
                0.7935067349684579, 1.4747830650139746, 6,
                doubleArrayOf(369.63726806640625, 30.284196853637695, 1.7231491804122925, 0.9453803300857544, 134.46925354003906, 14.952585220336914, 2.202873945236206, 0.9990479350090027),
            ),
            95 to Page(
                doubleArrayOf(-17.130220413208008, -6.726825714111328, 4.557885646820068, 15.155404090881348, 22.030061721801758),
                doubleArrayOf(-16.807958602905273, -6.701344013214111, 4.541699409484863, 14.915515899658203, 22.4625244140625),
                0.9621632801847517, 1.5925060932339976, 7,
                doubleArrayOf(370.3805236816406, 36.141578674316406, 1.7231491804122925, 0.9453803300857544, 148.68321228027344, 22.285024642944336, 2.202873945236206, 0.9990479350090027),
            ),
            240 to Page(
                doubleArrayOf(-10.12312126159668, -3.8102612495422363, 2.525310754776001, 8.873556137084961, 15.12611198425293),
                doubleArrayOf(-10.366365432739258, -4.0040178298950195, 2.6756043434143066, 9.109485626220703, 15.425225257873535),
                0.6775080702984645, 2.0521102736102996, 7,
                doubleArrayOf(368.5525817871094, 60.60110092163086, 1.7231491804122925, 0.9453803300857544, 135.96237182617188, 52.904144287109375, 2.202873945236206, 0.9990479350090027),
            ),
        )
    }
}
