package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.api.FavoriteUpdate
import io.github.hazymorning.amsel.api.ItemUri
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.MaException
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.itemByUri
import io.github.hazymorning.amsel.api.orNullOnFailure
import io.github.hazymorning.amsel.api.setFavorite
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.data.library.LibraryRepository
import io.github.hazymorning.amsel.playback.failureText
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The server never updates a queued item after a like (docs/API-NOTES.md, "Queue fields used"), so
// the playing item is read again ([refresh]) and events keep each item read here current.
class FavoritesRepository(
    private val api: MaApi,
    private val library: LibraryRepository,
    private val messages: MessageController,
    session: StateFlow<SessionState>,
    events: Flow<MaEvent>,
    private val scope: CoroutineScope,
) {
    private val _known = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Keyed by the uri each item was read or changed under. */
    val known: StateFlow<Map<String, Boolean>> = _known.asStateFlow()

    // Service uri to library uri: the server announces a like under the library uri, while a queue
    // names the song by the music service it plays from.
    private val libraryUris = ConcurrentHashMap<String, String>()

    // Bumped on every change, so a read that started before it cannot overwrite it.
    private val changes = AtomicLong()

    init {
        scope.launch {
            session.collect { if (it == SessionState.NeedsSetup) forget() }
        }
        scope.launch {
            events.collect { event ->
                when (event.name) {
                    "favorite_updated" -> event.decode(FavoriteUpdate.serializer())?.let {
                        apply(it.uri, it.favorite == true)
                    }

                    // 2.10 announces a like here; 2.11 sends null (docs/API-NOTES.md, "Events").
                    "media_item_updated" -> event.objectId?.takeIf(::followed)?.let { uri ->
                        event.decode(MediaItem.serializer())?.favorite?.let { apply(uri, it) }
                    }
                }
            }
        }
    }

    fun of(uri: String, listed: Boolean): Boolean = _known.value[uri] ?: listed

    /** For a music service's item that is in the library, the server answers with the library item. */
    suspend fun refresh(uri: String) {
        val asked = changes.get()
        val item = orNullOnFailure { api.itemByUri(uri) } ?: return
        if (item.uri != uri) libraryUris[uri] = item.uri
        if (changes.get() == asked) _known.update { it + (uri to (item.favorite == true)) }
    }

    fun set(uri: String, favorite: Boolean) {
        val item = ItemUri.parse(uri) ?: return
        val before = _known.value[uri]
        changes.incrementAndGet()
        _known.update { it + (uri to favorite) }
        scope.launch {
            library.setFavoriteLocally(uri, favorite)
            val failure = try {
                api.setFavorite(item, favorite)
                return@launch
            } catch (e: MaException) {
                e
            }
            changes.incrementAndGet()
            // Reverted, unless a later tap has changed it since.
            _known.update { known ->
                when {
                    known[uri] != favorite -> known
                    before == null -> known - uri
                    else -> known + (uri to before)
                }
            }
            library.setFavoriteLocally(uri, !favorite)
            messages.show(failureText(failure), retry = { set(uri, favorite) })
        }
    }

    // Only items read or changed here: the library index applies the events itself.
    private fun followed(uri: String): Boolean = uri in _known.value || libraryUris.containsValue(uri)

    private fun apply(uri: String, favorite: Boolean) {
        val aliases = libraryUris.filterValues { it == uri }.keys
        _known.update { known ->
            (aliases + uri).filter { it in known }.fold(known) { all, key -> all + (key to favorite) }
        }
    }

    private fun forget() {
        _known.value = emptyMap()
        libraryUris.clear()
    }
}
