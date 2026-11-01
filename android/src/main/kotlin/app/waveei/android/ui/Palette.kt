package app.waveei.android.ui

/** Colour arithmetic shared by the dots; the colours themselves live in AppColors, KeyboardColors and DotColors. */
object Palette {
    /** [color] with its alpha multiplied by [factor]. */
    fun alpha(color: Int, factor: Float): Int {
        val a = ((color ushr 24) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0xFFFFFF)
    }
}
