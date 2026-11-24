package io.github.hazymorning.amsel.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import io.github.hazymorning.amsel.sample.SampleData
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.navigation.BarItem
import io.github.hazymorning.amsel.ui.navigation.DeepLink
import io.github.hazymorning.amsel.ui.navigation.Route
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
class AppShellTest {
    @get:Rule val compose = createComposeRule()

    private var speakerTaps = 0
    private var groupTaps = 0
    private val controls = mutableListOf<PlayerControl>()
    private val opening = mutableStateOf<DeepLink?>(null)
    private var opened = 0

    private val bar = mutableStateOf(BarItem.Default)

    private fun shell(nowPlaying: NowPlayingUi? = null) = shell(mutableStateOf(nowPlaying))

    private fun shell(nowPlaying: State<NowPlayingUi?>) = compose.setContent { Shell(nowPlaying) }

    @Composable
    private fun Shell(nowPlaying: State<NowPlayingUi?>) {
        AmselTheme {
            AppShell(
                nowPlaying = nowPlaying.value,
                speaker = nowPlaying.value?.speaker ?: SampleData.speakers[0],
                onSpeaker = { speakerTaps++ },
                onGroup = { groupTaps++ },
                onControl = { controls += it },
                opening = opening.value,
                bar = bar.value,
                onOpened = {
                    opened++
                    opening.value = null
                },
                slots = ShellSlots(
                    screen = { route, navigator ->
                        Column {
                            var marks by rememberSaveable { mutableIntStateOf(0) }
                            Text("page $route")
                            TextButton(onClick = { marks++ }) { Text("mark, $marks so far") }
                            if (route == Route.Search) Text("new search ${LocalNewSearch.current}")
                            TextButton(onClick = { navigator.open(Route.Album("a1")) }) { Text("open album") }
                            TextButton(onClick = { navigator.open(Route.Artist("a")) }) { Text("open artist a") }
                            TextButton(onClick = { navigator.open(Route.Artist("b")) }) { Text("open artist b") }
                            TextButton(onClick = { navigator.back() }) { Text("back") }
                        }
                    },
                    nowPlaying = { _, _ ->
                        // As Now Playing does: a layer of its own for Up next, which the answer opens in full.
                        val upNext = rememberPlayerLayerState()
                        AnswerUpNext { upNext.open() }
                        Column {
                            Text("now playing layer")
                            if (upNext.fraction == 1f) Text("up next open")
                        }
                    },
                    sheets = {},
                ),
            )
        }
    }

    @Test fun theBarShowsThePlacesChosenAndALongPressArrangesThem() {
        bar.value = listOf(BarItem.Library, BarItem.Home)
        shell()
        compose.onNodeWithContentDescription("Search").assertDoesNotExist()
        compose.onNodeWithContentDescription("Speaker: This phone. Choose speaker").assertDoesNotExist()
        val library = compose.onNodeWithContentDescription("Library").getUnclippedBoundsInRoot()
        val home = compose.onNodeWithContentDescription("Home").getUnclippedBoundsInRoot()
        assertTrue(library.left < home.left)
        compose.onNodeWithContentDescription("Home").performTouchInput { longClick() }
        compose.onNodeWithText("page Bar").assertExists()
    }

    @Test fun startsOnHome() {
        shell()
        compose.onNodeWithText("page Home").assertExists()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
    }

    @Test fun aSpeakerCanBeChosenBeforeAnythingPlays() {
        shell()
        compose.onNodeWithContentDescription("Speaker: This phone. Choose speaker").performClick()
        assertEquals(1, speakerTaps)
        compose.onNodeWithContentDescription("Home").assertIsSelected()
    }

    @Test fun theMiniPlayerOpensNowPlaying() {
        shell(SampleData.nowPlaying)
        compose.onNodeWithText("now playing layer").assertDoesNotExist()
        compose.onNodeWithText(SampleData.nowPlaying.title, substring = true, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("now playing layer").assertExists()
    }

    @Test fun aSwipeUpAnywhereOnTheDockOpensNowPlaying() {
        shell(SampleData.nowPlaying)
        // From a tab, well under the player row: the whole dock is the handle.
        compose.onNodeWithContentDescription("Search").performTouchInput {
            swipeUp(
                startY = centerY,
                endY =
                    centerY - 400f,
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("now playing layer").assertExists()
        compose.onNodeWithContentDescription("Home").assertIsSelected()
    }

    @Test fun aSidewaysSwipeOnTheMiniPlayerIsNextAndPrevious() {
        shell(SampleData.nowPlaying)
        val words = compose.onNodeWithText(SampleData.nowPlaying.title, substring = true, useUnmergedTree = true)
        words.performTouchInput { swipeLeft(startX = centerX + 300f, endX = centerX - 300f) }
        words.performTouchInput { swipeRight(startX = centerX - 300f, endX = centerX + 300f) }
        assertEquals(listOf(PlayerControl.Next, PlayerControl.Previous), controls)
        compose.onNodeWithText("now playing layer").assertDoesNotExist()
    }

    @Test fun theMiniPlayerNamesTheSpeakerAndThenWhatPlays() {
        shell(SampleData.nowPlaying)
        val playing = SampleData.nowPlaying
        val card = compose.onNode(hasText(playing.speaker.name) and hasText(playing.title, substring = true))
        val words = card.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        assertEquals(listOf(playing.speaker.name, "${playing.title} · ${playing.artist}"), words)
    }

    @Test fun theMiniPlayerPlaysPausesAndSkips() {
        shell(SampleData.nowPlaying)
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Next").performClick()
        assertEquals(listOf(PlayerControl.PlayPause, PlayerControl.Next), controls)
        compose.onNodeWithText("now playing layer").assertDoesNotExist()
    }

    @Test fun theMiniPlayersSpeakerButtonChoosesASpeaker() {
        shell(SampleData.nowPlaying)
        val speaker = SampleData.nowPlaying.speaker.name
        compose.onNodeWithContentDescription("Speaker: $speaker, playing. Choose speaker").performClick()
        assertEquals(1, speakerTaps)
    }

    @Test fun aTabOpensAtItsTopAfterAnotherTabWasShown() {
        shell()
        compose.onNodeWithContentDescription("Library").performClick()
        compose.onNodeWithText("open album").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("page Album(key=a1)").assertExists()

        compose.onNodeWithContentDescription("Home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("page Home").assertExists()

        compose.onNodeWithContentDescription("Library").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("page Album(key=a1)").assertDoesNotExist()
        compose.onNodeWithText("page Library").assertExists()
    }

    @Test fun aPlaceTakenOutOfTheBarGivesWayToHomeAtItsTop() {
        shell()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("open album").performClick()
        compose.waitForIdle()
        bar.value = BarItem.Default - BarItem.Settings
        compose.waitForIdle()
        compose.onNodeWithText("page Album(key=a1)").assertExists()
        compose.onNodeWithText("back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("page Home").assertExists()
    }

    @Test fun searchOpensOutsideTheBarAndStays() {
        bar.value = BarItem.Default - BarItem.Search
        shell()
        opening.value = DeepLink.Search
        compose.waitForIdle()
        compose.onNodeWithText("page Search").assertExists()
    }

    @Test fun reselectingATabReturnsToItsTop() {
        shell()
        compose.onNodeWithText("open album").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("page Album(key=a1)").assertExists()

        compose.onNodeWithContentDescription("Home").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("page Home").assertExists()
        compose.onNodeWithText("page Album(key=a1)").assertDoesNotExist()
    }

    @Test fun anArtistOpenedAgainFromAnotherIsItsOwnStepBack() {
        shell()
        for (artist in listOf("a", "b", "a")) {
            compose.onNodeWithText("open artist $artist").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("page Artist(key=$artist)").assertExists()
        }
        for (page in listOf("Artist(key=b)", "Artist(key=a)", "Home")) {
            compose.onNodeWithText("back").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("page $page").assertExists()
        }
    }

    @Test fun pagesOpenedAgainComeBackAfterTheAppWasPutAway() {
        val restoring = StateRestorationTester(compose)
        val nothing = mutableStateOf<NowPlayingUi?>(null)
        restoring.setContent { Shell(nothing) }
        for (artist in listOf("a", "b", "a")) compose.onNodeWithText("open artist $artist").performClick()
        compose.onNodeWithText("mark, 0 so far").performClick()
        restoring.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("page Artist(key=a)").assertExists()
        compose.onNodeWithText("mark, 1 so far").assertExists()
        compose.onNodeWithText("back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("page Artist(key=b)").assertExists()
    }

    @Test fun anArtistOpenedAgainIsAPageOfItsOwnAndTheFirstKeepsItsPlace() {
        shell()
        compose.onNodeWithText("open artist a").performClick()
        compose.onNodeWithText("mark, 0 so far").performClick()
        compose.onNodeWithText("open artist b").performClick()
        compose.onNodeWithText("open artist a").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("page Artist(key=a)").assertExists()
        compose.onNodeWithText("mark, 0 so far").assertExists()
        compose.onNodeWithText("back").performClick()
        compose.onNodeWithText("back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("page Artist(key=a)").assertExists()
        compose.onNodeWithText("mark, 1 so far").assertExists()
    }

    @Test fun aSearchLinkOpensANewSearchOverAnyTabOrPage() {
        shell(SampleData.nowPlaying)
        compose.onNodeWithText(SampleData.nowPlaying.title, substring = true, useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("now playing layer").assertExists()
        opening.value = DeepLink.Search
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Search").assertIsSelected()
        compose.onNodeWithText("new search 1").assertExists()
        compose.onNodeWithText("now playing layer").assertDoesNotExist()
        assertEquals(1, opened)
        compose.onNodeWithText("open album").performClick()
        compose.waitForIdle()
        opening.value = DeepLink.Search
        compose.waitForIdle()
        compose.onNodeWithText("new search 2").assertExists()
        assertEquals(2, opened)
    }

    @Test fun aNowPlayingLinkWaitsForTheSpeakerToSayWhatPlays() {
        val playing = mutableStateOf<NowPlayingUi?>(null)
        shell(playing)
        opening.value = DeepLink.NowPlaying
        compose.waitForIdle()
        assertEquals(0, opened)
        playing.value = SampleData.nowPlaying
        compose.waitForIdle()
        compose.onNodeWithText("now playing layer").assertExists()
        assertEquals(1, opened)
    }

    @Test fun aNowPlayingLinkWithNothingPlayingLeavesHomeAsItIs() {
        val playing = mutableStateOf<NowPlayingUi?>(null)
        shell(playing)
        opening.value = DeepLink.NowPlaying
        compose.waitForIdle()
        assertEquals(0, opened)
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        assertEquals(1, opened)
        playing.value = SampleData.nowPlaying
        compose.waitForIdle()
        compose.onNodeWithText("now playing layer").assertDoesNotExist()
        compose.onNodeWithText("page Home").assertExists()
    }

    @Test fun anUpNextLinkOpensNowPlayingAndAsksForUpNext() {
        val playing = mutableStateOf<NowPlayingUi?>(null)
        shell(playing)
        opening.value = DeepLink.UpNext
        compose.waitForIdle()
        compose.onNodeWithText("up next open").assertDoesNotExist()
        playing.value = SampleData.nowPlaying
        compose.waitForIdle()
        compose.onNodeWithText("now playing layer").assertExists()
        compose.onNodeWithText("up next open").assertExists()
        assertEquals(1, opened)
    }

    @Test fun aGroupLinkOpensThePickerToGroup() {
        shell()
        opening.value = DeepLink.Group
        compose.waitForIdle()
        assertEquals(1, groupTaps)
        assertEquals(0, speakerTaps)
        assertEquals(1, opened)
    }

    @Test fun aSpeakersLinkOpensThePicker() {
        shell()
        opening.value = DeepLink.Speakers
        compose.waitForIdle()
        assertEquals(1, speakerTaps)
        assertEquals(1, opened)
    }
}
