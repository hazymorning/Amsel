package io.github.hazymorning.amsel.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

public const val EPISODE_PAGE: Int = 50

/**
 * `playlog_updated` and `media_item_played`. Every socket receives every user's: apply one only
 * when [userId] is the signed-in user's, or null for all users.
 */
@Serializable
public data class PlayProgress(
    val uri: String,
    @SerialName("media_type") val mediaType: String = "",
    @SerialName("fully_played") val fullyPlayed: Boolean? = null,
    @SerialName("seconds_played") @Serializable(with = LenientLongSerializer::class) val secondsPlayed: Long? = null,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("userid") val userId: String? = null,
)

/**
 * Newest first. Before schema 93 the whole podcast streams in partials, oldest first: it is sorted
 * here, and everything from [offset] comes as one page.
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

/** Item mappings without resume position or duration; an episode drops out a week after its last play. */
public suspend fun MaApi.inProgress(limit: Int): List<MediaItem> =
    list("music/in_progress_items", MediaItem.serializer()) { put("limit", limit) }

/** [fresh] is the item read again by its uri, null when that failed. */
public data class InProgressItem(val listed: MediaItem, val fresh: MediaItem?)

/** Each item read again by its uri, concurrently, for the progress and cover the listing lacks. */
public suspend fun MaApi.started(limit: Int): List<InProgressItem> = coroutineScope {
    inProgress(limit).map { item -> async { InProgressItem(item, orNullOnFailure { itemByUri(item.uri) }) } }.awaitAll()
}.filterNot { it.fresh?.fullyPlayed == true }

/** Refreshed by the server every six hours, so empty for a while after its first start; 2.10 answers `[]`. */
public suspend fun MaApi.latestEpisodes(): List<MediaItem> =
    list("music/recommendations/items", MediaItem.serializer()) {
        put("provider", "recommendations")
        put("item_id", "latest_episodes")
    }

/**
 * [played] false clears the progress. The item goes as an object: a uri is refused (error 999).
 * Needs the library.write scope.
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
