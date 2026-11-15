package io.github.hazymorning.amsel.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One number drives a player layer: taps, drags, throws, a list's pull and the back gesture. */
@RunWith(RobolectricTestRunner::class)
class PlayerLayerStateTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var layer: PlayerLayerState
    private lateinit var scope: CoroutineScope

    /** A layer [heightPx] tall, as its layout measures it. */
    private fun start(open: Boolean, heightPx: Float = 1000f) {
        compose.setContent {
            layer = remember { PlayerLayerState(open) }
            scope = rememberCoroutineScope()
        }
        compose.runOnIdle { layer.heightPx = heightPx }
    }

    private fun run(block: suspend PlayerLayerState.() -> Unit) {
        compose.runOnIdle { scope.launch { layer.block() } }
        compose.waitForIdle()
    }

    @Test fun opensAndCloses() {
        start(open = false)
        assertFalse(layer.isVisible)
        run { open() }
        assertEquals(1f, layer.fraction)
        assertTrue(layer.isOpen)
        run { close() }
        assertEquals(0f, layer.fraction)
        assertFalse(layer.isVisible)
    }

    @Test fun aDragFollowsTheFingerAndAShortOneSpringsBack() {
        start(open = true)
        compose.runOnIdle { layer.dragBy(deltaPx = 200f) }
        assertEquals(0.8f, layer.fraction, 0.001f)
        run { settle(velocityPx = 0f) }
        assertEquals(1f, layer.fraction)
        assertTrue(layer.isOpen)
    }

    @Test fun aDragPastAThirdOrAThrowDownCloses() {
        start(open = true)
        compose.runOnIdle { layer.dragBy(deltaPx = 400f) }
        run { settle(velocityPx = 0f) }
        assertEquals(0f, layer.fraction)
        assertFalse(layer.isOpen)

        run { open() }
        compose.runOnIdle { layer.dragBy(deltaPx = 50f) }
        run { settle(velocityPx = 900f) }
        assertFalse(layer.isOpen)
        assertEquals(0f, layer.fraction)
    }

    @Test fun aDragUpFromTheDockFollowsAndOpensPastAnEighth() {
        start(open = false)
        compose.runOnIdle { layer.dragBy(deltaPx = -100f) }
        assertEquals(0.1f, layer.fraction, 0.001f)
        assertTrue(layer.isVisible)
        run { settle(velocityPx = 0f) }
        assertEquals(0f, layer.fraction)
        assertFalse(layer.isVisible)

        compose.runOnIdle { layer.dragBy(deltaPx = -200f) }
        run { settle(velocityPx = 0f) }
        assertEquals(1f, layer.fraction)
        assertTrue(layer.isOpen)
    }

    @Test fun aSlowFlickUpFromTheDockOpens() {
        start(open = false)
        compose.runOnIdle { layer.dragBy(deltaPx = -40f) }
        run { settle(velocityPx = -300f) }
        assertTrue(layer.isOpen)
        assertEquals(1f, layer.fraction)
    }

    @Test fun aThrowGoesTheWayItWasThrown() {
        start(open = false)
        compose.runOnIdle { layer.dragBy(deltaPx = -50f) }
        run { settle(velocityPx = -900f) }
        assertTrue(layer.isOpen)
        assertEquals(1f, layer.fraction)

        // Pulled most of the way down, then thrown back up: it stays open.
        compose.runOnIdle { layer.dragBy(deltaPx = 800f) }
        run { settle(velocityPx = -900f) }
        assertTrue(layer.isOpen)
        assertEquals(1f, layer.fraction)
    }

    @Test fun aSlowReleaseIsNotAThrow() {
        start(open = true)
        compose.runOnIdle { layer.dragBy(deltaPx = 100f) }
        run { settle(velocityPx = 400f) }
        assertTrue(layer.isOpen)
    }

    @Test fun turningBackAnswersAtOnce() {
        start(open = true)
        compose.runOnIdle {
            // Up past fully open goes nowhere, so the way back down starts at once.
            layer.dragBy(deltaPx = -300f)
            layer.dragBy(deltaPx = 100f)
        }
        assertEquals(0.9f, layer.fraction, 0.001f)
    }

    @Test fun nothingMovesBeforeTheLayerHasAHeight() {
        start(open = false, heightPx = 0f)
        compose.runOnIdle { layer.dragBy(deltaPx = -400f) }
        assertEquals(0f, layer.fraction)
    }

    @Test fun theBackGestureOnlyPreviews() {
        start(open = true)
        compose.runOnIdle { layer.followBack(1f) }
        assertEquals(1f, layer.fraction)
        assertEquals(1f, layer.backProgress)
        assertTrue(layer.isOpen)
    }

    @Test fun aCancelledBackPreviewGrowsBack() {
        start(open = true)
        compose.runOnIdle { layer.followBack(0.6f) }
        run { open() }
        assertEquals(1f, layer.fraction)
        assertEquals(0f, layer.backProgress)
    }

    @Test fun aCommittedBackPreviewCloses() {
        start(open = true)
        compose.runOnIdle { layer.followBack(1f) }
        run { close() }
        assertEquals(0f, layer.fraction)
        assertEquals(0f, layer.backProgress)
        assertFalse(layer.isVisible)
    }

    @Test fun aListHandsItsPullAtTheTopToTheLayer() {
        start(open = true)
        val scroll = layer.nestedScroll
        val taken = compose.runOnIdle {
            scroll.onPostScroll(Offset.Zero, Offset(0f, 250f), NestedScrollSource.UserInput)
        }
        assertEquals(250f, taken.y)
        assertEquals(0.75f, layer.fraction, 0.001f)

        // Pushing back up lifts the layer before the list scrolls, and no further than open.
        val lifted = compose.runOnIdle { scroll.onPreScroll(Offset(0f, -400f), NestedScrollSource.UserInput) }
        assertEquals(-250f, lifted.y, 0.5f)
        assertEquals(1f, layer.fraction)

        // The list's own fling never moves the layer.
        val flung = compose.runOnIdle {
            scroll.onPostScroll(Offset.Zero, Offset(0f, 250f), NestedScrollSource.SideEffect)
        }
        assertEquals(0f, flung.y)
        assertEquals(1f, layer.fraction)
    }

    @Test fun aListThrownDownAtTheTopClosesTheLayer() {
        start(open = true)
        compose.runOnIdle {
            layer.nestedScroll.onPostScroll(Offset.Zero, Offset(0f, 100f), NestedScrollSource.UserInput)
        }
        run { nestedScroll.onPreFling(Velocity(0f, 1200f)) }
        assertFalse(layer.isOpen)
        assertEquals(0f, layer.fraction)
    }
}
