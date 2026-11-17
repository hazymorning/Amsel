package io.github.hazymorning.amsel.playback

import android.app.PendingIntent
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.hazymorning.amsel.api.MaException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Why the car has nothing to browse. */
enum class Blocked { SignIn, TooOld }

/**
 * Android Auto tree: root -> four tabs (Recently played, Playlists, Albums, and Artists, or Library
 * once the library holds books or podcasts), then library pages read live from the server. A play
 * from the car goes to This phone, since the car hears the phone: [beforeCarPlay] selects it,
 * and returns once the session holds it. Plays from any other controller go to whatever player the
 * session holds (the selected speaker). [text] reads the titles from `:playback`'s strings, so
 * they are translated with the app's; the root carries the app's own name, [rootTitle]. Covers are
 * [artwork] URIs, which a car that [cars] accepts may read once it connects.
 */
@OptIn(UnstableApi::class)
class BrowseTree(
    private val catalog: Catalog,
    private val scope: CoroutineScope,
    private val text: (Int) -> String,
    private val rootTitle: String,
    private val artwork: CarArtwork,
    private val cars: Cars,
    private val beforeCarPlay: suspend () -> Boolean,
    /** Why there is nothing to browse, once the stored session is known; null when signed in. */
    private val blocked: suspend () -> Blocked?,
    /** What a voice request plays: [PlaybackGraph.voiceUri]. */
    private val voiceUri: suspend (query: String?, focus: String?) -> String?,
    /** Opens Amsel on the phone: the car offers it while no one is signed in. */
    private val openApp: PendingIntent?,
    /** The heart beside the controls ([HEART]): Now Playing's, for what plays. */
    private val onHeart: () -> Unit = {},
) : MediaLibrarySession.Callback {
    /** The root tabs each browser shows at most, as it says when it asks for the root. */
    private val rootLimits = ConcurrentHashMap<String, Int>()
    private val pages = BrowsePages(catalog, text, artwork)

    /**
     * The service is exported for the system, the car and Bluetooth. They and the notification
     * browse the library and control playback, also when Android does not count the car as trusted
     * but [cars] finds it is one; any other app gets Media3's untrusted commands, which neither
     * browse nor control.
     */
    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.ConnectionResult> {
        val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
        val car = cars.isCar(controller)
        if (car) artwork.allow(controller.packageName)
        val full = controller.isTrusted || car
        if (full) {
            builder
                .setAvailableSessionCommands(LIBRARY_COMMANDS)
                .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
        } else {
            builder.setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_UNTRUSTED_SESSION_COMMANDS)
        }
        return Futures.immediateFuture(builder.build())
    }

    /**
     * A media button (a headset, a widget) while Amsel is closed: the speaker carries on with what
     * it holds on the server, as a pause and play would. The item stands in for it, under Amsel's
     * name, only until the speaker reports, so the notification has something to show at once;
     * RemotePlayer never sends it as a play. A card to resume from later there is not (see
     * onGetLibraryRoot).
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (!isForPlayback) return Futures.immediateFailedFuture(IllegalStateException("No card to resume from"))
        val standIn = MediaItem.Builder()
            .setMediaId(BrowseIds.RESUME)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(rootTitle).build())
            .build()
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(standIn), 0, C.TIME_UNSET))
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction != HEART.customAction) {
            return super.onCustomCommand(session, controller, customCommand, args)
        }
        onHeart()
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // No card to resume from in SystemUI's "recent" root: a stale card for a speaker someone
        // else now uses would mislead. A media button resumes (onPlaybackResumption).
        if (params?.isRecent == true) {
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
        }
        params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT)?.takeIf { it > 0 }?.let {
            rootLimits[browser.packageName] = it
        }
        // A legacy browser (the car) blocks the main thread until the root is there, so it never
        // waits for the session; whether anyone is signed in is answered with the tabs below it.
        return Futures.immediateFuture(root())
    }

    private fun root(): LibraryResult<MediaItem> {
        val extras =
            Bundle().apply {
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                )
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                )
            }
        val root = folder(BrowseIds.ROOT, rootTitle)
        return LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build())
    }

    /** Said in the car; signing in offers to open Amsel on the phone. */
    private fun errorFor(blocked: Blocked): SessionError = when (blocked) {
        Blocked.SignIn -> SessionError(
            SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED,
            text(R.string.browse_sign_in),
            Bundle().apply {
                openApp?.let {
                    putString(
                        MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                        text(R.string.browse_open_app),
                    )
                    putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, it)
                }
            },
        )

        Blocked.TooOld -> SessionError(SessionError.ERROR_NOT_SUPPORTED, text(R.string.error_too_old))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        scope.launch {
            val error = blocked()?.let(::errorFor) ?: readError {
                future.set(
                    LibraryResult.ofItemList(
                        pages.children(parentId, page, pageSize, rootLimits[browser.packageName]),
                        params,
                    ),
                )
            } ?: return@launch
            // Media3 passes a legacy browser (the car) only an authentication error; any other is sent.
            if (error.code != SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED) session.sendError(browser, error)
            future.set(LibraryResult.ofError(error))
        }
        return future
    }

    /** Runs [read]; what to tell the car when the server cannot be asked, or null. */
    private suspend fun readError(read: suspend () -> Unit): SessionError? = try {
        read()
        null
    } catch (e: MaException) {
        sessionError(e, text(failureText(e)))
    } catch (e: PhoneNotReadyException) {
        sessionError(e, text(failureText(e)))
    }

    /**
     * Browse items carry only ids; pass them through, RemotePlayer.handleSetMediaItems maps them. A
     * voice request ("play X") carries a search query instead and plays what [voiceUri] finds, or
     * fails when it finds nothing. With nothing named ("play music") and the speaker holding
     * something, the empty item passes through and play() resumes it.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val request = mediaItems.singleOrNull()?.takeIf { it.mediaId.isEmpty() }?.requestMetadata
            ?: return Futures.immediateFuture(mediaItems)
        val future = SettableFuture.create<MutableList<MediaItem>>()

        // The assistant and the car hear only an error sent to them; a failed future alone says nothing.
        fun fail(error: SessionError, cause: Exception) {
            mediaSession.sendError(controller, error)
            future.setException(cause)
        }
        scope.launch {
            // Signed out, the search would wait out the connection and then blame the network.
            val error = blocked()?.let(::errorFor) ?: readError {
                val query = request.searchQuery?.takeIf { it.isNotBlank() }
                val uri = try {
                    voiceUri(query, request.extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS))
                } catch (e: NoSuchElementException) {
                    val nothing = text(R.string.browse_not_found)
                    return@readError fail(SessionError(SessionError.ERROR_BAD_VALUE, nothing), e)
                }
                future.set(uri?.let { mutableListOf(MediaItem.Builder().setMediaId(it).build()) } ?: mediaItems)
            } ?: return@launch
            fail(error, IllegalStateException(error.message))
        }
        return future
    }

    /**
     * Media3 hands the items to the session's player only once this future completes, and reads
     * the player at that moment; so for the car, completing after [beforeCarPlay] sends them to
     * This phone's player.
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val fromCar = cars.isCar(controller)
        if (!fromCar) return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            // Resolved once the session holds This phone: "play music" asks what it holds.
            if (beforeCarPlay()) {
                future.setFuture(
                    super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs),
                )
                return@launch
            }
            // The car hears the phone: a play the phone cannot take goes to no other speaker.
            val error = SessionError(SessionError.ERROR_IO, text(R.string.error_phone_not_ready))
            mediaSession.sendError(controller, error)
            future.setException(IllegalStateException(error.message))
        }
        return future
    }

    companion object {
        /** Like or unlike what plays, from the notification, the lock screen or the car. */
        val HEART = SessionCommand("io.github.hazymorning.amsel.HEART", Bundle.EMPTY)

        /**
         * Browsing and control, without the library search the tree does not answer: offered, the
         * car shows a search box that never returns. A spoken "play X" plays through onAddMediaItems.
         * The heart rides along.
         */
        private val LIBRARY_COMMANDS: SessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .remove(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
                .remove(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
                .add(HEART)
                .build()
    }
}
