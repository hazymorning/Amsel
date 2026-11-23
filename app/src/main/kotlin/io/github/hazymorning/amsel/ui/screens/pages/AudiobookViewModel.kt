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
 * An audiobook page: the header from the library index and the chapters it showed last time at
 * once, then the server's book with its chapters. The person's progress follows the index's row, which events keep current; [playing]
 * is what plays on the current speaker, so the page follows the book while it is there. A book on
 * the phone ([download]) shows from its download where neither is there, as without a connection.
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

    /** The book on the speaker, as the page draws it: not each change to the volume or the speaker. */
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
        // A book the index does not hold has no row to follow: marks and plays arrive here.
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

    /** The [first] load paints what the page showed last time while the server is asked. */
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

/** What an audiobook page shows above its chapters. */
@Immutable
data class AudiobookUi(
    val key: String,
    val title: String,
    val art: ArtRef? = null,
    val author: String? = null,
    /** The first author's page, when Music Assistant knows them as an artist. */
    val authorKey: String? = null,
    val narrator: String? = null,
    val durationSec: Int? = null,
    /** Where the person stopped. */
    val resumeMs: Long? = null,
    val fullyPlayed: Boolean = false,
    val favorite: Boolean = false,
    /** From Audiobookshelf, so it can be downloaded; known once the server has sent the book. */
    val downloadable: Boolean = false,
) {
    /** The book as an item, for its ⋮ sheet. */
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

/** Whether the book plays on the speaker, and where it was at a known moment. */
@Immutable
data class BookPlayback(
    val isPlaying: Boolean,
    val positionMs: Long,
    val positionAtMs: Long,
    val speed: Float,
    val durationMs: Long,
    /** The position moves on with time: it plays, and is not still on its way to a place sought. */
    val runs: Boolean = isPlaying,
) {
    constructor(now: NowPlayingUi) :
        this(now.isPlaying, now.positionMs, now.positionAtMs, now.speed, now.durationMs, now.runs)

    fun positionAt(nowMs: Long): Long = positionAt(positionMs, positionAtMs, nowMs, runs, speed.toDouble(), durationMs)
}

/**
 * Everything an audiobook page draws. The library index usually knows the book, so the header
 * paints at once and only the chapters wait for the server.
 */
@Immutable
data class AudiobookUiState(
    val book: AudiobookUi? = null,
    val chapters: List<ChapterUi> = emptyList(),
    /** The book as it plays, or is paused, on the current speaker; null while it is not there. */
    val playback: BookPlayback? = null,
    /** The book is being fetched: the first load, or a Retry. */
    val loading: Boolean = false,
    /** The book could not be loaded from the server. */
    val unavailable: Boolean = false,
    /** How far its download has come; null when it is not on the phone, nor on its way. */
    val download: DownloadState? = null,
) {
    /** The page offers the download: the book is from Audiobookshelf, or some of it is on the phone. */
    val offersDownload: Boolean = download != null || book?.downloadable == true

    /** As an album's page: nothing to list, so the page says so and offers Retry; a downloaded book still plays. */
    val showUnavailable: Boolean = unavailable && chapters.isEmpty() && download != DownloadState.Done

    val chaptersLoading: Boolean = chapters.isEmpty() && loading

    /** Started and not finished, or on the speaker now: the button resumes. */
    val started: Boolean = playback != null || ((book?.resumeMs ?: 0) > 0 && book?.fullyPlayed != true)

    /** Where the book stands at [nowMs]: the speaker's position while it is current, else the resume point. */
    fun positionAt(nowMs: Long): Long? = playback?.positionAt(nowMs) ?: book?.takeIf { started }?.resumeMs

    /** The chapter the person is in at [nowMs], by index, or -1. */
    fun chapterAt(nowMs: Long): Int {
        val position = positionAt(nowMs) ?: return -1
        return ChapterNavigation.indexAt(chapters.map { it.startMs }, position)
    }
}
