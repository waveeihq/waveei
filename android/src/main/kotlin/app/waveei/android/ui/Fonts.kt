package app.waveei.android.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import app.waveei.android.R

/** Inter (SIL OFL 1.1), one variable file, for the app, the keyboard and the dots. */
object Fonts {
    const val REGULAR = 400
    const val MEDIUM = 500
    const val ROW = 550
    const val SEMIBOLD = 600
    const val TITLE = 650
    const val BOLD = 700

    private var typeface: Typeface? = null

    /** The variable typeface for a View; give a Paint its weight with [weight]. */
    fun typeface(context: Context): Typeface =
        typeface ?: (ResourcesCompat.getFont(context, R.font.inter) ?: Typeface.DEFAULT).also { typeface = it }

    /** Sets [paint] to Inter at [weight] (100..900, any value in between). */
    fun weight(context: Context, paint: Paint, weight: Int) {
        paint.typeface = typeface(context)
        paint.fontVariationSettings = "'wght' $weight"
    }

    /** Inter for Compose, at every weight the design uses. */
    val family = FontFamily(listOf(REGULAR, MEDIUM, ROW, SEMIBOLD, TITLE, BOLD).map {
        Font(R.font.inter, FontWeight(it), variationSettings = FontVariation.Settings(FontVariation.weight(it)))
    })
}
