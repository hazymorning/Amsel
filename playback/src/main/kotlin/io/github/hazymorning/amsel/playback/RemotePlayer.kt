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
 * One instance per target, swapped by the service with `MediaSession.setPlayer`. A speaker's volume is
 * its own ([DeviceInfo.PLAYBACK_TYPE_REMOTE]), This phone's the phone's media volume. Main thread only.
 */
@OptIn(UnstableApi::class)
class RemotePlayer(
    private val playerId: String,
    private val snapshots: StateFlow<SpeakerSnapshot?>,
    private val commands: SpeakerCommands,
    private val elapsedRealtime: () -> Long,
    private val local: LocalOutput?,
    private val onCommandFailed: (CommandFailure) -> Unit,
    /**
     * Commands outlive the player: the service swaps players when the speaker changes, and on This
     * phone's first join its id changes while a command still waits for that join.
     */
    appScope: CoroutineScope,
    /** Keys and the system slider never raise a speaker past it (0 for none); only the volume sheet does. */
    private val volumeLimit: () -> Int = { 0 },
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandScope = appScope + Dispatchers.Main.immediate

    /** Never cached: a seek shows in it before its command goes out. */
    private val snapshot: SpeakerSnapshot? get() = snapshots.value

    private var queuing: ListenableFuture<*>? = null

    /** A car or voice play waits for play(): a prepare alone starts nothing. */
    private var prepared: PlayRequest? = null

    /** After a media-button resume, shown buffering until the speaker reports what it holds or the play fails. */
    private var standIn: MediaItem? = null

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

    override fun handleStop(): ListenableFuture<*> {
        prepared = null
        local?.hold(true)
        return send({ !it.playing }) { id -> stop(id) }
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        // Called even when Media3 ignores the seek (mediaItemIndex == C.INDEX_UNSET, e.g. "next"
        // with no next item in the window), so the intent always reaches the server.
        val before = snapshot?.current?.queueItemId
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
                // A tapped chapter start rounds up into the chapter, as a step to it does.
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
            // Within one server step of the limit, the limit is set instead of stepping past it.
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

    // COMMAND_ADJUST_DEVICE_VOLUME covers mute too; Amsel offers no mute.
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

    /** Car and voice plays wait for play(): the assistant prepares before its announcement and plays after it. */
    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val resumed = mediaItems.singleOrNull()?.takeIf { it.mediaId == BrowseIds.RESUME }
        if (resumed != null) {
            prepared = null
            if (snapshot?.current == null) standIn = resumed
            return Futures.immediateVoidFuture()
        }
        val title = mediaItems.getOrNull(startIndex)?.mediaMetadata?.title?.toString()
        prepared = BrowseIds.toPlayRequest(playerId, mediaItems.map { it.mediaId }, startIndex)?.copy(title = title)
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

    /** Media3's placeholder for next or previous is the neighbouring item; a chapter step stays on this one. */
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
     * The target shows in the snapshot as soon as it is asked ([SpeakerCommands.seek]); the future
     * covers only the hop until then, while Media3 shows it, so a tap right after steps on from it.
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
     * From Media3's position, which includes a seek just asked for; null when the step leaves the item
     * (past its first or last chapter) or the command is not a step.
     */
    private fun chapterTarget(command: Int): Long? {
        val starts = snapshot?.current?.chapterStartsMs?.takeIf { it.size >= 2 } ?: return null
        return when (command) {
            Player.COMMAND_SEEK_TO_NEXT -> ChapterNavigation.nextStart(starts, currentPosition)

            Player.COMMAND_SEEK_TO_PREVIOUS -> ChapterNavigation.previousStart(
                starts,
                currentPosition,
                MA_RESTART_THRESHOLD_MS,
            )

            else -> null
        }
    }

    /**
     * The future keeps Media3's placeholder state until [confirm] matches a snapshot, [timeoutMs]
     * passes or the command fails. For This phone it waits for the join: the server unregisters a
     * disconnected phone's player.
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

        /** The largest volume_step the server can be set to. See docs/API-NOTES.md, 5.3 "Player commands". */
        const val MAX_SERVER_STEP = 10

        /** How long a seek's target may take to show in the snapshot: one hop between threads. */
        private const val SHOWN_TIMEOUT_MS = 500L
    }
}
