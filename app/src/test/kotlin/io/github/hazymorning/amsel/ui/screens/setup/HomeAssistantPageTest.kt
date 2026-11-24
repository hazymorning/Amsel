package io.github.hazymorning.amsel.ui.screens.setup

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The page holds Music Assistant's session token, so the end of its step must destroy it. */
@RunWith(RobolectricTestRunner::class)
class HomeAssistantPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var signingIn by mutableStateOf(true)

    private fun View.find(): WebView? = when (this) {
        is WebView -> this
        is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).find() }
        else -> null
    }

    @Test fun theEndOfTheStepDestroysThePage() {
        // The waiting spinner animates forever, so frames advance only by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            if (signingIn) HomeAssistantSignIn(URL, onCode = {}, onFailed = {}, onClose = {}, plainHttp = { true })
        }
        val page = compose.activity.window.decorView.find()!!

        signingIn = false
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertNull(compose.activity.window.decorView.find())
        assertTrue(shadowOf(page).wasDestroyCalled())
    }

    private companion object {
        const val URL = "https://ha.example/auth/authorize"
    }
}
