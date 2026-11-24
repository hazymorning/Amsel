package io.github.hazymorning.amsel.ui.screens.pages

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.hazymorning.amsel.app.ItemDownloadState
import io.github.hazymorning.amsel.app.art
import io.github.hazymorning.amsel.app.chapterList
import io.github.hazymorning.amsel.data.CachedPage
import io.github.hazymorning.amsel.data.downloads.DownloadManifest
import io.github.hazymorning.amsel.data.downloads.DownloadState
import io.github.hazymorning.amsel.playback.ChapterNavigation
import io.github.hazymorning.amsel.playback.positionAt
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import io.github.hazymorning.amsel.ui.mapping.chapterList
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.ChapterUi
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Progress follows the index row, which events keep current; [playing] is what plays on the current
 * speaker. Without the server and the index, as offline, a downloaded book shows from its [download].
 */
class AudiobookViewModel(
    private val key: String,
    private val source: PageSource,
    private val mapper: UiMapper,
    playing: Flow<NowPlayingUi?>,
    download: Flow<ItemDownloadState> = flowOf(ItemDownloadState()),
) : ViewModel() {
    private val page = MutableStateFlow(AudiobookUiState(loading = true))
    private var loading: Job? = null

    // Reduced to what the page draws, so a volume or speaker change does not update the page.
    private val onSpeaker = playing.map { now ->
        now?.takeIf { it.key == key }?.let(::BookPlayback)
    }.distinctUntilChanged()

    val state: StateFlow<AudiobookUiState> =
        combine(page, source.row(key), onSpeaker, download) { page, row, playback, download ->
            val manifest = download.manifest
            val header = page.book ?: row?.let(mapper::audiobookHeader) ?: manifest?.let { book(it, download) }
            val book =
                row?.let {
                    header?.copy(resumeMs = it.resumeMs, fullyPlayed = it.fullyPlayed == true, favorite = it.favorite)
                }
                    ?: header
            page.copy(
                book = book,
                chapters = page.chapters.ifEmpty { manifest?.chapterList().orEmpty() },
                playback = playback,
                download = download.state,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudiobookUiState(loading = true))

    init {
        load(first = true)
        // A book the index does not hold has no row to follow, so marks and plays come from here.
        viewModelScope.launch {
            source.progress.filter { it.uri == key }.collect { update ->
                page.update {
                    it.copy(book = it.book?.copy(resumeMs = update.positionMs, fullyPlayed = update.fullyPlayed))
                }
            }
        }
    }

    fun retry() = load()

    private fun book(manifest: DownloadManifest, download: ItemDownloadState) = AudiobookUi(
        key = key,
        title = manifest.title,
        art = download.cover?.art(),
        author = manifest.author,
        durationSec = manifest.durationSec.toInt().takeIf { it > 0 },
        downloadable = true,
    )

    /** Only the [first] load shows the cached page while the server is asked. */
    private fun load(first: Boolean = false) {
        loading?.cancel()
        page.update { it.copy(loading = true, unavailable = false) }
        loading = viewModelScope.launch {
            val load = PageLoad()
            val served = async { load.attempt { source.item(key) } }
            if (first) {
                source.shown(key)?.let { last ->
                    page.update {
                        it.copy(
                            book = it.book ?: mapper.audiobookHeader(last.item),
                            chapters = it.chapters.ifEmpty { chapterList(last.item) },
                        )
                    }
                }
            }
            val item = served.await()
            page.update {
                it.copy(
                    book = item?.let(mapper::audiobookHeader) ?: it.book,
                    chapters = item?.let(::chapterList) ?: it.chapters,
                    loading = false,
                    unavailable = item == null,
                )
            }
            if (item != null) source.keep(key, CachedPage(item))
            source.againOnceConnected(load, ::retry)
        }
    }
}

@Immutable
data class AudiobookUi(
    val key: String,
    val title: String,
    val art: ArtRef? = null,
    val author: String? = null,
    /** The first author's artist URI, when Music Assistant holds the author as an artist. */
    val authorKey: String? = null,
    val narrator: String? = null,
    val durationSec: Int? = null,
    val resumeMs: Long? = null,
    val fullyPlayed: Boolean = false,
    val favorite: Boolean = false,
    /** From Audiobookshelf, so it can be downloaded; known only once the server has sent the book. */
    val downloadable: Boolean = false,
) {
    val media: MediaUi get() = MediaUi(
        key,
        MediaKind.Audiobook,
        title,
        author,
        art,
        favorite,
        fullyPlayed = fullyPlayed,
        artistKey = authorKey,
        downloadable = downloadable,
    )
}

/** [positionMs] was the position at [positionAtMs]. */
@Immutable
data class BookPlayback(
    val isPlaying: Boolean,
    val positionMs: Long,
    val positionAtMs: Long,
    val speed: Float,
    val durationMs: Long,
    /** The position advances: playing, and not waiting for a seek to complete. */
    val runs: Boolean = isPlaying,
) {
    constructor(now: NowPlayingUi) :
        this(now.isPlaying, now.positionMs, now.positionAtMs, now.speed, now.durationMs, now.runs)

    fun positionAt(nowMs: Long): Long = positionAt(positionMs, positionAtMs, nowMs, runs, speed.toDouble(), durationMs)
}

@Immutable
data class AudiobookUiState(
    val book: AudiobookUi? = null,
    val chapters: List<ChapterUi> = emptyList(),
    /** Null while the book is not on the current speaker. */
    val playback: BookPlayback? = null,
    val loading: Boolean = false,
    val unavailable: Boolean = false,
    /** Null when the book is neither downloaded nor downloading. */
    val download: DownloadState? = null,
) {
    val offersDownload: Boolean = download != null || book?.downloadable == true

    // A fully downloaded book still plays, so it is never shown as unavailable.
    val showUnavailable: Boolean = unavailable && chapters.isEmpty() && download != DownloadState.Done

    val chaptersLoading: Boolean = chapters.isEmpty() && loading

    val started: Boolean = playback != null || ((book?.resumeMs ?: 0) > 0 && book?.fullyPlayed != true)

    fun positionAt(nowMs: Long): Long? = playback?.positionAt(nowMs) ?: book?.takeIf { started }?.resumeMs

    /** The chapter's index at [nowMs], or -1. */
    fun chapterAt(nowMs: Long): Int {
        val position = positionAt(nowMs) ?: return -1
        return ChapterNavigation.indexAt(chapters.map { it.startMs }, position)
    }
}
