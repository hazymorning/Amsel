package io.github.hazymorning.amsel.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.put

public const val POPULAR_TRACKS: Int = 5

private val TOP_TRACKS_TIMEOUT = 10.seconds

/**
 * `top_tracks` is the slow call, and empty for local files. From schema 92 the fallback, the
 * artist's first tracks, is asked at the same time; before it only when needed, as it then answers
 * every track. See docs/API-NOTES.md, "The artist page: where the time goes".
 */
public suspend fun MaApi.artistTopTracks(itemId: String, provider: String): List<MediaItem> {
    val paged = schema() >= Schema.LISTING_PAGING
    val own = suspend { firstTracks("music/artists/artist_tracks", itemId, provider, paged) }
    val top = suspend {
        orNullOnFailure {
            firstTracks("music/artists/top_tracks", itemId, provider, paged, TOP_TRACKS_TIMEOUT)
        }.orEmpty()
    }
    if (!paged) return top().ifEmpty { own() }
    // A failing fallback must not cancel the ranking; it is needed only when the ranking is empty.
    return supervisorScope {
        val stand = async { own() }
        val ranked = top()
        if (ranked.isNotEmpty()) ranked.also { stand.cancel() } else stand.await()
    }
}

/** Before schema 92 ([paged] false) these listings take no limit and answer every track, so the list is cut here. */
private suspend fun MaApi.firstTracks(
    command: String,
    itemId: String,
    provider: String,
    paged: Boolean,
    timeout: Duration = MaSocket.CALL_TIMEOUT,
): List<MediaItem> = list(command, MediaItem.serializer(), timeout) {
    ids(itemId, provider)
    if (paged) put("limit", POPULAR_TRACKS)
}.take(POPULAR_TRACKS)
