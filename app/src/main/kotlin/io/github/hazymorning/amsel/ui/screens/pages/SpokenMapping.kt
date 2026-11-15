package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.listening
import io.github.hazymorning.amsel.data.downloads.downloadable
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** A book page's header from the library index: the same fields the server fills, so nothing jumps. */
internal fun UiMapper.audiobookHeader(row: LibraryItemEntity) = AudiobookUi(
    key = row.uri,
    title = row.name,
    art = artFromProxyId(row.imageProxyId),
    author = row.subtitle,
    authorKey = row.artistUri,
    narrator = row.narrators,
    durationSec = row.durationSec,
    resumeMs = row.resumeMs,
    fullyPlayed = row.fullyPlayed == true,
    favorite = row.favorite,
)

internal fun UiMapper.audiobookHeader(item: MediaItem) = AudiobookUi(
    key = item.uri,
    title = item.name,
    art = art(item.cover),
    author = item.authorNames.ifBlank { null },
    authorKey = item.authors.firstNotNullOfOrNull { it.uri },
    narrator = item.narratorNames.ifBlank { null },
    durationSec = item.duration?.takeIf { it > 0 },
    resumeMs = item.resumePositionMs,
    fullyPlayed = item.fullyPlayed == true,
    favorite = item.favorite == true,
    downloadable = item.downloadable,
)

internal fun UiMapper.episode(item: MediaItem): EpisodeUi {
    val played = item.fullyPlayed == true
    val where = listening(item.duration, item.resumePositionMs, played)
    return EpisodeUi(
        key = item.uri,
        title = item.name,
        date = item.metadata?.releaseDate?.let(::dayOf),
        durationSec = item.duration?.takeIf { it > 0 },
        resumeMs = item.resumePositionMs,
        remainingSec = where?.second,
        played = played,
        art = art(item.cover),
        podcast = item.podcast?.name,
        downloadable = item.downloadable,
    )
}

/** The episode as an item, for its ⋮ sheet; on its podcast's page, without the way there. */
internal fun EpisodeUi.media(podcast: MediaUi?) = MediaUi(
    key = key,
    kind = MediaKind.Episode,
    title = title,
    subtitle = podcast?.title ?: this.podcast,
    art = art ?: podcast?.art,
    resumeMs = resumeMs,
    fullyPlayed = played,
    downloadable = downloadable,
)

/** The day of an ISO 8601 date-time or date, as the server sends a release date. */
internal fun dayOf(iso: String): LocalDate? = try {
    OffsetDateTime.parse(iso).toLocalDate()
} catch (e: DateTimeParseException) {
    try {
        LocalDate.parse(iso.take(ISO_DATE_LENGTH))
    } catch (e: DateTimeParseException) {
        null
    }
}

private const val ISO_DATE_LENGTH = 10
