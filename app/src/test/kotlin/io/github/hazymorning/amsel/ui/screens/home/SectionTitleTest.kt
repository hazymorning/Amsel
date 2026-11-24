package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.height
import io.github.hazymorning.amsel.sample.HomeSample
import io.github.hazymorning.amsel.ui.theme.AmselTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SectionTitleTest {
    @get:Rule val compose = createComposeRule()

    private val named = mutableListOf<String?>()

    private fun show(state: HomeUiState = HomeSample.editing) = compose.setContent {
        AmselTheme {
            HomeScreen(
                state,
                onOpen = {},
                onMore = {},
                onSeeAll = {},
                onOpenWebUi = {},
                editor = object : HomeEditor {
                    override fun setTitle(id: String, title: String?) {
                        named += title
                    }
                },
            )
        }
    }

    @Test fun aTitleAndItsRenameFieldTakeTheSameLinesInTheSamePlace() {
        val name = "Music for long Sunday afternoons in the garden"
        val sections = HomeSample.editing.sections
        show(HomeSample.editing.copy(sections = listOf(sections[1].copy(title = name)) + sections.drop(2)))
        val title = compose.onNode(title(name)).getUnclippedBoundsInRoot()
        val oneLine = compose.onNode(title("Smart playlists")).getUnclippedBoundsInRoot()
        assertTrue("the long name stands on one line", title.height > oneLine.height)
        val around = { listOf(control("What to show: Recently played"), control("Show as: Row"), more(name)) }
        val before = around()
        compose.onNode(title(name)).performClick()
        val field = compose.onNodeWithContentDescription("Section name")
        val typed = field.textLayout()
        assertEquals(2, typed.lineCount)
        // A title's touch target extends equally above its capitals and below its last baseline.
        val reach = (oneLine.height.value - CAPITALS) / 2
        val fieldTop = field.getUnclippedBoundsInRoot().top.value
        val density = compose.density.density
        assertEquals(title.top.value + reach + CAPITALS, fieldTop + typed.firstBaseline / density, A_PIXEL)
        assertEquals(title.bottom.value - reach, fieldTop + typed.lastBaseline / density, A_PIXEL)
        assertEquals(before, around())
    }

    @Test fun enterEndsANameAndIsNoPartOfIt() {
        show()
        compose.onNode(title("Pinned")).performClick()
        compose.onNodeWithContentDescription("Section name").performTextReplacement("Favourites\n")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Section name").assertDoesNotExist()
        assertEquals(listOf<String?>("Favourites"), named)
    }

    @Test fun doneEndsAName() {
        show()
        compose.onNode(title("Pinned")).performClick()
        compose.onNodeWithContentDescription("Section name").performTextReplacement("Favourites")
        compose.onNodeWithContentDescription("Section name").performImeAction()
        compose.waitForIdle()
        assertEquals(listOf<String?>("Favourites"), named)
    }

    @Test fun aTitleIsAHeadingInEditModeToo() {
        show()
        compose.onNode(title("Pinned")).assert(isHeading())
    }

    // The section's title node is the only one carrying TalkBack's move actions.
    private fun title(text: String) = hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)

    private fun control(description: String) =
        compose.onAllNodesWithContentDescription(description).onFirst().getUnclippedBoundsInRoot()

    private fun more(title: String) =
        compose.onNodeWithContentDescription("More options for $title").getUnclippedBoundsInRoot()

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }
}

private const val A_PIXEL = 0.34f

// titleLarge's cap height: 20.5 sp times the font's 0.713 cap ratio, in dp at font scale 1.
private const val CAPITALS = 20.5f * 0.713f
