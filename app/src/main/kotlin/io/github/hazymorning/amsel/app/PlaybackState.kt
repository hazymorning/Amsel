package io.github.hazymorning.amsel.app

import android.content.Context
import android.os.SystemClock
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.membersOf
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.playback.LocalPlayback
import io.github.hazymorning.amsel.playback.PhoneSpeaker
import io.github.hazymorning.amsel.playback.PlayDispatcher
import io.github.hazymorning.amsel.playback.PlayRequest
import io.github.hazymorning.amsel.playback.PlaybackTarget
import io.github.hazymorning.amsel.playback.RemotePlayer
import io.github.hazymorning.amsel.playback.SpeakerSnapshot
import io.github.hazymorning.amsel.playback.snapshotOf
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.displayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class PlaybackState(
    private val context: Context,
    private val data: DataGraph,
    selected: StateFlow<PlaybackTarget>,
    phone: PhoneSpeaker,
    dispatcher: PlayDispatcher,
    private val local: LocalPlayback,
    private val mapper: UiMapper,
    private val favorites: FavoritesRepository,
    scope: CoroutineScope,
) {
    /**
     * This phone is always available: its player leaves the server, or stays as unavailable, while
     * another speaker is selected, and joins again when it is picked.
     */
    val speakers: StateFlow<List<SpeakerUi>> = combine(data.speakers.players, phone.playerId) { players, phoneId ->
        fun row(player: Player, isPhone: Boolean) =
            mapper.speaker(player, isPhone, players.membersOf(player.playerId).size)
        val phoneRow = players[phoneId]?.let { row(it, isPhone = true).copy(available = true) }
            ?: SpeakerUi(id = phoneId ?: PHONE, name = "", isPhone = true)
        listOf(phoneRow) + players.values
            .filter { it.playerId != phoneId && it.isShownSpeaker(phoneId) }
            .sortedBy { it.shownName.lowercase() }
            .map { row(it, isPhone = false) }
    }.stateIn(scope, SharingStarted.Eagerly, listOf(SpeakerUi(PHONE, "", isPhone = true)))

    /** Known also while the server does not list it: before it answers, or once it joined a group. */
    val speaker: StateFlow<SpeakerUi> =
        combine(selected, data.speakers.players, data.prefs.prefs) { target, players, prefs ->
            val player = target.playerId?.let { players[it] }
            when {
                player != null -> mapper.speaker(
                    player,
                    isPhone = target.isPhone,
                    players.membersOf(player.playerId).size,
                )

                target.isPhone -> SpeakerUi(target.playerId ?: PHONE, "", isPhone = true)

                else -> SpeakerUi(target.playerId.orEmpty(), prefs?.speakerName.orEmpty())
            }
        }.stateIn(scope, SharingStarted.Eagerly, SpeakerUi(PHONE, "", isPhone = true))

    internal val optimistic = OptimisticTransportState(
        scope,
        elapsedRealtime = SystemClock::elapsedRealtime,
        timeoutMs = RemotePlayer.CONFIRM_TIMEOUT_MS,
        volumeTimeoutMs = RemotePlayer.CONFIRM_VOLUME_MS,
    )

    private val fromSpeaker: StateFlow<SpeakerReport?> = combine(
        selected,
        data.speakers.players,
        data.speakers.queues,
        dispatcher.pending,
        speaker,
    ) { target, players, queues, pending, speakerUi ->
        val id = target.playerId ?: return@combine null
        val queue = queues[id]?.queue
        val snapshot = players[id]?.let { player ->
            val media = player.currentMedia?.let { data.speakers.position(it, player.isPlaying) }
            snapshotOf(
                player,
                queues[id],
                media,
                id in pending,
                data.session.images,
                SystemClock.elapsedRealtime(),
            )
        }
        val starting = pending[id]
        if (starting != null) {
            // Until the play is done the speaker reports the queue being replaced: show what is
            // coming, from its start.
            val np = NowPlayingUi(
                title = starting.request.title.orEmpty(),
                artist = "",
                positionAtMs = starting.sinceMs,
                isPlaying = true,
                speaker = speakerUi,
            )
            val uri = playingUri(snapshot, starting.request)
            return@combine SpeakerReport(id, np, uri, setOfNotNull(uri), next = null)
        }
        val shown = snapshot?.let { mapper.nowPlaying(players.getValue(id), queue, it, speakerUi) }
        val next = snapshot?.let { mapper.nowPlayingNext(queue, it, speakerUi) }
        val uri = playingUri(snapshot, null)
        SpeakerReport(id, shown, uri, setOfNotNull(uri, shown?.albumKey, shown?.parentKey), next)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val reported: StateFlow<SpeakerReport?> =
        combine(fromSpeaker, local.now, selected, speaker) { fromSpeaker, downloaded, target, speakerUi ->
            if (downloaded == null || !target.isPhone) return@combine fromSpeaker
            val np = downloaded.nowPlaying(speakerUi)
            SpeakerReport(target.playerId ?: PHONE, np, np.key, setOfNotNull(np.key), next = null)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val playingUri: Flow<String?> = reported.map { it?.uri }.distinctUntilChanged()

    /** [playingUri] with its album or podcast, so a list of those can mark the one that plays. */
    val playingContext: Flow<Set<String>> = reported.map { it?.context.orEmpty() }.distinctUntilChanged()

    // The queue's copy of an item keeps the finished state it was queued with, and the ⋮ offers by
    // the latest mark or progress. See docs/API-NOTES.md, "Queue fields used".
    private val finished = MutableStateFlow<Pair<String, Boolean>?>(null)

    private val moving = MutableStateFlow<NowPlayingUi?>(null)

    /** Taps and music on its way show here at once, before the speaker reports them. */
    val nowPlaying: StateFlow<NowPlayingUi?> =
        combine(reported, optimistic.pending, finished, moving, favorites.known) {
                current,
                pending,
                done,
                move,
                liked,
            ->
            val shown = current?.nowPlaying?.let { np ->
                val shown = optimistic.overlay(np, pending[current.playerId])
                if (done != null && done.first == shown.key) shown.copy(fullyPlayed = done.second) else shown
            }
            val music = if (move != null && shown?.key != move.key) move else shown
            music?.key?.let(liked::get)?.let { music.copy(favorite = it) } ?: music
        }.stateIn(scope, SharingStarted.Eagerly, null)

    val heart: StateFlow<Boolean?> =
        combine(nowPlaying, data.permissions) { np, permissions -> np?.heart(permissions.canWriteLibrary) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun nowPlayingOf(id: String): Flow<NowPlayingUi?> = combine(
        data.speakers.players,
        data.speakers.queues,
        speakers,
        optimistic.pending,
    ) { players, queues, rows, pending ->
        val player = players[id] ?: return@combine null
        val speakerUi = rows.firstOrNull { it.id == id } ?: mapper.speaker(player, isPhone = false)
        val media = player.currentMedia?.let { data.speakers.position(it, player.isPlaying) }
        val now = SystemClock.elapsedRealtime()
        val snapshot = snapshotOf(player, queues[id], media, false, data.session.images, now)
        mapper.nowPlaying(player, queues[id]?.queue, snapshot, speakerUi)?.let { optimistic.overlay(it, pending[id]) }
    }

    fun toggleHeart() {
        val np = nowPlaying.value ?: return
        if (heart.value == null) return
        np.key?.let { favorites.set(it, !np.favorite) }
    }

    /** [playingUri] now. */
    internal val uri: String? get() = reported.value?.uri

    // What plays from this phone's files only the phone can fade and pause.
    internal fun playsDownload(speakerId: String): Boolean {
        val here = local.now.value ?: return false
        return reported.value?.let { it.playerId == speakerId && it.uri == here.manifest.uri } == true
    }

    /** The queue's next item, as a skip will show it. */
    internal val next: NowPlayingUi? get() = reported.value?.next

    init {
        scope.launch {
            reported.collect { current -> current?.nowPlaying?.let { optimistic.settle(current.playerId, it) } }
        }
        scope.launch {
            data.library.progress.collect { update ->
                if (update.uri == reported.value?.nowPlaying?.key) finished.value = update.uri to update.fullyPlayed
            }
        }
        // Reset on every change of item: one that comes back starts from its queued state again.
        scope.launch { reported.map { it?.nowPlaying?.key }.distinctUntilChanged().collect { finished.value = null } }
        // The queue's copy keeps the like it was queued with: read again whenever an item starts,
        // and after a reconnect, which may have missed a change.
        scope.launch {
            combine(
                reported.map { it?.nowPlaying?.key }.distinctUntilChanged(),
                data.session.state.map { it is SessionState.Ready }.distinctUntilChanged(),
            ) { key, ready -> key.takeIf { ready } }
                .collectLatest { key -> key?.let { favorites.refresh(it) } }
        }
    }

    // The speaker changes at once but the music only once the server has moved it: [music] shows
    // meanwhile, until the speaker reports it or CONFIRM_TIMEOUT_MS runs out.
    internal suspend fun whileMoving(music: NowPlayingUi, move: suspend () -> Unit) {
        moving.value = music
        try {
            move()
            withTimeoutOrNull(RemotePlayer.CONFIRM_TIMEOUT_MS) { reported.first { it?.nowPlaying?.key == music.key } }
        } finally {
            moving.compareAndSet(music, null)
        }
    }

    internal fun nameOf(playerId: String): String = nameOf(
        speakers.value.firstOrNull { it.id == playerId }
            ?: data.speakers.players.value[playerId]?.let { mapper.speaker(it, isPhone = false) }
            ?: speaker.value,
    )

    /** Read when the sentence is made, so it follows a change of language. */
    internal fun nameOf(speaker: SpeakerUi): String = speaker.displayName(context.resources)

    companion object {
        /** The phone's row id before the server knows it. */
        const val PHONE = "this-phone"
    }
}

private data class SpeakerReport(
    val playerId: String,
    val nowPlaying: NowPlayingUi?,
    val uri: String?,
    val context: Set<String>,
    val next: NowPlayingUi?,
)

// A pending play's item first, so a tapped row is marked at once and unmarked if the play fails.
internal fun playingUri(snapshot: SpeakerSnapshot?, starting: PlayRequest?): String? =
    starting?.starts() ?: snapshot?.current?.uri?.ifEmpty { null }

// startItem can be a chapter's position rather than a uri; a shuffled list has no known first item.
private fun PlayRequest.starts(): String? = startItem?.takeIf { ItemUri.parse(it) != null }
    ?: uris.singleOrNull()
    ?: uris.firstOrNull()?.takeUnless { shuffle }
