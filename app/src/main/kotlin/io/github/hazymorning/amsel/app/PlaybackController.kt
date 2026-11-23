package io.github.hazymorning.amsel.app

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.QueueOption
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.api.isEndlessMix
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.playback.ChapterNavigation
import io.github.hazymorning.amsel.playback.DataPlaybackGraph
import io.github.hazymorning.amsel.playback.LocalPlayback
import io.github.hazymorning.amsel.playback.PhoneNotReadyException
import io.github.hazymorning.amsel.playback.PhoneSpeaker
import io.github.hazymorning.amsel.playback.PlayDispatcher
import io.github.hazymorning.amsel.playback.PlayRequest
import io.github.hazymorning.amsel.playback.PlaybackService
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.playback.failureText
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.positionAt
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** What became of a play, for what answers it ([HintController]). */
sealed interface PlaybackEvent {
    /** It was held back: [speaker] cannot be reached; [again] asks for it anew. */
    class Unreachable(val speaker: SpeakerUi, val again: () -> Unit) : PlaybackEvent

    /** It went out to [speaker]. */
    class Started(val speaker: SpeakerUi) : PlaybackEvent

    /** A book or an episode, [title], was asked for on speaker [speakerId]. */
    class SpokenStarted(val title: String, val speakerId: String) : PlaybackEvent
}

/**
 * What a screen may do with playback, wherever it is; what plays is [PlaybackState]'s. Transport goes
 * through the one Media3 session (so the app, the notification, headphone buttons and Android Auto
 * behave identically); play actions go through the PlayDispatcher.
 */
class PlaybackController(
    private val context: Context,
    private val data: DataGraph,
    private val graph: DataPlaybackGraph,
    private val playing: PlaybackState,
    private val phone: PhoneSpeaker,
    private val dispatcher: PlayDispatcher,
    private val messages: MessageController,
    private val local: LocalPlayback,
    private val scope: CoroutineScope,
) {
    private val _events = Channel<PlaybackEvent>(EVENTS_BUFFER, BufferOverflow.DROP_OLDEST)

    /** What became of the plays asked here, for the hints to answer ([HintController]). */
    val events: Flow<PlaybackEvent> = _events.receiveAsFlow()

    private var connection: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    /**
     * Remote volume changes, at most one every 150 ms: a drag or a held step sends only where it
     * is by then, and the last level always goes.
     */
    private val volumes = Channel<Pair<String, Int>>(Channel.CONFLATED)

    init {
        scope.launch {
            for ((id, level) in volumes) {
                val error = try {
                    data.session.api.setVolume(id, level)
                    delay(VOLUME_EVERY_MS)
                    continue
                } catch (e: MaException) {
                    e
                }
                playing.optimistic.clear(id)
                messages.show(failureText(error), playing.nameOf(id), retry = { volumes.trySend(id to level) })
            }
        }
        // Play on and the dock would stay empty until the next connect: say so, with Retry, each
        // time the app comes to the front while it lasts.
        scope.launch {
            combine(data.speakersFailure, data.session.foreground) { error, inFront -> error?.takeIf { inFront } }
                .filterNotNull()
                .collect { error -> messages.show(failureText(error), retry = data::reloadSpeakers) }
        }
        // A download plays on this phone only: picking a speaker pauses it, and it waits there.
        scope.launch(Dispatchers.Main) {
            graph.target.collect { target -> if (!target.isPhone) local.pause() }
        }
        scope.launch {
            // Plays and transport from the app, the notification, a headset or the car.
            graph.failures.collect { failure ->
                playing.optimistic.clear(failure.playerId)
                val retry = failure.retry?.let { phone.afterStop(failure.error, scope, it) }
                messages.show(failure.text, playing.nameOf(failure.playerId), retry = retry)
            }
        }
    }

    /**
     * Connects the app to the session, which starts the service, until [release]: while the app is
     * in front. Main thread only. A refused connection leaves no controller, and taps say so.
     */
    suspend fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        connection = future
        controller = future.connected()
    }

    /** The controller once [this] connection completes; null when it was refused or released first. */
    private suspend fun ListenableFuture<MediaController>.connected(): MediaController? =
        suspendCancellableCoroutine { cont ->
            addListener(
                {
                    val connected = if (isCancelled) {
                        null
                    } else {
                        try {
                            get()
                        } catch (e: ExecutionException) {
                            null
                        }
                    }
                    cont.resume(connected)
                },
                context.mainExecutor,
            )
        }

    /** Retry after the session refused the app: connect again, then do what was tapped. */
    private fun reconnect(control: PlayerControl) {
        scope.launch(Dispatchers.Main) {
            release()
            connect()
            control(control)
        }
    }

    /** Lets the service go once nothing plays (Media3: release the controller in onStop). */
    fun release() {
        connection?.let(MediaController::releaseFuture)
        connection = null
        controller = null
    }

    /**
     * Plays [uris] on the current speaker: an album, playlist or artist, or tracks; [startItem]
     * starts a list at a track, or a book at a chapter, which begins at [startPositionMs]. [title]
     * is shown at once while the speaker starts.
     */
    fun play(
        uris: List<String>,
        startItem: String? = null,
        shuffle: Boolean = false,
        option: QueueOption = QueueOption.Replace,
        title: String? = null,
        startPositionMs: Long? = null,
    ) {
        val target = graph.target.value
        val speaker = playing.speaker.value
        // In front, a speaker out of reach is not sent the play: This phone is offered in its place.
        // Behind the app's back it goes out, and the server says why it fails.
        if (speaker.id == target.playerId && !speaker.isPhone && !speaker.available && data.session.foreground.value) {
            val again = { play(uris, startItem, shuffle, option, title, startPositionMs) }
            _events.trySend(PlaybackEvent.Unreachable(speaker, again))
            return
        }
        val downloaded = uris.singleOrNull()?.takeIf { option == QueueOption.Replace && target.isPhone }
            ?.takeIf { data.downloads.manifest(it) != null }
        if (downloaded != null) return playDownload(downloaded, startPositionMs)
        // Anything else on this phone comes from the server: the download lets go of the session.
        // For a mix only once the server has songs for it, which the dispatcher learns.
        val mix = option == QueueOption.Replace && uris.singleOrNull()?.let(::isEndlessMix) == true
        if (target.isPhone && !mix) local.stopDownloadPlayback()
        dispatcher.play(
            PlayRequest(
                target.playerId ?: PlaybackState.PHONE,
                uris,
                option,
                startItem,
                shuffle,
                title,
                target.isPhone,
                startPositionMs,
            ),
        )
        _events.trySend(PlaybackEvent.Started(speaker))
    }

    /**
     * A download on This phone plays from its files: the phone's stream, if one plays, is paused
     * first, silent at once, so the two are never heard together.
     */
    private fun playDownload(uri: String, startPositionMs: Long?) {
        val phoneId = phone.playerId.value
        if (local.now.value == null && phoneId != null && data.speakers.players.value[phoneId]?.isPlaying == true) {
            phone.hold(true)
            scope.launch {
                try {
                    data.session.api.pause(phoneId)
                } catch (e: MaException) {
                    // Held silent already; the server learns of it when it can.
                }
            }
        }
        scope.launch(Dispatchers.Main) { local.play(uri, startPositionMs) }
    }

    /**
     * A book or an episode from a card or a row, played as the official app plays it, so the server
     * starts it at its own bookmark: the queue's place may be an old save it brought back after
     * This phone left. Returns true when it already plays, so nothing was sent.
     */
    fun resume(uri: String, title: String?): Boolean {
        val now = playing.nowPlaying.value
        if (now?.key == uri && now.isPlaying) return true
        if (hasPausedPosition(uri)) control(PlayerControl.PlayPause) else play(listOf(uri), title = title)
        title?.let(::spokenStarted)
        return false
    }

    /** A book's or an episode's play button: pauses it while it plays here, else plays it as [resume] does. */
    fun playOrPause(uri: String, title: String?) {
        val now = playing.nowPlaying.value
        val pauses = playing.uri == uri && now?.isPlaying == true
        if (pauses || hasPausedPosition(uri)) {
            control(PlayerControl.PlayPause)
        } else {
            play(listOf(uri), title = title)
        }
        if (!pauses) title?.let(::spokenStarted)
    }

    private fun spokenStarted(title: String) {
        _events.trySend(PlaybackEvent.SpokenStarted(title, playing.speaker.value.id))
    }

    /**
     * The current speaker holds [uri] at a place the server does not have: one sought while it was
     * paused, or where it was before the server rebuilt This phone's queue. Its own play starts
     * there, while the bookmark play_media resumes from is older.
     */
    private fun hasPausedPosition(uri: String): Boolean {
        val id = graph.target.value.playerId ?: return false
        return playing.uri == uri && data.downloads.manifest(uri) == null &&
            data.speakers.pausedPosition(id)?.uri == uri
    }

    /**
     * Call before anything starts playback on [queueId] outside [play] (Up next's tap on a song):
     * on This phone it releases the local hold left by a pause, which would otherwise silence
     * and pause the stream the server is about to send; on any speaker, a pause that still waits
     * for an earlier start gives way.
     */
    fun beforePlayOn(queueId: String) {
        if (queueId == phone.playerId.value) phone.hold(false)
        graph.commands.starting(queueId)
    }

    /**
     * "Play X on Amsel" from the assistant (MEDIA_PLAY_FROM_SEARCH), decided as the car's voice
     * play is ([DataPlaybackGraph.voiceUri]). Signed out or on a too-old server, the screen on show
     * already says what to do.
     */
    fun playFromSearch(query: String, focus: String?) {
        scope.launch {
            if (graph.blocked() != null) return@launch
            val error = try {
                val uri = graph.voiceUri(query.takeIf { it.isNotBlank() }, focus)
                if (uri != null) {
                    play(listOf(uri))
                } else if (playing.nowPlaying.value?.isPlaying != true) {
                    withContext(Dispatchers.Main) { control(PlayerControl.PlayPause) }
                }
                return@launch
            } catch (e: NoSuchElementException) {
                return@launch messages.show(PlaybackR.string.browse_not_found)
            } catch (e: MaException) {
                e
            } catch (e: PhoneNotReadyException) {
                e
            }
            messages.show(failureText(error, play = true), retry = { playFromSearch(query, focus) })
        }
    }

    /**
     * Transport from the app's own controls. The tap shows at once ([OptimisticTransport]); play
     * and pause follow what the button shows, so a second tap before the speaker answers undoes
     * the first rather than repeating it.
     */
    fun control(control: PlayerControl) {
        val id = graph.target.value.playerId
        val play = !(playing.nowPlaying.value?.isPlaying ?: controller?.playWhenReady ?: false)
        val optimistic = playing.optimistic
        if (id != null) {
            when (control) {
                PlayerControl.PlayPause -> optimistic.playing(id, play)

                is PlayerControl.SetShuffle -> optimistic.shuffle(id, control.on)

                is PlayerControl.SetRepeat -> optimistic.repeat(id, control.mode)

                is PlayerControl.SetVolume -> optimistic.volume(id, control.volume)

                // A step to a book's next chapter stays on the book; the seek itself shows where it goes.
                PlayerControl.Next -> if (!playing.nowPlaying.value.hasNextChapter()) {
                    playing.next?.let { optimistic.item(id, it) }
                }

                else -> Unit
            }
        }
        scope.launch(Dispatchers.Main) {
            // A tap right after the app comes to the front waits for the connection on its way.
            val c = controller ?: connection?.connected()
            if (c == null) {
                id?.let(optimistic::clear)
                messages.show(PlaybackR.string.error_not_connected, retry = { reconnect(control) })
                return@launch
            }
            when (control) {
                PlayerControl.PlayPause -> if (play) c.play() else c.pause()

                PlayerControl.Next -> c.seekToNext()

                PlayerControl.Previous -> c.seekToPrevious()

                PlayerControl.SeekBack -> c.seekBack()

                PlayerControl.SeekForward -> c.seekForward()

                is PlayerControl.Seek -> c.seekTo(control.positionMs)

                is PlayerControl.SetShuffle -> c.shuffleModeEnabled = control.on

                is PlayerControl.SetRepeat -> c.repeatMode = when (control.mode) {
                    RepeatMode.Off -> Player.REPEAT_MODE_OFF
                    RepeatMode.All -> Player.REPEAT_MODE_ALL
                    RepeatMode.One -> Player.REPEAT_MODE_ONE
                }

                is PlayerControl.SetVolume -> graph.target.value.playerId?.let { volumes.trySend(it to control.volume) }
            }
        }
    }
}

/** Whether next steps to another chapter of what plays, as the session decides it (RemotePlayer). */
private fun NowPlayingUi?.hasNextChapter(): Boolean {
    if (this == null || !hasChapters) return false
    return ChapterNavigation.nextStart(chapters.map { it.startMs }, positionAt(SystemClock.elapsedRealtime())) != null
}

private const val VOLUME_EVERY_MS = 150L

/** Plays asked in a burst: the hints answer the latest. */
private const val EVENTS_BUFFER = 8
