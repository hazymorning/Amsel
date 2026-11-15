package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Play on's header: Group for the group that exists or the speaker in use, Ungroup beside it while one does. */
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
        compose.onNodeWithContentDescription("Ungroup all").assertDoesNotExist()
        compose.onNodeWithContentDescription("Group").performClick()
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
        compose.onNodeWithContentDescription("Group +2").assertIsDisplayed()
        // Making a group is not offered while one stands.
        compose.onNodeWithContentDescription("Group").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ungroup all").performClick()
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
        compose.onNodeWithContentDescription("Ungroup all").assertIsDisplayed()
        compose.onNodeWithContentDescription("Group +2").performClick()
        // The step is the group's, whichever speaker the phone is set to.
        compose.onNodeWithText("Group · Kitchen").assertIsDisplayed()
        compose.onNodeWithText("Bedroom").performClick()
        compose.onNodeWithText("This phone").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf(Triple("kitchen", listOf(phone.id), listOf("bedroom"))), asked)
    }

    @Test fun aSpeakerThatCannotLeadHasNoGroupAndNoUngroup() {
        show { PlayOnSheetContent(PlayOnUiState(PlayerSample.speakers, "kitchen"), onSelect = {}) }
        compose.onNodeWithContentDescription("Group").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ungroup all").assertDoesNotExist()
    }

    @Test fun ungroupIsAGlyphAloneBesideGroupOnTheHeadingsLine() {
        val state = PlayOnUiState(grouped, "kitchen", kitchen, kitchensGroup, grouped = true)
        show { PlayOnSheetContent(state, onSelect = {}) }
        val heading = compose.onNodeWithText("Play on").getUnclippedBoundsInRoot()
        val group = compose.onNodeWithContentDescription("Group +2").getUnclippedBoundsInRoot()
        val ungroup = compose.onNodeWithContentDescription("Ungroup all")
        ungroup.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val glyph = ungroup.getUnclippedBoundsInRoot()
        // One line: the heading, then Ungroup, then Group on the margin, the two at one height.
        assertTrue(group.middle in heading.top..heading.bottom)
        assertEquals(group.middle.value, glyph.middle.value, 0.5f)
        assertTrue(heading.left < glyph.left && glyph.right <= group.left)
        assertEquals(411f - 24f, group.right.value, 0.5f)
    }

    @Config(qualifiers = "de-w392dp-h850dp-xxhdpi")
    @Test
    fun inGermanOnAPhoneTheControlsStayOnTheHeadingsLine() {
        var members by mutableIntStateOf(0)
        show { PlayOnHeader(members, grouped = members != 0, onGrouping = {}, onUngroup = {}) }
        val heading = compose.onNodeWithText("Abspielen auf").getUnclippedBoundsInRoot()
        val alone = compose.onNodeWithContentDescription("Gruppieren").getUnclippedBoundsInRoot()
        assertTrue(alone.middle in heading.top..heading.bottom)
        assertEquals(392f - 24f, alone.right.value, 0.5f)
        for (others in 1..9) {
            members = others
            val group = compose.onNodeWithContentDescription("Gruppe +$others").getUnclippedBoundsInRoot()
            val glyph = compose.onNodeWithContentDescription("Gruppe auflösen").getUnclippedBoundsInRoot()
            assertTrue("+$others", group.middle in heading.top..heading.bottom)
            assertTrue("+$others", glyph.right <= group.left)
            assertEquals(392f - 24f, group.right.value, 0.5f)
        }
    }

    /** As high as Now Playing's speaker pill, Group and Ungroup stand under the heading where a phone is narrower. */
    @Config(qualifiers = "de-w360dp-h640dp-xxhdpi")
    @Test
    fun inGermanOnANarrowPhoneAGroupsControlsStandUnderTheHeading() {
        show { PlayOnHeader(9, grouped = true, onGrouping = {}, onUngroup = {}) }
        val heading = compose.onNodeWithText("Abspielen auf").getUnclippedBoundsInRoot()
        val group = compose.onNodeWithContentDescription("Gruppe +9").getUnclippedBoundsInRoot()
        val glyph = compose.onNodeWithContentDescription("Gruppe auflösen").getUnclippedBoundsInRoot()
        assertTrue(group.middle > heading.bottom)
        assertEquals(group.middle.value, glyph.middle.value, 0.5f)
        // Under the heading they start on the margin, as it does: Group first, Ungroup after it.
        assertEquals(24f, group.left.value, 0.5f)
        assertTrue(group.right <= glyph.left)
    }

    @Config(qualifiers = "de-w360dp-h640dp-xxhdpi")
    @Test
    fun whereTheLineIsTooShortTheControlsStandUnderTheHeadingGroupOnTheMargin() {
        RuntimeEnvironment.setFontScale(2f)
        show { PlayOnHeader(members = 2, grouped = true, onGrouping = {}, onUngroup = {}) }
        val heading = compose.onNodeWithText("Abspielen auf").getUnclippedBoundsInRoot()
        val group = compose.onNodeWithContentDescription("Gruppe +2").getUnclippedBoundsInRoot()
        val glyph = compose.onNodeWithContentDescription("Gruppe auflösen").getUnclippedBoundsInRoot()
        assertTrue(group.middle > heading.bottom)
        assertEquals(24f, group.left.value, 0.5f)
        assertTrue(glyph.left >= group.right)
        assertEquals(group.middle.value, glyph.middle.value, 0.5f)
    }

    private val DpRect.middle get() = (top + bottom) / 2
}
