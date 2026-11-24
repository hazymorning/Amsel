package io.github.hazymorning.amsel.data.home

import io.github.hazymorning.amsel.api.LibrarySort
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.MediaItemImage
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.itemByUri
import io.github.hazymorning.amsel.api.latestEpisodes
import io.github.hazymorning.amsel.api.libraryItems
import io.github.hazymorning.amsel.api.mostPlayed
import io.github.hazymorning.amsel.api.orNullOnFailure
import io.github.hazymorning.amsel.api.recentlyPlayed
import io.github.hazymorning.amsel.api.started
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.data.library.LibraryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HomeItem(
    val uri: String,
    @SerialName("kind") val mediaType: String,
    val name: String,
    val subtitle: String? = null,
    val image: MediaItemImage? = null,
    val favorite: Boolean = false,
    val artistUri: String? = null,
    val durationSec: Int? = null,
    val resumeMs: Long? = null,
    val fullyPlayed: Boolean = false,
    /** An episode's podcast. */
    val parentUri: String? = null,
    /** A smart playlist, whose cover Music Assistant draws as a grid of album covers. */
    val smart: Boolean = false,
    /** A playlist the server fills anew each time it plays. */
    val dynamic: Boolean = false,
    /** For an artist without a picture: its albums' covers from the index. */
    val covers: List<String> = emptyList(),
)

/**
 * [fallback]: nothing has been played yet, so Recently played shows the library's albums ("Your albums").
 * [speaker]: whose plays Played here shows; null when it shows the whole history, as nothing played there.
 */
@Serializable
data class SectionItems(
    val items: List<HomeItem> = emptyList(),
    val fallback: Boolean = false,
    val speaker: String? = null,
)

fun interface SectionLoader {
    suspend fun load(option: SectionOption, limit: Int, pinned: List<String>, previous: SectionItems?): SectionItems
}

// The library index where it can answer, the server for what changes with every play.
// See docs/API-NOTES.md, "Home sections".
class ServerSectionLoader(
    private val api: MaApi,
    private val library: () -> LibraryRepository,
    // A player id, which is also its queue's id.
    private val speaker: () -> String?,
) : SectionLoader {
    override suspend fun load(
        option: SectionOption,
        limit: Int,
        pinned: List<String>,
        previous: SectionItems?,
    ): SectionItems = when (option) {
        SectionOption.Pinned -> SectionItems(pins(pinned.take(limit), previous))
        SectionOption.Everything -> recentOrAlbums(limit)
        SectionOption.PlayedHere -> playedHere(limit)
        SectionOption.ContinueListening -> SectionItems(continueListening(limit, previous))
        SectionOption.SmartPlaylists -> index(library().smartPlaylists(limit))
        SectionOption.YourPlaylists -> index(library().ownPlaylists(limit))
        SectionOption.FavoritePlaylists -> favorites(MediaType.Playlist, limit)
        SectionOption.RecentPlaylists -> recent(MediaType.Playlist, limit)
        SectionOption.NewAlbums -> index(library().firstRows(MediaType.Album, LibrarySort.RecentlyAdded, limit))
        SectionOption.RecentAlbums -> recent(MediaType.Album, limit)
        SectionOption.FavoriteAlbums -> favorites(MediaType.Album, limit)
        SectionOption.RandomAlbums -> randomAlbums(limit, previous)
        SectionOption.TopArtists -> server(api.mostPlayed(MediaType.Artist, limit))
        SectionOption.FavoriteArtists -> favorites(MediaType.Artist, limit)
        SectionOption.RecentArtists -> recent(MediaType.Artist, limit)
        SectionOption.AllStations -> index(library().firstRows(MediaType.Radio, LibrarySort.AToZ, limit))
        SectionOption.FavoriteStations -> favorites(MediaType.Radio, limit)
        SectionOption.RecentStations -> recent(MediaType.Radio, limit)
        SectionOption.NewAudiobooks -> index(library().firstRows(MediaType.Audiobook, LibrarySort.RecentlyAdded, limit))
        SectionOption.NewEpisodes -> server(api.latestEpisodes().take(limit))
        SectionOption.YourPodcasts -> index(library().firstRows(MediaType.Podcast, LibrarySort.RecentlyPlayed, limit))
        SectionOption.Search -> SectionItems()
    }

    private fun index(rows: List<LibraryItemEntity>) = SectionItems(rows.map { it.toHomeItem() })

    // Starts with [shown], so See all begins with the albums Home showed.
    private suspend fun randomAlbums(limit: Int, shown: SectionItems?): SectionItems {
        val first = shown?.items.orEmpty()
        val uris = first.mapTo(HashSet()) { it.uri }
        val more = index(library().random(MediaType.Album, limit)).items.filterNot { it.uri in uris }
        return SectionItems((first + more).take(limit))
    }

    private suspend fun favorites(type: MediaType, limit: Int) = index(library().favorites(type, limit))

    private suspend fun recent(type: MediaType, limit: Int) =
        server(api.libraryItems(type, LibrarySort.RecentlyPlayed, limit = limit, playedOnly = true))

    private suspend fun playedHere(limit: Int): SectionItems {
        val here = speaker() ?: return recentOrAlbums(limit)
        val played = server(api.recentlyPlayed(limit, queueId = here))
        return if (played.items.isEmpty()) recentOrAlbums(limit) else played.copy(speaker = here)
    }

    private suspend fun recentOrAlbums(limit: Int): SectionItems {
        val recent = server(api.recentlyPlayed(limit))
        if (recent.items.isNotEmpty()) return recent
        return index(library().firstRows(MediaType.Album, LibrarySort.AToZ, limit)).copy(fallback = true)
    }

    // `in_progress_items` carries no position, so each item is looked up, and a book's row takes its progress; one
    // that cannot be looked up keeps its row, else its previous entry.
    // See docs/API-NOTES.md, "Audiobooks and podcasts".
    private suspend fun continueListening(limit: Int, previous: SectionItems?): List<HomeItem> {
        val before = previous?.items.orEmpty().associateBy { it.uri }
        val started = api.started(limit)
        val rows = library().rows(started.filter { it.fresh == null }.map { it.listed.uri })
        return started.map { (listed, fresh) ->
            fresh?.let { library().rememberProgress(it) }
            fresh?.toHomeItem() ?: rows[listed.uri]?.toHomeItem() ?: before[listed.uri] ?: listed.toHomeItem()
        }.filterNot { it.fullyPlayed }
    }

    // The index's row where it has one: recently played items carry no artists or owner.
    private suspend fun server(items: List<MediaItem>): SectionItems {
        val rows = library().rows(items.map { it.uri })
        return SectionItems(items.map { rows[it.uri]?.toHomeItem() ?: it.toHomeItem() })
    }

    // A pin neither the index nor the server answers keeps its previous entry, so it never disappears offline.
    private suspend fun pins(uris: List<String>, previous: SectionItems?): List<HomeItem> = coroutineScope {
        val before = previous?.items.orEmpty().associateBy { it.uri }
        val rows = library().rows(uris)
        uris.map { uri ->
            async {
                rows[uri]?.toHomeItem()
                    ?: orNullOnFailure { api.itemByUri(uri) }?.toHomeItem()
                    ?: before[uri]
            }
        }.awaitAll().filterNotNull()
    }
}

/**
 * Options listed from the library index, shown as loading during its first sync rather than hidden. Recently
 * played counts, as its fallback is the index's albums; Pinned does not, as the pins say whether it is empty.
 */
val SectionOption.readsIndex: Boolean
    get() = this != SectionOption.Pinned && this !in FROM_SERVER

val SectionOption.indexKind: MediaType?
    get() = if (!readsIndex) {
        null
    } else {
        when (source) {
            SectionSource.Playlists -> MediaType.Playlist
            SectionSource.Albums, SectionSource.RecentlyPlayed -> MediaType.Album
            SectionSource.Artists -> MediaType.Artist
            SectionSource.Radio -> MediaType.Radio
            SectionSource.Audiobooks -> MediaType.Audiobook
            SectionSource.Podcasts -> MediaType.Podcast
            SectionSource.Pinned, SectionSource.Search -> null
        }
    }

private val FROM_SERVER = setOf(
    SectionOption.Search,
    SectionOption.ContinueListening,
    SectionOption.NewEpisodes,
    SectionOption.RecentPlaylists,
    SectionOption.RecentAlbums,
    SectionOption.TopArtists,
    SectionOption.RecentArtists,
    SectionOption.RecentStations,
)

internal fun LibraryItemEntity.toHomeItem() = HomeItem(
    uri = uri,
    mediaType = mediaType,
    name = name,
    subtitle = subtitle,
    image = imageProxyId?.let { MediaItemImage(proxyId = it) },
    favorite = favorite,
    artistUri = artistUri,
    durationSec = durationSec,
    resumeMs = resumeMs,
    fullyPlayed = fullyPlayed == true,
    smart = isSmart,
    dynamic = isDynamic,
    covers = albumCovers?.lines().orEmpty(),
)

internal fun MediaItem.toHomeItem() = HomeItem(
    uri = uri,
    mediaType = mediaType,
    name = name,
    subtitle = subtitle,
    image = cover,
    favorite = favorite == true,
    artistUri = artistUri,
    durationSec = duration?.takeIf { isSpokenWord && it > 0 },
    resumeMs = resumePositionMs,
    fullyPlayed = fullyPlayed == true,
    parentUri = podcast?.uri,
    smart = isSmartPlaylist,
    dynamic = isDynamic,
)
