package io.github.hazymorning.amsel.widget

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.app.Favorites
import io.github.hazymorning.amsel.app.OptimisticTransport
import io.github.hazymorning.amsel.data.DataGraph
import io.github.hazymorning.amsel.playback.SpeakerCommands

/**
 * The widgets' volume, shuffle, repeat and heart, as Amsel's own buttons do them: shown at once
 * ([OptimisticTransport], [Favorites]), sent to the speaker, and taken back if it refuses.
 */
internal class WidgetCommands(
    private val context: Context,
    private val data: DataGraph,
    private val commands: SpeakerCommands,
    private val optimistic: OptimisticTransport,
    private val favorites: Favorites,
) : WidgetActions {
    override fun volumeLimit(): Int = data.prefs.prefs.value?.volumeLimit ?: 0

    /** The server's setting, read now and then: it rarely changes, and a tap should not wait for it. */
    private var step: Pair<Int?, Long>? = null

    override suspend fun volumeStep(level: Int): Int {
        val now = SystemClock.elapsedRealtime()
        val known = step?.takeIf { (_, at) -> now - at < STEP_KEPT_MS }
        val configured = if (known != null) {
            known.first
        } else {
            (
                try {
                    data.session.api.volumeStep()
                } catch (e: MaException) {
                    null
                }
                ).also { step = it to now }
        }
        return configured ?: serverStep(level)
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
