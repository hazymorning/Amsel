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
import io.github.hazymorning.amsel.data.downloads.DownloadRepository
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

/** [playerId] is null while This phone has no id yet. */
data class PlaybackTarget(val playerId: String?, val isPhone: Boolean)

@OptIn(UnstableApi::class)
interface PlaybackGraph {
    val target: StateFlow<PlaybackTarget>

    /**
     * The session's player: the selected speaker, or This phone while its music goes on and the
     * selected speaker shows nothing, so what is heard can be paused.
     */
    val sessionTarget: StateFlow<PlaybackTarget> get() = target

    val phoneAudible: StateFlow<Boolean> get() = Quiet

    /** Null where Now Playing shows no heart: a guest, a book or an episode, nothing playing. */
    val heart: StateFlow<Boolean?>

    fun toggleHeart()

    /** 0 for none. */
    fun volumeLimit(): Int = 0

    val phone: LocalOutput?

    val downloads: DownloadRepository? get() = null

    val local: LocalPlayback? get() = null

    fun snapshots(playerId: String): StateFlow<SpeakerSnapshot?>

    fun speakerName(playerId: String): String

    val commands: SpeakerCommands
    val elapsedRealtime: () -> Long
    val catalog: Catalog

    /** The app's scope, for work that must outlive the player or the service that started it. */
    val scope: CoroutineScope

    val artwork: BitmapLoader

    /**
     * The connection stays open while the session's speaker plays; a download playing from the
     * phone's files needs none and counts as not playing.
     */
    fun onPlayingChanged(playing: Boolean)

    fun onServiceCreated() {}

    fun onCommandFailed(failure: CommandFailure)

    val failures: Flow<CommandFailure>

    /** Returns once [target] is This phone. */
    suspend fun selectPhone()

    /** Waits until the stored session is restored; null when signed in. */
    suspend fun blocked(): BrowseBlockReason?

    /**
     * With no [query] ("play music"): null while the speaker holds something, which then plays on,
     * else what was played last. Throws [NoSuchElementException] when there is nothing to play.
     */
    suspend fun voiceUri(query: String?, focus: String?): String?

    fun playerFor(target: PlaybackTarget): Player = RemotePlayer(
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
        /** Set as the app starts, before Android creates a service. */
        lateinit var instance: PlaybackGraph
        private val NoSpeaker = MutableStateFlow<SpeakerSnapshot?>(null)
        private val Quiet = MutableStateFlow(false)
    }
}

/** Media3 owns the notification and every foreground transition. */
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

    private var buttonsShown: Pair<Boolean, Boolean?>? = null

    /** The target the session's player was built for. */
    private val held = MutableStateFlow<PlaybackTarget?>(null)

    private var notificationDismissed = false

    override fun onCreate() {
        super.onCreate()
        val graph = PlaybackGraph.instance
        graph.onServiceCreated()
        val first = graph.sessionTarget.value
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
                    CarDetector(packageManager),
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
        // To every controller, so the car, the lock screen or a watch shows it too.
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
        // This phone playing behind another speaker needs the service in the foreground too.
        scope.launch { graph.phoneAudible.drop(1).collect { triggerNotificationUpdate() } }
    }

    /**
     * One session, new player: Media3 re-publishes state and DeviceInfo to every controller (which
     * rebuilds the platform VolumeProvider), and the platform session keeps its identity, so
     * media-button routing carries over.
     */
    private fun swapPlayer(session: MediaLibrarySession, next: Player) {
        val previous = session.player
        previous.removeListener(playingListener)
        next.addListener(playingListener)
        session.player = next
        // A download's player is kept: it holds the item, and plays again on the next swap.
        if (PlaybackGraph.instance.local?.owns(previous) != true) previous.release()
        showButtons(session)
        // The new player may start in its final state (its speaker's snapshot is cached) and report
        // no change, so the connection is told here.
        PlaybackGraph.instance.onPlayingChanged(next.speakerPlays)
    }

    /** COMMAND_SEEK_BACK is offered only for a book or an episode, which gets the skips instead of the heart. */
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

    /** False when This phone could not be selected or the session did not swap to it in time. */
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

    /**
     * Media3 brings the notification back for any speaker that holds an item, a stopped one too; a
     * dismissed one stays away until the speaker plays again.
     */
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (session.player.playWhenReady) notificationDismissed = false
        val foreground = startInForegroundRequired || PlaybackGraph.instance.phoneAudible.value
        if (!notificationDismissed) super.onUpdateNotification(session, foreground)
    }

    /**
     * Media3's default pauses every player when the task is removed while nothing plays; on a remote
     * speaker that could pause someone else's music, so only the service stops.
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
        private const val SWAP_WAIT_MS = 5_000L

        /** For routing volume keys from the activity; same process only. */
        @Volatile
        var current: MediaLibrarySession? = null
            private set
    }
}

/**
 * Buffering counts: a play starting or a seek deep into a long file can take long
 * (docs/API-NOTES.md, "Seek"), and the connection must stay open meanwhile.
 */
private val Player.playsOrStarts: Boolean
    get() = playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)

/** A download plays from the phone's own files and needs no connection. */
private val Player.speakerPlays: Boolean
    get() = playsOrStarts && PlaybackGraph.instance.local?.owns(this) != true

@OptIn(UnstableApi::class)
internal fun sessionError(error: Throwable, message: String): SessionError {
    val code = when {
        error !is MaCommandException -> SessionError.ERROR_IO
        error.isPermissionError -> SessionError.ERROR_PERMISSION_DENIED
        else -> SessionError.ERROR_UNKNOWN
    }
    return SessionError(code, message)
}
