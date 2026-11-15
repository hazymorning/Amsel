package io.github.hazymorning.amsel.api

import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `proxy_id` is how artwork is fetched. */
@Serializable
public data class MediaItemImage(val type: String = "thumb", @SerialName("proxy_id") val proxyId: String? = null)

@Serializable
public data class MediaItemMetadata(
    @Serializable(with = ImageList::class) val images: List<MediaItemImage>? = null,
    /** ISO 8601, e.g. `2026-10-03T00:00:00+00:00`: an episode's publish date. */
    @SerialName("release_date") val releaseDate: String? = null,
    /** On `get` and on the queue item, not in summary listings. */
    @Serializable(with = ChapterList::class) val chapters: List<Chapter>? = null,
)

/**
 * A chapter of a book or episode, in seconds on the item's own timeline. [position] is what
 * `play_media(start_item)` names: 1-based for most books, 0-based for a single file with
 * embedded chapters. [end] may be missing.
 */
@Serializable
public data class Chapter(
    @Serializable(with = LenientInt::class) val position: Int? = null,
    val name: String = "",
    val start: Double = 0.0,
    val end: Double? = null,
)

/**
 * A nested reference to another item (track → album, album → artists). Some answers nest the whole
 * item instead, which brings [metadata] and no [image].
 */
@Serializable
public data class ItemMapping(
    val name: String,
    val uri: String,
    val image: MediaItemImage? = null,
    @Serializable(with = MetadataOrNull::class) val metadata: MediaItemMetadata? = null,
) {
    public val cover: MediaItemImage? get() = image ?: metadata?.images.coverOf()
}

/** Where an item comes from: one music service (or MA's own `builtin` provider) and its id there. */
@Serializable
public data class ProviderMapping(
    @SerialName("item_id") val itemId: String,
    @SerialName("provider_domain") val providerDomain: String,
    @SerialName("provider_instance") val providerInstance: String = providerDomain,
)

/**
 * Album, artist, track, playlist, radio station, audiobook, podcast or episode: one class for all,
 * told apart by [mediaType]. Library listings return slim items whose null fields are omitted, so
 * everything optional has a default.
 */
@Serializable
public data class MediaItem(
    @SerialName("item_id") val itemId: String,
    val provider: String,
    val name: String,
    val uri: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("sort_name") val sortName: String? = null,
    @Serializable(with = MetadataOrNull::class) val metadata: MediaItemMetadata? = null,
    /** 2.10: household favourite. 2.11: true like, false dislike, null none (for the caller). */
    val favorite: Boolean? = null,
    @Serializable(with = LenientInt::class) val year: Int? = null,
    /** An album's kind: "album", "single", "compilation", ... */
    @SerialName("album_type") val albumType: String? = null,
    @Serializable(with = MappingList::class) val artists: List<ItemMapping> = emptyList(),
    @Serializable(with = MappingOrNull::class) val album: ItemMapping? = null,
    /** Seconds. */
    @Serializable(with = LenientInt::class) val duration: Int? = null,
    @SerialName("track_number") @Serializable(with = LenientInt::class) val trackNumber: Int? = null,
    /**
     * 1-based position inside a playlist; an episode's place in its podcast, which Audiobookshelf
     * sets to the publish time in epoch milliseconds.
     */
    @Serializable(with = LenientLong::class) val position: Long? = null,
    val owner: String? = null,
    @SerialName("is_editable") val isEditable: Boolean = false,
    /** A playlist the server fills anew each time it plays, its own "Infinite Mix" among them. */
    @SerialName("is_dynamic") val isDynamic: Boolean = false,
    /** ItemMapping-style single image (recently played items). */
    val image: MediaItemImage? = null,
    @SerialName("provider_mappings") @Serializable(with = ProviderMappingList::class)
    val providerMappings: List<ProviderMapping> = emptyList(),
    /** A book's or podcast's publisher. */
    val publisher: String? = null,
    @Serializable(with = CreditList::class) val authors: List<Credit> = emptyList(),
    @Serializable(with = CreditList::class) val narrators: List<Credit> = emptyList(),
    /** A book's or episode's progress for the calling user; omitted from summary items while unknown. */
    @SerialName("resume_position_ms") @Serializable(with = LenientLong::class) val resumePositionMs: Long? = null,
    @SerialName("fully_played") val fullyPlayed: Boolean? = null,
    /** An episode's podcast. */
    @Serializable(with = MappingOrNull::class) val podcast: ItemMapping? = null,
) {
    /** The cover: the item's own picture ([coverOf]), else the album's or the podcast's. */
    public val cover: MediaItemImage?
        get() = metadata?.images.coverOf() ?: image ?: album?.cover ?: podcast?.cover

    public val artistNames: String get() = artists.joinToString(", ") { it.name }

    public val authorNames: String get() = authors.joinToString(", ") { it.name }

    public val narratorNames: String get() = narrators.joinToString(", ") { it.name }

    /** Where the item's artist link leads: its first artist, else a book's first author. */
    public val artistUri: String? get() = artists.firstOrNull()?.uri ?: authors.firstNotNullOfOrNull { it.uri }

    /**
     * The line an item is listed with: a book's authors, an episode's podcast, a podcast's
     * publisher, a playlist's owner, otherwise the artists.
     */
    public val subtitle: String?
        get() = when (mediaType) {
            MediaType.Audiobook.api -> authorNames
            MediaType.PodcastEpisode.api -> podcast?.name
            MediaType.Podcast.api -> publisher
            MediaType.Playlist.api -> owner
            else -> artistNames
        }?.ifBlank { null }

    public val isSpokenWord: Boolean get() = MediaType.isSpokenWord(mediaType)

    public val isCompilation: Boolean get() = albumType == "compilation"

    /** The book's current chapters; see [currentChapters] for what the server may add to them. */
    public val chapters: List<Chapter> get() = currentChapters(metadata?.chapters.orEmpty())

    public val isLibraryItem: Boolean get() = provider == LIBRARY_PROVIDER

    /**
     * A smart playlist: one the Smart Playlists plugin made ([isPluginSmartPlaylist]), or one of
     * Music Assistant's own ("Recently added tracks", "Random Album", ...), made by the `builtin`
     * provider and not editable. Playlists a user makes in Music Assistant come from the same
     * provider but are editable.
     */
    public val isSmartPlaylist: Boolean
        get() = isPluginSmartPlaylist ||
            (
                mediaType == MediaType.Playlist.api && !isEditable &&
                    providerMappings.any { it.providerDomain == BUILTIN_PROVIDER }
                )

    /** Made from the user's own rules by the Smart Playlists plugin; editable, owned by "Smart Playlist". */
    public val isPluginSmartPlaylist: Boolean
        get() = mediaType == MediaType.Playlist.api &&
            providerMappings.any { it.providerDomain == SMART_PLAYLIST_PROVIDER }
}

/**
 * An item's uri, `<provider>://<media_type>/<item_id>`. Only the first slash after the scheme
 * splits: provider ids keep their own slashes and spaces.
 */
public data class ItemUri(val provider: String, val mediaType: String, val itemId: String) {
    val isLibraryItem: Boolean get() = provider == LIBRARY_PROVIDER

    override fun toString(): String = "$provider://$mediaType/$itemId"

    public companion object {
        public fun parse(uri: String): ItemUri? {
            val provider = uri.substringBefore("://", "")
            val rest = uri.substringAfter("://", "")
            val mediaType = rest.substringBefore('/', "")
            val itemId = rest.substringAfter('/', "")
            if (provider.isEmpty() || mediaType.isEmpty() || itemId.isEmpty()) return null
            return ItemUri(provider, mediaType, itemId)
        }
    }
}

private const val LIBRARY_PROVIDER = "library"

/** Music Assistant's own provider: smart playlists, playlists made in MA, added radio URLs. */
public const val BUILTIN_PROVIDER: String = "builtin"

/** The Smart Playlists plugin (2.10 and later): rule-based playlists it adds to the library. */
public const val SMART_PLAYLIST_PROVIDER: String = "smart_playlist"

public enum class MediaType(public val api: String) {
    Album("album"),
    Artist("artist"),
    Track("track"),
    Playlist("playlist"),
    Radio("radio"),
    Audiobook("audiobook"),
    Podcast("podcast"),

    /** Never a library kind of its own: episodes are listed through their podcast. */
    PodcastEpisode("podcast_episode"),
    ;

    /** The listing path segment: `music/albums/...`. */
    public val plural: String get() = "${api}s"

    public companion object {
        /** A book or a podcast episode: played in place, resumed by the server, with chapters. */
        public fun isSpokenWord(mediaType: String?): Boolean =
            mediaType == Audiobook.api || mediaType == PodcastEpisode.api
    }
}

@Serializable
public data class SearchResults(
    // An item that does not decode is left out, not the whole search.
    @Serializable(with = MediaItemList::class) val artists: List<MediaItem> = emptyList(),
    @Serializable(with = MediaItemList::class) val albums: List<MediaItem> = emptyList(),
    @Serializable(with = MediaItemList::class) val tracks: List<MediaItem> = emptyList(),
    @Serializable(with = MediaItemList::class) val playlists: List<MediaItem> = emptyList(),
    /** Radio stations; the server names this list in the singular. */
    @Serializable(with = MediaItemList::class) val radio: List<MediaItem> = emptyList(),
    @Serializable(with = MediaItemList::class) val audiobooks: List<MediaItem> = emptyList(),
    @Serializable(with = MediaItemList::class) val podcasts: List<MediaItem> = emptyList(),
)

/** `favorite_updated` payload (78+). */
@Serializable
public data class FavoriteUpdate(val uri: String, val favorite: Boolean? = null)

/** The Library sort button's three orders. */
public enum class LibrarySort { AToZ, RecentlyAdded, RecentlyPlayed }

/**
 * Where the person stands in a book or episode of [durationSec]: how far through (0 to 1) and the
 * seconds left. Null before they start it, once they finish it, or without a length.
 */
public fun listening(durationSec: Int?, resumeMs: Long?, fullyPlayed: Boolean): Pair<Float, Int>? {
    val total = durationSec?.takeIf { it > 0 } ?: return null
    val at = resumeMs?.takeIf { it > 0 && !fullyPlayed } ?: return null
    val seconds = (at / 1000).toInt().coerceAtMost(total)
    return seconds.toFloat() / total to total - seconds
}

/**
 * The server merges a changed chapter list into the stored one instead of replacing it (an
 * Audiobookshelf chapter edit): the stored chapters, then those of the new list it did not hold.
 * So the last chapter at each position is the current one, and a stored chapter that overlaps the
 * one before it belongs to a longer list that was replaced. Lists without numbered, rising
 * positions are taken as they come.
 */
internal fun currentChapters(all: List<Chapter>): List<Chapter> {
    val positions = all.map { it.position ?: return all }
    if (positions.zipWithNext().all { (a, b) -> b > a } || positions.distinct().size == 1) return all
    val kept = mutableListOf<Chapter>()
    for (chapter in all.associateBy { it.position }.values.sortedBy { it.position }) {
        val end = kept.lastOrNull()?.end
        if (end == null || chapter.start >= end - CHAPTER_OVERLAP_S) kept += chapter
    }
    return kept
}

/** Chapter ends and starts from different sources differ by rounding. */
private const val CHAPTER_OVERLAP_S = 1.0

/**
 * What `start_item` starts this book at [chapter] with, or null when nothing does. The server
 * starts at the first chapter with that position in its whole list, stale ones included
 * ([currentChapters]): after a merge a chapter's own position can lead to another (live on 2.10.9,
 * chapter 25 of a book with chapters from files and from Audiobookshelf started chapter 26), so
 * then any position whose first chapter starts where [chapter] does.
 */
public fun MediaItem.startItemFor(chapter: Chapter): String? {
    val firsts = metadata?.chapters.orEmpty().filter { it.position != null }.distinctBy { it.position }
    val leadsThere = { first: Chapter -> abs(first.start - chapter.start) < SAME_START_S }
    val own = firsts.firstOrNull { it.position == chapter.position }?.takeIf(leadsThere)
    return (own ?: firsts.firstOrNull(leadsThere))?.position?.toString()
}

/** Two chapters start at the same place: their sources round differently, but well within this. */
private const val SAME_START_S = 0.5

/** A server background task, as a command's answer and `tasks_updated` give it. */
@Serializable
internal data class BackgroundTask(
    val id: String,
    val status: String = "",
    @SerialName("last_error") val lastError: String? = null,
) {
    val finished: Boolean get() = status in FINISHED_TASK

    val failed: Boolean get() = status == "failed" || status == "cancelled"
}

private val FINISHED_TASK = setOf("success", "partial_success", "failed", "cancelled")
