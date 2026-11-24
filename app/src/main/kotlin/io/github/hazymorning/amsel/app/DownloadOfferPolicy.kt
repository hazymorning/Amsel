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
 * Offered when a book or episode plays on This phone: streamed, it stops where the server is out of
 * reach, and a play shows interest without any counting. [itemAt] is null while the server cannot
 * say, and the item is then not marked offered.
 */
class DownloadOfferPolicy(
    playing: StateFlow<NowPlayingUi?>,
    inFront: StateFlow<Boolean>,
    downloads: StateFlow<Map<String, DownloadState>>,
    private val offered: suspend (uri: String) -> Boolean,
    private val setOffered: suspend (uri: String) -> Unit,
    private val itemAt: suspend (uri: String) -> MediaUi?,
) {
    val offers: Flow<MediaUi> = combine(playing, inFront, downloads) { now, front, states ->
        now?.takeIf { front && it.isPlaying && it.speaker.isPhone && it.spoken }?.key?.takeIf { it !in states }
    }.distinctUntilChanged().mapNotNull { uri ->
        if (uri == null || offered(uri)) return@mapNotNull null
        val item = itemAt(uri) ?: return@mapNotNull null
        setOffered(uri)
        item.takeIf { it.downloadable }
    }
}
