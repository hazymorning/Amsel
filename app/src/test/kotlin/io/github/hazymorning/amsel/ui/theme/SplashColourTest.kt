package io.github.hazymorning.amsel.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import io.github.hazymorning.amsel.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The window and the system splash must be painted before Compose is, so they repeat the page colour here. */
@RunWith(RobolectricTestRunner::class)
class SplashColourTest {
    @Test fun theDaySplashIsThePage() = assertEquals(LightColors.surface, splash())

    @Config(qualifiers = "night")
    @Test
    fun theNightSplashIsThePage() = assertEquals(DarkColors.surface, splash())

    private fun splash() =
        Color(ApplicationProvider.getApplicationContext<Context>().getColor(R.color.splash_background))
}
