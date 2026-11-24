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

enum class BrowseBlockReason { SignIn, TooOld }

/**
 * A play from the car goes to This phone, as the car plays the phone's audio: [beforeCarPlay]
 * selects it and returns once the session holds it. Other controllers play on the session's player.
 */
@OptIn(UnstableApi::class)
class BrowseTree(
    private val catalog: Catalog,
    private val scope: CoroutineScope,
    private val text: (Int) -> String,
    private val rootTitle: String,
    private val artwork: CarArtwork,
    private val cars: CarDetector,
    private val beforeCarPlay: suspend () -> Boolean,
    private val blocked: suspend () -> BrowseBlockReason?,
    private val voiceUri: suspend (query: String?, focus: String?) -> String?,
    private val openApp: PendingIntent?,
    private val onHeart: () -> Unit = {},
) : MediaLibrarySession.Callback {
    private val rootLimits = ConcurrentHashMap<String, Int>()
    private val pages = BrowsePages(catalog, text, artwork)

    /**
     * The service is exported: only trusted controllers, and cars [cars] verifies though Android may not
     * trust them, browse and control. Any other app gets Media3's untrusted commands, which do neither.
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
     * A media button while Amsel is closed resumes what the speaker holds on the server; the stand-in
     * item fills the notification until the speaker reports. No card to resume from (see onGetLibraryRoot).
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
        // No card in SystemUI's "recent" root: a stale card for a speaker someone else now uses would mislead.
        if (params?.isRecent == true) {
            return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
        }
        params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT)?.takeIf { it > 0 }?.let {
            rootLimits[browser.packageName] = it
        }
        // A legacy browser (the car) blocks its main thread until the root arrives, so the root never
        // waits for the session; a sign-in error comes with the children.
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

    private fun errorFor(blocked: BrowseBlockReason): SessionError = when (blocked) {
        BrowseBlockReason.SignIn -> SessionError(
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

        BrowseBlockReason.TooOld -> SessionError(SessionError.ERROR_NOT_SUPPORTED, text(R.string.error_too_old))
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
            // Media3 passes a legacy browser (the car) only an authentication error; any other goes by sendError.
            if (error.code != SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED) session.sendError(browser, error)
            future.set(LibraryResult.ofError(error))
        }
        return future
    }

    private suspend fun readError(read: suspend () -> Unit): SessionError? = try {
        read()
        null
    } catch (e: MaException) {
        sessionError(e, text(failureText(e)))
    } catch (e: PhoneNotReadyException) {
        sessionError(e, text(failureText(e)))
    }

    /**
     * Browse items pass on as ids (RemotePlayer.handleSetMediaItems maps them). A voice request has an
     * empty id; for "play music" while the speaker holds something it passes on too, and play() resumes.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val request = mediaItems.singleOrNull()?.takeIf { it.mediaId.isEmpty() }?.requestMetadata
            ?: return Futures.immediateFuture(mediaItems)
        val future = SettableFuture.create<MutableList<MediaItem>>()

        // The assistant and the car learn only of an error sent to them, not of a failed future.
        fun fail(error: SessionError, cause: Exception) {
            mediaSession.sendError(controller, error)
            future.setException(cause)
        }
        scope.launch {
            // Signed out, the search would wait for a connection and then report a network error.
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
            if (beforeCarPlay()) {
                future.setFuture(
                    super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs),
                )
                return@launch
            }
            // The car plays the phone's audio: a play the phone cannot take goes to no other speaker.
            val error = SessionError(SessionError.ERROR_IO, text(R.string.error_phone_not_ready))
            mediaSession.sendError(controller, error)
            future.setException(IllegalStateException(error.message))
        }
        return future
    }

    companion object {
        val HEART = SessionCommand("io.github.hazymorning.amsel.HEART", Bundle.EMPTY)

        /**
         * Without library search, which the tree does not answer: offered, the car shows a search box
         * that never returns. A spoken "play X" plays through onAddMediaItems.
         */
        private val LIBRARY_COMMANDS: SessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .remove(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
                .remove(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
                .add(HEART)
                .build()
    }
}
