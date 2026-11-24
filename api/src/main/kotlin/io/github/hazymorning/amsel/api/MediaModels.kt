package io.github.hazymorning.amsel.api

import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class MediaItemImage(val type: String = "thumb", @SerialName("proxy_id") val proxyId: String? = null)

@Serializable
public data class MediaItemMetadata(
    @Serializable(with = ImageListSerializer::class) val images: List<MediaItemImage>? = null,
    /** ISO 8601, e.g. `2026-10-03T00:00:00+00:00`. */
    @SerialName("release_date") val releaseDate: String? = null,
    /** On `get` and on the queue item, not in summary listings. */
    @Serializable(with = ChapterListSerializer::class) val chapters: List<Chapter>? = null,
)

/**
 * Seconds on the item's own timeline. [position] is what `start_item` names: 1-based for most
 * books, 0-based for a single file with embedded chapters.
 */
@Serializable
public data class Chapter(
    @Serializable(with = LenientIntSerializer::class) val position: Int? = null,
    val name: String = "",
    val start: Double = 0.0,
    val end: Double? = null,
)

/** Some answers nest the whole item instead, with [metadata] and no [image]. */
@Serializable
public data class ItemMapping(
    val name: String,
    val uri: String,
    val image: MediaItemImage? = null,
    @Serializable(with = MetadataOrNullSerializer::class) val metadata: MediaItemMetadata? = null,
) {
    public val cover: MediaItemImage? get() = image ?: metadata?.images.coverOf()
}

@Serializable
public data class ProviderMapping(
    @SerialName("item_id") val itemId: String,
    @SerialName("provider_domain") val providerDomain: String,
    @SerialName("provider_instance") val providerInstance: String = providerDomain,
)

/** Summary items in library listings omit null fields, so every optional field has a default. */
@Serializable
public data class MediaItem(
    @SerialName("item_id") val itemId: String,
    val provider: String,
    val name: String,
    val uri: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("sort_name") val sortName: String? = null,
    @Serializable(with = MetadataOrNullSerializer::class) val metadata: MediaItemMetadata? = null,
    /** 2.10: household favourite. 2.11: true like, false dislike, null none (for the caller). */
    val favorite: Boolean? = null,
    @Serializable(with = LenientIntSerializer::class) val year: Int? = null,
    @SerialName("album_type") val albumType: String? = null,
    @Serializable(with = MappingListSerializer::class) val artists: List<ItemMapping> = emptyList(),
    @Serializable(with = MappingOrNullSerializer::class) val album: ItemMapping? = null,
    /** Seconds. */
    @Serializable(with = LenientIntSerializer::class) val duration: Int? = null,
    @SerialName("track_number") @Serializable(with = LenientIntSerializer::class) val trackNumber: Int? = null,
    /** 1-based in a playlist; for an episode, Audiobookshelf sends the publish time in epoch ms. */
    @Serializable(with = LenientLongSerializer::class) val position: Long? = null,
    val owner: String? = null,
    @SerialName("is_editable") val isEditable: Boolean = false,
    /** A playlist the server fills anew on each play, such as its "Infinite Mix". */
    @SerialName("is_dynamic") val isDynamic: Boolean = false,
    /** Only in ItemMapping-shaped answers, such as recently played items. */
    val image: MediaItemImage? = null,
    @SerialName("provider_mappings") @Serializable(with = ProviderMappingListSerializer::class)
    val providerMappings: List<ProviderMapping> = emptyList(),
    val publisher: String? = null,
    @Serializable(with = CreditListSerializer::class) val authors: List<Credit> = emptyList(),
    @Serializable(with = CreditListSerializer::class) val narrators: List<Credit> = emptyList(),
    /** The calling user's; omitted from summary items while null. */
    @SerialName(
        "resume_position_ms",
    ) @Serializable(with = LenientLongSerializer::class) val resumePositionMs: Long? = null,
    @SerialName("fully_played") val fullyPlayed: Boolean? = null,
    @Serializable(with = MappingOrNullSerializer::class) val podcast: ItemMapping? = null,
) {
    public val cover: MediaItemImage?
        get() = metadata?.images.coverOf() ?: image ?: album?.cover ?: podcast?.cover

    public val artistNames: String get() = artists.joinToString(", ") { it.name }

    public val authorNames: String get() = authors.joinToString(", ") { it.name }

    public val narratorNames: String get() = narrators.joinToString(", ") { it.name }

    public val artistUri: String? get() = artists.firstOrNull()?.uri ?: authors.firstNotNullOfOrNull { it.uri }

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

    /** Without the stale chapters the server merges in ([currentChapters]). */
    public val chapters: List<Chapter> get() = currentChapters(metadata?.chapters.orEmpty())

    public val isLibraryItem: Boolean get() = provider == LIBRARY_PROVIDER

    /**
     * The plugin's, or `builtin` and not editable: playlists made in Music Assistant are `builtin`
     * too, but editable. See docs/API-NOTES.md, "Telling smart playlists apart".
     */
    public val isSmartPlaylist: Boolean
        get() = isPluginSmartPlaylist ||
            (
                mediaType == MediaType.Playlist.api && !isEditable &&
                    providerMappings.any { it.providerDomain == BUILTIN_PROVIDER }
                )

    public val isPluginSmartPlaylist: Boolean
        get() = mediaType == MediaType.Playlist.api &&
            providerMappings.any { it.providerDomain == SMART_PLAYLIST_PROVIDER }
}

/**
 * `<provider>://<media_type>/<item_id>`. Only the first slash after the scheme splits: provider ids
 * keep their own slashes and spaces.
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

public const val BUILTIN_PROVIDER: String = "builtin"

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

    public val plural: String get() = "${api}s"

    public companion object {
        public fun isSpokenWord(mediaType: String?): Boolean =
            mediaType == Audiobook.api || mediaType == PodcastEpisode.api
    }
}

@Serializable
public data class SearchResults(
    // An item that does not decode is left out, not the whole search.
    @Serializable(with = MediaItemListSerializer::class) val artists: List<MediaItem> = emptyList(),
    @Serializable(with = MediaItemListSerializer::class) val albums: List<MediaItem> = emptyList(),
    @Serializable(with = MediaItemListSerializer::class) val tracks: List<MediaItem> = emptyList(),
    @Serializable(with = MediaItemListSerializer::class) val playlists: List<MediaItem> = emptyList(),
    /** Radio stations; the server names this list in the singular. */
    @Serializable(with = MediaItemListSerializer::class) val radio: List<MediaItem> = emptyList(),
    @Serializable(with = MediaItemListSerializer::class) val audiobooks: List<MediaItem> = emptyList(),
    @Serializable(with = MediaItemListSerializer::class) val podcasts: List<MediaItem> = emptyList(),
)

@Serializable
public data class FavoriteUpdate(val uri: String, val favorite: Boolean? = null)

public enum class LibrarySort { AToZ, RecentlyAdded, RecentlyPlayed }

/** Fraction played (0 to 1) and seconds left; null before a start, after the end, or without a duration. */
public fun listening(durationSec: Int?, resumeMs: Long?, fullyPlayed: Boolean): Pair<Float, Int>? {
    val total = durationSec?.takeIf { it > 0 } ?: return null
    val at = resumeMs?.takeIf { it > 0 && !fullyPlayed } ?: return null
    val seconds = (at / 1000).toInt().coerceAtMost(total)
    return seconds.toFloat() / total to total - seconds
}

/**
 * The server merges an edited chapter list into the stored one: the last chapter at each position
 * is current, and one overlapping the chapter before it is stale. Lists with an unnumbered chapter,
 * rising positions or one position throughout stay as they are. See docs/API-NOTES.md, "Audiobooks and podcasts".
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
 * The server starts at the first chapter with that position in its whole list, stale ones included,
 * so this picks a position whose first chapter starts where [chapter] does, or null.
 * See docs/API-NOTES.md, "start_item".
 */
public fun MediaItem.startItemFor(chapter: Chapter): String? {
    val firsts = metadata?.chapters.orEmpty().filter { it.position != null }.distinctBy { it.position }
    val leadsThere = { first: Chapter -> abs(first.start - chapter.start) < SAME_START_S }
    val own = firsts.firstOrNull { it.position == chapter.position }?.takeIf(leadsThere)
    return (own ?: firsts.firstOrNull(leadsThere))?.position?.toString()
}

/** Sources round chapter starts differently, but well within this. */
private const val SAME_START_S = 0.5

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
