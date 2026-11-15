package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.RepeatMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * A fake speaker: commands change the snapshot the way the server's events would.
 * [calls] records every command for tests.
 */
class FakeSpeaker(
    initial: SpeakerSnapshot,
    private val queue: List<QueueEntry>,
    private val elapsedRealtime: () -> Long,
) : SpeakerCommands {
    private val state = MutableStateFlow<SpeakerSnapshot?>(initial)
    val snapshots: StateFlow<SpeakerSnapshot?> = state
    val calls = mutableListOf<String>()

    private fun index(): Int = queue.indexOfFirst { it.queueItemId == state.value?.current?.queueItemId }

    private fun jumpTo(i: Int) = state.update {
        it?.copy(
            current = queue.getOrNull(i),
            next = queue.getOrNull(i + 1),
            elapsedSeconds = 0.0,
            elapsedAtMs = elapsedRealtime(),
        )
    }

    override suspend fun play(playerId: String) {
        calls += "play"
        state.update { it?.copy(playing = true, elapsedAtMs = elapsedRealtime()) }
    }

    override suspend fun pause(playerId: String) {
        calls += "pause"
        state.update { it?.copy(playing = false) }
    }

    override suspend fun stop(playerId: String) {
        calls += "stop"
        state.update { it?.copy(playing = false) }
    }

    override suspend fun next(playerId: String) {
        calls += "next"
        jumpTo(index() + 1)
    }

    override suspend fun previous(playerId: String) {
        calls += "previous"
        val s = state.value ?: return
        if (s.elapsedSeconds >= 5) jumpTo(index()) else jumpTo((index() - 1).coerceAtLeast(0))
    }

    /** As the real one: the place shows at once, standing still while the speaker plays, until it is there. */
    override fun seek(playerId: String, positionSeconds: Int, step: Boolean) {
        calls += "seek:$positionSeconds"
        state.update {
            it?.copy(elapsedSeconds = positionSeconds.toDouble(), elapsedAtMs = elapsedRealtime(), seeking = it.playing)
        }
    }

    override suspend fun playIndex(playerId: String, queueItemId: String) {
        calls += "play_index:$queueItemId"
        jumpTo(queue.indexOfFirst { it.queueItemId == queueItemId })
    }

    override suspend fun setVolume(playerId: String, level: Int) {
        calls += "volume_set:$level"
        state.update { it?.copy(volumeLevel = level) }
    }

    override suspend fun volumeUp(playerId: String) {
        calls += "volume_up"
        state.update { it?.copy(volumeLevel = ((it.volumeLevel ?: 0) + 3).coerceAtMost(100)) }
    }

    override suspend fun volumeDown(playerId: String) {
        calls += "volume_down"
        state.update { it?.copy(volumeLevel = ((it.volumeLevel ?: 0) - 3).coerceAtLeast(0)) }
    }

    override suspend fun setShuffle(playerId: String, enabled: Boolean) {
        calls += "shuffle:$enabled"
        state.update { it?.copy(shuffle = enabled) }
    }

    override suspend fun setRepeat(playerId: String, mode: RepeatMode) {
        calls += "repeat:$mode"
        state.update { it?.copy(repeat = mode) }
    }

    override suspend fun playMedia(request: PlayRequest) {
        calls += "play_media:${request.uris.joinToString(",")}@${request.startItem}"
        state.update { it?.copy(playActionInProgress = true) }
    }

    /** Simulates a server event, e.g. another household member changing the speaker. */
    fun push(transform: (SpeakerSnapshot) -> SpeakerSnapshot) = state.update { it?.let(transform) }
}
