package io.github.hazymorning.amsel.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.put

/** Popular tracks an artist page shows. */
public const val POPULAR_TRACKS: Int = 5

/** How long the server may take to rank an artist's tracks before the stand-in shows. */
private val TOP_TRACKS_TIMEOUT = 10.seconds

/**
 * The artist's popular tracks as the server ranks them. Local files have no top tracks, so the
 * artist's own first tracks stand in. `top_tracks` is the slow call: the server asks the artist's
 * music services (and Last.fm-style plugins) and, before 2.11, does so on every request. From
 * schema 92 both lists are asked at once and limited; older servers send every track of the
 * artist, so the stand-in is asked only when it is needed.
 */
public suspend fun MaApi.artistTopTracks(itemId: String, provider: String): List<MediaItem> {
    val paged = schema() >= Schema.LISTING_PAGING
    // The artist's first tracks in the server's default order (by name).
    val own = suspend { firstTracks("music/artists/artist_tracks", itemId, provider, paged) }
    // The ranking, or nothing when a music service fails or takes too long to rank.
    val top = suspend {
        orNullOnFailure {
            firstTracks("music/artists/top_tracks", itemId, provider, paged, TOP_TRACKS_TIMEOUT)
        }.orEmpty()
    }
    if (!paged) return top().ifEmpty { own() }
    // The stand-in failing must not cancel the ranking; it matters only when the ranking is empty.
    return supervisorScope {
        val stand = async { own() }
        val ranked = top()
        if (ranked.isNotEmpty()) ranked.also { stand.cancel() } else stand.await()
    }
}

/**
 * [command]'s first [POPULAR_TRACKS]: the limit is sent from schema 92 ([paged]); older servers
 * answer every track, so it is applied here as well.
 */
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
