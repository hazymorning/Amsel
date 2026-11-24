package io.github.hazymorning.amsel.ui.screens.search

import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.SearchResults
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi

/**
 * The server sends no stand-in for an artist without a picture (docs/API-NOTES.md, "Images"): use the
 * index's covers for it ([held], by uri), else those of its albums and songs in [raw].
 */
internal fun UiMapper.listed(item: MediaItem, raw: SearchResults, held: Map<String, ArtRef>): MediaUi {
    val shown = media(item)
    if (shown.art != null || shown.kind != MediaKind.Artist) return shown
    val by = (raw.albums + raw.tracks).filter { found -> found.artists.any { it.uri == item.uri } }
    return shown.copy(art = held[item.uri] ?: albumsArt(by.mapNotNull { (it.album?.cover ?: it.cover)?.proxyId }))
}
