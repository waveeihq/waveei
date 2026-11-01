package app.waveei.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The dots' settings: their defaults, and stored choices kept as they are. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DotPrefsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stored = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        stored.edit().clear().commit()
    }

    @Test
    fun aNewInstallRestsSeeThroughAndActsAtOneTap() {
        val prefs = Prefs(context)
        assertEquals(40, prefs.dotTransparency)
        assertTrue(prefs.dotsOneTap)
    }

    @Test
    fun aStoredTransparencyIsKept() {
        stored.edit().putInt("dot_transparency", 0).commit()
        assertEquals(0, Prefs(context).dotTransparency)
    }
}
