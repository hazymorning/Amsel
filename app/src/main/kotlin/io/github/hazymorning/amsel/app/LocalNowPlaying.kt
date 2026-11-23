package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.data.downloads.DownloadManifest
import io.github.hazymorning.amsel.playback.LocalPlaybackState
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.ChapterUi
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import java.io.File
import kotlin.math.roundToLong

/** A download playing on this phone as Now Playing shows it: its cover and chapters are on the phone too. */
internal fun LocalPlaybackState.nowPlaying(speaker: SpeakerUi): NowPlayingUi = NowPlayingUi(
    title = manifest.title,
    artist = manifest.author.orEmpty(),
    art = cover?.let { ArtRef(it.toString()) },
    durationMs = manifest.durationMs,
    positionMs = positionMs,
    positionAtMs = atMs,
    isPlaying = playing,
    canShuffle = false,
    speaker = speaker,
    key = manifest.uri,
    kind = manifest.kind,
    chapters = manifest.chapterList(),
)

/** A downloaded cover, as artwork: its path in the app's own files, which need no escaping. */
fun File.art(): ArtRef = ArtRef("file://$absolutePath")

val DownloadManifest.kind: MediaKind
    get() = if (mediaType == MediaType.PodcastEpisode.api) MediaKind.Episode else MediaKind.Audiobook

val DownloadManifest.durationMs: Long get() = (durationSec * MS).roundToLong()

/** The chapters as Audiobookshelf has them, each ending where the next begins when it gives no end. */
fun DownloadManifest.chapterList(): List<ChapterUi> {
    val sorted = chapters.sortedBy { it.startSec }
    return sorted.mapIndexed { i, chapter ->
        val startMs = (chapter.startSec * MS).roundToLong()
        val endMs = (chapter.endSec * MS).roundToLong().takeIf { it > startMs }
            ?: sorted.getOrNull(i + 1)?.let { (it.startSec * MS).roundToLong() }
            ?: durationMs
        ChapterUi(i + 1, chapter.title.trim(), startMs, endMs, position = null)
    }
}

private const val MS = 1000.0
