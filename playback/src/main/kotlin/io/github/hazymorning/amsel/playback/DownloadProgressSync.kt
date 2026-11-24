package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.data.NetworkKey
import io.github.hazymorning.amsel.data.downloads.DownloadManifest
import io.github.hazymorning.amsel.data.downloads.LocalProgress
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class DownloadPosition(val manifest: DownloadManifest, val progress: LocalProgress)

/**
 * Sends to Audiobookshelf only every 5 minutes while playing, as each wakes the phone's radio. After a
 * failed send none goes while it plays until the network changes: a home address does not answer
 * from elsewhere. See docs/API-NOTES.md, "Audiobookshelf for downloads".
 */
internal class DownloadProgressSync(
    /** The player's thread, where [place] must be read. */
    private val scope: CoroutineScope,
    private val network: StateFlow<NetworkKey?>,
    private val place: () -> DownloadPosition?,
    private val keep: (DownloadPosition) -> Unit,
    private val send: suspend (DownloadPosition) -> Unit,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private var ticking: Job? = null

    @Volatile private var failed: Failure? = null

    /** Media3 can report a stop after another item is loaded: that stop must not save the new one. */
    private var playingUri: String? = null

    fun playing(playing: Boolean) {
        ticking?.cancel()
        if (playing) {
            playingUri = place()?.manifest?.uri
            ticking = scope.launch {
                var beat = 0
                while (isActive) {
                    delay(SAVE_EVERY_MS)
                    save(send = ++beat % SEND_EVERY == 0 && !waitsForNetwork())
                }
            }
        } else if (place()?.manifest?.uri == playingUri) {
            save(send = true)
        }
    }

    fun stop() {
        ticking?.cancel()
        playingUri = null
    }

    fun save(send: Boolean) {
        val place = place() ?: return
        scope.launch(io) {
            try {
                keep(place)
            } catch (e: IOException) {
                // Storage full: the next save tries again.
            }
            if (send) sendNow(place)
        }
    }

    private suspend fun sendNow(place: DownloadPosition) {
        val on = network.value
        failed = try {
            send(place)
            null
        } catch (e: IOException) {
            Failure(on)
        }
    }

    private fun waitsForNetwork(): Boolean = failed?.let { it.network == network.value } == true

    private class Failure(val network: NetworkKey?)

    companion object {
        const val SAVE_EVERY_MS = 10_000L

        const val SEND_EVERY = 30
    }
}
