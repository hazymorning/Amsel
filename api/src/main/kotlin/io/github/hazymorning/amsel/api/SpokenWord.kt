package io.github.hazymorning.amsel.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Episodes a podcast page asks for at a time. */
public const val EPISODE_PAGE: Int = 50

/**
 * `playlog_updated` and `media_item_played`: where one user (all users when [userId] is null)
 * now stands in an item. Every socket receives every user's.
 */
@Serializable
public data class PlayProgress(
    val uri: String,
    @SerialName("media_type") val mediaType: String = "",
    @SerialName("fully_played") val fullyPlayed: Boolean? = null,
    @SerialName("seconds_played") @Serializable(with = LenientLong::class) val secondsPlayed: Long? = null,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("userid") val userId: String? = null,
)

/**
 * Up to [limit] of a podcast's episodes from [offset], newest first, each with the caller's
 * progress. Before schema 93 the server cannot page: the whole podcast streams in partials, oldest
 * first, so everything from [offset] comes at once, sorted here.
 */
public suspend fun MaApi.podcastEpisodes(
    itemId: String,
    provider: String,
    offset: Int = 0,
    limit: Int = EPISODE_PAGE,
): ItemPage<MediaItem> {
    if (schema() < Schema.EPISODE_PAGING) {
        val all = list("music/podcasts/podcast_episodes", MediaItem.serializer(), STREAM_TIMEOUT) {
            ids(itemId, provider)
        }
        return all.sortedByDescending { it.position ?: Long.MIN_VALUE }.pageFrom(offset)
    }
    return page("music/podcasts/podcast_episodes", MediaItem.serializer(), offset, limit, STREAM_TIMEOUT) {
        ids(itemId, provider)
        put("sort_field", "position")
        put("sort_direction", "desc")
    }
}

/**
 * Books and episodes the caller has started and not finished, last played first. Item mappings
 * without position or duration; an episode drops out a week after it was last played.
 */
public suspend fun MaApi.inProgress(limit: Int): List<MediaItem> =
    list("music/in_progress_items", MediaItem.serializer()) { put("limit", limit) }

/** A book or episode in progress as [inProgress] lists it, and as read again by its uri when that worked. */
public data class Started(val listed: MediaItem, val fresh: MediaItem?)

/**
 * [inProgress] with each item read again by its uri, side by side, for the progress and cover the
 * listing lacks; one finished meanwhile is left out.
 */
public suspend fun MaApi.started(limit: Int): List<Started> = coroutineScope {
    inProgress(limit).map { item -> async { Started(item, orNullOnFailure { itemByUri(item.uri) }) } }.awaitAll()
}.filterNot { it.fresh?.fullyPlayed == true }

/**
 * The newest episode of every library podcast, newest first, with the caller's progress: Music
 * Assistant's own "Latest podcast episodes" row, refreshed every six hours. 2.11 only; 2.10
 * answers an empty list.
 */
public suspend fun MaApi.latestEpisodes(): List<MediaItem> =
    list("music/recommendations/items", MediaItem.serializer()) {
        put("provider", "recommendations")
        put("item_id", "latest_episodes")
    }

/**
 * Marks a book or episode finished, or clears its progress. The server takes the item as an
 * object: a uri is refused (error 999) on both releases. Needs the library.write scope.
 */
public suspend fun MaApi.setPlayed(item: ItemUri, name: String, played: Boolean) {
    client.call(
        if (played) "music/mark_played" else "music/mark_unplayed",
        buildJsonObject {
            putJsonObject("media_item") {
                put("item_id", item.itemId)
                put("provider", item.provider)
                put("name", name)
                put("media_type", item.mediaType)
            }
        },
    )
}
