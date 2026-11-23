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

/**
 * Every speaker and its queue, kept current from server events. Speakers are Music Assistant
 * players; "This phone" is one of them once Sendspin runs. A queue's position is the server's,
 * except where the app has put it ([SeekTracker]).
 */
class SpeakerRepository(
    private val api: MaApi,
    private val elapsedRealtime: () -> Long,
    /** Ends a seek the server never confirmed (without one the next report after it does). */
    private val scope: CoroutineScope? = null,
) : SeekTracker {
    private val _players = MutableStateFlow<Map<String, Player>>(emptyMap())
    private val _queues = MutableStateFlow<Map<String, QueueState>>(emptyMap())
    private val _queueItemsChanged =
        MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _gone = MutableStateFlow<Set<String>>(emptySet())

    /** Server clock minus phone clock, in seconds. */
    @Volatile private var skew = 0.0

    /** One reload at a time; [heard] holds the events that came while its lists were on their way. */
    private val reloading = Mutex()
    private var heard: MutableList<Pair<MaEvent, Long>>? = null

    private val _loads = MutableStateFlow(0)

    /** By queue id; read and written with this object's lock held, as every queue change is. */
    private val seeks = mutableMapOf<String, PendingSeek>()
    private var tickets = 0L

    /** What This phone's queue held, and where, when its client left the server; under the lock too. */
    private val away = mutableMapOf<String, ItemPosition>()

    val players: StateFlow<Map<String, Player>> = _players.asStateFlow()

    /** How often the server's lists were read since the last [clear]; events keep them current between reads. */
    val loads: StateFlow<Int> = _loads.asStateFlow()
    val queues: StateFlow<Map<String, QueueState>> = _queues.asStateFlow()

    /** Queue ids whose Up next changed; re-fetch their items. */
    val queueItemsChanged: SharedFlow<String> = _queueItemsChanged.asSharedFlow()

    /**
     * Players the server has dropped: This phone's, when its Sendspin client closes after the
     * socket's idle close (no event may tell us) or the server says it is unavailable. They stay in
     * [players], so the notification keeps the paused song, but are not on the server again until it
     * adds them again or a reload lists them; an update does not count, as 2.11.0b7 sends one calling
     * the phone available a second after dropping it (live).
     */
    val gone: StateFlow<Set<String>> = _gone.asStateFlow()

    /** The server dropped [id] while no event could reach us (see [gone]). */
    fun markGone(id: String) = _gone.update { it + id }

    /** A moment the server names in Unix seconds, on this phone's clock in milliseconds. */
    fun phoneMillis(serverSeconds: Double): Long = ((serverSeconds - skew) * MS_PER_SECOND).toLong()

    /**
     * Loads everything after (re)connecting: events are not replayed. The lists can be older than
     * events heard while they were on their way, so those apply again over them.
     */
    suspend fun refresh() = reloading.withLock { load() }

    /** Lists the speakers unless that was done since the last [clear]: what each holds is known then. */
    suspend fun ensureListed() = reloading.withLock { if (_loads.value == 0) load() }

    /** Reads the lists over [api], a socket of its own: no events follow them, so the next read does. */
    suspend fun read(api: MaApi) = reloading.withLock { load(api) }

    private suspend fun load(from: MaApi = api) {
        synchronized(this) { heard = mutableListOf() }
        try {
            skew = orNullOnFailure { (from.serverTime() ?: epochSeconds()) - epochSeconds() } ?: 0.0
            val players = from.players()
            val queues = from.queues()
            synchronized(this) {
                _players.value = players.associate { it.playerId to it.asShown() }
                // The reload is the server's own list: whatever it holds is there.
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

    /** [at]: when the event came, on this phone's monotonic clock. */
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
                // A queue not known yet brings its own time when it comes.
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
     * This phone's client left the server, which rebuilds the queue at the next join from a cache
     * that keeps the time of its last pause or new item, never of a seek (2.10.9 and 2.11, live): a
     * book whose chapter was picked came back at the old one, as far back as chapter 1. So what the
     * queue [queueId] holds and where is kept for [rejoined], for a book or an episode.
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

    /** This phone's client joined again: its queue shows where it was, and the next play starts there. */
    fun rejoined(queueId: String) = synchronized(this) { away.remove(queueId)?.let { setPausedPosition(queueId, it) } }

    override fun queueStatus(queueId: String): QueueStatus? = synchronized(this) {
        val queue = _queues.value[queueId]?.queue ?: return@synchronized null
        val uri = queue.currentUri ?: return@synchronized null
        if (!queue.isSourceOf(_players.value[queueId])) return@synchronized null
        val starting = queue.playActionInProgress
        QueueStatus(uri, queue.isPlaying || starting || seeks[queueId] is PendingSeek.Seeking, starting)
    }

    override fun startSeek(queueId: String, place: ItemPosition): Long = synchronized(this) {
        val state = _queues.value[queueId]
        val at = elapsedRealtime()
        val here = state?.queue?.currentUri == place.uri
        // Where the server has the queue, not where an earlier seek of ours put it.
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

    override suspend fun started(queueId: String) {
        _queues.first { it[queueId]?.queue?.playActionInProgress != true }
    }

    private fun currentSeek(queueId: String, ticket: Long): PendingSeek.Seeking? =
        (seeks[queueId] as? PendingSeek.Seeking)?.takeIf { it.ticket == ticket }

    /** The server's latest word since the answer counts now, if it fits: none may follow it for a while. */
    private fun applyLastReport(queueId: String, ticket: Long) = synchronized(this) {
        val seek = currentSeek(queueId, ticket) ?: return@synchronized
        val state = _queues.value[queueId] ?: return@synchronized
        val last = seek.reported ?: return@synchronized
        _queues.value += queueId to shown(state.queue, last, elapsedRealtime())
    }

    /** A seek the server never reported reached gives way to what it last said. */
    private fun expireSeek(queueId: String, ticket: Long) = synchronized(this) {
        val seek = currentSeek(queueId, ticket)?.takeIf { it.over(elapsedRealtime()) } ?: return@synchronized
        seeks.remove(queueId)
        showServers(queueId, seek.reported)
    }

    /** The queue shows [position] again, moving with the server's word; where it stands when none is known. */
    private fun showServers(queueId: String, position: PositionAnchor?) {
        val state = _queues.value[queueId] ?: return
        val at = position ?: PositionAnchor(state.position.seconds, elapsedRealtime())
        _queues.value += queueId to QueueState(state.queue, at)
    }

    /**
     * What [queue] shows, given the position [reported] for it and weighed at [nowMs]: the report,
     * unless the app has put the queue elsewhere ([PendingSeek]). Call with the lock held.
     */
    private fun shown(queue: PlayerQueue, reported: PositionAnchor, nowMs: Long = reported.atElapsedMs): QueueState {
        val seek = seeks[queue.queueId] ?: return QueueState(queue, reported)
        val (state, still) = seek.shows(queue, reported, nowMs)
        if (still == null) seeks.remove(queue.queueId) else seeks[queue.queueId] = still
        return state
    }

    /**
     * Where [media] is now, from the time the server last gave for it; null when it gave none.
     * Another source than the speaker's queue (AirPlay, Spotify Connect, a group's leader) reports
     * its time only there.
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

    /** [seconds] as the server gave them at [updated] (its clock), moved on to now while [playing]. */
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
