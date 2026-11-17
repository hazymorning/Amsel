package io.github.hazymorning.amsel.ui.screens.pages

import io.github.hazymorning.amsel.AppGraph
import io.github.hazymorning.amsel.api.ItemPage
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.MaApi
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
import io.github.hazymorning.amsel.app.Messages
import io.github.hazymorning.amsel.data.PageMemory
import io.github.hazymorning.amsel.data.ShownPage
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.data.library.LibraryRepository
import io.github.hazymorning.amsel.data.library.ProgressUpdate
import io.github.hazymorning.amsel.playback.failureText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * What the pages load, by item uri: the library index's row for the first paint (and a book's or
 * podcast's progress as it changes), then the server's item and its lists. One seam, so the
 * ViewModels test without a socket.
 */
interface PageSource {
    /** The index's row for [key] as it changes; null when the index does not hold it. */
    fun row(key: String): Flow<LibraryItemEntity?>

    /** The index's albums by the artist [key] (as their first artist), for the first paint. */
    suspend fun cachedAlbums(key: String): List<LibraryItemEntity>

    /** The item itself, a book's chapters included; null, or a not-found error, when it no longer exists. */
    suspend fun item(key: String): MediaItem?

    suspend fun albumTracks(key: String): List<MediaItem>

    suspend fun artistAlbums(key: String): List<MediaItem>

    /** At most five, as the server ranks them. */
    suspend fun artistTopTracks(key: String): List<MediaItem>

    /** A playlist's tracks from [offset], each with its 1-based position: a page of [limit], or all of them on 2.10. */
    suspend fun playlistTracks(key: String, offset: Int, limit: Int): ItemPage<MediaItem>

    /** A podcast's episodes from [offset], newest first: a page of [limit], or all of them on 2.10. */
    suspend fun episodes(key: String, offset: Int, limit: Int): ItemPage<MediaItem>

    /** Artists like the artist [key], as the server finds them; none for local files. */
    suspend fun similarArtists(key: String): List<MediaItem>

    /** Songs like the song [key]; a command error where no music service knows any. */
    suspend fun similarSongs(key: String): List<MediaItem>

    /** Books and episodes the person moved through, finished or marked, as it happens. */
    val progress: Flow<ProgressUpdate>

    /** What the page of [key] showed last time, for its first paint; null when this phone holds none. */
    suspend fun shown(key: String): ShownPage?

    /** Keeps what the page of [key] now shows, for the next visit's first paint. */
    suspend fun keep(key: String, page: ShownPage)

    /** The index's album covers of each of the artists [keys] that has no picture; none it does not hold. */
    suspend fun albumCovers(keys: List<String>): Map<String, List<String>>
}

/** The real [PageSource]: the server, and the library index and the pages last shown for the first paint. */
class ServerPageSource(
    private val api: MaApi,
    private val library: () -> LibraryRepository,
    private val memory: PageMemory,
) : PageSource {
    override fun row(key: String): Flow<LibraryItemEntity?> = flow { emitAll(library().observeRow(key)) }

    override suspend fun cachedAlbums(key: String): List<LibraryItemEntity> = library().albumsBy(key)

    /** A book's row in the index takes the person's progress from the server's fresh copy. */
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

    override suspend fun shown(key: String): ShownPage? = memory.read(key)

    override suspend fun keep(key: String, page: ShownPage) = memory.write(key, page)

    override suspend fun albumCovers(keys: List<String>): Map<String, List<String>> = library().albumCovers(keys)

    private inline fun <T> ids(key: String, load: (id: String, provider: String) -> T): T {
        val uri = requireNotNull(ItemUri.parse(key)) { "Not an item uri: $key" }
        return load(uri.itemId, uri.provider)
    }
}

/** The pages' [PageSource] on the app's session and library index. */
internal fun AppGraph.pageSource(): PageSource = ServerPageSource(data.session.api, { data.library }, data.pages)

/**
 * The server's similar items as a page lists them: without the item [key] they were asked for,
 * which the server may return under another id ([seed], once it is known), and each once.
 */
internal fun List<MediaItem>.similarTo(key: String, seed: MediaItem?): List<MediaItem> =
    (seed?.let(::withoutSeed) ?: this).filterNot { it.uri == key }.distinctBy { it.uri }

/**
 * The calls of one page load: each answers, or is null when the server could not be asked or
 * refused, and the first such [failure] is kept for the page to say.
 */
internal class Load {
    var failure: Exception? = null
        private set

    suspend fun <T> attempt(call: suspend () -> T): T? = try {
        call()
    } catch (e: MaException) {
        failure = failure ?: e
        null
    }
}

/**
 * A page that still shows what it has says why [load] failed, with Retry; one with nothing to show
 * says so in its own place ([showUnavailable]).
 */
internal fun Messages.loadFailed(load: Load, showUnavailable: Boolean, retry: () -> Unit) {
    val failure = load.failure ?: return
    if (!showUnavailable) show(failureText(failure), retry = retry)
}
