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

// Taps show at once, as the speaker reports back only through the server, later (docs/API-NOTES.md,
// "Player commands"). A request stands until reported or timed out, so a lost command falls back to
// the speaker's own state. Seeks are not kept here: SeekTracker shows them.
internal class OptimisticTransportState(
    private val scope: CoroutineScope,
    private val elapsedRealtime: () -> Long,
    private val timeoutMs: Long,
    private val volumeTimeoutMs: Long,
) {
    data class PendingRequest<T>(val value: T, val atMs: Long, val untilMs: Long)

    data class PendingRequests(
        val playing: PendingRequest<Boolean>? = null,
        val shuffle: PendingRequest<Boolean>? = null,
        val repeat: PendingRequest<RepeatMode>? = null,
        val volume: PendingRequest<Int>? = null,
        /** The item a skip moves to, shown from its start. */
        val item: PendingRequest<NowPlayingUi>? = null,
    ) {
        val isEmpty get() = playing == null && shuffle == null && repeat == null && volume == null && item == null
    }

    private val _pending = MutableStateFlow<Map<String, PendingRequests>>(emptyMap())

    val pending: StateFlow<Map<String, PendingRequests>> = _pending.asStateFlow()

    fun playing(playerId: String, on: Boolean) = ask(playerId, timeoutMs) { w, wish -> w.copy(playing = wish(on)) }

    fun shuffle(playerId: String, on: Boolean) = ask(playerId, timeoutMs) { w, wish -> w.copy(shuffle = wish(on)) }

    fun repeat(playerId: String, mode: RepeatMode) = ask(playerId, timeoutMs) { w, wish -> w.copy(repeat = wish(mode)) }

    fun volume(playerId: String, level: Int) =
        ask(playerId, volumeTimeoutMs) { w, wish -> w.copy(volume = wish(level)) }

    fun item(playerId: String, next: NowPlayingUi) = ask(playerId, timeoutMs) { w, wish -> w.copy(item = wish(next)) }

    fun clear(playerId: String) = _pending.update { it - playerId }

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
     * A play shows as playing at once, but its position stands until the speaker reports that it
     * plays, so the seek line never runs ahead of the sound.
     */
    fun overlay(reported: NowPlayingUi, pending: PendingRequests?): NowPlayingUi {
        if (pending == null) return reported
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

    // A newer request for the same control has its own, later deadline and stays.
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
