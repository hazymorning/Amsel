package io.github.hazymorning.amsel.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.data.QueueState
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.data.downloads.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@OptIn(UnstableApi::class)
class DataPlaybackGraph(
    private val data: DataGraph,
    private val dispatcher: PlayDispatcher,
    seekDispatcher: SeekDispatcher,
    override val target: StateFlow<PlaybackTarget>,
    override val scope: CoroutineScope,
    override val phone: PhoneSpeaker,
    override val local: LocalPlayback,
    override val artwork: BitmapLoader,
    override val heart: StateFlow<Boolean?>,
    private val onHeart: () -> Unit,
    private val nameOf: (playerId: String) -> String,
) : PlaybackGraph {
    override fun toggleHeart() = onHeart()

    override fun volumeLimit(): Int = data.prefs.prefs.value?.volumeLimit ?: 0

    private val api: MaApi get() = data.session.api

    override val downloads: DownloadRepository get() = data.downloads
    private val snapshotFlows = mutableMapOf<String, StateFlow<SpeakerSnapshot?>>()

    override val sessionTarget: StateFlow<PlaybackTarget> =
        combine(target, phone.playerId, data.speakers.players, data.speakers.gone) { selected, phoneId, players, gone ->
            val phoneMusic = phoneId != null && phoneId !in gone && players[phoneId]?.hasMusic == true
            // The app's buttons act through this session: a selected speaker showing anything stays its target.
            val selectedShows =
                selected.playerId?.let(players::get)?.let { it.hasMusic || it.currentMedia != null } == true
            if (!selected.isPhone && !selectedShows &&
                phoneMusic
            ) {
                PlaybackTarget(phoneId, isPhone = true)
            } else {
                selected
            }
        }.stateIn(scope, SharingStarted.Eagerly, target.value)

    override val phoneAudible: StateFlow<Boolean> get() = phone.audible

    private val sessionPlaying = MutableStateFlow(false)

    private val _serviceCreated = MutableStateFlow(false)

    /** Stays true once a service was created: playback may run in this process without any screen. */
    val serviceCreated: StateFlow<Boolean> = _serviceCreated.asStateFlow()

    init {
        sessionPlaying.onEach(data.session::setPlaying).launchIn(scope)
        // This phone playing behind another speaker keeps the connection open too.
        phone.audible.onEach(data.session::setAudible).launchIn(scope)
        // So does music paused on This phone: the server drops its queue once it leaves (Session.setHolding).
        combine(target, phone.playerId, data.speakers.queues) { selected, id, queues ->
            selected.isPhone && id != null && queues[id]?.queue?.currentItem != null
        }.distinctUntilChanged()
            .onEach(data.session::setHolding)
            .launchIn(scope)
    }

    override val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime

    private val staleSince: StateFlow<Long?> =
        data.session.stale.map { stale -> if (stale) elapsedRealtime() else null }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val commands: MaSpeakerCommands =
        MaSpeakerCommands(api, dispatcher, data.session::wake, data.speakers, seekDispatcher, scope)
    override val catalog: Catalog = DataCatalog(
        api,
        data.session::wake,
        indexed = { it in data.library.heldTypes.first() },
        covers = { uris -> data.library.albumCovers(uris).mapValues { (_, covers) -> covers.first() } },
    )

    private val commandFailures =
        MutableSharedFlow<CommandFailure>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val failures: Flow<CommandFailure> =
        merge(commandFailures, commands.failures, dispatcher.failures, phone.failures)

    override fun onPlayingChanged(playing: Boolean) {
        sessionPlaying.value = playing
    }

    override fun onServiceCreated() {
        _serviceCreated.value = true
    }

    override fun onCommandFailed(failure: CommandFailure) {
        commandFailures.tryEmit(failure)
    }

    override suspend fun blocked(): BrowseBlockReason? = when (
        data.session.state.first {
            it != SessionState.Restoring
        }
    ) {
        SessionState.NeedsSetup -> BrowseBlockReason.SignIn
        SessionState.Unsupported -> BrowseBlockReason.TooOld
        else -> null
    }

    override suspend fun selectPhone() {
        data.prefs.setSpeaker(null)
        target.first { it.isPhone }
    }

    override suspend fun voiceUri(query: String?, focus: String?): String? {
        val uri = if (query != null) {
            catalog.search(query, focus)
        } else {
            if (holdsSomething()) return null
            catalog.recent(1).firstOrNull()?.uri
        }
        return uri ?: throw NoSuchElementException("Nothing found for \"${query.orEmpty()}\"")
    }

    private suspend fun holdsSomething(): Boolean {
        data.session.wake()
        data.speakers.ensureListed()
        val target = sessionTarget.value
        val id = if (target.isPhone) phone.awaitConnected() else target.playerId ?: return false
        val speakers = data.speakers
        return snapshot(id, speakers.players.value, speakers.queues.value, dispatcher.pending.value)?.current != null
    }

    /**
     * While the server is stale a speaker shows paused where it was: nothing says it still plays, and
     * one shown playing would keep the service in the foreground and the lock screen's clock running.
     */
    @Synchronized
    override fun snapshots(playerId: String): StateFlow<SpeakerSnapshot?> = snapshotFlows.getOrPut(playerId) {
        combine(
            data.speakers.players,
            data.speakers.queues,
            dispatcher.pending,
            staleSince,
        ) { players, queues, pending, since ->
            snapshot(playerId, players, queues, pending)?.let { s -> since?.let(s::standingAt) ?: s }
        }.stateIn(scope, SharingStarted.WhileSubscribed(), null)
    }

    private fun snapshot(
        playerId: String,
        players: Map<String, Player>,
        queues: Map<String, QueueState>,
        pending: Map<String, *>,
    ): SpeakerSnapshot? {
        val player = players[playerId] ?: return null
        return snapshotOf(
            player,
            queues[playerId],
            player.currentMedia?.let { data.speakers.position(it, player.isPlaying) },
            pending.containsKey(playerId),
            data.session.images,
            elapsedRealtime(),
        )
    }

    override fun speakerName(playerId: String): String = nameOf(playerId)
}
