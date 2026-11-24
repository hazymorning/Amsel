package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.PlayerMedia
import io.github.hazymorning.amsel.api.PlayerQueue
import io.github.hazymorning.amsel.api.orNullOnFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

class SpeakerRepository(
    private val api: MaApi,
    private val elapsedRealtime: () -> Long,
    // Times out a seek the server never confirms; without it, the next report after the window does.
    private val scope: CoroutineScope? = null,
) : SeekTracker {
    private val _players = MutableStateFlow<Map<String, Player>>(emptyMap())
    private val _queues = MutableStateFlow<Map<String, QueueState>>(emptyMap())
    private val _queueItemsChanged =
        MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _gone = MutableStateFlow<Set<String>>(emptySet())

    // Server clock minus phone clock, in seconds.
    @Volatile private var skew = 0.0

    // One reload at a time; [heard] holds the events that arrive while its lists load.
    private val reloading = Mutex()
    private var heard: MutableList<Pair<MaEvent, Long>>? = null

    private val _loads = MutableStateFlow(0)

    // By queue id; guarded by this object's lock, like every queue change.
    private val seeks = mutableMapOf<String, PendingSeek>()
    private var tickets = 0L

    // This phone's item and position when its client left the server; guarded by the lock too.
    private val away = mutableMapOf<String, ItemPosition>()

    val players: StateFlow<Map<String, Player>> = _players.asStateFlow()

    /** List reads since the last [clear]; 0 means not listed yet. */
    val loads: StateFlow<Int> = _loads.asStateFlow()
    val queues: StateFlow<Map<String, QueueState>> = _queues.asStateFlow()

    /** Queue ids whose items changed; the event carries no items, so re-fetch them. */
    val queueItemsChanged: SharedFlow<String> = _queueItemsChanged.asSharedFlow()

    /**
     * Players the server dropped (This phone after the idle close, or reported unavailable), kept in [players] for
     * the notification. Back only on player_added or a reload: an update can still call a dropped player
     * available. See docs/API-NOTES.md, "This phone (Sendspin)".
     */
    val gone: StateFlow<Set<String>> = _gone.asStateFlow()

    /** The server dropped [id] while the socket was closed, so no event says so. */
    fun markGone(id: String) = _gone.update { it + id }

    /** Server Unix seconds to phone wall-clock milliseconds. */
    fun phoneMillis(serverSeconds: Double): Long = ((serverSeconds - skew) * MS_PER_SECOND).toLong()

    /**
     * Call after every connect: events are not replayed. Events heard during the load are applied again over its
     * older lists.
     */
    suspend fun refresh() = reloading.withLock { load() }

    suspend fun ensureListed() = reloading.withLock { if (_loads.value == 0) load() }

    /** Over a socket of its own (the widget's): no events follow, so only the next read updates them. */
    suspend fun read(api: MaApi) = reloading.withLock { load(api) }

    private suspend fun load(from: MaApi = api) {
        synchronized(this) { heard = mutableListOf() }
        try {
            skew = orNullOnFailure { (from.serverTime() ?: epochSeconds()) - epochSeconds() } ?: 0.0
            val players = from.players()
            val queues = from.queues()
            synchronized(this) {
                _players.value = players.associate { it.playerId to it.asShown() }
                // The server's own list: nothing in it is gone.
                _gone.value = emptySet()
                _queues.value = queues.associate { it.queueId to shown(it, anchor(it)) }
                heard?.forEach { (event, at) -> apply(event, at) }
                _loads.update { it + 1 }
            }
        } finally {
            synchronized(this) { heard = null }
        }
    }

    fun onEvent(event: MaEvent) = synchronized(this) {
        val at = elapsedRealtime()
        heard?.add(event to at)
        apply(event, at)
    }

    // [at] is on the monotonic clock.
    private fun apply(event: MaEvent, at: Long) {
        val id = event.objectId ?: return
        when (event.name) {
            "player_added", "player_updated" -> event.decode(Player.serializer())?.let { p ->
                _players.update { it + (p.playerId to p.asShown()) }
                when {
                    event.name == "player_added" -> _gone.update { it - p.playerId }
                    !p.available -> _gone.update { it + p.playerId }
                }
            }

            "player_removed" -> {
                _players.update { it - id }
                _gone.update { it - id }
            }

            "queue_added", "queue_updated" ->
                event.decode(PlayerQueue.serializer())?.let(::putQueue)

            // The queue itself follows in queue_updated.
            "queue_items_updated" -> _queueItemsChanged.tryEmit(id)

            "queue_time_updated" -> {
                val seconds = (event.data as? JsonPrimitive)?.doubleOrNull ?: return
                // An unknown queue brings its own time when it arrives.
                val state = _queues.value[id] ?: return
                _queues.value += id to shown(state.queue, PositionAnchor(seconds, at))
            }
        }
    }

    fun clear() = synchronized(this) {
        _loads.value = 0
        _players.value = emptyMap()
        _gone.value = emptySet()
        _queues.value = emptyMap()
        seeks.clear()
        away.clear()
    }

    /**
     * This phone's client left: at the next join the server rebuilds its queue from a cache that never holds a
     * seek, so a book's or episode's place is kept for [rejoined]. See docs/API-NOTES.md, "This phone (Sendspin)".
     */
    fun left(queueId: String) = synchronized(this) {
        val state = _queues.value[queueId]
        val uri = state?.queue?.currentItem?.mediaItem?.takeIf { it.isSpokenWord }?.uri
        if (state == null || uri == null) {
            away.remove(queueId)
        } else {
            away[queueId] = ItemPosition(uri, state.secondsAt(elapsedRealtime()))
        }
    }

    /** Shows the queue at the place kept by [left]; the next play starts there. */
    fun rejoined(queueId: String) = synchronized(this) { away.remove(queueId)?.let { setPausedPosition(queueId, it) } }

    override fun currentItemStatus(queueId: String): CurrentItemStatus? = synchronized(this) {
        val queue = _queues.value[queueId]?.queue ?: return@synchronized null
        val uri = queue.currentUri ?: return@synchronized null
        if (!queue.isSourceOf(_players.value[queueId])) return@synchronized null
        val starting = queue.playActionInProgress
        CurrentItemStatus(uri, queue.isPlaying || starting || seeks[queueId] is PendingSeek.Seeking, starting)
    }

    override fun startSeek(queueId: String, place: ItemPosition): Long = synchronized(this) {
        val state = _queues.value[queueId]
        val at = elapsedRealtime()
        val here = state?.queue?.currentUri == place.uri
        // The server's position, not where an earlier seek of ours put it.
        val before = when (val earlier = seeks[queueId]) {
            is PendingSeek.Seeking -> earlier.reported ?: earlier.before
            is PendingSeek.Paused -> null
            null -> state?.position
        }
        val seek = PendingSeek.Seeking(place, ++tickets, at, met = here, before = before)
        seeks[queueId] = seek
        if (state != null && here) {
            _queues.value += queueId to state.copy(position = PositionAnchor(place.seconds, at), seeking = true)
        }
        seek.ticket
    }

    override fun sent(queueId: String, ticket: Long) = synchronized(this) {
        val seek = currentSeek(queueId, ticket) ?: return@synchronized
        seeks[queueId] = seek.copy(sentAtMs = elapsedRealtime())
    }

    override fun answered(queueId: String, ticket: Long) {
        synchronized(this) {
            val seek = currentSeek(queueId, ticket) ?: return
            seeks[queueId] = seek.copy(answeredAtMs = elapsedRealtime())
        }
        scope?.launch {
            delay(PendingSeek.SETTLE_MS + 1)
            applyLastReport(queueId, ticket)
            delay(PendingSeek.WINDOW_MS - PendingSeek.SETTLE_MS)
            expireSeek(queueId, ticket)
        }
    }

    override fun failed(queueId: String, ticket: Long) = synchronized(this) {
        val seek = currentSeek(queueId, ticket) ?: return@synchronized
        seeks.remove(queueId)
        showServers(queueId, seek.reported ?: seek.before)
    }

    override fun setPausedPosition(queueId: String, place: ItemPosition) = synchronized(this) {
        val state = _queues.value[queueId]
        val at = elapsedRealtime()
        seeks[queueId] = PendingSeek.Paused(place, at, rested = state?.queue?.isPlaying == false)
        if (state != null && state.queue.currentUri == place.uri) {
            _queues.value += queueId to QueueState(state.queue, PositionAnchor(place.seconds, at), resting = true)
        }
    }

    override fun pausedPosition(queueId: String): ItemPosition? = synchronized(this) {
        (seeks[queueId] as? PendingSeek.Paused)?.place
    }

    override suspend fun awaitStarted(queueId: String) {
        _queues.first { it[queueId]?.queue?.playActionInProgress != true }
    }

    private fun currentSeek(queueId: String, ticket: Long): PendingSeek.Seeking? =
        (seeks[queueId] as? PendingSeek.Seeking)?.takeIf { it.ticket == ticket }

    // The latest report since the answer counts now, if it fits: none may follow it for a while.
    private fun applyLastReport(queueId: String, ticket: Long) = synchronized(this) {
        val seek = currentSeek(queueId, ticket) ?: return@synchronized
        val state = _queues.value[queueId] ?: return@synchronized
        val last = seek.reported ?: return@synchronized
        _queues.value += queueId to shown(state.queue, last, elapsedRealtime())
    }

    private fun expireSeek(queueId: String, ticket: Long) = synchronized(this) {
        val seek = currentSeek(queueId, ticket)?.takeIf { it.over(elapsedRealtime()) } ?: return@synchronized
        seeks.remove(queueId)
        showServers(queueId, seek.reported)
    }

    private fun showServers(queueId: String, position: PositionAnchor?) {
        val state = _queues.value[queueId] ?: return
        val at = position ?: PositionAnchor(state.position.seconds, elapsedRealtime())
        _queues.value += queueId to QueueState(state.queue, at)
    }

    // Call with the lock held.
    private fun shown(queue: PlayerQueue, reported: PositionAnchor, nowMs: Long = reported.atElapsedMs): QueueState {
        val seek = seeks[queue.queueId] ?: return QueueState(queue, reported)
        val (state, still) = seek.shows(queue, reported, nowMs)
        if (still == null) seeks.remove(queue.queueId) else seeks[queue.queueId] = still
        return state
    }

    /**
     * For a source other than the speaker's queue (AirPlay, Spotify Connect, a group's leader), which reports its
     * time only in [media].
     */
    fun position(media: PlayerMedia, playing: Boolean): PositionAnchor? {
        val seconds = media.elapsedTime ?: return null
        return anchor(seconds, media.elapsedTimeLastUpdated, playing, speed = 1.0)
    }

    private fun putQueue(queue: PlayerQueue) {
        _queues.value += queue.queueId to shown(queue, anchor(queue))
    }

    private fun anchor(queue: PlayerQueue) =
        anchor(queue.elapsedTime, queue.elapsedTimeLastUpdated, queue.isPlaying, queue.speed)

    // [updated] is on the server's clock.
    private fun anchor(seconds: Double, updated: Double?, playing: Boolean, speed: Double): PositionAnchor {
        val drift = if (playing && updated != null) epochSeconds() + skew - updated else 0.0
        return PositionAnchor(seconds + drift.coerceIn(0.0, MAX_DRIFT_S) * speed, elapsedRealtime())
    }

    private fun epochSeconds(): Double = System.currentTimeMillis() / MS_PER_SECOND

    private companion object {
        const val MS_PER_SECOND = 1000.0
        const val MAX_DRIFT_S = 6 * 3600.0
    }
}
