package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import io.github.hazymorning.amsel.app.MemberVolume
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Volume: a bar per speaker that is its own slider, for a finger and for TalkBack. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class VolumeSheetTest {
    @get:Rule val compose = createComposeRule()

    private val levels = mutableListOf<Int>()
    private val members = mutableListOf<Pair<String, Int>>()

    private fun show(level: Int?, group: List<MemberVolume> = emptyList(), limit: Int = 0) = compose.setContent {
        AmselTheme {
            VolumeSheetContent(
                "Kitchen",
                level,
                group,
                onLevel = { levels += it },
                onMember = { id, at -> members += id to at },
                limit = limit,
            )
        }
    }

    @Test fun talkBackSetsTheLevel() {
        show(level = 40)
        compose.onNodeWithContentDescription("Kitchen").performSemanticsAction(SemanticsActions.SetProgress) {
            it(0.6f)
        }
        assertEquals(60, levels.last())
        compose.onNodeWithContentDescription("Kitchen")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "60\u00A0%"))
    }

    @Test fun aTapSetsTheLevelWhereItLands() {
        show(level = 40)
        compose.onNodeWithContentDescription("Kitchen").performTouchInput { click(percentOffset(0.75f, 0.5f)) }
        assertEquals(75, levels.last())
    }

    @Test fun aGroupHasABarForEachSpeakerUnderItsOwn() {
        show(
            level = 40,
            group = listOf(MemberVolume("bedroom", "Bedroom", 25), MemberVolume("living", "Living room", 70)),
        )
        compose.onNodeWithText("In the group").assertExists()
        compose.onNodeWithContentDescription("Bedroom").performSemanticsAction(SemanticsActions.SetProgress) {
            it(0.3f)
        }
        assertEquals("bedroom" to 30, members.last())
        assertEquals(emptyList<Int>(), levels)
    }

    @Test fun withALimitABarStopsOnItAndAsksBeforeGoingLouder() {
        show(level = 40, limit = 70)
        compose.onNodeWithContentDescription("Kitchen").performTouchInput { click(percentOffset(0.9f, 0.5f)) }
        // It stops on the limit, then asks in place of the bars.
        assertEquals(70, levels.last())
        compose.onNodeWithText("Set Kitchen louder than your limit of 70\u00A0%?").assertExists()
        compose.onNodeWithText("Set to 90\u00A0%").performClick()
        assertEquals(90, levels.last())
        // Let past, it asks no more while the sheet is open.
        compose.onNodeWithContentDescription("Kitchen").performSemanticsAction(SemanticsActions.SetProgress) {
            it(0.95f)
        }
        assertEquals(95, levels.last())
    }

    @Test fun stayingKeepsTheBarOnTheLimit() {
        show(level = 40, limit = 70)
        compose.onNodeWithContentDescription("Kitchen").performSemanticsAction(SemanticsActions.SetProgress) {
            it(1f)
        }
        compose.onNodeWithText("Stay at 70\u00A0%").performClick()
        assertEquals(listOf(70), levels)
        compose.onNodeWithContentDescription("Kitchen")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "70\u00A0%"))
    }

    @Test fun aSpeakerThatSetsItsOwnVolumeSaysSoAndTakesNoChange() {
        show(level = null)
        compose.onNodeWithContentDescription("Kitchen")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Disabled))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Volume is set on the speaker"))
    }
}
