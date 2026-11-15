package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.data.downloads.DownloadState
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull

/**
 * Offers a book's or an episode's download so it is not forgotten: once for each, the first time
 * it plays on This phone with the app in front. That is all the rule there is. It plays here, so
 * it would stop where the server is out of reach, and playing it is the one sign of interest that
 * needs no counting. What is on the phone or on its way is never offered, nor what does not come
 * from Audiobookshelf. [itemAt] is the item at a uri as the server has it, or null while it cannot say.
 */
class DownloadOffer(
    playing: StateFlow<NowPlayingUi?>,
    inFront: StateFlow<Boolean>,
    downloads: StateFlow<Map<String, DownloadState>>,
    private val offered: suspend (uri: String) -> Boolean,
    private val setOffered: suspend (uri: String) -> Unit,
    private val itemAt: suspend (uri: String) -> MediaUi?,
) {
    /** Each item to offer, as its turn comes. */
    val offers: Flow<MediaUi> = combine(playing, inFront, downloads) { now, front, states ->
        now?.takeIf { front && it.isPlaying && it.speaker.isPhone && it.spoken }?.key?.takeIf { it !in states }
    }.distinctUntilChanged().mapNotNull { uri ->
        if (uri == null || offered(uri)) return@mapNotNull null
        val item = itemAt(uri) ?: return@mapNotNull null
        setOffered(uri)
        item.takeIf { it.downloadable }
    }
}
