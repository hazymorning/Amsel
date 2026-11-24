package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.api.ItemPage
import io.github.hazymorning.amsel.api.MaJson
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.fixture
import io.github.hazymorning.amsel.data.CachedPage
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.data.library.ProgressUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/** A [PageSource] the page tests fill by hand, with gates that hold its answers. */
internal class FakePageSource(gated: Boolean = false, popularHeld: Boolean = false, similarHeld: Boolean = false) :
    PageSource {
    private var gate = CompletableDeferred<Unit>().apply { if (!gated) complete(Unit) }

    /** Holds the popular songs alone until completed: the slow call on a real server. */
    val popularGate = CompletableDeferred<Unit>().apply { if (!popularHeld) complete(Unit) }

    /** Holds the similar artists or songs alone until completed. */
    val similarGate = CompletableDeferred<Unit>().apply { if (!similarHeld) complete(Unit) }
    val rows = MutableStateFlow<LibraryItemEntity?>(null)
    var albumRows: List<LibraryItemEntity> = emptyList()
    var item: MediaItem? = null
    var tracks: List<MediaItem> = emptyList()
    var albums: List<MediaItem> = emptyList()
    var similar: List<MediaItem> = emptyList()

    /** Thrown by the similar artists or songs alone. */
    var similarFailure: Throwable? = null
    var similarCalls = 0

    /** A podcast's episodes, page by page. */
    var pages: List<List<MediaItem>> = emptyList()

    /** Playlist pages held back by their offset until completed. */
    val held = mutableMapOf<Int, CompletableDeferred<Unit>>()

    /** The offsets episodes were asked from. */
    val asked = mutableListOf<Int>()
    override val progress = MutableSharedFlow<ProgressUpdate>(extraBufferCapacity = 4)
    var failure: Throwable? = null
    var itemCalls = 0

    /** Thrown by the item alone: a music service slow to describe it. */
    var itemFailure: Throwable? = null

    /** What each page showed last time, by key: never held back, as the phone answers. */
    val shownPages = mutableMapOf<String, CachedPage>()

    /** The album covers the index made each artist without a picture, by uri. */
    var covers: Map<String, List<String>> = emptyMap()

    fun open() = gate.complete(Unit)

    /** Holds every answer from now until [open]. */
    fun close() {
        gate = CompletableDeferred()
    }

    override fun row(key: String): Flow<LibraryItemEntity?> = rows

    override suspend fun cachedAlbums(key: String) = albumRows

    override suspend fun item(key: String): MediaItem? {
        itemCalls++
        itemFailure?.let { throw it }
        return answer(item)
    }

    override suspend fun albumTracks(key: String) = answer(tracks)

    override suspend fun artistAlbums(key: String) = answer(albums)

    override suspend fun artistTopTracks(key: String): List<MediaItem> {
        popularGate.await()
        return answer(tracks)
    }

    override suspend fun playlistTracks(key: String, offset: Int, limit: Int): ItemPage<MediaItem> {
        held[offset]?.await()
        val page = answer(tracks.drop(offset).take(limit))
        return ItemPage(page, complete = offset + page.size >= tracks.size, next = offset + page.size)
    }

    override suspend fun episodes(key: String, offset: Int, limit: Int): ItemPage<MediaItem> {
        val page = answer(pages.getOrElse(offset / limit) { emptyList() })
        asked += offset
        return ItemPage(page, complete = page.size < limit, next = offset + page.size)
    }

    override suspend fun shown(key: String) = shownPages[key]

    override suspend fun keep(key: String, page: CachedPage) {
        shownPages[key] = page
    }

    override suspend fun albumCovers(keys: List<String>) = covers.filterKeys { it in keys }

    /** Each one a connection made anew. */
    val connections = MutableSharedFlow<Unit>()

    override suspend fun connectedAgain() = connections.first()

    override suspend fun similarArtists(key: String) = similar()

    override suspend fun similarSongs(key: String) = similar()

    private suspend fun similar(): List<MediaItem> {
        similarCalls++
        similarGate.await()
        similarFailure?.let { throw it }
        return similar
    }

    private suspend fun <T> answer(value: T): T {
        gate.await()
        failure?.let { throw it }
        return value
    }
}

/** Keeps [state] collected for the test's lifetime, as a screen would. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> TestScope.watch(state: StateFlow<T>): StateFlow<T> {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
    return state
}

internal const val ALBUM = "library://album/4"
internal const val ARTIST = "library://artist/3"

/** A recorded 2.11 result. */
internal fun result(name: String): JsonElement =
    fixture("2.11", name).getValue("response").jsonObject.getValue("result")

internal fun one(name: String): MediaItem = MaJson.decodeFromJsonElement(MediaItem.serializer(), result(name))

internal fun list(name: String): List<MediaItem> =
    MaJson.decodeFromJsonElement(ListSerializer(MediaItem.serializer()), result(name))

/** A library index row. */
internal fun row(
    uri: String,
    kind: String,
    name: String,
    subtitle: String? = null,
    artist: String? = null,
    editable: Boolean = false,
    year: Int? = null,
) = LibraryItemEntity(
    uri = uri,
    mediaType = kind,
    name = name,
    sortName = name.lowercase(),
    subtitle = subtitle,
    imageProxyId = null,
    year = year,
    favorite = false,
    addedRank = 0,
    playedRank = null,
    isEditable = editable,
    artistUri = artist,
)
