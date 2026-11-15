package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.app.GroupChoice
import io.github.hazymorning.amsel.sample.PlayerSample
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Play on: Group as a big button under the speakers, and Ungroup over them while a group stands. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class PlayOnUngroupTest {
    @get:Rule val compose = createComposeRule()

    private val kitchen = PlayerSample.speakers.first { it.id == "kitchen" }
    private val phone = PlayerSample.speakers.first { it.isPhone }

    /** Kitchen leads the Bedroom and the Living room: one row in the list, the two in its group step. */
    private val grouped = PlayerSample.speakers.filter { it.id != "living" && it.id != "bedroom" }
        .map { if (it.id == "kitchen") it.copy(others = 2) else it }
    private val kitchensGroup = listOf(
        GroupChoice("bedroom", "Bedroom", isPhone = false, member = true),
        GroupChoice("living", "Living room", isPhone = false, member = true),
        GroupChoice(phone.id, "", isPhone = true, member = false),
    )

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        AmselTheme { content() }
    }

    @Test fun withNoGroupThereIsGroupForTheSpeakerInUseAndNoUngroup() {
        val alone = listOf(GroupChoice("living", "Living room", isPhone = false, member = false))
        show { PlayOnSheetContent(PlayOnUiState(PlayerSample.speakers, "kitchen", kitchen, alone), onSelect = {}) }
        compose.onNodeWithText("Ungroup all").assertDoesNotExist()
        compose.onNodeWithText("Group").performClick()
        compose.onNodeWithText("Group · Kitchen").assertIsDisplayed()
    }

    @Test fun aGroupTheSpeakerInUseLeadsNamesItsSizeAndUngroupLetsEveryoneGo() {
        var ungrouped = 0
        show {
            PlayOnSheetContent(
                PlayOnUiState(grouped, "kitchen", kitchen, kitchensGroup, grouped = true),
                onSelect = {},
                onUngroup = { ungrouped++ },
            )
        }
        compose.onNodeWithText("Group +2").assertIsDisplayed()
        // Making a group is not offered while one stands.
        compose.onNodeWithText("Group").assertDoesNotExist()
        compose.onNodeWithText("Ungroup all").performClick()
        assertEquals(1, ungrouped)
    }

    @Test fun aGroupAnotherSpeakerLeadsOpensThatLeadersGroupStep() {
        val asked = mutableListOf<Triple<String, List<String>, List<String>>>()
        show {
            PlayOnSheetContent(
                PlayOnUiState(grouped, phone.id, kitchen, kitchensGroup, grouped = true),
                onSelect = {},
                onGroup = { leaderId, add, remove -> asked += Triple(leaderId, add, remove) },
            )
        }
        compose.onNodeWithText("Ungroup all").assertIsDisplayed()
        compose.onNodeWithText("Group +2").performClick()
        // The step is the group's, whichever speaker the phone is set to.
        compose.onNodeWithText("Group · Kitchen").assertIsDisplayed()
        compose.onNodeWithText("Bedroom").performClick()
        compose.onNodeWithText("This phone").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf(Triple("kitchen", listOf(phone.id), listOf("bedroom"))), asked)
    }

    @Test fun aSpeakerThatCannotLeadHasNoGroupAndNoUngroup() {
        show { PlayOnSheetContent(PlayOnUiState(PlayerSample.speakers, "kitchen"), onSelect = {}) }
        compose.onNodeWithText("Group").assertDoesNotExist()
        compose.onNodeWithText("Ungroup all").assertDoesNotExist()
    }

    @Test fun ungroupIsABigButtonOverTheFirstSpeakerAndGroupOneUnderTheLast() {
        val state = PlayOnUiState(grouped, "kitchen", kitchen, kitchensGroup, grouped = true)
        show { PlayOnSheetContent(state, onSelect = {}) }
        val heading = compose.onNodeWithText("Play on").getUnclippedBoundsInRoot()
        val ungroup = compose.onNodeWithText("Ungroup all").getUnclippedBoundsInRoot()
        val group = compose.onNodeWithText("Group +2").getUnclippedBoundsInRoot()
        // This phone is the first speaker Play on lists, Kitchen the last.
        val first = compose.onNodeWithText("This phone").getUnclippedBoundsInRoot()
        val last = compose.onNodeWithText("Kitchen +2").getUnclippedBoundsInRoot()
        for (button in listOf(ungroup, group)) {
            assertEquals(24f, button.left.value, 0.5f)
            assertEquals(411f - 24f, button.right.value, 0.5f)
            assertTrue(button.bottom - button.top >= 48.dp)
        }
        assertTrue(heading.bottom < ungroup.top && ungroup.bottom < first.top)
        // Well apart from the list: 40 under the last speaker's row, on top of the row's own room.
        assertEquals(40f, (group.top - last.bottom).value, 0.5f)
    }
}
