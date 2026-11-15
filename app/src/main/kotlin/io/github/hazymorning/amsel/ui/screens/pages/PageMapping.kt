package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaUi

/** An album page's header from the library index: the same fields the server fills, so nothing jumps. */
internal fun UiMapper.albumHeader(row: LibraryItemEntity) = AlbumUi(
    key = row.uri,
    title = row.name,
    art = artFromProxyId(row.imageProxyId),
    artist = row.subtitle,
    artistKey = row.artistUri,
    year = row.year,
    favorite = row.favorite,
    compilation = row.compilation || row.subtitle == null,
)

internal fun UiMapper.albumHeader(item: MediaItem) = AlbumUi(
    key = item.uri,
    title = item.name,
    art = art(item.cover),
    artist = item.artistNames.ifBlank { null },
    artistKey = item.artists.firstOrNull()?.uri,
    year = item.year,
    favorite = item.favorite == true,
    compilation = item.isCompilation || item.artistNames.isBlank(),
)

internal fun UiMapper.playlistHeader(row: LibraryItemEntity) = PlaylistUi(
    key = row.uri,
    title = row.name,
    art = artFromProxyId(row.imageProxyId),
    owner = row.subtitle,
    favorite = row.favorite,
    editable = row.isEditable,
    dynamic = row.isDynamic,
)

internal fun UiMapper.playlistHeader(item: MediaItem) = PlaylistUi(
    key = item.uri,
    title = item.name,
    art = art(item.cover),
    owner = item.owner?.ifBlank { null },
    favorite = item.favorite == true,
    editable = item.isEditable,
    dynamic = item.isDynamic,
)

/**
 * Albums as a page lists them: newest first (undated last, otherwise the server's order), each
 * once, with the year as the secondary line; [except] leaves out the album the page is about.
 */
internal fun UiMapper.newestFirst(albums: List<MediaItem>, except: String? = null): List<MediaUi> = albums
    .filter { it.uri != except }
    .distinctBy { it.uri }
    .sortedByDescending { it.year ?: Int.MIN_VALUE }
    .map { media(it).copy(subtitle = it.year?.toString()) }

/**
 * What the page of an artist with no picture, and none in the index, shows for it: the covers of
 * the [albums] it lists, newest first and compilations last, then of its popular [songs]' albums
 * (a song's own picture is often its file's copy of the album's, so it would repeat one album).
 */
internal fun UiMapper.pageCovers(albums: List<MediaItem>, songs: List<MediaItem>): ArtRef? = albumsArt(
    albums.sortedWith(compareBy<MediaItem> { it.isCompilation }.thenByDescending { it.year ?: Int.MIN_VALUE })
        .mapNotNull { it.cover?.proxyId } + songs.mapNotNull { (it.album?.cover ?: it.cover)?.proxyId },
)

/** The library index's albums in the order [newestFirst] gives the server's: newest first, then by name. */
internal fun UiMapper.newestFirstRows(rows: List<LibraryItemEntity>): List<MediaUi> = rows
    .sortedBy { it.sortName.lowercase() }
    .sortedByDescending { it.year ?: Int.MIN_VALUE }
    .map { media(it).copy(subtitle = it.year?.toString()) }
