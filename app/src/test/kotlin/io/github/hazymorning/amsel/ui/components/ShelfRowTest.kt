package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ShelfRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aFlingComesToRestOnAWholeCover() {
        val state = LazyListState()
        compose.setContent {
            ShelfRow((1..20).toList(), key = { it }, Modifier.testTag("shelf"), state) { Box(Modifier.size(152.dp)) }
        }
        compose.onNodeWithTag("shelf").performTouchInput { swipeLeft(durationMillis = 120) }
        compose.waitForIdle()
        assertTrue("the shelf did not move", state.firstVisibleItemIndex > 0)
        assertEquals("a cover is cut at the margin", 0, state.firstVisibleItemScrollOffset)
    }
}
