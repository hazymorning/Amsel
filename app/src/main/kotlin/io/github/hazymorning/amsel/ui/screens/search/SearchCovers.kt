package io.github.hazymorning.amsel.ui.screens.search

import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.SearchResults
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi

/**
 * [item] of the answer [raw] as Search lists it. An artist without a picture wears the covers the
 * library index made it ([held], by uri), else those of the answer's own albums and songs by it,
 * which a guest the index does not hold has.
 */
internal fun UiMapper.listed(item: MediaItem, raw: SearchResults, held: Map<String, ArtRef>): MediaUi {
    val shown = media(item)
    if (shown.art != null || shown.kind != MediaKind.Artist) return shown
    val by = (raw.albums + raw.tracks).filter { found -> found.artists.any { it.uri == item.uri } }
    return shown.copy(art = held[item.uri] ?: albumsArt(by.mapNotNull { (it.album?.cover ?: it.cover)?.proxyId }))
}
