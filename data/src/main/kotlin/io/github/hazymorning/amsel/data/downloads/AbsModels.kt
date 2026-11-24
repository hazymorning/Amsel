package io.github.hazymorning.amsel.data.downloads

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val AbsJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
internal data class AbsLogin(val user: AbsUser)

@Serializable
internal data class AbsUser(
    val username: String = "",
    val accessToken: String? = null,
    val refreshToken: String? = null,
    /** Before 2.26 the only token, long-lived. */
    val token: String? = null,
)

@Serializable
internal data class AbsItem(val id: String, val media: AbsMedia = AbsMedia())

@Serializable
internal data class AbsMedia(
    val metadata: AbsMetadata = AbsMetadata(),
    val duration: Double = 0.0,
    /** In timeline order; [AbsTrack.startOffset] is on the book's timeline. */
    val tracks: List<AbsTrack> = emptyList(),
    val chapters: List<AbsChapter> = emptyList(),
    val episodes: List<AbsEpisode> = emptyList(),
)

@Serializable
internal data class AbsMetadata(val title: String? = null, val authorName: String? = null, val author: String? = null)

@Serializable
internal data class AbsTrack(
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    /** `/api/items/{id}/file/{ino}`. */
    val contentUrl: String = "",
    val metadata: AbsFileMeta? = null,
) {
    val ino: String get() = contentUrl.substringAfterLast('/')
}

@Serializable
internal data class AbsFileMeta(val ext: String? = null, val size: Long = 0)

@Serializable
internal data class AbsChapter(val start: Double, val end: Double, val title: String = "")

@Serializable
internal data class AbsEpisode(
    val id: String,
    val title: String? = null,
    val audioTrack: AbsTrack? = null,
    val chapters: List<AbsChapter> = emptyList(),
)

/** [lastUpdate] in epoch ms. */
@Serializable
data class AbsProgress(
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdate: Long = 0,
)
