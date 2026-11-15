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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** An album, playlist, artist, radio station, book, podcast or episode as a Home section keeps it on disk. */
@Serializable
data class HomeItem(
    val uri: String,
    /** The media type: "album", "artist", "playlist", "radio", "audiobook", "podcast" or "podcast_episode". */
    @SerialName("kind") val mediaType: String,
    val name: String,
    /** Artists for an album, owner for a playlist, authors for a book, the podcast for an episode. */
    val subtitle: String? = null,
    val image: MediaItemImage? = null,
    val favorite: Boolean = false,
    /** An album's first artist or a book's first author, for "Go to artist". */
    val artistUri: String? = null,
    /** A book's or episode's length in seconds. */
    val durationSec: Int? = null,
    /** Where the person stopped in a book or episode. */
    val resumeMs: Long? = null,
    val fullyPlayed: Boolean = false,
    /** An episode's podcast, for "Go to podcast". */
    val parentUri: String? = null,
    /** A smart playlist, whose cover Music Assistant draws as a grid of album covers. */
    val smart: Boolean = false,
    /** A playlist the server fills anew each time it plays. */
    val dynamic: Boolean = false,
    /** An artist without a picture: its albums' covers from the index, which it wears. */
    val covers: List<String> = emptyList(),
)

/**
 * One section's items. [fallback]: nothing has been played yet, so "Recently played" shows the
 * library's albums instead ("Your albums"). [speaker]: the speaker whose plays Played here shows;
 * null while the whole history stands in for one nothing has played on.
 */
@Serializable
data class SectionItems(
    val items: List<HomeItem> = emptyList(),
    val fallback: Boolean = false,
    val speaker: String? = null,
)

/** Loads up to [limit] items of one option; [pinned] are the pins, [previous] what it had before. */
fun interface SectionLoader {
    suspend fun load(option: SectionOption, limit: Int, pinned: List<String>, previous: SectionItems?): SectionItems
}

/**
 * Where each option's items come from: the library index where it can answer (instant, and kept
 * current by events), the server for what changes with every play.
 * Pins resolve from the index, then the server; a pin neither can answer keeps its previous
 * entry, so a pin never disappears while offline.
 */
class SectionSources(
    private val api: MaApi,
    private val library: () -> LibraryRepository,
    /** The speaker music plays on, by player id, which is its queue's id too. */
    private val speaker: () -> String? = { null },
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
        SectionOption.Speakers, SectionOption.Search -> SectionItems()
    }

    private fun index(rows: List<LibraryItemEntity>) = SectionItems(rows.map { it.toHomeItem() })

    /** Albums in random order, going on from [shown]: See all starts with those Home showed. */
    private suspend fun randomAlbums(limit: Int, shown: SectionItems?): SectionItems {
        val first = shown?.items.orEmpty()
        val uris = first.mapTo(HashSet()) { it.uri }
        val more = index(library().random(MediaType.Album, limit)).items.filterNot { it.uri in uris }
        return SectionItems((first + more).take(limit))
    }

    private suspend fun favorites(type: MediaType, limit: Int) = index(library().favorites(type, limit))

    private suspend fun recent(type: MediaType, limit: Int) =
        server(api.libraryItems(type, LibrarySort.RecentlyPlayed, limit = limit, playedOnly = true))

    /** What last played on the speaker in use; for one nothing has played on yet, the whole history. */
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

    /**
     * Books and episodes in progress, last played first. The server's list says nothing of where the
     * person stands, so each is looked up, and a book's row in the index takes what it says. One
     * that cannot be looked up keeps its row, else its previous entry.
     */
    private suspend fun continueListening(limit: Int, previous: SectionItems?): List<HomeItem> {
        val before = previous?.items.orEmpty().associateBy { it.uri }
        return api.started(limit).map { (listed, fresh) ->
            fresh?.let { library().rememberProgress(it) }
            fresh?.toHomeItem() ?: library().get(listed.uri)?.toHomeItem() ?: before[listed.uri] ?: listed.toHomeItem()
        }.filterNot { it.fullyPlayed }
    }

    /** Server items, each taking the index's row when it has one: recently played items carry no artists. */
    private suspend fun server(items: List<MediaItem>) =
        SectionItems(items.map { library().get(it.uri)?.toHomeItem() ?: it.toHomeItem() })

    private suspend fun pins(uris: List<String>, previous: SectionItems?): List<HomeItem> {
        val before = previous?.items.orEmpty().associateBy { it.uri }
        return uris.mapNotNull { uri ->
            library().get(uri)?.toHomeItem()
                ?: orNullOnFailure { api.itemByUri(uri) }?.toHomeItem()
                ?: before[uri]
        }
    }
}

/**
 * Options listed from the library index. While it syncs for the first time they are empty, so
 * Home draws them as loading rather than hiding them. "Recently played" counts, as its fallback is
 * the index's albums; Pinned does not, as the pins themselves say whether it is empty.
 */
val SectionOption.readsIndex: Boolean
    get() = this != SectionOption.Pinned && this !in FROM_SERVER

/** The kind an option lists from the library index (Recently played falls back to albums); null for the server's. */
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
            SectionSource.Pinned, SectionSource.Speakers, SectionSource.Search -> null
        }
    }

private val FROM_SERVER = setOf(
    SectionOption.Speakers,
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
