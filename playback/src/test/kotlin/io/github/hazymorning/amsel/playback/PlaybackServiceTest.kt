package io.github.hazymorning.amsel.playback

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MediaItemImage
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/** Artwork never loads in these tests. */
@OptIn(UnstableApi::class)
private object NoArtwork : BitmapLoader {
    override fun supportsMimeType(mimeType: String) = false

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        Futures.immediateFailedFuture(UnsupportedOperationException())

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        Futures.immediateFailedFuture(UnsupportedOperationException())
}

/** A library with nothing in it. */
internal object Catalogless : Catalog {
    override suspend fun recent(limit: Int) = emptyList<BrowseNode>()

    override suspend fun inProgress(limit: Int) = emptyList<BrowseNode>()

    override suspend fun holds(tab: String) = false

    override suspend fun library(tab: String, offset: Int, limit: Int) = emptyList<BrowseNode>()

    override suspend fun children(parentUri: String, offset: Int, limit: Int) = emptyList<BrowseNode>()

    override suspend fun search(query: String, focus: String?): String? = null
}

/**
 * The service with a fake graph: Kitchen plays, the Office is paused, This phone is idle. Each
 * speaker's snapshot is fixed, as the real graph's cached flows are.
 */
private object TestGraph : PlaybackGraph {
    override val elapsedRealtime: () -> Long = { 1_000L }
    override val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val speakers = mapOf(
        "kitchen" to snapshot("kitchen", playing = true),
        "office" to snapshot("office", playing = false),
        "phone" to snapshot("phone", playing = false),
        "study" to MutableStateFlow<SpeakerSnapshot?>(
            SpeakerSnapshot(
                name = "study",
                available = true,
                playing = true,
                current = QueueEntry(
                    "study-book",
                    "library://audiobook/2",
                    "Book",
                    "Author",
                    null,
                    270,
                    null,
                    mediaType = "audiobook",
                ),
                canSeek = true,
            ),
        ),
    )

    val targetFlow = MutableStateFlow(Target("kitchen", isPhone = false))
    val playing = mutableListOf<Boolean>()
    val heartFlow = MutableStateFlow<Boolean?>(null)
    var hearts = 0

    /** Runs once inside the next playerFor: a change that lands while the session is built. */
    var whileBuilding: (() -> Unit)? = null

    fun reset() {
        targetFlow.value = Target("kitchen", isPhone = false)
        playing.clear()
        whileBuilding = null
        heartFlow.value = null
        hearts = 0
    }

    override val heart: StateFlow<Boolean?> get() = heartFlow

    override fun toggleHeart() {
        hearts++
    }

    override val target: StateFlow<Target> get() = targetFlow
    override val phone: LocalOutput? = null
    override val commands: SpeakerCommands =
        FakeSpeaker(requireNotNull(speakers.getValue("kitchen").value), emptyList(), elapsedRealtime)
    override val catalog: Catalog = Catalogless
    override val artwork: BitmapLoader = NoArtwork
    override val failures: Flow<CommandFailure> = emptyFlow()

    override fun onCommandFailed(failure: CommandFailure) = Unit

    override suspend fun blocked(): Blocked? = null

    override suspend fun voiceUri(query: String?, focus: String?): String? = null

    override fun snapshots(playerId: String): StateFlow<SpeakerSnapshot?> = speakers.getValue(playerId)

    override fun speakerName(playerId: String): String = speakers[playerId]?.value?.name ?: playerId

    override fun onPlayingChanged(playing: Boolean) {
        this.playing += playing
    }

    override suspend fun selectPhone() {
        targetFlow.value = Target("phone", isPhone = true)
    }

    override fun playerFor(target: Target): Player {
        whileBuilding?.invoke()
        whileBuilding = null
        return super.playerFor(target)
    }

    private fun snapshot(id: String, playing: Boolean) = MutableStateFlow<SpeakerSnapshot?>(
        SpeakerSnapshot(
            name = id,
            available = true,
            playing = playing,
            current = QueueEntry("$id-song", "library://track/1", "Song", "Artist", "Album", 200, null),
        ),
    )
}

@RunWith(RobolectricTestRunner::class)
class PlaybackServiceTest {
    private val app = RuntimeEnvironment.getApplication()
    private val launcher = ComponentName(app, "io.github.hazymorning.amsel.MainActivity")
    private lateinit var service: ServiceController<PlaybackService>

    private val session: MediaLibrarySession get() = requireNotNull(PlaybackService.current)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun setUp() {
        PlaybackGraph.provider = { TestGraph }
        TestGraph.reset()
        shadowOf(app.packageManager).addActivityIfNotPresent(launcher)
        shadowOf(app.packageManager).addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun start() {
        service = Robolectric.buildService(PlaybackService::class.java).create()
        idle()
    }

    private var destroyed = false

    @After fun tearDown() {
        if (!destroyed) service.destroy()
    }

    @Test fun tappingTheNotificationOpensAmsel() {
        start()
        val activity = shadowOf(session.sessionActivity).savedIntent
        assertEquals(launcher, activity.component)
    }

    @Test fun theConnectionHearsWhatTheNewSpeakerIsDoingOnEverySwap() {
        start()
        assertEquals(listOf(true), TestGraph.playing) // Kitchen already plays when the service starts
        TestGraph.targetFlow.value = Target("office", isPhone = false)
        idle()
        // Back to Kitchen: its snapshot is cached and already playing, so no isPlaying change fires.
        TestGraph.targetFlow.value = Target("kitchen", isPhone = false)
        idle()
        assertEquals(listOf(true, false, true), TestGraph.playing)
    }

    @Test fun aBookShowsNowPlayingsFiveControlsAndMusicTakesTheSkipsAway() {
        start()
        assertTrue(session.mediaButtonPreferences.isEmpty())
        TestGraph.targetFlow.value = Target("study", isPhone = false)
        idle()
        val buttons = session.mediaButtonPreferences
        assertEquals(
            listOf(
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_FORWARD,
            ),
            buttons.map { it.playerCommand },
        )
        assertEquals(
            listOf(
                CommandButton.SLOT_BACK_SECONDARY,
                CommandButton.SLOT_BACK,
                CommandButton.SLOT_FORWARD,
                CommandButton.SLOT_FORWARD_SECONDARY,
            ),
            buttons.map { it.slots.get(0) },
        )
        TestGraph.targetFlow.value = Target("kitchen", isPhone = false)
        idle()
        assertTrue(session.mediaButtonPreferences.isEmpty())
    }

    @Test fun theNotificationShowsNowPlayingsHeartAndFollowsIt() {
        start()
        assertTrue(session.mediaButtonPreferences.isEmpty())
        TestGraph.heartFlow.value = false
        idle()
        val heart = session.mediaButtonPreferences.single()
        assertEquals(BrowseTree.HEART, heart.sessionCommand)
        assertEquals(CommandButton.ICON_HEART_UNFILLED, heart.icon)
        assertEquals("Like", heart.displayName)
        TestGraph.heartFlow.value = true
        idle()
        assertEquals(CommandButton.ICON_HEART_FILLED, session.mediaButtonPreferences.single().icon)
        assertEquals("Unlike", session.mediaButtonPreferences.single().displayName)
        // A book has no heart, even while one is reported: its controls take the places.
        TestGraph.targetFlow.value = Target("study", isPhone = false)
        idle()
        assertTrue(session.mediaButtonPreferences.none { it.sessionCommand == BrowseTree.HEART })
        assertEquals(4, session.mediaButtonPreferences.size)
    }

    @Test fun whenTheServiceEndsTheConnectionNoLongerCountsOnItsPlaying() {
        start()
        assertEquals(listOf(true), TestGraph.playing) // Kitchen plays
        service.destroy()
        destroyed = true
        assertEquals(listOf(true, false), TestGraph.playing)
    }

    @Test fun aSpeakerPickedWhileTheSessionIsBuiltIsNotLost() {
        TestGraph.whileBuilding = { TestGraph.targetFlow.value = Target("office", isPhone = false) }
        start()
        assertEquals("office-song", session.player.currentMediaItem?.mediaId)
    }

    @Test fun aPlayFromTheCarFirstMovesTheSessionToThisPhone() {
        start()
        val done = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            service.get().holdPhone()
            done.complete(Unit)
        }
        idle()
        assertTrue(done.isCompleted)
        assertEquals("phone-song", session.player.currentMediaItem?.mediaId)
        scope.cancel()
    }
}

/** The car's tree: its plays go to This phone, other controllers' to the selected speaker. */
@RunWith(RobolectricTestRunner::class)
class BrowseTreeTest {
    private val app = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val items = mutableListOf(MediaItem.Builder().setMediaId("library://album/7").build())
    private val speaker = FakeSpeaker(
        SpeakerSnapshot("Kitchen", available = true, playing = false),
        emptyList(),
    ) { 0L }
    private val player =
        RemotePlayer("kitchen", speaker.snapshots, speaker, {
            0L
        }, local = null, onCommandFailed = {}, appScope = scope)
    private var session: MediaLibrarySession? = null

    private fun tree(
        catalog: Catalog,
        beforeCarPlay: suspend () -> Boolean = { true },
        blocked: suspend () -> Blocked? = { null },
        voiceUri: suspend (String?, String?) -> String? = { _, _ -> null },
        onHeart: () -> Unit = {},
    ) = BrowseTree(
        catalog,
        scope,
        app::getString,
        "Amsel",
        CarArtwork(app),
        Cars(app.packageManager),
        beforeCarPlay,
        blocked,
        voiceUri,
        openApp = null,
        onHeart = onHeart,
    )

    private fun sessionFor(tree: BrowseTree) = MediaLibrarySession.Builder(app, player, tree).build().also {
        session =
            it
    }

    private fun controller(packageName: String) = controllerOf(packageName, AUTO_UID)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Before fun installAndroidAuto() {
        // As Android 10 and later bring it.
        installApp(ANDROID_AUTO, AUTO_UID, system = true)
    }

    @After fun tearDown() {
        session?.release()
        player.release()
        scope.cancel()
    }

    @Test fun aPlayFromAndroidAutoWaitsForThisPhone() {
        val phoneHeld = CompletableDeferred<Unit>()
        var asked = 0
        val tree = tree(Catalogless, beforeCarPlay = {
            asked++
            phoneHeld.await()
            true
        })
        val result = tree.onSetMediaItems(sessionFor(tree), controller(ANDROID_AUTO), items, 0, 0)
        idle()
        assertEquals(1, asked)
        assertFalse(result.isDone) // the items must not reach the speaker's player yet
        phoneHeld.complete(Unit)
        idle()
        assertEquals(items, result.get().mediaItems)
    }

    @Test fun aPlayFromAndroidAutoThePhoneCannotTakeGoesNowhere() {
        val tree = tree(Catalogless, beforeCarPlay = { false })
        val result = tree.onSetMediaItems(sessionFor(tree), controller(ANDROID_AUTO), items, 0, 0)
        idle()
        assertTrue(result.isDone)
        assertThrows(ExecutionException::class.java) { result.get() }
    }

    @Test fun aPlayFromAnythingElseGoesToTheSelectedSpeaker() {
        var asked = 0
        val tree = tree(Catalogless, beforeCarPlay = {
            asked++
            true
        })
        val result = tree.onSetMediaItems(sessionFor(tree), controller(app.packageName), items, 0, 0)
        idle()
        assertEquals(0, asked)
        assertEquals(items, result.get().mediaItems)
    }

    private fun BrowseTree.children(parentId: String): List<MediaItem> {
        val result = onGetChildren(requireNotNull(session), controller(ANDROID_AUTO), parentId, 0, 10, null)
        idle()
        return result.get().value.orEmpty()
    }

    private fun List<MediaItem>.titles() = map { it.mediaMetadata.title.toString() }

    @Test fun theRootAnswersBeforeTheSessionIsKnown() {
        // A legacy browser (the car) blocks the main thread until the root is there.
        val tree =
            tree(Catalogless, blocked = { awaitCancellation() })
        sessionFor(tree)
        assertTrue(tree.onGetLibraryRoot(requireNotNull(session), controller(ANDROID_AUTO), null).isDone)
    }

    @Test fun withNoOneSignedInTheCarSaysSoAtOnce() {
        val tree =
            tree(Catalogless, blocked = { Blocked.SignIn })
        sessionFor(tree)
        val result = tree.onGetChildren(requireNotNull(session), controller(ANDROID_AUTO), BrowseIds.ROOT, 0, 10, null)
        idle()
        val error = requireNotNull(result.get().sessionError)
        assertEquals(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, error.code)
        assertEquals("When it’s safe, sign in on your phone", error.message)
    }

    @Test fun aVoicePlayWithNoOneSignedInSaysSoAtOnce() {
        val tree = tree(Catalogless, blocked = { Blocked.SignIn })
        val session = sessionFor(tree)
        val spoken = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("blue").build())
            .build()
        val result = tree.onAddMediaItems(session, controller(ANDROID_AUTO), mutableListOf(spoken))
        idle()
        val error = runCatching { result.get() }.exceptionOrNull()?.cause
        assertEquals("When it’s safe, sign in on your phone", error?.message)
    }

    @Test fun aVoicePlayPlaysWhatTheGraphDecides() {
        val asked = mutableListOf<String?>()
        val tree = tree(Catalogless, voiceUri = { query, _ ->
            asked += query
            query?.let { "library://album/3" }
        })
        val session = sessionFor(tree)
        val named = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("blue").build())
            .build()
        val found = tree.onAddMediaItems(session, controller(ANDROID_AUTO), mutableListOf(named))
        // Nothing named and the speaker holding something: the empty item passes, and play() resumes it.
        val nothingNamed = MediaItem.Builder().setRequestMetadata(MediaItem.RequestMetadata.Builder().build()).build()
        val carriesOn = tree.onAddMediaItems(session, controller(ANDROID_AUTO), mutableListOf(nothingNamed))
        idle()
        assertEquals(listOf("blue", null), asked)
        assertEquals("library://album/3", found.get().single().mediaId)
        assertEquals(listOf(nothingNamed), carriesOn.get())
    }

    @Test fun aVoicePlayThatFindsNothingSaysSo() {
        val tree = tree(Catalogless, voiceUri = { _, _ -> throw NoSuchElementException() })
        val session = sessionFor(tree)
        val spoken = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery("blue").build())
            .build()
        val result = tree.onAddMediaItems(session, controller(ANDROID_AUTO), mutableListOf(spoken))
        idle()
        assertTrue(runCatching { result.get() }.exceptionOrNull()?.cause is NoSuchElementException)
    }

    @Test fun aLibraryReadThatFailsTellsTheCarWhy() {
        val unreachable = object : Catalog by Catalogless {
            override suspend fun library(tab: String, offset: Int, limit: Int): List<BrowseNode> =
                throw MaConnectionException("Not connected")
        }
        val tree = tree(unreachable)
        val session = sessionFor(tree)
        val result = tree.onGetChildren(session, controller(ANDROID_AUTO), BrowseIds.ALBUMS, 0, 10, null)
        idle()
        assertEquals("Can’t reach Music\u00A0Assistant", result.get().sessionError?.message)
    }

    @Test fun theCarBrowsesButIsNotOfferedASearchTheTreeCannotAnswer() {
        val tree = tree(Catalogless)
        val session = sessionFor(tree)
        val commands = tree.onConnectAsync(session, controller(ANDROID_AUTO)).get().availableSessionCommands
        assertTrue(commands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN))
        assertFalse(commands.contains(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH))
    }

    @Test fun theHeartBesideTheControlsLikesWhatPlays() {
        var hearts = 0
        val tree = tree(Catalogless, onHeart = { hearts++ })
        val session = sessionFor(tree)
        val car = controller(ANDROID_AUTO)
        assertTrue(tree.onConnectAsync(session, car).get().availableSessionCommands.contains(BrowseTree.HEART))
        val result = tree.onCustomCommand(session, car, BrowseTree.HEART, Bundle.EMPTY).get()
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        assertEquals(1, hearts)
        // An app that is not trusted with control may not like things on the person's behalf.
        val stranger = controller("com.example.widget")
        assertFalse(tree.onConnectAsync(session, stranger).get().availableSessionCommands.contains(BrowseTree.HEART))
    }

    @Test fun theTabsReadTheirTitlesFromResources() {
        val tree = tree(Catalogless)
        sessionFor(tree)
        assertEquals(
            listOf("Recently played", "Playlists", "Albums", "Artists"),
            tree.children(BrowseIds.ROOT).titles(),
        )
    }

    @Test fun withBooksOrPodcastsTheFourthTabIsTheLibrary() {
        val tree = tree(Shelf)
        sessionFor(tree)
        assertEquals(
            listOf("Recently played", "Playlists", "Albums", "Library"),
            tree.children(BrowseIds.ROOT).titles(),
        )
        assertEquals(listOf("Artists", "Audiobooks"), tree.children(BrowseIds.LIBRARY).titles())
    }

    @Test fun recentlyPlayedOpensWithWhatToContinue() {
        val tree = tree(Shelf)
        sessionFor(tree)
        val (book, album) = tree.children(BrowseIds.RECENT).map { it.mediaMetadata }
        val extras = requireNotNull(book.extras)
        assertEquals("Continue listening", extras.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED,
            extras.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS),
        )
        assertEquals(0.4, extras.getDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE), 0.0)
        assertEquals(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK, book.mediaType)
        assertEquals("Music", album.extras?.getString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE))
        // The car reads artwork only through the app's provider.
        assertEquals(Uri.parse("content://${app.packageName}.artwork/c0ffee"), album.artworkUri)
    }

    @Test fun aPageNeverHoldsMoreThanTheBrowserAskedFor() {
        val tree = tree(Shelf)
        val session = sessionFor(tree)
        val recent = tree.onGetChildren(session, controller(ANDROID_AUTO), BrowseIds.RECENT, 0, 1, null)
        idle()
        assertEquals(listOf("The Lantern Road"), recent.get().value?.titles())
        val tabsAgain = tree.onGetChildren(session, controller(ANDROID_AUTO), BrowseIds.ROOT, 1, 10, null)
        idle()
        assertEquals(emptyList<MediaItem>(), tabsAgain.get().value)
    }

    @Test fun anEpisodePlaysAloneNotItsPodcastFromIt() {
        val tree = tree(Shelf)
        sessionFor(tree)
        val episode = tree.children("library://podcast/1").single()
        assertEquals("library://podcast_episode/9", episode.mediaId)
        assertEquals(
            MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED,
            episode.mediaMetadata.extras?.getInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS),
        )
    }

    /** Books but no podcasts; a book half heard, an album played, an episode finished. */
    private object Shelf : Catalog {
        override suspend fun recent(limit: Int) = listOf(
            BrowseNode(
                "library://album/7",
                "Blue",
                "Joni Mitchell",
                MediaItemImage(proxyId = "c0ffee"),
                browsable = true,
                mediaType = "album",
            ),
        )

        override suspend fun inProgress(limit: Int) = listOf(
            BrowseNode(
                "library://audiobook/2",
                "The Lantern Road",
                "Ada Quill",
                null,
                false,
                "audiobook",
                played = 0.4,
            ),
        )

        override suspend fun holds(tab: String) = tab == "audiobook"

        override suspend fun search(query: String, focus: String?): String? = null

        override suspend fun library(tab: String, offset: Int, limit: Int) = emptyList<BrowseNode>()

        override suspend fun children(parentUri: String, offset: Int, limit: Int) = listOf(
            BrowseNode(
                "library://podcast_episode/9",
                "Tides",
                "Harbour Talk",
                null,
                false,
                "podcast_episode",
                1.0,
                true,
            ),
        )
    }

    private companion object {
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"
        const val AUTO_UID = 10_150
    }
}
