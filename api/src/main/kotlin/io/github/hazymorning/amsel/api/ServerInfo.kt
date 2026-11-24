package io.github.hazymorning.amsel.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class ServerInfo(
    @SerialName("server_id") val serverId: String,
    @SerialName("server_version") val serverVersion: String,
    @SerialName("schema_version") val schemaVersion: Int,
    val name: String? = null,
)

/**
 * Supports 2.10 (schema 65) and 2.11 (99). Gate each feature on its exact number: older servers
 * silently drop arguments they do not know.
 */
public object Schema {
    public const val MIN_SUPPORTED: Int = 65

    public const val FAVORITES_PER_USER: Int = 78

    public const val TYPED_SORT: Int = 86

    /** `album_tracks` takes `limit` and `offset`, and without a limit answers at most 500 items. */
    public const val ALBUM_TRACK_PAGING: Int = 89

    /** `artist_albums`, `artist_tracks` and `top_tracks` page as `album_tracks` does from [ALBUM_TRACK_PAGING]. */
    public const val LISTING_PAGING: Int = 92

    public const val PLAYLIST_PAGING: Int = 93

    public const val EPISODE_PAGING: Int = 93

    /**
     * 2.11's schema, as 2.10 answers the `latest_episodes` row with nothing. See docs/API-NOTES.md,
     * "Audiobooks and podcasts".
     */
    public const val LATEST_EPISODES: Int = 99

    public fun isSupported(schema: Int): Boolean = schema >= MIN_SUPPORTED
}
