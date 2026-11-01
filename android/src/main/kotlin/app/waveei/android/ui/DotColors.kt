package app.waveei.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import app.waveei.android.Prefs

/**
 * The floating dots' colours and their pop-ups' veil, day and night. The dots look the
 * same day and night; the menus and bubbles follow Settings › Theme like the keyboard.
 *
 * Over other apps the veil cannot blur what is behind it only within its bounds (Android's blur behind would blur
 * the whole screen), so it is the design's opaque fallback: the veil at 94 %.
 */
class DotColors private constructor(
    val night: Boolean,
    val veil: Int,
    /** The 1 dp ring around the veil, outside its edge; none at night. */
    val veilRing: Int,
    /** The 1 dp light line inside the veil's edge. */
    val veilHairline: Int,
    val veilShadow: Int,
    val ink: Int,
    val icon: Int,
    val red: Int,
) {
    /** Draws a veil card in [rect]: the soft shadow, the ring outside, the fill and the inner hairline. */
    fun drawVeil(canvas: Canvas, rect: RectF, radius: Float, density: Float, paint: Paint, alpha: Float = 1f) {
        paint.style = Paint.Style.FILL
        paint.color = Palette.alpha(veil, alpha)
        paint.setShadowLayer(VEIL_SHADOW_BLUR * density, 0f, VEIL_SHADOW_Y * density, Palette.alpha(veilShadow, alpha))
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        if (veilRing != 0) {
            paint.color = Palette.alpha(veilRing, alpha)
            canvas.drawRoundRect(rect.left - density / 2, rect.top - density / 2, rect.right + density / 2, rect.bottom + density / 2, radius + density / 2, radius + density / 2, paint)
        }
        paint.color = Palette.alpha(veilHairline, alpha)
        canvas.drawRoundRect(rect.left + density / 2, rect.top + density / 2, rect.right - density / 2, rect.bottom - density / 2, radius - density / 2, radius - density / 2, paint)
        paint.style = Paint.Style.FILL
    }

    companion object {
        /** The switch dot: a white pearl. */
        const val PEARL = 0xFFFFFFFF.toInt()

        /** The faint shade towards the lower edge of both dots: #0B4290 at 9 %. */
        const val SHADE = 0x170B4290

        /** The record dot: white sky over the keyboard's blue water, a lighter back wave and a white surface line. */
        const val SKY = 0xFFF1F7FF.toInt()
        const val BACK_WAVE = 0xB3BFE2FF.toInt()
        const val SURFACE = 0xCCFFFFFF.toInt()

        /** The recording ring around the record dot, the keyboard's day accent: 4.52:1 on white, 4.05:1 on #10151C. */
        const val RING = 0xFF0E70F4.toInt()

        /** Both dots' shadow, two layers of #0B285A at 22 %: 0 1 2 and 0 4 12; no ring, no halo. */
        const val SHADOW = 0x380B285A

        /** The veil's shadow, 0 4 14 (Android's shadow radius for a 14 dp CSS blur). */
        const val VEIL_SHADOW_Y = 4f
        const val VEIL_SHADOW_BLUR = 11f

        val DAY = DotColors(
            night = false,
            veil = 0xF0FFFFFF.toInt(),
            veilRing = 0x12121821,
            veilHairline = 0xB3FFFFFF.toInt(),
            veilShadow = 0x12121821,
            ink = 0xFF15191F.toInt(),
            icon = 0xFF2A3340.toInt(),
            red = 0xFFD92D20.toInt(),
        )

        val NIGHT = DotColors(
            night = true,
            veil = 0xF01E293C.toInt(),
            veilRing = 0,
            veilHairline = 0x1F96B4E6,
            veilShadow = 0x38000000,
            ink = 0xFFE9EEF6.toInt(),
            icon = 0xFFA9B4C6.toInt(),
            red = 0xFFFF7A6E.toInt(),
        )

        /** Day or night as the keyboard picks it from Settings › Theme. */
        fun resolve(context: Context, theme: Prefs.Theme): DotColors = if (KeyboardColors.resolve(context, theme).night) NIGHT else DAY
    }
}
