package app.waveei.core.tank

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Home's water: the card is a close look at the near edge of a wide pool, as beer in a glass.
 * Tilting the phone makes the edge rise on the low side and sway like water, a jolt sends small ripples along it, and
 * bubbles rise through it, ride the slosh and pop at the edge. Sizes
 * are dp, times seconds. Not thread-safe: one [step] at a time, and its results read between steps.
 *
 * [random] gives 0 until 1, as Math.random; a seeded one makes the bubbles the same on every run.
 */
class Glass(private val random: () -> Double = Random.Default::nextDouble) {
    /** The near edge: dp off the rest level at [SAMPLES] even points across the card, after the last [step]. */
    val edge = FloatArray(SAMPLES)
    /** The far edge of the top face, the same way; at rest it is the near one. */
    val far = FloatArray(SAMPLES)
    /** How much the water moves, 0 calm to 1: the caustics sharpen and the shafts brighten with it. */
    var stir = 0.0
        private set
    /** The caustics' own clock: steady on calm water, up to four times as fast while it moves. */
    var causticTime = 4 * 0.35
        private set
    /** Four numbers a bubble for the shader: x, y (dp over the card's bottom left), radius and how strongly it shows. */
    val bubbles = FloatArray(4 * NB)
    /** How many of [bubbles] there are. */
    var count = 0
        private set

    private val edgePool = Pool(EDGE)
    private val bodyPool = Pool(BODY)
    private val ripplePool = Pool(RIPPLE)
    private var seen: FloatArray? = null
    private var clock = 0.0
    // The front-to-back tilt (z) of the last FAR_LAG seconds, oldest first, and arrays to reuse for it.
    private val history = ArrayDeque<Past>()
    private val spare = ArrayList<Past>()
    private val z = FloatArray(SAMPLES)

    private var list: ArrayList<Bubble>? = null
    private var heldX = 0.0
    private var heldY = 0.0
    private var owe = 0.0
    private var shake = 0.0

    /**
     * Runs [dt] seconds (capped at 0.1 by the caller) with [rawX], [rawY], the accelerometer
     * with gravity in screen axes (x right, y up; upright and still 0, 9.81), on a card [card] dp wide whose water
     * rests [rest] dp over its bottom: the pools first, then the bubbles on the new edge.
     */
    fun step(dt: Double, rawX: Double, rawY: Double, card: Double, rest: Double = LEVEL) {
        glassStep(dt, rawX, rawY, card)
        bubbleStep(dt, rawX, rawY, card, rest)
    }

    private fun glassStep(dt: Double, rawX: Double, rawY: Double, card: Double) {
        edgePool.slosh(dt, rawX, rawY, card).copyInto(edge)
        val b = bodyPool.slosh(dt, rawX, rawY, card)
        val rip = ripplePool.slosh(dt, rawX, rawY, card)
        // The edge's sides rise and fall SIDES more: its straight part, the tilt (fitted across the card), is raised by
        // that much; the waves on it stay as they are, and the ripples ride on top. How it tilts front to back (z) is
        // worked out with Z_SIDES of the tilt and no ripples. The tilt turns about the pool's middle.
        var sxy = 0.0
        var sxx = 0.0
        for (j in 0 until SAMPLES) {
            val x = j - (SAMPLES - 1) / 2.0
            sxy += x * edge[j]
            sxx += x * x
        }
        val slope = sxy / sxx
        for (j in 0 until SAMPLES) {
            val tilt = slope * (j - (SAMPLES - 1) / 2.0 + OFF * (SAMPLES - 1))
            z[j] = (edge[j] + Z_SIDES * tilt).toFloat()
            edge[j] = ((edge[j] + SIDES * tilt).toFloat() + rip[j].toDouble()).toFloat()
        }
        // How much the water moves: how fast the lighter pool's surface changes; rises within 0.15 s, settles over 1 s.
        var moved = 0.0
        val last = seen
        if (last != null && dt > 0) for (j in 0 until SAMPLES) moved += abs(b[j] - last[j].toDouble()) / dt / SAMPLES
        seen = b.copyInto(last ?: FloatArray(SAMPLES))
        val now = min(1.0, moved / 12)
        stir += (now - stir) * ease(dt, if (now > stir) 0.15 else 1.0)
        causticTime += dt * 0.15 * 0.35 * (1 + 3 * stir)
        // The far edge: the surface's tilt front to back (z) as it was FAR_LAG ago, against it now, DEPTH of that.
        clock += dt
        val past = spare.removeLastOrNull() ?: Past()
        past.time = clock
        z.copyInto(past.z)
        history.addLast(past)
        while (history.size > 2 && history[1].time <= clock - FAR_LAG) spare.add(history.removeFirst())
        val then = history.first().z
        for (j in 0 until SAMPLES) far[j] = (edge[j] + DEPTH * (then[j] - z[j].toDouble())).toFloat()
    }

    private fun bubbleStep(dt: Double, rawX: Double, rawY: Double, card: Double, rest: Double) {
        val list = list ?: ArrayList<Bubble>().also {
            // the water already has its bubbles on the way up
            list = it
            heldX = rawX
            heldY = rawY
            repeat(5) { _ ->
                val x = rand(0.0, card)
                val y = rand(0.0, rest - 10)
                it.add(Bubble(x, y, rand(0.9, 2.6), 1.0))
            }
        }
        val k = ease(dt, BUBBLE_HOLD_LAG)
        heldX += (rawX - heldX) * k
        heldY += (rawY - heldY) * k
        val jolt = hypot(rawX - heldX, rawY - heldY)
        val shook = min(1.0, max(0.0, jolt - STILL) / SHAKE)
        shake += (shook - shake) * ease(dt, if (shook > shake) UP else DOWN)
        val pace = CALM + PACE * shake
        // born: from below, and under the surface on a jolt
        owe += dt * BASE * pace
        while (owe >= 1) {
            owe -= 1
            val x = rand(0.0, card)
            list.add(Bubble(x, -4.0, rand(0.9, 2.6), 0.0))
        }
        var burst = dt * BURST * max(0.0, jolt - JOLT)
        while (burst > random()) {
            burst -= 1
            val x = rand(0.0, card)
            val y = surfaceAt(x, card, rest) - rand(5.0, 35.0)
            list.add(Bubble(x, y, rand(0.7, 1.8), 0.0))
        }
        while (list.size > NB) list.removeAt(0)
        // moved, and popped at the edge
        val l = POOL * card
        val dx = l / GRID
        val x0 = (l - card) / 2 + OFF * card
        val u = edgePool.u
        val s = ease(dt, LAG)
        list.removeAll { b ->
            b.age += dt
            b.t += dt * pace
            val f = max(0.0, min(GRID - 0.001, (x0 + b.x) / dx))
            val i = floor(f).toInt()
            val flow = u[i] + (u[i + 1] - u[i]) * (f - i)
            val rise = pace * (RISE_SMALL + (RISE_BIG - RISE_SMALL) * (b.r - 0.9) / 1.7)
            b.vx += (flow + rise * SLANT * rawX / EARTH - b.vx) * s
            b.vy += (rise * rawY / EARTH - b.vy) * s
            b.x += b.vx * dt
            b.y += b.vy * dt
            if (b.pop == 0.0 && b.y + b.r * 0.5 >= surfaceAt(b.x, card, rest)) b.pop = 1e-6
            if (b.pop > 0) b.pop += dt
            !(b.pop < POP && b.x > -10 && b.x < card + 10 && b.y > -20)
        }
        // shown: fading in, and swelling as they fade on popping; they sway a little as they go
        count = 0
        for (b in list) {
            val popping = b.pop > 0
            val show = b.w * min(1.0, b.age / IN) * (if (popping) 1 - b.pop / POP else 1.0)
            val o = 4 * count++
            bubbles[o] = (b.x + sin(b.t * 1.7 + b.ph) * 2).toFloat()
            bubbles[o + 1] = b.y.toFloat()
            bubbles[o + 2] = (b.r * (if (popping) 1 + b.pop / POP * 0.4 else 1.0)).toFloat()
            bubbles[o + 3] = show.toFloat()
        }
    }

    /** The edge's height over the card's bottom at [x], dp. */
    private fun surfaceAt(x: Double, card: Double, rest: Double): Double {
        val f = max(0.0, min(SAMPLES - 1.001, x / card * (SAMPLES - 1)))
        val i = floor(f).toInt()
        return rest + edge[i] + (edge[i + 1] - edge[i].toDouble()) * (f - i)
    }

    private fun rand(a: Double, b: Double) = a + random() * (b - a)

    private inner class Bubble(var x: Double, var y: Double, val r: Double, var age: Double) {
        val w = rand(0.7, 1.0)
        var pop = 0.0
        val ph = rand(0.0, 6.3)
        var t = 0.0
        var vx = 0.0
        var vy = 0.0
    }

    private class Past {
        var time = 0.0
        val z = FloatArray(SAMPLES)
    }

    /** How a pool answers the pull; see [EDGE], [BODY] and [RIPPLE]. */
    private class Kind(
        val k: Double,
        val speed: Double,
        val loss: Double,
        val smooth: Double,
        val push: Double = 0.0,
        val holdLag: Double = 0.0,
        val pullLag: Double = 0.03,
    )

    /**
     * One pool: linear shallow water on a staggered grid of [GRID] cells, [POOL] card widths wide, so its walls are off
     * the screen; the flow at the walls stays 0. The pull (smoothed over the kind's pull lag) speeds the water up
     * everywhere at once, but the surface only rises where the flow piles up, at the walls; from there the lean runs in
     * as a wave, so a tilt reaches the card late, bends its edge as it crosses, rocks back and forth and settles.
     */
    private class Pool(private val kind: Kind) {
        val eta = DoubleArray(GRID)
        val u = DoubleArray(GRID + 1)
        private var pullX = 0.0
        private var pullY = -EARTH
        private var held = Double.NaN
        private val e = FloatArray(SAMPLES)

        /** One step; the surface across the card, dp off its rest, at even points. */
        fun slosh(dt: Double, rawX: Double, rawY: Double, card: Double): FloatArray {
            val k = ease(dt, kind.pullLag)
            pullX += (-rawX - pullX) * k
            pullY += (-rawY - pullY) * k
            val now = atan2(pullX, -pullY)
            var want = kind.k * now
            if (kind.push != 0.0) {
                if (held.isNaN()) held = now
                held += (now - held) * ease(dt, kind.holdLag)
                want += kind.push * (now - held)
            }
            val lean = tan(max(-MAX_LEAN, min(MAX_LEAN, want)))
            val l = POOL * card
            val dx = l / GRID
            val g = kind.speed * kind.speed / LEVEL
            // Small steps, so it stays stable at any frame rate.
            val steps = max(1, ceil(dt / 0.004).toInt())
            val h = dt / steps
            repeat(steps) {
                for (i in 1 until GRID) {
                    u[i] += h * (-g * ((eta[i] - eta[i - 1]) / dx - lean) - kind.loss * u[i] + kind.smooth * (u[i + 1] - 2 * u[i] + u[i - 1]) / (dx * dx))
                }
                for (i in 0 until GRID) eta[i] -= h * LEVEL * (u[i + 1] - u[i]) / dx
            }
            for (j in 0 until SAMPLES) {
                val x = (l - card) / 2 + OFF * card + card * j / (SAMPLES - 1)
                val f = max(0.0, min(GRID - 1.001, x / dx - 0.5))
                val i = floor(f).toInt()
                e[j] = (eta[i] + (eta[i + 1] - eta[i]) * (f - i)).toFloat()
            }
            return e
        }
    }

    companion object {
        const val EARTH = 9.81
        /** The edges' points across the card. */
        const val SAMPLES = 33
        /** The bubbles at most at once. */
        const val NB = 48
        /** The pools' depth and the design's rest level: .56 of the 150 dp under the title, dp. */
        const val LEVEL = 0.56 * 150

        // The water leans at most MAX_LEAN; the edge's straight part rises SIDES more, the top face's Z_SIDES; the far
        // edge lags the near one by FAR_LAG, DEPTH of the difference. The pool is POOL card widths wide on GRID cells,
        // its middle OFF card widths right of the card's, so the edge turns about a point 40 % across the card.
        private const val MAX_LEAN = 10 * Math.PI / 180
        private const val SIDES = 0.8
        private const val Z_SIDES = 0.3
        private const val DEPTH = 0.7
        private const val FAR_LAG = 0.07
        private const val POOL = 1.25
        private const val OFF = 0.1
        private const val GRID = 120

        /**
         * The edge: leans little, so it rises and falls low, held back as by a thick liquid; after a tilt it comes to its
         * lean with one small overshoot and stays, without rocking back.
         */
        private val EDGE = Kind(k = 0.12, speed = 420.0, loss = 2.5, smooth = 800.0)
        /** Only the stir: how fast the light water under the edge moves. */
        private val BODY = Kind(k = 0.3, speed = 420.0, loss = 0.6, smooth = 800.0)
        /**
         * Ripples: only a jolt (the pull's change from how it has been held over its hold lag) stirs them; slower and
         * less smoothed than the edge, so they run as shorter waves along it and die within a couple of seconds.
         */
        private val RIPPLE = Kind(k = 0.0, speed = 260.0, loss = 1.2, smooth = 80.0, push = 0.1, holdLag = 0.35)

        // The bubbles rise at RISE_SMALL dp/s for a radius of 0.9 dp up to RISE_BIG for 2.6, SLANT of the way aside
        // toward the real up, settling on that within LAG; BASE are born a second at full pace. Calm, they go at CALM
        // of their pace; shaken (the jolt beyond STILL m/s², SHAKE more for all of it) up to CALM + PACE, within UP s,
        // calming over DOWN s. A jolt beyond JOLT m/s² churns in BURST a second per m/s² over it. A bubble at the edge
        // pops over POP s; a new one fades in over IN s.
        private const val CALM = 0.65
        private const val PACE = 0.7
        private const val STILL = 0.5
        private const val SHAKE = 4.0
        private const val UP = 0.1
        private const val DOWN = 1.5
        private const val RISE_SMALL = 7.0
        private const val RISE_BIG = 18.0
        private const val LAG = 0.12
        private const val SLANT = 0.5
        private const val BASE = 0.7
        private const val BUBBLE_HOLD_LAG = 0.35
        private const val JOLT = 1.5
        private const val BURST = 7.0
        private const val POP = 0.06
        private const val IN = 0.15

        /** How far a value smoothed over [lag] seconds moves toward its target in a step of [dt]. */
        private fun ease(dt: Double, lag: Double) = 1 - exp(-dt / lag)
    }
}
