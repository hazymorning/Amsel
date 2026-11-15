package io.github.hazymorning.amsel.api

import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.put

/** How many similar artists or songs are asked for: 2.11 answers no more, whatever is asked. */
public const val SIMILAR_ITEMS: Int = 25

/** How long the server may ask its music services before the answer counts as nothing. */
private val SIMILAR_TIMEOUT = 10.seconds

/**
 * Artists like this one, as the artist's music services or a plugin name them: the library's
 * artist where it holds one, else the service's own. Local files have none. The limit is always
 * sent and never 0, which 2.10 takes for none at all.
 */
public suspend fun MaApi.similarArtists(itemId: String, provider: String): List<MediaItem> =
    list("music/artists/similar_artists", MediaItem.serializer(), SIMILAR_TIMEOUT) {
        ids(itemId, provider)
        put("limit", SIMILAR_ITEMS)
    }

/**
 * Songs like this one, from the first source that knows any. They are the music service's own
 * items, also where the library holds the song, and the song itself can be among them under
 * another id ([withoutSeed]). Local files have none, and a source that fails answers an error.
 */
public suspend fun MaApi.similarTracks(itemId: String, provider: String): List<MediaItem> =
    list("music/tracks/similar_tracks", MediaItem.serializer(), SIMILAR_TIMEOUT) {
        ids(itemId, provider)
        put("limit", SIMILAR_ITEMS)
    }

/** These without [seed] itself: no item of its uri, and none that one of its music services holds under the same id. */
public fun List<MediaItem>.withoutSeed(seed: MediaItem): List<MediaItem> {
    val own = seed.providerMappings.map { it.providerInstance to it.itemId }.toSet() + (seed.provider to seed.itemId)
    return filterNot { item ->
        item.uri == seed.uri ||
            (item.provider to item.itemId) in own ||
            item.providerMappings.any { (it.providerInstance to it.itemId) in own }
    }
}
