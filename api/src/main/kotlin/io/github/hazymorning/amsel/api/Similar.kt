package io.github.hazymorning.amsel.api

import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.put

/** 2.11 answers at most 25, whatever is asked. */
public const val SIMILAR_ITEMS: Int = 25

private val SIMILAR_TIMEOUT = 10.seconds

/**
 * The library's artist where it holds one, else the music service's. `limit` is always sent and
 * never 0, which 2.10 takes for none at all. See docs/API-NOTES.md, "Similar: the request".
 */
public suspend fun MaApi.similarArtists(itemId: String, provider: String): List<MediaItem> =
    list("music/artists/similar_artists", MediaItem.serializer(), SIMILAR_TIMEOUT) {
        ids(itemId, provider)
        put("limit", SIMILAR_ITEMS)
    }

/**
 * The music service's own items, also where the library holds the song, and the song itself can be
 * among them under another id ([withoutSeed]). A failing source answers an error.
 */
public suspend fun MaApi.similarTracks(itemId: String, provider: String): List<MediaItem> =
    list("music/tracks/similar_tracks", MediaItem.serializer(), SIMILAR_TIMEOUT) {
        ids(itemId, provider)
        put("limit", SIMILAR_ITEMS)
    }

public fun List<MediaItem>.withoutSeed(seed: MediaItem): List<MediaItem> {
    val own = seed.providerMappings.map { it.providerInstance to it.itemId }.toSet() + (seed.provider to seed.itemId)
    return filterNot { item ->
        item.uri == seed.uri ||
            (item.provider to item.itemId) in own ||
            item.providerMappings.any { (it.providerInstance to it.itemId) in own }
    }
}
