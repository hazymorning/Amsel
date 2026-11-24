package io.github.hazymorning.amsel.widget

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.app.FavoritesRepository
import io.github.hazymorning.amsel.app.OptimisticTransportState
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.playback.SpeakerCommands
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The widgets' volume, shuffle, repeat and heart, as Amsel's own buttons do them: shown at once
 * ([OptimisticTransportState], [FavoritesRepository]), sent to the speaker, and taken back if it refuses.
 */
internal class OptimisticWidgetActions(
    private val context: Context,
    private val data: DataGraph,
    private val commands: SpeakerCommands,
    private val optimistic: OptimisticTransportState,
    private val favorites: FavoritesRepository,
    private val scope: CoroutineScope,
) : WidgetActions {
    override fun volumeLimit(): Int = data.prefs.prefs.value?.volumeLimit ?: 0

    /** The server's setting (null: its ladder) and when it was read: it rarely changes. */
    @Volatile private var step: Pair<Int?, Long>? = null
    private var asking: Job? = null

    /** What the server said last, else its ladder; read again in the background while a tap goes on. */
    override fun volumeStep(level: Int): Int {
        val known = step
        val stale = known == null || SystemClock.elapsedRealtime() - known.second >= STEP_KEPT_MS
        if (stale && asking?.isActive != true) {
            asking = scope.launch {
                try {
                    step = data.session.api.volumeStep() to SystemClock.elapsedRealtime()
                } catch (e: MaException) {
                    // A refusal (no right to read settings) stands; no answer is asked again at the next tap.
                    if (e is MaCommandException) step = null to SystemClock.elapsedRealtime()
                }
            }
        }
        return known?.first ?: serverStep(level)
    }

    override suspend fun nudge(speakerId: String, up: Boolean, shown: Int) =
        shown(speakerId, { optimistic.volume(speakerId, shown) }) {
            if (up) commands.volumeUp(speakerId) else commands.volumeDown(speakerId)
        }

    override suspend fun volume(speakerId: String, level: Int) = shown(speakerId, {
        optimistic.volume(speakerId, level)
    }) {
        commands.setVolume(speakerId, level)
    }

    override fun phoneVolume(up: Boolean) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    override suspend fun shuffle(speakerId: String, on: Boolean) = shown(speakerId, {
        optimistic.shuffle(speakerId, on)
    }) {
        commands.setShuffle(speakerId, on)
    }

    override suspend fun repeat(speakerId: String, mode: RepeatMode) =
        shown(speakerId, { optimistic.repeat(speakerId, mode) }) { commands.setRepeat(speakerId, mode) }

    override fun favourite(uri: String, on: Boolean) = favorites.set(uri, on)

    private suspend fun shown(speakerId: String, show: () -> Unit, send: suspend () -> Unit) {
        show()
        try {
            send()
        } catch (e: MaException) {
            optimistic.clear(speakerId)
            throw e
        }
    }

    private companion object {
        const val STEP_KEPT_MS = 10 * 60 * 1000L
    }
}
