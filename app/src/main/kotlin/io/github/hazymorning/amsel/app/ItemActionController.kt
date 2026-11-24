package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.QueueOption
import io.github.hazymorning.amsel.api.endlessMixUri
import io.github.hazymorning.amsel.api.setPlayed
import io.github.hazymorning.amsel.data.ServerExtras
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.data.UserPermissions
import io.github.hazymorning.amsel.data.home.HomeRepository
import io.github.hazymorning.amsel.data.library.LibraryRepository
import io.github.hazymorning.amsel.data.library.ProgressUpdate
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.ui.model.ActionsContext
import io.github.hazymorning.amsel.ui.model.ItemAction
import io.github.hazymorning.amsel.ui.model.ItemActionsUiState
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.model.isSong
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed interface ActionTarget {
    val key: String

    data class Media(val item: MediaUi, val context: ActionsContext = ActionsContext.Library) : ActionTarget {
        override val key: String get() = item.key
    }

    data class Track(
        val track: TrackUi,
        val context: ActionsContext = ActionsContext.Library,
        val queueItemId: String? = null,
        val playlistKey: String? = null,
        /** 1-based, as the server numbers a playlist's tracks. */
        val position: Int? = null,
        val playlistName: String? = null,
    ) : ActionTarget {
        override val key: String get() = track.key
    }
}

/** Applied by the open Up next, so the change shows there at once and keeps its order with its other edits. */
sealed interface UpNextEdit {
    val queueItemId: String

    data class Remove(override val queueItemId: String) : UpNextEdit

    /** A row the server will not move (it has played) is queued again from [uri]. */
    data class PlayNext(override val queueItemId: String, val uri: String) : UpNextEdit
}

class ItemActionController(
    private val api: MaApi,
    private val library: LibraryRepository,
    private val playback: PlaybackController,
    private val playlists: PlaylistEditor,
    private val messages: MessageController,
    private val home: HomeRepository,
    private val permissions: StateFlow<UserPermissions>,
    private val favorites: FavoritesRepository,
    private val downloading: DownloadController,
    session: StateFlow<SessionState>,
    private val scope: CoroutineScope,
    private val extras: StateFlow<ServerExtras>,
    private val dynamicQueue: () -> Boolean,
    private val mixTitle: (of: String) -> String,
    /** False while a download plays from this phone's files. */
    private val serverQueue: () -> Boolean,
) {
    private val markAgain = ConcurrentHashMap<String, Job>()

    init {
        scope.launch {
            session.collect { state ->
                // A waiting mark belongs to the signed-out user and server.
                if (state == SessionState.NeedsSetup) markAgain.values.forEach(Job::cancel)
            }
        }
    }

    private val _upNextEdits =
        MutableSharedFlow<UpNextEdit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val upNextEdits: SharedFlow<UpNextEdit> = _upNextEdits.asSharedFlow()

    fun state(target: ActionTarget): ItemActionsUiState {
        val all = when (target) {
            is ActionTarget.Media -> ItemActionsUiState.forMedia(
                target.item.copy(favorite = favorites.of(target.item.key, target.item.favorite)),
                // An episode is played from its podcast; it is not pinned on its own.
                pinned = home.isPinned(target.item.key).takeIf { target.item.kind != MediaKind.Episode },
                context = target.context,
                download = downloading.states.value[target.item.key],
                extras = extras.value,
                dynamicQueue = dynamicQueue(),
            )

            is ActionTarget.Track -> ItemActionsUiState.forTrack(
                target.track.copy(favorite = favorites.of(target.track.key, target.track.favorite)),
                target.context,
                extras.value,
                dynamicQueue(),
                dj = offersDj(target.track, extras.value, serverQueue()),
            )
        }
        // A guest can play but not change the library: those actions are hidden, not refused.
        return if (permissions.value.canWriteLibrary) all else all.copy(actions = all.actions - LIBRARY_WRITES)
    }

    /** Performs [action] in the app's scope, so it never depends on the sheet staying open. */
    fun perform(target: ActionTarget, action: ItemAction, navigator: Navigator) {
        val uri = target.key
        when (action) {
            ItemAction.Play, ItemAction.Shuffle ->
                playback.play(listOf(uri), shuffle = action == ItemAction.Shuffle, title = titleOf(target))

            ItemAction.PlayNext -> playNext(target, uri)

            ItemAction.AddToUpNext -> playback.play(listOf(uri), option = QueueOption.Add)

            ItemAction.EndlessMix -> playback.play(listOf(endlessMixUri(uri)), title = mixTitle(titleOf(target)))

            ItemAction.SimilarSongs -> navigator.open(Route.SimilarSongs(uri, titleOf(target)))

            ItemAction.AddToFavorites, ItemAction.RemoveFromFavorites ->
                favorites.set(uri, action == ItemAction.AddToFavorites)

            // Each opens a sheet of its own (ItemActionsRoute).
            ItemAction.AddToPlaylist,
            ItemAction.Bookmarks,
            ItemAction.SleepTimer,
            ItemAction.TransferPlayback,
            ItemAction.Dj,
            -> Unit

            ItemAction.Download -> downloading.download(uri)

            ItemAction.CancelDownload, ItemAction.RemoveDownload -> downloading.remove(uri)

            ItemAction.GoToArtist, ItemAction.GoToAuthor -> artistOf(target)?.let { navigator.open(Route.Artist(it)) }

            ItemAction.GoToAudiobook -> navigator.open(Route.Audiobook(uri))

            ItemAction.GoToPodcast -> (target as? ActionTarget.Media)?.item?.parentKey?.let {
                navigator.open(Route.Podcast(it))
            }

            ItemAction.MarkFinished, ItemAction.MarkPlayed, ItemAction.MarkUnfinished, ItemAction.MarkUnplayed ->
                (target as? ActionTarget.Media)?.item?.let {
                    setPlayed(it, action == ItemAction.MarkFinished || action == ItemAction.MarkPlayed)
                }

            ItemAction.GoToAlbum -> (target as? ActionTarget.Track)?.track?.albumKey?.let {
                navigator.open(Route.Album(it))
            }

            ItemAction.RemoveFromUpNext -> (target as? ActionTarget.Track)?.queueItemId?.let { id ->
                _upNextEdits.tryEmit(UpNextEdit.Remove(id))
            }

            ItemAction.PinToHome -> pin(uri, pinned = true)

            ItemAction.UnpinFromHome -> pin(uri, pinned = false)

            ItemAction.RemoveFromPlaylist -> (target as? ActionTarget.Track)?.let(playlists::remove)
        }
    }

    // In Up next the song is already queued: Play next moves it, as dragging does, instead of adding it again.
    private fun playNext(target: ActionTarget, uri: String) {
        val queued = (target as? ActionTarget.Track)?.takeIf { it.context == ActionsContext.UpNext }?.queueItemId
        if (queued == null) return playback.play(listOf(uri), option = QueueOption.Next)
        _upNextEdits.tryEmit(UpNextEdit.PlayNext(queued, uri))
    }

    private fun setPlayed(media: MediaUi, played: Boolean): Unit = send {
        val uri = media.key
        val name = media.title
        val item = ItemUri.parse(uri) ?: return@send
        val undo = library.setPlayedLocally(uri, played, ProgressUpdate(uri, media.fullyPlayed, media.resumeMs ?: 0))
        try {
            api.setPlayed(item, name, played)
        } catch (e: Exception) {
            undo()
            throw e
        }
        // The Audiobookshelf provider drops a mark within 8 s of its last update and its next sync undoes
        // it, so the mark goes again past that guard (docs/API-NOTES.md, "Audiobooks and podcasts").
        markAgain.put(
            uri,
            scope.launch {
                delay(MARK_AGAIN_MS)
                // If the first fell inside the guard, this one is the mark: a failure here loses it.
                val error = try {
                    api.setPlayed(item, name, played)
                    return@launch
                } catch (e: MaException) {
                    e
                }
                messages.show(failureText(error), retry = { setPlayed(media, played) })
            }.also { job -> job.invokeOnCompletion { markAgain.remove(uri, job) } },
        )?.cancel()
    }

    private fun pin(uri: String, pinned: Boolean) {
        scope.launch {
            try {
                if (pinned) home.pin(uri) else home.unpin(uri)
                messages.show(if (pinned) R.string.home_pinned else R.string.home_unpinned)
            } catch (e: IOException) {
                messages.show(R.string.home_edit_failed, retry = { pin(uri, pinned) })
            }
        }
    }

    private fun send(change: suspend () -> Unit) {
        scope.launch {
            try {
                change()
            } catch (e: MaException) {
                messages.show(failureText(e), retry = { send(change) })
            }
        }
    }

    private fun artistOf(target: ActionTarget) = when (target) {
        is ActionTarget.Media -> target.item.artistKey
        is ActionTarget.Track -> target.track.artistKey
    }

    private fun titleOf(target: ActionTarget) = when (target) {
        is ActionTarget.Media -> target.item.title
        is ActionTarget.Track -> target.track.title
    }

    companion object {
        private val LIBRARY_WRITES = setOf(
            ItemAction.AddToFavorites,
            ItemAction.RemoveFromFavorites,
            ItemAction.AddToPlaylist,
            ItemAction.RemoveFromPlaylist,
            ItemAction.MarkFinished,
            ItemAction.MarkUnfinished,
            ItemAction.MarkPlayed,
            ItemAction.MarkUnplayed,
        )
    }
}

internal fun offersDj(playing: TrackUi, extras: ServerExtras, serverQueue: Boolean): Boolean =
    extras.djHosts.isNotEmpty() && serverQueue && (playing.djBreak || isSong(playing.key))

// Past the Audiobookshelf provider's 8 s guard.
private const val MARK_AGAIN_MS = 9_000L
