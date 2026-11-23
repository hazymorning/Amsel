package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.positionAt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What a tap on play, pause, shuffle, repeat, the volume or next asked of a speaker, shown at once:
 * the speaker only reports back through the server, a few hundred milliseconds later (a second on
 * some). Each request stands until the speaker reports the same or its time runs out, so a command
 * that never arrives shows the speaker's own state again instead of one it never reached. Where a
 * seek goes is not kept here: the speakers' own positions show it at once (`Seeks`).
 */
internal class OptimisticTransport(
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
    private val timeoutMs: Long,
    private val volumeTimeoutMs: Long,
) {
    data class PendingRequest<T>(val value: T, val atMs: Long, val untilMs: Long)

    /** One speaker's standing requests; null where nothing was asked. */
    data class PendingRequests(
        val playing: PendingRequest<Boolean>? = null,
        val shuffle: PendingRequest<Boolean>? = null,
        val repeat: PendingRequest<RepeatMode>? = null,
        val volume: PendingRequest<Int>? = null,
        /** The queue's next item a skip moves to, from its start. */
        val item: PendingRequest<NowPlayingUi>? = null,
    ) {
        val isEmpty get() = playing == null && shuffle == null && repeat == null && volume == null && item == null
    }

    private val _pending = MutableStateFlow<Map<String, PendingRequests>>(emptyMap())

    /** Every speaker's standing requests, by player id. */
    val pending: StateFlow<Map<String, PendingRequests>> = _pending.asStateFlow()

    fun playing(playerId: String, on: Boolean) = ask(playerId, timeoutMs) { w, wish -> w.copy(playing = wish(on)) }

    fun shuffle(playerId: String, on: Boolean) = ask(playerId, timeoutMs) { w, wish -> w.copy(shuffle = wish(on)) }

    fun repeat(playerId: String, mode: RepeatMode) = ask(playerId, timeoutMs) { w, wish -> w.copy(repeat = wish(mode)) }

    fun volume(playerId: String, level: Int) =
        ask(playerId, volumeTimeoutMs) { w, wish -> w.copy(volume = wish(level)) }

    /** A skip to [next], as Now Playing will show it from its start. */
    fun item(playerId: String, next: NowPlayingUi) = ask(playerId, timeoutMs) { w, wish -> w.copy(item = wish(next)) }

    /** A command failed: the speaker's own state shows again, beside the sentence that says so. */
    fun clear(playerId: String) = _pending.update { it - playerId }

    /** Drops what [reported] for [playerId] already shows: the speaker has caught up. */
    fun settle(playerId: String, reported: NowPlayingUi) = _pending.update { all ->
        val w = all[playerId] ?: return@update all
        val left = PendingRequests(
            playing = w.playing?.takeIf { it.value != reported.isPlaying },
            shuffle = w.shuffle?.takeIf { it.value != reported.shuffle },
            repeat = w.repeat?.takeIf { it.value != reported.repeat },
            volume = w.volume?.takeIf { reported.volume != null && it.value != reported.volume },
            item = w.item?.takeIf { it.value.key != reported.key || it.value.title != reported.title },
        )
        if (left.isEmpty) all - playerId else all + (playerId to left)
    }

    /**
     * [reported] as the speaker will be once it has done what was asked. A pause stops the position
     * where it stood at the tap; a play shows as playing at once, its position standing until the
     * speaker reports that it plays, so the seek line never runs ahead of the sound.
     */
    fun overlay(reported: NowPlayingUi, pending: PendingRequests?): NowPlayingUi {
        if (pending == null) return reported
        // A skip shows the next item from the tap on, standing at its start, with the speaker's own controls.
        val base = pending.item?.let { skip ->
            skip.value.copy(
                isPlaying = reported.isPlaying,
                arriving = reported.isPlaying,
                canSkip = reported.canSkip,
                canSeek = reported.canSeek,
                shuffle = reported.shuffle,
                repeat = reported.repeat,
                speaker = reported.speaker,
                volume = reported.volume,
                positionAtMs = skip.atMs,
            )
        } ?: reported
        val shown = base.copy(
            shuffle = pending.shuffle?.value ?: base.shuffle,
            repeat = pending.repeat?.value ?: base.repeat,
            volume = base.volume?.let { pending.volume?.value ?: it },
        )
        val playing = pending.playing?.takeIf { it.value != reported.isPlaying } ?: return shown
        return if (playing.value) {
            shown.copy(isPlaying = true, arriving = true)
        } else {
            shown.copy(
                isPlaying = false,
                arriving = false,
                positionMs = shown.positionAt(playing.atMs),
                positionAtMs = playing.atMs,
            )
        }
    }

    private fun <T> ask(
        playerId: String,
        lifetimeMs: Long,
        change: (PendingRequests, (T) -> PendingRequest<T>) -> PendingRequests,
    ) {
        val at = elapsedRealtime()
        val until = at + lifetimeMs
        _pending.update { all ->
            all + (playerId to change(all[playerId] ?: PendingRequests()) { PendingRequest(it, at, until) })
        }
        scope.launch {
            delay(lifetimeMs)
            expire(until)
        }
    }

    /** Drops every request that ran out by [untilMs]; a newer one for the same thing has its own time. */
    private fun expire(untilMs: Long) = _pending.update { all ->
        all.mapValues { (_, w) ->
            PendingRequests(
                playing = w.playing?.takeIf { it.untilMs > untilMs },
                shuffle = w.shuffle?.takeIf { it.untilMs > untilMs },
                repeat = w.repeat?.takeIf { it.untilMs > untilMs },
                volume = w.volume?.takeIf { it.untilMs > untilMs },
                item = w.item?.takeIf { it.untilMs > untilMs },
            )
        }.filterValues { !it.isEmpty }
    }
}
