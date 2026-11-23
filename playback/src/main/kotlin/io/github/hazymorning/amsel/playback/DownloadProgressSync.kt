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

/** Where the person is in a download. */
internal data class DownloadPosition(val manifest: DownloadManifest, val progress: LocalProgress)

/**
 * A playing download's place: kept on the phone every 10 s, and sent to Audiobookshelf each time it
 * stops playing and every 5 minutes while it plays, as each send wakes the phone's radio. After a
 * send fails, none goes while it plays until the phone is on another network: on a train a home
 * address answers no one. A start takes the newer of the two places, so nothing is lost meanwhile.
 */
internal class DownloadProgressSync(
    /** Where the player lives: [place] is read there. */
    private val scope: CoroutineScope,
    private val network: StateFlow<NetworkKey?>,
    /** Where the person is now; null once nothing is loaded. */
    private val place: () -> DownloadPosition?,
    private val keep: (DownloadPosition) -> Unit,
    private val send: suspend (DownloadPosition) -> Unit,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private var ticking: Job? = null

    @Volatile private var failed: Failure? = null

    /** What started playing: a stop Media3 tells of once another item is loaded is not that one's. */
    private var playingUri: String? = null

    /** It started or stopped playing: saves every 10 s from now on, or once now and sent. */
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

    /** The item was let go: nothing of it is saved any more. */
    fun stop() {
        ticking?.cancel()
        playingUri = null
    }

    /** Keeps the place on the phone, and with [send] sends it to Audiobookshelf. */
    fun save(send: Boolean) {
        val place = place() ?: return
        scope.launch(io) {
            try {
                keep(place)
            } catch (e: IOException) {
                // A full phone: the next save keeps it.
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

    /** A send failed while the phone was on [network]. */
    private class Failure(val network: NetworkKey?)

    companion object {
        const val SAVE_EVERY_MS = 10_000L

        /** Every 30th save, once in 5 minutes, also goes to Audiobookshelf. */
        const val SEND_EVERY = 30
    }
}
