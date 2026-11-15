package io.github.hazymorning.amsel.data.library

import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.MediaType

internal fun MediaItem.toEntity(addedRank: Int, playedRank: Int?) = LibraryItemEntity(
    uri = uri,
    mediaType = mediaType,
    name = name,
    sortName = sortName ?: name.lowercase(),
    subtitle = subtitle,
    imageProxyId = cover?.proxyId,
    year = year,
    favorite = favorite == true,
    addedRank = addedRank,
    playedRank = playedRank,
    isEditable = isEditable,
    artistUri = artistUri,
    isSmart = isSmartPlaylist,
    smartPlugin = isPluginSmartPlaylist,
    isDynamic = isDynamic,
    compilation = isCompilation,
    durationSec = duration?.takeIf { mediaType == MediaType.Audiobook.api && it > 0 },
    resumeMs = resumePositionMs,
    fullyPlayed = fullyPlayed,
    narrators = narratorNames.ifBlank { null },
    altUri = providerMappings.firstOrNull()?.let { ItemUri(it.providerInstance, mediaType, it.itemId).toString() },
    albumArtists = artists.drop(1).takeIf { mediaType == MediaType.Album.api && it.isNotEmpty() }
        ?.joinToString("\n") { it.uri },
)

/** The row an item event leaves: the item as sent, over [existing] for what only the row knows. */
internal fun MediaItem.toEntityOver(existing: LibraryItemEntity?, addedRank: Int): LibraryItemEntity {
    val row = toEntity(addedRank, existing?.playedRank)
    // An item sent without its provider mappings keeps the row's smart-playlist mark.
    val unmapped = providerMappings.isEmpty()
    return row.copy(
        // 2.11 sends favorite = null in item events; keep the row's own state then.
        favorite = favorite ?: existing?.favorite ?: false,
        isSmart = isSmartPlaylist || (unmapped && existing?.isSmart == true),
        smartPlugin = isPluginSmartPlaylist || (unmapped && existing?.smartPlugin == true),
        isDynamic = isDynamic || (unmapped && existing?.isDynamic == true),
        // Progress in an item event is the last listener's, whoever that was: the row keeps the
        // person's own, and a new row waits for the next sync or this person's own play.
        resumeMs = existing?.resumeMs,
        fullyPlayed = existing?.fullyPlayed,
        // An artist's covers come from its albums, which its own event does not hold.
        albumCovers = existing?.albumCovers.takeIf { row.imageProxyId == null },
    )
}
