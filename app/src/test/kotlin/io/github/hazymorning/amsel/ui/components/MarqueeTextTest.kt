package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A line longer than its room glides to its end and back, again and again; one that fits, or does not run, rests. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MarqueeTextTest {
    @get:Rule val compose = createComposeRule()

    private val long = "Harbour Lights (Extended Version) · Kestrel & Finch"

    private fun show(text: String, runs: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AmselTheme {
                Box(Modifier.width(120.dp)) {
                    MarqueeText(
                        text,
                        MaterialTheme.typography.bodyMedium,
                        Color.Black,
                        Modifier.testTag("line"),
                        runs = runs,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun frame() = compose.onNodeWithTag("line").captureToImage().asAndroidBitmap()

    /** Whether the line stands at its start at each half second of the next [seconds]. */
    private fun atStart(seconds: Int): List<Boolean> {
        val start = frame()
        return List(seconds * 2) {
            compose.mainClock.advanceTimeBy(500)
            frame().sameAs(start)
        }
    }

    @Test fun aLongLineGlidesAwayAndBackForAsLongAsItRuns() {
        show(long)
        val rests = atStart(60)
        val leaves = rests.zipWithNext().count { (before, after) -> before && !after }
        assertTrue("left its start $leaves times in a minute: $rests", leaves >= 2)
        assertTrue("it rests at its start first", rests.take(4).all { it })
    }

    @Test fun aLineThatFitsRests() {
        show("Lanterns")
        assertEquals(List(20) { true }, atStart(10))
    }

    @Test fun aLineThatDoesNotRunRests() {
        show(long, runs = false)
        assertEquals(List(20) { true }, atStart(10))
    }
}
