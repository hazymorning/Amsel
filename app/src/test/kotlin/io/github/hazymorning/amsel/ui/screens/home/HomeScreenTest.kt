package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.sample.HomeSample
import io.github.hazymorning.amsel.ui.components.LocalDockInset
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Touch and TalkBack paths into, through and out of Home's edit mode. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    /** Records what the screen asked edit mode to do. */
    private class Recorder : HomeEditor {
        val calls = mutableListOf<String>()

        override fun startEditing() {
            calls += "start"
        }

        override fun doneEditing() {
            calls += "done"
        }

        override fun setOption(id: String, option: SectionOption) {
            calls += "option $id $option"
        }

        override fun setLayout(id: String, layout: SectionLayout) {
            calls += "layout $id $layout"
        }

        override fun move(id: String, index: Int) {
            calls += "move $id $index"
        }

        override fun arrangePins(order: List<String>) {
            calls += "arrange " + order.joinToString(" ") { it.removePrefix("library://") }
        }

        override fun setTitle(id: String, title: String?) {
            calls += "title $id $title"
        }

        override fun setTitled(id: String, titled: Boolean) {
            calls += "titled $id $titled"
        }

        override fun moveUp(id: String) {
            calls += "up $id"
        }

        override fun moveDown(id: String) {
            calls += "down $id"
        }

        override fun remove(id: String) {
            calls += "remove $id"
        }

        override fun add() {
            calls += "add"
        }
    }

    private fun show(state: HomeUiState, editor: HomeEditor) = compose.setContent {
        AmselTheme {
            HomeScreen(
                state,
                onOpen = {},
                onMore = {},
                onSeeAll = {},
                onOpenWebUi = {},
                editor = editor,
            )
        }
    }

    @Test fun longPressingASectionTitleStartsEditing() {
        val editor = Recorder()
        show(HomeSample.home, editor)
        compose.onNodeWithText("Smart playlists").performTouchInput { longClick() }
        assertEquals(listOf("start"), editor.calls)
    }

    @Test fun talkBackOffersEditHomeOnATitle() {
        val editor = Recorder()
        show(HomeSample.home, editor)
        compose.onNodeWithText("Smart playlists").performSemanticsAction(SemanticsActions.OnLongClick)
        assertEquals(listOf("start"), editor.calls)
    }

    @Test fun doneLeavesEditMode() {
        val editor = Recorder()
        show(HomeSample.editing, editor)
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf("done"), editor.calls)
    }

    @Test fun theFirstChipChoosesWhatASectionShows() {
        val editor = Recorder()
        show(HomeSample.editing, editor)
        compose.onAllNodesWithContentDescription("What to show: Recently played").onFirst().performClick()
        // Further down the sheet than the screen reaches: TalkBack's click, which needs no scrolling.
        compose.onNodeWithText("Random albums").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("option s2 RandomAlbums"), editor.calls)
    }

    @Test fun theSecondChipChoosesHowItShowsThem() {
        val editor = Recorder()
        show(HomeSample.editing, editor)
        compose.onAllNodesWithContentDescription("Show as: Row").onFirst().performClick()
        compose.onNodeWithText("Big covers").performClick()
        assertEquals(listOf("layout s2 Big"), editor.calls)
    }

    @Test fun theSectionsMoreRemovesIt() {
        val editor = Recorder()
        show(HomeSample.editing, editor)
        compose.onAllNodesWithContentDescription("More options for Recently played").onFirst().performClick()
        compose.onNodeWithText("Move up").assertDoesNotExist()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf("remove s2"), editor.calls)
    }

    @Test fun theSectionsMoreHidesItsTitleAndShowsItAgain() {
        val editor = Recorder()
        show(HomeSample.editing, editor)
        compose.onAllNodesWithContentDescription("More options for Recently played").onFirst().performClick()
        compose.onNodeWithText("Hide title").performClick()
        assertEquals(listOf("titled s2 false"), editor.calls)
    }

    @Test fun aSectionShowsItsTitleOnlyWhereItsTitled() {
        val speakers = HomeSample.speakers
        val state = mutableStateOf(speakers)
        compose.setContent { AmselTheme { HomeScreen(state.value, {}, {}, {}, {}, editor = Recorder()) } }
        compose.onNodeWithText("Speakers").assertDoesNotExist()
        compose.onNodeWithText("Recently played").assertIsDisplayed()
        state.value = speakers.copy(
            sections = speakers.sections.map { it.copy(titled = it.option == SectionOption.Speakers) },
        )
        compose.onNodeWithText("Speakers").assertIsDisplayed()
        compose.onNodeWithText("Recently played").assertDoesNotExist()
    }

    @Test fun withEveryTitleHiddenTheFirstKeepsItsOwnSoEditHomeStaysInReach() {
        val editor = Recorder()
        val home = HomeSample.home
        show(home.copy(sections = home.sections.map { it.copy(titled = false) }), editor)
        compose.onNodeWithText("Smart playlists").assertDoesNotExist()
        compose.onNodeWithText("Recently played").performTouchInput { longClick() }
        assertEquals(listOf("start"), editor.calls)
    }

    @Test fun talkBackMovesASectionFromItsTitle() {
        val editor = Recorder()
        show(HomeSample.editing, editor)
        compose.onNode(movable("Recently played")).act("Move up")
        compose.onNode(movable("Recently played")).act("Move down")
        assertEquals(listOf("up s2", "down s2"), editor.calls)
    }

    @Test fun holdingASectionAndMovingItCarriesItDown() {
        val editor = Recorder()
        show(HomeSample.editing, editor)
        val title = compose.onNode(movable("Pinned"))
        // The first move comes after the long press's time, which is when the press counts as held.
        title.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, 1f))
        }
        // A finger moves over many frames; the list lays out the swap between them.
        repeat(12) {
            title.performTouchInput { moveBy(Offset(0f, 120f)) }
            compose.waitForIdle()
        }
        title.performTouchInput { up() }
        compose.waitForIdle()
        assertTrue("no move in ${editor.calls}", editor.calls.single().startsWith("move s1 "))
    }

    @Test fun holdingATitleAndLettingGoNeitherMovesNorRenames() {
        val editor = Recorder()
        show(HomeSample.editing, editor)
        compose.onNode(movable("Pinned")).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Section name").assertDoesNotExist()
        assertEquals(emptyList<String>(), editor.calls)
    }

    @Test fun aTapOnATitleRenamesTheSectionOnce() {
        val editor = Recorder()
        show(HomeSample.editing, editor)
        compose.onNode(movable("Pinned")).performClick()
        compose.onNodeWithContentDescription("Section name").performTextReplacement("Favourites")
        compose.onNodeWithContentDescription("Section name").performImeAction()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Section name").assertDoesNotExist()
        assertEquals(listOf("title s1 Favourites"), editor.calls)
    }

    @Test fun holdingAPinAndMovingItPutsItFirst() {
        val editor = Recorder()
        show(HomeSample.editing, editor)
        compose.onNode(movable("Saltwater")).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { moveBy(Offset(-width / 10f - 5f, 0f)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("arrange album/3 playlist/1 radio/1 artist/2 album/1 album/2"), editor.calls)
    }

    @Test fun talkBackMovesAPin() {
        val editor = Recorder()
        show(HomeSample.editing, editor)
        compose.onNode(movable("Saltwater")).act("Move later")
        assertEquals(1, editor.calls.size)
        assertTrue(editor.calls.single().startsWith("arrange "))
    }

    @Test fun sectionsThatFoundNothingLeaveAWayBackToEditing() {
        val editor = Recorder()
        show(HomeSample.nothingShown, editor)
        compose.onNodeWithText("Add music in Music\u00A0Assistant").assertDoesNotExist()
        compose.onNodeWithText("Edit Home").performClick()
        assertEquals(listOf("start"), editor.calls)
    }

    @Test fun anEmptyLibrarySendsToMusicAssistant() {
        var opened = false
        compose.setContent {
            AmselTheme {
                HomeScreen(
                    HomeSample.empty,
                    onOpen = {},
                    onMore = {},
                    onSeeAll = {},
                    onOpenWebUi = { opened = true },
                )
            }
        }
        compose.onNodeWithText("Edit Home").assertDoesNotExist()
        compose.onNodeWithText("Open Music\u00A0Assistant").performClick()
        assertTrue(opened)
    }

    @Test fun anEmptyHomeOffersASection() {
        val editor = Recorder()
        show(HomeSample.noSections, editor)
        compose.onNodeWithText("Add section").performClick()
        assertEquals(listOf("start", "add"), editor.calls)
    }

    @Test fun addSectionStandsClearOfTheDock() {
        val dock = 180.dp
        val inset = mutableStateOf(dock)
        compose.setContent {
            AmselTheme {
                CompositionLocalProvider(LocalDockInset provides inset) {
                    HomeScreen(
                        HomeSample.editing,
                        onOpen = {},
                        onMore = {},
                        onSeeAll = {},
                        onOpenWebUi = {},
                        editor = Recorder(),
                    )
                }
            }
        }
        val list = compose.onAllNodes(hasScrollAction()).onFirst()
        list.performScrollToNode(hasText("Add section"))
        // To the list's end: scrolling to the button only brings it into the list, dock or not.
        repeat(3) { list.performTouchInput { swipeUp() } }
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val button = compose.onNodeWithText("Add section").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(
            "Add section ends at ${button.bottom}, the dock starts at ${screen.bottom - dock}",
            button.bottom <= screen.bottom - dock,
        )
    }

    /** TalkBack's action named [label] on this node. */
    private fun SemanticsNodeInteraction.act(label: String) {
        val actions = fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { actions.first { it.label == label }.action() }
    }

    /** What a held press moves: it alone carries TalkBack's moves. */
    private fun movable(text: String) = hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)
}
