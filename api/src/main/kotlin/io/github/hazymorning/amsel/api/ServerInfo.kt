package io.github.hazymorning.amsel.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The first frame on every `/ws` connection. */
@Serializable
public data class ServerInfo(
    @SerialName("server_id") val serverId: String,
    @SerialName("server_version") val serverVersion: String,
    @SerialName("schema_version") val schemaVersion: Int,
    val name: String? = null,
)

/**
 * The app supports the current and previous release: 2.10 (schema 65) and 2.11 (99). Gate each
 * feature on its exact number: older servers silently drop arguments they do not know.
 */
public object Schema {
    public const val MIN_SUPPORTED: Int = 65

    /** Per-user favourites: like / dislike / none, `music/favorites/set_item`, `favorite_updated`. */
    public const val FAVORITES_PER_USER: Int = 78

    /** `sort_field` / `sort_direction` and `music/sort_options`; `order_by` deprecated. */
    public const val TYPED_SORT: Int = 86

    /** `album_tracks` takes `limit` / `offset` (and sort, search), and answers 500 items when no limit is sent. */
    public const val ALBUM_TRACK_PAGING: Int = 89

    /** `artist_albums`, `artist_tracks` and `top_tracks` page as `album_tracks` does from [ALBUM_TRACK_PAGING]. */
    public const val LISTING_PAGING: Int = 92

    /** `playlist_tracks` answers one page (`limit`, `offset`) instead of streaming partials. */
    public const val PLAYLIST_PAGING: Int = 93

    /** `podcast_episodes` pages and sorts (`limit`, `offset`, `sort_field`), from the same commit. */
    public const val EPISODE_PAGING: Int = 93

    /** The recommendations' `latest_episodes` row, seen on 2.11.0b7; 2.10.9 answers it with nothing. */
    public const val LATEST_EPISODES: Int = 99

    public fun isSupported(schema: Int): Boolean = schema >= MIN_SUPPORTED
}
