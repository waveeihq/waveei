package app.waveei.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RecordingCanvas
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Half
import android.view.Surface
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import app.waveei.android.DebugLog
import app.waveei.core.tank.Glass
import java.nio.ShortBuffer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Home's title-card water: it fills [modifier]'s box, the card's whole 379 × 244 dp (the card clips it and
 * the title sits over its top). [level] is the share under water of the design's water canvas, the 150 dp under the
 * title (0.22 to 0.56 by the Access ticks passed), as the frames draw it; a change runs there over 1400 ms. The water
 * tilts and sloshes with the phone and never listens to the voice. [night] takes the night water. Where the phone
 * cannot run the water's shader, still water stands in its place.
 */
@Composable
fun HomeTank(level: Float, modifier: Modifier = Modifier, night: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f) {
    val art = remember {
        runCatching { TankArt() }
            .onFailure { DebugLog.add("tank: no shader, still water instead: ${it.javaClass.simpleName}") }
            .getOrNull()
    }
    val shown = animateFloatAsState(level, tween(LEVEL_MS, easing = DECELERATE), label = "tank level")
    val look = if (night) TANK_NIGHT else TANK_DAY
    if (art == null) {
        StillWater(shown, look, modifier)
        return
    }
    val view = LocalView.current
    val tilt = rememberTilt(view)
    val water = remember { TankWater() }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(tilt.front) {
        // Only while the screen is in front, as the sensor: in the background nothing runs.
        if (!tilt.front) return@LaunchedEffect
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0.0 else min(0.1, (now - last) / 1e9)
            last = now
            if (water.card > 0f) water.step(dt, tilt.x, tilt.y, shown.value * WATER_DP)
            frame = now
        }
    }
    Canvas(modifier.graphicsLayer()) {
        frame
        water.card = size.width / density
        drawIntoCanvas {
            // A software canvas (a screenshot of the view) cannot run the shader: Android refuses it there.
            if (it.nativeCanvas.isHardwareAccelerated) art.draw(it.nativeCanvas, size.width, size.height, density, water, shown.value * WATER_DP, look)
            else stillWater(shown.value, look)
        }
    }
}

/** The water's level by the Access ticks passed, 0 to 4: shares of the 150 dp under the title. */
val TANK_LEVELS = floatArrayOf(0.22f, 0.31f, 0.39f, 0.48f, 0.56f)

/** The simulation and its clock: [Glass] a frame at a time, on the main thread (well under a millisecond a frame). */
internal class TankWater(val glass: Glass = Glass()) {
    /** The card's width, dp, as last drawn. */
    var card = 0f
    /** Seconds the water has run. */
    var seconds = 0.0
        private set

    fun step(dt: Double, x: Double, y: Double, rest: Float) {
        seconds += dt
        glass.step(dt, x, y, card.toDouble(), rest.toDouble())
    }
}

/** The water's colours, day and night. */
internal class TankLook(val surface: FloatArray, val bottom: FloatArray, val top: FloatArray, val caustics: Float, val far: FloatArray, val line: Float)

internal val TANK_DAY = TankLook(floatArrayOf(0.56f, 0.80f, 1f), floatArrayOf(0.22f, 0.55f, 0.96f), floatArrayOf(0.72f, 0.88f, 1f), 0.5f, floatArrayOf(1f, 1f, 1f), 0.6f)
// At night the back face fades into the card (#172131), with no light line: a white one would read as a lid.
internal val TANK_NIGHT = TankLook(floatArrayOf(0.24f, 0.52f, 0.95f), floatArrayOf(0.07f, 0.2f, 0.52f), floatArrayOf(0.2f, 0.36f, 0.62f), 0.35f, floatArrayOf(0.09f, 0.129f, 0.192f), 0f)

/** What the shader draws from: the edges as a 33 × 1 bitmap, and the rest as uniforms. */
internal class TankArt {
    private val shader = RuntimeShader(TANK_SHADER)
    private val paint = Paint().apply { shader = this@TankArt.shader }
    private val node = RenderNode("tank")
    private val edges = Bitmap.createBitmap(Glass.SAMPLES, 1, Bitmap.Config.RGBA_F16)
    private val halves = ShortArray(4 * Glass.SAMPLES)

    init {
        // Read as it is, without colour management: the channels are heights, not colours.
        val s = BitmapShader(edges, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        s.filterMode = BitmapShader.FILTER_MODE_NEAREST
        shader.setInputBuffer("uEdges", s)
    }

    /**
     * Draws [water] over [w] × [h] pixels, its rest level [rest] dp over the bottom: on a phone's
     * dense screen into a layer at most two pixels to a dp, then stretched.
     */
    // halves is a ShortArray of half floats; lint reads the indexed set as widening to Unit.
    @android.annotation.SuppressLint("HalfFloat")
    fun draw(canvas: android.graphics.Canvas, w: Float, h: Float, density: Float, water: TankWater, rest: Float, look: TankLook) {
        if (w <= 0f || h <= 0f) return
        val glass = water.glass
        val one = Half.toHalf(1f)
        for (j in 0 until Glass.SAMPLES) {
            halves[4 * j] = Half.toHalf(glass.edge[j])
            halves[4 * j + 1] = Half.toHalf(glass.far[j])
            halves[4 * j + 3] = one
        }
        edges.copyPixelsFromBuffer(ShortBuffer.wrap(halves))
        shader.setFloatUniform("T", (4 + water.seconds * 0.15).toFloat())
        shader.setFloatUniform("uLevel", rest * density / h)
        shader.setFloatUniform("uStir", glass.stir.toFloat())
        shader.setFloatUniform("uCT", glass.causticTime.toFloat())
        shader.setFloatUniform("uBubs", glass.bubbles)
        shader.setIntUniform("uNB", glass.count)
        shader.setFloatUniform("cS", look.surface)
        shader.setFloatUniform("cD", look.bottom)
        shader.setFloatUniform("cTop", look.top)
        shader.setFloatUniform("uCaus", look.caustics)
        shader.setFloatUniform("cFar", look.far)
        shader.setFloatUniform("uLine", look.line)

        val scale = min(1f, MAX_DENSITY / density)
        if (scale >= 1f || canvas !is RecordingCanvas) {
            shader.setFloatUniform("R", w, h)
            shader.setFloatUniform("D", density)
            canvas.drawRect(0f, 0f, w, h, paint)
            return
        }
        val lw = max(1, (w * scale).roundToInt())
        val lh = max(1, (h * scale).roundToInt())
        shader.setFloatUniform("R", lw.toFloat(), lh.toFloat())
        shader.setFloatUniform("D", density * lh / h)
        node.setPosition(0, 0, lw, lh)
        node.setUseCompositingLayer(true, null)
        val c = node.beginRecording()
        c.drawRect(0f, 0f, lw.toFloat(), lh.toFloat(), paint)
        node.endRecording()
        canvas.save()
        canvas.scale(w / lw, h / lh)
        canvas.drawRenderNode(node)
        canvas.restore()
    }
}

/** Where the shader cannot run: the water still at its level, the top face over it, the body light to deep. */
@Composable
private fun StillWater(level: State<Float>, look: TankLook, modifier: Modifier) {
    Canvas(modifier) { stillWater(level.value, look) }
}

internal fun DrawScope.stillWater(level: Float, look: TankLook) {
    val surface = size.height - level * WATER_DP.dp.toPx()
    val top = TOP_DP.dp.toPx()
    drawRect(look.top.color(), Offset(0f, surface - top), Size(size.width, top), alpha = 0.9f)
    drawRect(
        Brush.verticalGradient(listOf(look.surface.color(), look.bottom.color()), startY = surface, endY = size.height),
        Offset(0f, surface),
        Size(size.width, size.height - surface),
    )
}

private fun FloatArray.color() = Color(this[0], this[1], this[2])

/**
 * The accelerometer with gravity, as it comes (it carries the hand's jolts too, which move the water), turned to the
 * screen's way up (x right, y the rest of gravity), reading up as an accelerometer does: upright and still 0, 9.81, as
 * it stays where the phone has no sensor. Events come on the main thread, as the frames do.
 */
private class Tilt(private val view: View) : SensorEventListener {
    var x = 0.0
        private set
    var y = Glass.EARTH
        private set
    /** Whether the screen is in front: only then the sensor is listened to and the water runs. */
    var front by mutableStateOf(false)

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        when (view.display?.rotation) {
            Surface.ROTATION_90 -> { x = -v[1].toDouble(); y = v[0].toDouble() }
            Surface.ROTATION_180 -> { x = -v[0].toDouble(); y = -v[1].toDouble() }
            Surface.ROTATION_270 -> { x = v[1].toDouble(); y = -v[0].toDouble() }
            else -> { x = v[0].toDouble(); y = v[1].toDouble() }
        }
        // Only the turn left and right moves the water: what is not across the screen counts as down, so a phone lying
        // flat reads level. The water leans by the angle of x against y, and with the phone on a table both are only
        // noise, so the angle would jump about and the water fret.
        y = hypot(y, v[2].toDouble())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Listens to the accelerometer while the screen is in front. */
@Composable
private fun rememberTilt(view: View): Tilt {
    val tilt = remember(view) { Tilt(view) }
    val owner = view.context as? LifecycleOwner
    DisposableEffect(owner, tilt) {
        val sensors = view.context.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        fun listen(on: Boolean) {
            tilt.front = on
            if (sensor == null) return
            if (on) sensors.registerListener(tilt, sensor, SensorManager.SENSOR_DELAY_GAME) else sensors.unregisterListener(tilt)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) listen(true)
            if (event == Lifecycle.Event.ON_PAUSE) listen(false)
        }
        if (owner == null) listen(true) else owner.lifecycle.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            listen(false)
        }
    }
    return tilt
}

/** The design's water canvas: the 150 dp under the title, the share [HomeTank]'s level is of. */
private const val WATER_DP = 150f
/** The top face's depth over the water, dp, as the shader's TOP. */
private const val TOP_DP = 11f
/** A level change, ms, standard-decelerate. */
private const val LEVEL_MS = 1400
private val DECELERATE = CubicBezierEasing(0f, 0f, 0f, 1f)
/** Pixels to a dp the water is drawn at, at most. */
private const val MAX_DENSITY = 2f
