package io.github.hazymorning.amsel.journey

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.hazymorning.amsel.AmselApp
import io.github.hazymorning.amsel.AppGraph
import io.github.hazymorning.amsel.MainActivity
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.albumTracks
import io.github.hazymorning.amsel.api.endlessMixOf
import io.github.hazymorning.amsel.api.libraryItems
import io.github.hazymorning.amsel.data.TokenVault
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.PlaybackService
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The app with an in-memory token vault: Robolectric has no Android Keystore. */
class JourneyApp : AmselApp() {
    // Robolectric runs no App Startup provider, so the process lifecycle never tells the session
    // that the app is in front; the activity under test is.
    override fun onCreate() {
        super.onCreate()
        graph.data.session.setForeground(true)
    }

    override fun createGraph(): AppGraph = AppGraph(
        this,
        object : TokenVault {
            override fun seal(token: String) = token

            override fun open(sealed: String) = sealed
        },
    )
}

/**
 * The household test's first tasks, driven through the real app against a real Music Assistant
 * (scripts/test-server with the fake Kitchen speaker): set the app up by typed address, sign in,
 * open Library, open an album, play it on Kitchen, move within the song while it plays and while
 * it rests, take it along to Living Room, start an endless mix of the album from its menu, and
 * open the songs similar to one of its songs.
 * Skipped unless MA_URL is set.
 */
@RunWith(RobolectricTestRunner::class)
// Legacy graphics by default: on GitHub's JDK the native graphics runtime aborts when this test
// runs alone (JniConstants: Class not found: java/nio/FloatBuffer). Set AMSEL_JOURNEY_SHOTS=1
// (with -Drobolectric.graphicsMode=NATIVE) to record the step screenshots locally.
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [36], application = JourneyApp::class, qualifiers = "w411dp-h891dp-xxhdpi")
class FirstRunJourneyTest {
    val compose = createAndroidComposeRule<MainActivity>()

    /** Robolectric binds services only when told how: hand it the real PlaybackService's binder. */
    private val playbackService = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val intent = Intent(MediaSessionService.SERVICE_INTERFACE)
            val service = Robolectric.buildService(PlaybackService::class.java, intent).create().get()
            shadowOf(
                app,
            ).setComponentNameAndServiceForBindService(
                ComponentName(app, PlaybackService::class.java),
                service.onBind(intent),
            )
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(playbackService).around(compose)

    private val url: String? = System.getenv("MA_URL")

    /** Pictures of the real journey for review (not compared): app/build/outputs/journey/. */
    private fun shot(name: String) {
        if (System.getenv("AMSEL_JOURNEY_SHOTS") != "1") return
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/journey/$name.png")
    }

    /** Words on screen, or a glyph's label: Shuffle and Play are glyphs on a page, the speaker one in the dock. */
    private fun waitFor(text: String, timeoutMs: Long = 20_000) = compose.waitUntil(timeoutMs) {
        compose.onAllNodes(hasText(text, substring = true) or hasContentDescription(text, substring = true))
            .fetchSemanticsNodes()
            .isNotEmpty()
    }

    /** Until [done], with the main thread's work run meanwhile: taps reach the session through it. */
    private fun await(timeoutMs: Long = 10_000, done: () -> Boolean) = compose.waitUntil(timeoutMs) {
        compose.waitForIdle()
        done()
    }

    @Test fun `set up, sign in, play an album on Kitchen, move within it and take it along`() {
        assumeTrue("MA_URL not set", url != null)

        // 1. Set the app up.
        waitFor("Find my server")
        shot("01_welcome")
        compose.onNodeWithText("Find my server").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Type an address").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithText("Type an address").performClick()
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(url!!.removePrefix("http://"))
        shot("02_server_address")
        compose.onNodeWithText("Connect").performClick()

        waitFor("Username")
        compose.onNode(hasSetTextAction() and hasText("Username")).performTextInput("amsel")
        compose.onNode(hasSetTextAction() and hasText("Password")).performTextInput("amsel-test-pw")
        shot("03_sign_in")
        compose.onAllNodesWithText("Sign in").fetchSemanticsNodes()
        compose.onNode(
            hasText("Sign in") and !hasSetTextAction() and androidx.compose.ui.test.hasClickAction(),
        ).performClick()

        // Home says its first tip above the sections; closed, it is gone for good.
        waitFor("Long-press a heading", 30_000)
        compose.onNodeWithContentDescription("Dismiss tip").performClick()

        // Home, with the server's library.
        waitFor("New in your library", 30_000)
        compose.onNodeWithContentDescription("Dismiss tip").assertDoesNotExist()
        Thread.sleep(1500)
        shot("04_home")

        // Pick Kitchen as the speaker (the Play on sheet is covered by its own tests).
        val graph = compose.activity.graph
        val kitchen = runBlocking { graph.data.session.api.players().first { it.shownName == "Kitchen" } }
        runBlocking { graph.data.prefs.setSpeaker(kitchen.playerId) }

        // 2. Find an album and play it.
        compose.onNodeWithContentDescription("Library").performClick()
        waitFor("Albums")
        compose.onNodeWithText("Albums").performClick()
        waitFor("First Light", 30_000)
        shot("05_library_albums")
        compose.onAllNodesWithText("First Light").onFirst().performClick()
        waitFor("Shuffle")
        Thread.sleep(1000)
        shot("06_album")
        // The page's play, over the dock's own: the speaker may hold a song from before.
        val plays = compose.onAllNodes(hasContentDescription("Play") and androidx.compose.ui.test.hasClickAction())
        val page = plays.fetchSemanticsNodes().withIndex().minBy { it.value.boundsInRoot.top }.index
        plays[page].performClick()

        // The mini player shows the music on Kitchen (its card names the speaker whatever plays, so
        // the pause is what says it), and the server agrees.
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasContentDescription("Pause")).fetchSemanticsNodes().isNotEmpty()
        }
        waitFor("Kitchen")
        Thread.sleep(2000)
        shot("07_playing_on_kitchen")
        fun server() = runBlocking { graph.data.session.api.queues().first { it.queueId == kitchen.playerId } }
        compose.waitUntil(30_000) { server().state == "playing" }

        // 3. Move within the song, as the line under the words does: the place shows at once,
        // standing, the speaker goes there, and the server's old time on top of it never shows.
        val shown = CopyOnWriteArrayList<NowPlayingUi>()
        val watching = graph.scope.launch { graph.playing.nowPlaying.collect { it?.let(shown::add) } }
        val song = server().currentItem?.queueItemId
        val sought = System.nanoTime()
        compose.runOnUiThread { graph.playback.control(PlayerControl.Seek(4_000)) }
        await { shown.any { it.arriving && it.positionMs == 4_000L } }
        await(20_000) { graph.playing.nowPlaying.value?.arriving == false }
        shot("08_sought")
        val passedMs = (System.nanoTime() - sought) / 1_000_000
        // The songs are seconds long: a server slow to restart the stream is in the next one by
        // now, whose own time is right. What is checked is the song sought in.
        val from = shown.indexOfFirst { it.arriving && it.positionMs == 4_000L }
        val after = shown.drop(from).takeWhile { it.key == shown[from].key }.map { it.positionMs }
        assertTrue("shown after the seek: $after", after.all { it in 4_000..4_500 + passedMs })
        val now = server()
        assertTrue(
            "the server at ${now.elapsedTime}",
            now.currentItem?.queueItemId != song || now.elapsedTime in 3.5..(6.5 + passedMs / 1000.0),
        )

        // 4. Pause, move again: the speaker stays paused and is sent nothing; play starts there.
        await(20_000) { server().let { it.state == "playing" && it.elapsedTime >= 2.0 } }
        compose.runOnUiThread { graph.playback.control(PlayerControl.PlayPause) }
        await(20_000) { server().state != "playing" }
        val rested = server().elapsedTime
        compose.runOnUiThread { graph.playback.control(PlayerControl.Seek(1_000)) }
        await { graph.playing.nowPlaying.value?.let { it.positionMs == 1_000L && !it.isPlaying } == true }
        Thread.sleep(1_500)
        compose.waitForIdle()
        val resting = server()
        assertTrue(
            "the paused speaker was sent a seek: $resting",
            resting.state != "playing" && resting.elapsedTime == rested,
        )
        compose.runOnUiThread { graph.playback.control(PlayerControl.PlayPause) }
        await(20_000) { server().let { it.state == "playing" && it.elapsedTime in 0.5..(rested - 0.5) } }
        watching.cancel()

        // 5. Choose another speaker from the dock: music that plays in a room is asked about, then
        // goes along, and the room it left holds nothing.
        compose.onNodeWithContentDescription("Speaker: Kitchen, playing. Choose speaker").performClick()
        waitFor("Play on")
        compose.onNodeWithText("Living Room").performClick()
        waitFor("Take it along")
        shot("09_take_along")
        compose.onNodeWithText("Take it along").performClick()
        val api = graph.data.session.api
        val living = runBlocking { api.players().first { it.shownName == "Living Room" } }
        await(30_000) {
            val queues = runBlocking { api.queues() }
            val there = queues.first { it.queueId == living.playerId }
            there.state == "playing" && there.currentItem != null && server().items == 0
        }
        await { graph.playing.speaker.value.id == living.playerId }
        waitFor("Speaker: Living Room, playing. Choose speaker")
        shot("10_on_living_room")

        // 6. An endless mix of the album, from the menu on its page: the server makes the mix in
        // place of what played, and the app names it.
        await { graph.data.extras.offered.value.mix }
        compose.onNodeWithContentDescription("More options for First Light").performClick()
        waitFor("Endless mix")
        compose.onNodeWithText("Endless mix").performClick()
        await(30_000) {
            val there = runBlocking { api.queues() }.first { it.queueId == living.playerId }
            there.isDynamic && there.endlessMixOf == "First Light" && there.state == "playing"
        }
        await(20_000) { graph.playing.nowPlaying.value?.mix == "First Light" }
        shot("11_endless_mix")

        // 7. While the mix plays, the album's menu leaves out what the server would only take as
        // one more source of it.
        compose.onNodeWithContentDescription("More options for First Light").performClick()
        waitFor("Endless mix")
        compose.onNodeWithText("Add to Up next").assertDoesNotExist()
        compose.onNodeWithText("Play next").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).onFirst()
            .performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Endless mix").fetchSemanticsNodes().isEmpty() }

        // 8. A song's menu leads to the songs like it: a page of its own, named for the song.
        val firstSong = runBlocking {
            val album = api.libraryItems(MediaType.Album).first { it.name == "First Light" }
            api.albumTracks(album.itemId, album.provider).first().name
        }
        val held = SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
        compose.onAllNodes(hasText(firstSong) and held).onFirst().performSemanticsAction(SemanticsActions.OnLongClick)
        waitFor("Similar songs")
        compose.onNodeWithText("Play next").assertExists()
        compose.onNodeWithText("Similar songs").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasText("Similar to", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        shot("12_similar_songs")
        runBlocking { api.clearQueue(living.playerId) }
    }
}
