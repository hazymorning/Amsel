package io.github.hazymorning.amsel.app

import android.content.Context
import android.os.SystemClock
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.membersOf
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.playback.DataPlaybackGraph
import io.github.hazymorning.amsel.playback.LocalPlayback
import io.github.hazymorning.amsel.playback.PhoneSpeaker
import io.github.hazymorning.amsel.playback.PlayDispatcher
import io.github.hazymorning.amsel.playback.PlayRequest
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

/**
 * What plays and where, as every screen shows it: the speakers, the one the music plays on, and
 * what plays there. A tap on the transport ([optimistic]) or a play on its way shows at once,
 * before the speaker reports it.
 */
class Playing(
    private val context: Context,
    private val data: DataGraph,
    graph: DataPlaybackGraph,
    phone: PhoneSpeaker,
    dispatcher: PlayDispatcher,
    private val local: LocalPlayback,
    private val mapper: UiMapper,
    private val favorites: Favorites,
    scope: CoroutineScope,
) {
    /**
     * Every speaker for the "Play on" sheet, "This phone" first, with what each plays. This phone can
     * always be chosen: its player leaves the server (or stays as unavailable) once another speaker is
     * chosen, and joins again when it is picked.
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

    /**
     * The speaker the music plays on: what the speaker button shows, also while the server does not
     * list it (before it answers, or once the speaker joined a group).
     */
    val speaker: StateFlow<SpeakerUi> =
        combine(graph.target, data.speakers.players, data.prefs.prefs) { target, players, prefs ->
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

    internal val optimistic = OptimisticTransport(
        scope,
        elapsedRealtime = SystemClock::elapsedRealtime,
        timeoutMs = RemotePlayer.CONFIRM_TIMEOUT_MS,
        volumeTimeoutMs = RemotePlayer.CONFIRM_VOLUME_MS,
    )

    /** What the current speaker reports, and what a list marks as playing there. */
    private val fromSpeaker: StateFlow<Reported?> = combine(
        graph.target,
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
            // A play is on its way: the tap shows at once what is coming, from its start. What the
            // speaker reports until the play is done is the queue being replaced.
            val np = NowPlayingUi(
                title = starting.request.title.orEmpty(),
                artist = "",
                positionAtMs = starting.sinceMs,
                isPlaying = true,
                speaker = speakerUi,
            )
            val uri = playingUri(snapshot, starting.request)
            return@combine Reported(id, np, uri, setOfNotNull(uri), next = null)
        }
        val shown = snapshot?.let { mapper.nowPlaying(players.getValue(id), queue, it, speakerUi) }
        val next = snapshot?.let { mapper.nowPlayingNext(queue, it, speakerUi) }
        val uri = playingUri(snapshot, null)
        Reported(id, shown, uri, setOfNotNull(uri, shown?.albumKey, shown?.parentKey), next)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** A download playing on this phone, which the session holds instead of the phone's stream. */
    private val reported: StateFlow<Reported?> =
        combine(fromSpeaker, local.now, graph.target, speaker) { fromSpeaker, downloaded, target, speakerUi ->
            if (downloaded == null || !target.isPhone) return@combine fromSpeaker
            val np = downloaded.nowPlaying(speakerUi)
            Reported(target.playerId ?: PHONE, np, np.key, setOfNotNull(np.key), next = null)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The uri playing on the current speaker, or starting there: the row a list marks with the playing ring. */
    val playingUri: Flow<String?> = reported.map { it?.uri }.distinctUntilChanged()

    /** [playingUri] and the album or podcast it comes from, for a list of those. */
    val playingContext: Flow<Set<String>> = reported.map { it?.context.orEmpty() }.distinctUntilChanged()

    /**
     * The playing book's or episode's latest finished state, from a mark or the server's progress:
     * the queue's copy of the item keeps the one it was queued with, and the ⋮ offers by it.
     */
    private val finished = MutableStateFlow<Pair<String, Boolean>?>(null)

    /** Music moving to the current speaker ("Play my music here"), until the speaker reports it. */
    private val moving = MutableStateFlow<NowPlayingUi?>(null)

    /**
     * What plays on the current speaker, or null when nothing does (the mini player hides). A tap on
     * play, pause, shuffle, repeat or the volume shows here at once, before the speaker reports it,
     * and so does music on its way there.
     */
    val nowPlaying: StateFlow<NowPlayingUi?> =
        combine(reported, optimistic.wishes, finished, moving, favorites.known) { current, wishes, done, move, liked ->
            val shown = current?.nowPlaying?.let { np ->
                val shown = optimistic.overlay(np, wishes[current.playerId])
                if (done != null && done.first == shown.key) shown.copy(fullyPlayed = done.second) else shown
            }
            val music = if (move != null && shown?.key != move.key) move else shown
            music?.key?.let(liked::get)?.let { music.copy(favorite = it) } ?: music
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Now Playing's heart for the notification and the car ([NowPlayingUi.heart]). */
    val heart: StateFlow<Boolean?> =
        combine(nowPlaying, data.permissions) { np, permissions -> np?.heart(permissions.canWriteLibrary) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** A tap on that heart, as on Now Playing's. */
    fun toggleHeart() {
        val np = nowPlaying.value ?: return
        if (heart.value == null) return
        np.key?.let { favorites.set(it, !np.favorite) }
    }

    /** [playingUri] now. */
    internal val uri: String? get() = reported.value?.uri

    /** Whether [speakerId] plays a download from this phone's files, which only the phone can fade and pause. */
    internal fun playsDownload(speakerId: String): Boolean {
        val here = local.now.value ?: return false
        return reported.value?.let { it.playerId == speakerId && it.uri == here.manifest.uri } == true
    }

    /** The queue's next item on the current speaker, as a skip will show it. */
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
        // Another item starts from its own queued state; a mark seen while it was current before is stale.
        scope.launch { reported.map { it?.nowPlaying?.key }.distinctUntilChanged().collect { finished.value = null } }
        // The queue's copy keeps the like it was queued with: ask again whenever an item starts,
        // and after a reconnect, which may have missed a change.
        scope.launch {
            combine(
                reported.map { it?.nowPlaying?.key }.distinctUntilChanged(),
                data.session.state.map { it is SessionState.Ready }.distinctUntilChanged(),
            ) { key, ready -> key.takeIf { ready } }
                .collectLatest { key -> key?.let { favorites.refresh(it) } }
        }
    }

    /**
     * Shows [music] on the current speaker while [move] takes it there, and until the speaker
     * reports it or its time to confirm runs out: the speaker changes at once, the music only once
     * the server has moved it.
     */
    internal suspend fun whileMoving(music: NowPlayingUi, move: suspend () -> Unit) {
        moving.value = music
        try {
            move()
            withTimeoutOrNull(RemotePlayer.CONFIRM_TIMEOUT_MS) { reported.first { it?.nowPlaying?.key == music.key } }
        } finally {
            moving.compareAndSet(music, null)
        }
    }

    /** The name of speaker [playerId] as Play on shows it, for a sentence about it. */
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

private data class Reported(
    val playerId: String,
    val nowPlaying: NowPlayingUi?,
    val uri: String?,
    val context: Set<String>,
    /** The queue's next item, as a skip will show it. */
    val next: NowPlayingUi?,
)

/**
 * The item a play action is starting (so a tapped row answers at once, and gives way again if the
 * action fails), else what the speaker plays.
 */
internal fun playingUri(snapshot: SpeakerSnapshot?, starting: PlayRequest?): String? =
    starting?.starts() ?: snapshot?.current?.uri?.ifEmpty { null }

/**
 * Its start item, unless that is a chapter's position; else its one item, or its first unless
 * shuffled.
 */
private fun PlayRequest.starts(): String? = startItem?.takeIf { ItemUri.parse(it) != null }
    ?: uris.singleOrNull()
    ?: uris.firstOrNull()?.takeUnless { shuffle }
