package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.AppGraph
import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.ItemPage
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.albumTracks
import io.github.hazymorning.amsel.api.artistAlbums
import io.github.hazymorning.amsel.api.artistTopTracks
import io.github.hazymorning.amsel.api.itemByUri
import io.github.hazymorning.amsel.api.playlistTracks
import io.github.hazymorning.amsel.api.podcastEpisodes
import io.github.hazymorning.amsel.api.similarArtists
import io.github.hazymorning.amsel.api.similarTracks
import io.github.hazymorning.amsel.api.withoutSeed
import io.github.hazymorning.amsel.app.MessageController
import io.github.hazymorning.amsel.data.CachedPage
import io.github.hazymorning.amsel.data.PageCache
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.data.library.LibraryRepository
import io.github.hazymorning.amsel.data.library.ProgressUpdate
import io.github.hazymorning.amsel.playback.failureText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** Keys are item URIs. An interface, so the ViewModels test without a socket. */
interface PageSource {
    /** Null while the index does not hold [key]. */
    fun row(key: String): Flow<LibraryItemEntity?>

    /** Index albums whose first artist is [key]. */
    suspend fun cachedAlbums(key: String): List<LibraryItemEntity>

    /** Includes a book's chapters; null or a not-found error when the item no longer exists. */
    suspend fun item(key: String): MediaItem?

    suspend fun albumTracks(key: String): List<MediaItem>

    suspend fun artistAlbums(key: String): List<MediaItem>

    /** At most five, as the server ranks them. */
    suspend fun artistTopTracks(key: String): List<MediaItem>

    /**
     * Tracks from [offset], each with its 1-based position: a page of [limit], or everything from
     * [offset] on 2.10 (docs/API-NOTES.md, "Pages and sub-listings").
     */
    suspend fun playlistTracks(key: String, offset: Int, limit: Int): ItemPage<MediaItem>

    /**
     * Episodes from [offset], newest first: a page of [limit], or everything from [offset] on 2.10
     * (docs/API-NOTES.md, "Audiobooks and podcasts").
     */
    suspend fun episodes(key: String, offset: Int, limit: Int): ItemPage<MediaItem>

    /** Empty for local files (docs/API-NOTES.md, "Extras"). */
    suspend fun similarArtists(key: String): List<MediaItem>

    /** Fails with a command error where no music service knows the song (docs/API-NOTES.md, "Extras"). */
    suspend fun similarSongs(key: String): List<MediaItem>

    val progress: Flow<ProgressUpdate>

    /** The page as last shown, for the first paint; null when none is cached. */
    suspend fun shown(key: String): CachedPage?

    suspend fun keep(key: String, page: CachedPage)

    /** Album covers for each of [keys] without a picture of its own; artists the index lacks are left out. */
    suspend fun albumCovers(keys: List<String>): Map<String, List<String>>

    /** Returns at the next new connection: after the current one ends, or at the first. */
    suspend fun connectedAgain()
}

class ServerPageSource(
    private val api: MaApi,
    private val library: () -> LibraryRepository,
    private val memory: PageCache,
    private val connected: Flow<Boolean>,
) : PageSource {
    override fun row(key: String): Flow<LibraryItemEntity?> = flow { emitAll(library().observeRow(key)) }

    override suspend fun cachedAlbums(key: String): List<LibraryItemEntity> = library().albumsBy(key)

    // A book's index row takes the user's progress from this fresh copy.
    override suspend fun item(key: String): MediaItem? = api.itemByUri(key)?.also {
        if (it.mediaType == MediaType.Audiobook.api) library().rememberProgress(it)
    }

    override suspend fun albumTracks(key: String): List<MediaItem> = ids(key) { id, provider ->
        api.albumTracks(id, provider)
    }

    override suspend fun artistAlbums(key: String): List<MediaItem> = ids(key) { id, provider ->
        api.artistAlbums(id, provider)
    }

    override suspend fun artistTopTracks(key: String): List<MediaItem> = ids(key) { id, provider ->
        api.artistTopTracks(id, provider)
    }

    override suspend fun playlistTracks(key: String, offset: Int, limit: Int): ItemPage<MediaItem> = ids(key) {
            id,
            provider,
        ->
        api.playlistTracks(id, provider, offset, limit)
    }

    override suspend fun episodes(key: String, offset: Int, limit: Int): ItemPage<MediaItem> = ids(key) {
            id,
            provider,
        ->
        api.podcastEpisodes(id, provider, offset, limit)
    }

    override suspend fun similarArtists(key: String): List<MediaItem> = ids(key) { id, provider ->
        api.similarArtists(id, provider)
    }

    override suspend fun similarSongs(key: String): List<MediaItem> = ids(key) { id, provider ->
        api.similarTracks(id, provider)
    }

    override val progress: Flow<ProgressUpdate> = flow { emitAll(library().progress) }

    override suspend fun shown(key: String): CachedPage? = memory.read(key)

    override suspend fun keep(key: String, page: CachedPage) = memory.write(key, page)

    override suspend fun albumCovers(keys: List<String>): Map<String, List<String>> = library().albumCovers(keys)

    override suspend fun connectedAgain() {
        connected.dropWhile { it }.first { it }
    }

    private inline fun <T> ids(key: String, load: (id: String, provider: String) -> T): T {
        val uri = requireNotNull(ItemUri.parse(key)) { "Not an item uri: $key" }
        return load(uri.itemId, uri.provider)
    }
}

internal fun AppGraph.pageSource(): PageSource = ServerPageSource(
    data.session.api,
    { data.library },
    data.pages,
    data.session.client.state.map { it is ConnectionState.Connected },
)

// Without the item [key] asked for, which the server may return under another id ([seed]), and each
// once (docs/API-NOTES.md, "Extras").
internal fun List<MediaItem>.similarTo(key: String, seed: MediaItem?): List<MediaItem> =
    (seed?.let(::withoutSeed) ?: this).filterNot { it.uri == key }.distinctBy { it.uri }

/** [attempt] returns null on a server error; the first error is kept as [failure]. */
internal class PageLoad {
    var failure: Exception? = null
        private set

    suspend fun <T> attempt(call: suspend () -> T): T? = try {
        call()
    } catch (e: MaException) {
        failure = failure ?: e
        null
    }
}

// After a connection failure, runs [again] at the next connection, so a page opened while Amsel
// reconnects (as on returning to the foreground) fills in by itself, as Home and the Library do.
internal suspend fun PageSource.againOnceConnected(load: PageLoad, again: () -> Unit) {
    if (load.failure !is MaConnectionException) return
    connectedAgain()
    again()
}

// A page with content shows the failure as a message with Retry; an empty one says so itself
// ([showUnavailable]).
internal fun MessageController.loadFailed(load: PageLoad, showUnavailable: Boolean, retry: () -> Unit) {
    val failure = load.failure ?: return
    if (!showUnavailable) show(failureText(failure), retry = retry)
}
