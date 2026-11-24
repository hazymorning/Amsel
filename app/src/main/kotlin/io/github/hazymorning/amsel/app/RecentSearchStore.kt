package io.github.hazymorning.amsel.app

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable

// A song keeps its TrackUi, so its ⋮ still offers its album and artist.
@Immutable
sealed interface RecentItem {
    val key: String

    data class Song(val track: TrackUi) : RecentItem {
        override val key: String get() = track.key
    }

    data class Other(val item: MediaUi) : RecentItem {
        override val key: String get() = item.key
    }
}

class RecentSearchStore(context: Context, scope: CoroutineScope) {
    private val store = JsonListStore(File(context.filesDir, "recent_searches.json"), Stored.serializer(), scope)

    val items: StateFlow<List<RecentItem>> =
        store.items.map { all -> all.mapNotNull(Stored::item) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(track: TrackUi) = add(Stored(track))

    fun add(item: MediaUi) = add(Stored(item))

    fun clear() = store.change { emptyList() }

    private fun add(entry: Stored) = store.change { all ->
        (listOf(entry) + all.filterNot { it.key == entry.key }).take(MAX)
    }

    @Serializable
    private data class Stored(
        val key: String,
        val kind: String,
        val title: String,
        val subtitle: String? = null,
        val art: String? = null,
        val parentKey: String? = null,
        val album: String? = null,
        val albumKey: String? = null,
        val artistKey: String? = null,
        val dynamic: Boolean = false,
        // An artist's album covers in a 2x2 grid, or a smart playlist's crop of the server's collage.
        val quarters: List<String> = emptyList(),
        val mosaic: Boolean = false,
    ) {
        constructor(track: TrackUi) : this(
            track.key,
            MediaKind.Track.name,
            track.title,
            track.artist,
            track.art?.base,
            album = track.album,
            albumKey = track.albumKey,
            artistKey = track.artistKey,
        )

        constructor(item: MediaUi) : this(
            item.key,
            item.kind.name,
            item.title,
            item.subtitle,
            item.art?.base,
            parentKey = item.parentKey,
            artistKey = item.artistKey,
            dynamic = item.dynamic,
            quarters = item.art?.quarters?.map { it.base }.orEmpty(),
            mosaic = item.art?.mosaic == true,
        )

        fun item(): RecentItem? {
            val kind = MediaKind.entries.firstOrNull { it.name == kind } ?: return null
            val art = art?.let { ArtRef(it, mosaic, quarters.map(::ArtRef)) }
            return if (kind == MediaKind.Track) {
                RecentItem.Song(
                    TrackUi(key, title, subtitle.orEmpty(), album, art, albumKey = albumKey, artistKey = artistKey),
                )
            } else {
                val item = MediaUi(key, kind, title, subtitle, art, parentKey = parentKey, artistKey = artistKey)
                RecentItem.Other(item.copy(dynamic = dynamic))
            }
        }
    }

    private companion object {
        const val MAX = 12
    }
}
