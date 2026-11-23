package io.github.hazymorning.amsel.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.data.downloads.Downloads
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the session's player follows: a speaker's id (null while this phone has none yet). */
data class Target(val playerId: String?, val isPhone: Boolean)

/** What the service needs from AppGraph. In the app this is `:data` plus the PlayDispatcher. */
@OptIn(UnstableApi::class)
interface PlaybackGraph {
    /** The selected speaker, or this phone: where the app plays and what it controls. */
    val target: StateFlow<Target>

    /**
     * The player the session holds (the notification, the lock screen, a headset, the car, and the
     * app's own buttons): the selected speaker, or This phone while its music goes on and the
     * selected speaker shows nothing at all, so what is heard can be paused.
     */
    val sessionTarget: StateFlow<Target> get() = target

    /** This phone's audio plays or is about to, whichever speaker the session holds. */
    val phoneAudible: StateFlow<Boolean> get() = Quiet

    /**
     * Now Playing's heart for what plays: liked or not, and null where Now Playing leaves it out
     * (a guest, a book or an episode, nothing playing). The notification and the car show it too.
     */
    val heart: StateFlow<Boolean?>

    /** A tap on that heart. */
    fun toggleHeart()

    /** The volume limit the person set, 0 for none: speakers' keys stop at it (see [RemotePlayer]). */
    fun volumeLimit(): Int = 0

    /** This phone's output, when it is the target. */
    val phone: LocalOutput?

    /** Books and episodes downloaded for listening without a connection. */
    val downloads: Downloads? get() = null

    /** A download playing on this phone: the session holds its player while it holds something. */
    val local: LocalPlayback? get() = null

    fun snapshots(playerId: String): StateFlow<SpeakerSnapshot?>

    /** Speaker [playerId] as a sentence about it names it, in the car as in the app. */
    fun speakerName(playerId: String): String

    val commands: SpeakerCommands
    val elapsedRealtime: () -> Long
    val catalog: Catalog

    /** The app's: work that must finish though the player or the service that started it is gone. */
    val scope: CoroutineScope

    /** Loads the artwork the session shows, on the app's own HTTP client and cache. */
    val artwork: BitmapLoader

    /**
     * The session's speaker started or stopped playing: keep the connection open meanwhile. A
     * download playing from the phone's files is no speaker and needs no connection.
     */
    fun onPlayingChanged(playing: Boolean)

    /** The playback service was created: playback may run with none of the app's screens shown. */
    fun onServiceCreated() {}

    /** A transport command did not reach its speaker: it joins [failures]. */
    fun onCommandFailed(failure: CommandFailure)

    /** Commands and plays that did not reach their speaker, from the app, the notification or the car. */
    val failures: Flow<CommandFailure>

    /** Makes "This phone" the selected speaker; returns once [target] says so. */
    suspend fun selectPhone()

    /** Why the car has nothing to browse, once the stored session is known; null when signed in. */
    suspend fun blocked(): Blocked?

    /**
     * What a voice request plays on the target: the uri the server finds for [query]; with no
     * query ("play music"), null while the speaker holds something, which plays on, else what was
     * played last. Throws [NoSuchElementException] when there is nothing to play.
     */
    suspend fun voiceUri(query: String?, focus: String?): String?

    /** A speaker, or this phone (same mapping, local output) once its player id is known. */
    fun playerFor(target: Target): Player = RemotePlayer(
        playerId = target.playerId.orEmpty(),
        snapshots = target.playerId?.let(::snapshots) ?: NoSpeaker,
        commands = commands,
        elapsedRealtime = elapsedRealtime,
        local = if (target.isPhone) phone else null,
        onCommandFailed = ::onCommandFailed,
        appScope = scope,
        volumeLimit = ::volumeLimit,
    )

    companion object {
        /** The app's, set as it starts, before Android creates a service. */
        lateinit var instance: PlaybackGraph
        private val NoSpeaker = MutableStateFlow<SpeakerSnapshot?>(null)
        private val Quiet = MutableStateFlow(false)
    }
}

/**
 * The one MediaLibraryService: one MediaLibrarySession for the service's lifetime, whose player is
 * the selected speaker. Media3 owns the notification and every foreground transition.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private val playingListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                PlaybackGraph.instance.onPlayingChanged(player.speakerPlays)
            }
        }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            session?.let(::showButtons)
        }
    }

    /** What the notification shows beside play: the skips of a book or an episode, and the heart. */
    private var buttonsShown: Pair<Boolean, Boolean?>? = null

    /** The target the session's player was built for. */
    private val held = MutableStateFlow<Target?>(null)

    /** The notification was swiped away: it stays away until the speaker plays again. */
    private var notificationDismissed = false

    override fun onCreate() {
        super.onCreate()
        val graph = PlaybackGraph.instance
        graph.onServiceCreated()
        val first = graph.sessionTarget.value
        // Tapping the notification, the lock-screen card or the Quick Settings player opens Amsel.
        val openApp = sessionActivity()
        val builder =
            MediaLibrarySession.Builder(
                this,
                graph.playerFor(first),
                BrowseTree(
                    graph.catalog,
                    scope,
                    ::getString,
                    applicationInfo.loadLabel(packageManager).toString(),
                    CarArtwork(this),
                    Cars(packageManager),
                    ::holdPhone,
                    graph::blocked,
                    graph::voiceUri,
                    openApp,
                    graph::toggleHeart,
                ),
            )
        openApp?.let(builder::setSessionActivity)
        builder.setBitmapLoader(graph.artwork)
        val built = builder.build()
        session = built
        current = built
        held.value = first
        built.player.addListener(playingListener)
        showButtons(built)
        scope.launch { graph.heart.collect { showButtons(built) } }
        // A speaker that already plays fires no change: tell the connection now.
        graph.onPlayingChanged(built.player.speakerPlays)
        // Said where the command came from too: the car, the lock screen, a watch.
        scope.launch { graph.failures.collect { built.sendError(errorFor(it)) } }
        scope.launch {
            // Compared with what the player was built for rather than dropping the first value:
            // a target that changed while the session was being built must still swap.
            val local = graph.local
            val holding = local?.now?.map { it != null }?.distinctUntilChanged() ?: flowOf(false)
            combine(graph.sessionTarget, holding) { target, downloaded -> target to (downloaded && target.isPhone) }
                .collect { (target, downloaded) ->
                    val wanted = if (downloaded) local?.player else null
                    if (wanted != null && built.player !== wanted) {
                        swapPlayer(built, wanted)
                        held.value = target
                    } else if (wanted == null && (target != held.value || local?.owns(built.player) == true)) {
                        swapPlayer(built, graph.playerFor(target))
                        held.value = target
                    }
                }
        }
        // This phone playing behind another speaker keeps the service in front, or Android stops it.
        scope.launch { graph.phoneAudible.drop(1).collect { triggerNotificationUpdate() } }
    }

    /**
     * One session, new player: Media3 re-publishes metadata, state, timeline and DeviceInfo
     * (so the platform VolumeProvider is rebuilt) to every controller, and the platform session
     * keeps its identity, so media-button routing earned by local playback carries over.
     */
    private fun swapPlayer(session: MediaLibrarySession, next: Player) {
        val previous = session.player
        previous.removeListener(playingListener)
        next.addListener(playingListener)
        session.player = next
        // A download's player is kept: it holds the item, and plays again on the next swap.
        if (PlaybackGraph.instance.local?.owns(previous) != true) previous.release()
        showButtons(session)
        // The new player may start in its final state (its speaker's snapshot is cached), so no
        // change would keep the connection's "something plays" flag right.
        PlaybackGraph.instance.onPlayingChanged(next.speakerPlays)
    }

    /**
     * A book or an episode shows Now Playing's five controls for it: back 15, previous (a chapter),
     * play, next and forward 30, the skips outermost; the player offers seeking back only for
     * those. Music has previous, play and next, and Now Playing's heart beside them wherever Now
     * Playing shows it.
     */
    private fun showButtons(session: MediaLibrarySession) {
        val spoken = session.player.isCommandAvailable(Player.COMMAND_SEEK_BACK)
        val heart = PlaybackGraph.instance.heart.value.takeUnless { spoken }
        if (buttonsShown == spoken to heart) return
        buttonsShown = spoken to heart
        session.setMediaButtonPreferences(if (spoken) spokenButtons() else listOfNotNull(heart?.let(::heartButton)))
    }

    private fun heartButton(liked: Boolean): CommandButton =
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setSessionCommand(BrowseTree.HEART)
            .setDisplayName(getString(if (liked) R.string.session_unlike else R.string.session_like))
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build()

    private fun spokenButtons(): List<CommandButton> {
        val back = (SKIP_BACK_MS / 1000).toInt()
        val forward = (SKIP_FORWARD_MS / 1000).toInt()
        return listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName(resources.getQuantityString(R.plurals.back_seconds, back, back))
                .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setDisplayName(getString(R.string.session_previous))
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setDisplayName(getString(R.string.session_next))
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName(resources.getQuantityString(R.plurals.forward_seconds, forward, forward))
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    /**
     * The car hears the phone, so a play from Android Auto goes to This phone whatever speaker is
     * selected: select it and return once the session holds the phone's player, which then
     * receives the car's items. False when it could not be selected, or the session did not take it.
     */
    @VisibleForTesting
    internal suspend fun holdPhone(): Boolean {
        if (PlaybackGraph.instance.target.value.isPhone && held.value?.isPhone == true) return true
        try {
            PlaybackGraph.instance.selectPhone()
        } catch (e: IOException) {
            return false
        }
        return withTimeoutOrNull(SWAP_WAIT_MS) { held.first { it?.isPhone == true } } != null
    }

    private fun errorFor(failure: CommandFailure): SessionError =
        sessionError(failure.error, getString(failure.text, PlaybackGraph.instance.speakerName(failure.playerId)))

    private fun sessionActivity(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY, false) == true) {
            notificationDismissed = true
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /** Media3 brings the notification back for any speaker that holds an item, a stopped one too. */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (session.player.playWhenReady) notificationDismissed = false
        val foreground = startInForegroundRequired || PlaybackGraph.instance.phoneAudible.value
        if (!notificationDismissed) super.onUpdateNotification(session, foreground)
    }

    /**
     * Media3's default pauses every player when the task is swiped away while nothing plays.
     * For a remote speaker that would pause music someone else may be listening to, so only stop
     * the service, never the speaker.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val playing = isPlaybackOngoing && session?.player?.playsOrStarts == true
        if (!playing && !PlaybackGraph.instance.phoneAudible.value) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.removeListener(playingListener)
            if (PlaybackGraph.instance.local?.owns(player) != true) player.release()
            release()
        }
        session = null
        current = null
        // Nothing reports the speaker's playing any more: let the socket idle-close as usual.
        PlaybackGraph.instance.onPlayingChanged(false)
        super.onDestroy()
    }

    companion object {
        /** How long a car play waits for the session to hold This phone before going on. */
        private const val SWAP_WAIT_MS = 5_000L

        /** The live session, for routing volume keys from the activity (same process only). */
        @Volatile
        var current: MediaLibrarySession? = null
            private set
    }
}

/**
 * It plays, or is on its way to: a play starting or a seek buffers, for minutes deep in a long file,
 * and the connection must stay open meanwhile as it does while it plays.
 */
private val Player.playsOrStarts: Boolean
    get() = playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

/** A speaker plays or starts: a download plays from the phone's own files, with no server to keep. */
private val Player.speakerPlays: Boolean
    get() = playsOrStarts && PlaybackGraph.instance.local?.owns(this) != true

/** What a controller (the car, the lock screen, a watch) is told when [error] stopped what it asked. */
@OptIn(UnstableApi::class)
internal fun sessionError(error: Throwable, message: String): SessionError {
    val code = when {
        error !is MaCommandException -> SessionError.ERROR_IO
        error.isPermissionError -> SessionError.ERROR_PERMISSION_DENIED
        else -> SessionError.ERROR_UNKNOWN
    }
    return SessionError(code, message)
}
