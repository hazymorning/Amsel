package io.github.hazymorning.amsel.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One Music Assistant speaker behind Media3's [Player], This phone included: state comes from
 * [snapshots] and every command goes to [commands]. A speaker's volume is its own
 * ([DeviceInfo.PLAYBACK_TYPE_REMOTE]), This phone's the phone's media volume. One instance per
 * selected speaker; the service swaps instances with `MediaSession.setPlayer` and releases the old
 * one.
 *
 * Must be created and used on the main thread (the session's application looper).
 */
@OptIn(UnstableApi::class)
class RemotePlayer(
    private val playerId: String,
    private val snapshots: StateFlow<SpeakerSnapshot?>,
    private val commands: SpeakerCommands,
    private val elapsedRealtime: () -> Long,
    /** Set for "This phone": the audio comes out of this phone, not a speaker. */
    private val local: LocalOutput?,
    /** A command did not reach the speaker; the app shows it with Retry. */
    private val onCommandFailed: (CommandFailure) -> Unit,
    /**
     * The app's: commands outlive the player, since the service swaps players when the speaker
     * changes, and when This phone joins the server for the first time its id changes mid-command
     * (the command is still waiting for that join). Each one is bounded by its own timeouts.
     */
    appScope: CoroutineScope,
    /**
     * The level a speaker's keys and the system's slider never raise it past, 0 for none: only the
     * volume sheet goes louder, and asks first.
     */
    private val volumeLimit: () -> Int = { 0 },
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandScope = appScope + Dispatchers.Main.immediate

    /** Read where it is needed, never kept: a seek shows in it before its command is on its way. */
    private val snapshot: SpeakerSnapshot? get() = snapshots.value

    /** The last play_media until the snapshot says it is in progress. */
    private var queuing: ListenableFuture<*>? = null

    /** A car or voice play, sent with the play() that follows it: a prepare alone starts nothing. */
    private var prepared: PlayRequest? = null

    /**
     * What a media button resumed with while the speaker had not reported yet (Amsel was closed):
     * shown, buffering, until it reports what it holds, or the play fails.
     */
    private var standIn: MediaItem? = null

    /** Where a step to another chapter goes, until the snapshot shows it (see [getPlaceholderState]). */
    private var steppingTo: Long? = null

    init {
        scope.launch {
            snapshots.collect {
                if (it?.current != null) standIn = null
                invalidateState()
            }
        }
    }

    override fun getState(): State {
        val state = speakerState(snapshot, phone = local != null, elapsedRealtime)
        val item = standIn ?: return state
        return state.buildUpon()
            .setAvailableCommands(
                state.availableCommands.buildUpon().addAll(
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                ).build(),
            ).setPlaylist(listOf(MediaItemData.Builder(BrowseIds.RESUME).setMediaItem(item).build()))
            .setCurrentMediaItemIndex(0)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_BUFFERING)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val request = prepared.also { prepared = null }
        if (playWhenReady && request != null) {
            // The book or episode already on the speaker carries on: play_media would start it
            // over from the bookmark the server stored at its last pause.
            val current = snapshot?.current
            val carriesOn = current?.spoken == true && request.uris == listOf(current.uri) && request.startItem == null
            if (!carriesOn) return playMedia(request)
            local?.hold(false)
            return playOrPause(true)
        }
        val starting = queuing?.isDone == false || snapshot?.playActionInProgress == true
        if (playWhenReady && starting) {
            // A play() again while play_media starts: play_media already starts playback.
            commands.starting(local?.id ?: playerId)
            return Futures.immediateVoidFuture()
        }
        if (!playWhenReady) standIn = null
        // On this phone a pause is silent at once (before the server round trip); play releases it.
        local?.hold(!playWhenReady)
        return playOrPause(playWhenReady).also(::endStandIn)
    }

    /** Once a resuming play is answered: a speaker that still reports nothing had nothing to resume. */
    private fun endStandIn(sent: ListenableFuture<*>) {
        if (standIn == null) return
        sent.addListener({
            if (snapshot?.current == null && standIn != null) {
                standIn = null
                invalidateState()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun playOrPause(playWhenReady: Boolean): ListenableFuture<*> = if (playWhenReady) {
        send({ it.playing || it.seeking || it.playActionInProgress }) { id -> play(id) }
    } else {
        send({ !it.playing }) { id -> pause(id) }
    }

    /** The assistant's "stop", and a dismissed notification. */
    override fun handleStop(): ListenableFuture<*> {
        prepared = null
        local?.hold(true)
        return send({ !it.playing }) { id -> stop(id) }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // Called even when Media3 ignores the seek (mediaItemIndex == C.INDEX_UNSET, e.g. "next"
        // with no next item in the window), so the intent always reaches the server.
        val before = snapshot?.current?.queueItemId
        // A book's or episode's chapters come first; past the first or last one, the queue's item.
        val chapter = chapterTarget(seekCommand)
        if (chapter != null) {
            steppingTo = chapter
            return seek(chapter, step = true, intoSecond = true).also { if (it.isDone) steppingTo = null }
        }
        return when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                send({ it.current?.queueItemId != before }) { id -> next(id) }
            }

            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                send({ it.current?.queueItemId != before || it.elapsedSeconds < 2 }) { id -> previous(id) }
            }

            Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD -> {
                seek(positionMs, step = true)
            }

            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> {
                // A tapped chapter is entered, as a step to it is.
                val chapterStart = positionMs in snapshot?.current?.chapterStartsMs.orEmpty()
                seek(positionMs, step = false, intoSecond = chapterStart)
            }

            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> {
                val timeline = currentTimeline
                val target =
                    if (mediaItemIndex in 0 until timeline.windowCount) {
                        timeline.getWindow(mediaItemIndex, Timeline.Window()).uid as? String
                    } else {
                        null
                    }
                if (target == null || target == before) {
                    Futures.immediateVoidFuture()
                } else {
                    send({ it.current?.queueItemId == target }) { id -> playIndex(id, target) }
                }
            }

            else -> {
                Futures.immediateVoidFuture()
            }
        }
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        val before = snapshot?.volumeLevel ?: 0
        val asked = deviceVolume.coerceIn(0, MAX_VOLUME)
        val limit = volumeLimit()
        // The system's slider goes no louder than the limit, nor louder than a level above it the
        // volume sheet allowed; it can always go quieter.
        val level = if (limit in 1..<asked && asked > before) maxOf(limit, before) else asked
        if (level == before) return Futures.immediateVoidFuture()
        return send({ it.volumeLevel == level }, CONFIRM_VOLUME_MS) { id -> setVolume(id, level) }
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        val before = snapshot?.volumeLevel ?: 0
        val limit = volumeLimit()
        if (limit > 0) {
            if (before >= limit) return Futures.immediateVoidFuture()
            // The server's step grows to 5 at most: the step that would pass the limit stops on it.
            if (before + MAX_SERVER_STEP > limit) {
                return send({ it.volumeLevel == limit }, CONFIRM_VOLUME_MS) { id -> setVolume(id, limit) }
            }
        }
        return send({ (it.volumeLevel ?: 0) > before }, CONFIRM_VOLUME_MS) { id -> volumeUp(id) }
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        val before = snapshot?.volumeLevel ?: 0
        return send({ (it.volumeLevel ?: 0) < before }, CONFIRM_VOLUME_MS) { id -> volumeDown(id) }
    }

    // Media3 asks every player that adjusts its volume to mute too; Amsel offers no mute.
    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        send({ it.shuffle == shuffleModeEnabled }) { id -> setShuffle(id, shuffleModeEnabled) }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> {
        val mode =
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.One
                Player.REPEAT_MODE_ALL -> RepeatMode.All
                else -> RepeatMode.Off
            }
        return send({ it.repeat == mode }) { id -> setRepeat(id, mode) }
    }

    /**
     * Android Auto and voice plays arrive here (via MediaLibrarySession.Callback.onSetMediaItems)
     * and wait for play(): the assistant prepares before its announcement and plays after it.
     */
    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val resumed = mediaItems.singleOrNull()?.takeIf { it.mediaId == BrowseIds.RESUME }
        if (resumed != null) {
            // The speaker carries on with its own queue: nothing is queued anew.
            prepared = null
            if (snapshot?.current == null) standIn = resumed
            return Futures.immediateVoidFuture()
        }
        prepared = BrowseIds.toPlayRequest(playerId, mediaItems.map { it.mediaId }, startIndex)
        return Futures.immediateVoidFuture()
    }

    private fun playMedia(request: PlayRequest): ListenableFuture<*> {
        // Completes once the speaker reports the play starting, not when it plays: play_media can
        // take tens of seconds, and while a handle* future is pending, SimpleBasePlayer ignores
        // invalidateState() (no server updates reach Media3).
        val sent = send({ it.playActionInProgress }) { id ->
            playMedia(request.copy(queueId = id, onPhone = local != null))
        }
        queuing = sent
        return sent
    }

    /**
     * What Media3 shows until a command's future is done. For a next or previous that is the
     * neighbouring item; a step to another chapter stays on this item, at the chapter's start.
     */
    override fun getPlaceholderState(suggestedPlaceholderState: State): State {
        val chapter = steppingTo ?: return suggestedPlaceholderState
        steppingTo = null
        return getState().buildUpon().setContentPositionMs(chapter).build()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    /**
     * A seek within what the speaker holds. Where it goes shows in the snapshot as soon as it is
     * asked ([SpeakerCommands.seek]); the future only covers the moment until that snapshot is
     * here, during which Media3 shows the target itself, so a tap right after steps on from it.
     */
    private fun seek(positionMs: Long, step: Boolean, intoSecond: Boolean = false): ListenableFuture<*> {
        val item = snapshot?.current ?: return Futures.immediateVoidFuture()
        val seconds = item.seekSeconds(positionMs, intoSecond)
        commands.seek(playerId, seconds, step)
        val shows = { s: SpeakerSnapshot ->
            s.current?.queueItemId != item.queueItemId || (s.elapsedSeconds == seconds.toDouble() && !s.runs)
        }
        if (snapshot?.let(shows) != false) return Futures.immediateVoidFuture()
        val done = SettableFuture.create<Unit>()
        commandScope.launch {
            withTimeoutOrNull(SHOWN_TIMEOUT_MS) { snapshots.first { it == null || shows(it) } }
            done.set(Unit)
        }
        return done
    }

    /**
     * Where next or previous goes inside a book or episode with two or more chapters, from where
     * Media3 has it now (a seek just asked for included); null when the step leaves the item (past
     * its first or last chapter) or the command is not a step.
     */
    private fun chapterTarget(command: Int): Long? {
        val starts = snapshot?.current?.chapterStartsMs?.takeIf { it.size >= 2 } ?: return null
        return when (command) {
            Player.COMMAND_SEEK_TO_NEXT -> Chapters.nextStart(starts, currentPosition)
            Player.COMMAND_SEEK_TO_PREVIOUS -> Chapters.previousStart(starts, currentPosition, MA_RESTART_THRESHOLD_MS)
            else -> null
        }
    }

    /**
     * Sends a command; the returned future keeps Media3's optimistic placeholder state until the
     * server confirms it ([confirm] matches a new snapshot), [timeoutMs] passes, or it fails. On
     * this phone the command waits until its Sendspin client has joined the server: before that
     * the server does not know the phone's player (it unregisters a disconnected one).
     */
    private fun send(
        confirm: ((SpeakerSnapshot) -> Boolean)?,
        timeoutMs: Long = CONFIRM_TIMEOUT_MS,
        command: suspend SpeakerCommands.(playerId: String) -> Unit,
    ): ListenableFuture<*> {
        val done = SettableFuture.create<Unit>()
        commandScope.launch {
            try {
                val id = local?.awaitConnected() ?: playerId
                commands.command(id)
                if (confirm != null) {
                    withTimeoutOrNull(timeoutMs) { snapshots.first { it != null && confirm(it) } }
                }
            } catch (e: MaException) {
                onCommandFailed(CommandFailure(playerId, e) { send(null, timeoutMs, command) })
            } catch (e: PhoneNotReadyException) {
                onCommandFailed(CommandFailure(playerId, e) { send(null, timeoutMs, command) })
            } finally {
                done.set(Unit)
            }
        }
        return done
    }

    companion object {
        const val MAX_VOLUME = 100
        const val MA_RESTART_THRESHOLD_MS = 5_000L
        const val CONFIRM_TIMEOUT_MS = 3_000L
        const val CONFIRM_VOLUME_MS = 1_500L

        /** The largest step the server's volume up takes (`players/controller.py`, adaptive). */
        const val MAX_SERVER_STEP = 5

        /** How long a seek's target may take to show in the snapshot: one hop between threads. */
        private const val SHOWN_TIMEOUT_MS = 500L
    }
}
