package io.github.hazymorning.amsel.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.orNullOnFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Media ids in the browse tree. A track row carries its parent so play_media can use start_item. */
object BrowseIds {
    const val ROOT = "root"
    const val RECENT = "tab:recent"
    const val PLAYLISTS = "tab:playlist"
    const val ALBUMS = "tab:album"
    const val ARTISTS = "tab:artist"
    const val AUDIOBOOKS = "tab:audiobook"
    const val PODCASTS = "tab:podcast"
    const val LIBRARY = "folder:library"

    /** What a media button resumes while Amsel is closed: whatever the speaker holds (RemotePlayer). */
    const val RESUME = "resume"
    private const val SEP = "|"

    fun track(parentUri: String, trackUri: String) = "$parentUri$SEP$trackUri"

    /**
     * The play on [queueId] the car asks for. The item at [startIndex] decides: `album|track` plays
     * the album from that track; plain uris play in order from it (the server's start_item works
     * only within an album or a playlist).
     */
    fun toPlayRequest(queueId: String, mediaIds: List<String>, startIndex: Int): PlayRequest? {
        val ids = mediaIds.drop(startIndex.coerceIn(0, mediaIds.lastIndex.coerceAtLeast(0))).filter {
            it.contains("://")
        }
        val first = ids.firstOrNull() ?: return null
        if (first.contains(SEP)) {
            val (parent, track) = first.split(SEP, limit = 2)
            return PlayRequest(queueId, listOf(parent), startItem = track)
        }
        return PlayRequest(queueId, ids.map { it.substringAfter(SEP) })
    }
}

/**
 * What each page of the car's tree lists, read live from [catalog]: the tabs, a tab's or a
 * folder's items, and Recently played. [BrowseTree] answers the car with them; [text] reads the
 * titles from `:playback`'s strings, and covers are [artwork] URIs.
 */
@OptIn(UnstableApi::class)
internal class BrowsePages(
    private val catalog: Catalog,
    private val text: (Int) -> String,
    private val artwork: CarArtwork,
) {
    /**
     * Page [page] of [parentId]'s items, at most [MAX_PAGE] of them; the root lists [rootTabs] tabs,
     * as many as the browser shows. Android Auto does not page (Media3 asks it for page 0 of
     * Int.MAX_VALUE), so a folder shows its first [MAX_PAGE] items there.
     */
    suspend fun children(parentId: String, page: Int, pageSize: Int, rootTabs: Int?): List<MediaItem> {
        val limit = pageSize.coerceAtMost(MAX_PAGE)
        // Counted in what each page returns: a browser paging in larger steps skips nothing.
        val offset = page * limit
        // The tabs and Recently played are not paged: page 0 holds them, as much as a page holds.
        if (page > 0 && parentId in UNPAGED) return emptyList()
        return when (parentId) {
            BrowseIds.ROOT -> tabs().take(rootTabs ?: ROOT_TABS)

            BrowseIds.LIBRARY -> {
                listOf(folder(BrowseIds.ARTISTS, text(R.string.artists))) + spokenTabs()
            }

            BrowseIds.RECENT -> {
                recent(limit)
            }

            BrowseIds.PLAYLISTS, BrowseIds.ALBUMS, BrowseIds.ARTISTS, BrowseIds.AUDIOBOOKS, BrowseIds.PODCASTS -> {
                catalog.library(parentId.substringAfter(':'), offset, limit).map { it.toItem(parentId) }
            }

            else -> {
                catalog.children(parentId, offset, limit).map { it.toItem(parentId) }
            }
        }
    }

    private suspend fun tabs(): List<MediaItem> {
        val fourth =
            if (spokenTabs().isEmpty()) {
                folder(BrowseIds.ARTISTS, text(R.string.artists))
            } else {
                folder(BrowseIds.LIBRARY, text(R.string.library))
            }
        return listOf(
            folder(BrowseIds.RECENT, text(R.string.recently_played)),
            folder(BrowseIds.PLAYLISTS, text(R.string.playlists)),
            folder(BrowseIds.ALBUMS, text(R.string.albums)),
            fourth,
        )
    }

    /** Audiobooks (their covers in a grid) and Podcasts, each only while the library holds some. */
    private suspend fun spokenTabs(): List<MediaItem> = buildList {
        if (holds(BrowseIds.AUDIOBOOKS)) {
            val grid = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            val audiobooks = text(R.string.audiobooks)
            add(folder(BrowseIds.AUDIOBOOKS, audiobooks, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS, grid))
        }
        if (holds(BrowseIds.PODCASTS)) {
            add(folder(BrowseIds.PODCASTS, text(R.string.podcasts), MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS))
        }
    }

    private suspend fun holds(tab: String) = catalog.holds(tab.substringAfter(':'))

    /**
     * Books and episodes to continue come first under their own heading, then the music. Both are
     * asked at once: one after the other, an unreachable server would keep the car waiting twice.
     */
    private suspend fun recent(limit: Int): List<MediaItem> = coroutineScope {
        // Music still lists when the books and episodes in progress cannot be read.
        val reading = async { orNullOnFailure { catalog.inProgress(CONTINUE_LIMIT) }.orEmpty() }
        val music = catalog.recent(limit)
        val started = reading.await()
        if (started.isEmpty()) return@coroutineScope music.map { it.toItem(BrowseIds.RECENT) }
        val continuing = text(R.string.continue_listening)
        val musicGroup = text(R.string.browse_music)
        // Media3 refuses a page longer than the browser asked for.
        (
            started.map { it.toItem(BrowseIds.RECENT, continuing) } +
                music.map { it.toItem(BrowseIds.RECENT, musicGroup) }
            )
            .take(limit)
    }

    private fun BrowseNode.toItem(parentId: String, group: String? = null): MediaItem {
        // An episode plays alone: from its podcast, start_item would queue every newer episode too.
        val alone = browsable || parentId.startsWith("tab:") || mediaType == MediaType.PodcastEpisode.api
        val extras =
            Bundle().apply {
                group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
                played?.let { putCompletion(it, finished) }
            }
        val metadata =
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setArtist(subtitle)
                .setArtworkUri(cover?.proxyId?.let(artwork::uri))
                .setIsBrowsable(browsable)
                .setIsPlayable(true)
                .setMediaType(media3Type(mediaType, browsable))
                .setExtras(extras.takeUnless { it.isEmpty })
                .build()
        return MediaItem
            .Builder()
            .setMediaId(if (alone) uri else BrowseIds.track(parentId, uri))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun Bundle.putCompletion(played: Double, finished: Boolean) {
        val status =
            when {
                finished -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
                played > 0 -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
                else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
            }
        putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)
        if (status == MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED) {
            putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, played)
        }
    }

    private companion object {
        const val MAX_PAGE = 100
        const val CONTINUE_LIMIT = 10

        /** Root tabs where a browser names no limit: Android Auto shows four. */
        const val ROOT_TABS = 4

        val UNPAGED = setOf(BrowseIds.ROOT, BrowseIds.LIBRARY, BrowseIds.RECENT)
    }
}

/** A folder; [playableStyle] sets how its playable children show (a list unless given). */
@OptIn(UnstableApi::class)
internal fun folder(
    id: String,
    title: String,
    type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    playableStyle: Int? = null,
): MediaItem = MediaItem
    .Builder()
    .setMediaId(id)
    .setMediaMetadata(
        MediaMetadata
            .Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(type)
            .setExtras(
                playableStyle?.let {
                    Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it) }
                },
            ).build(),
    ).build()

/** Media3's media type for an MA one; a browsable item that is not a book or podcast lists as an album. */
internal fun media3Type(mediaType: String, browsable: Boolean): Int = when (mediaType) {
    MediaType.Audiobook.api -> MediaMetadata.MEDIA_TYPE_AUDIO_BOOK
    MediaType.Podcast.api -> MediaMetadata.MEDIA_TYPE_PODCAST
    MediaType.PodcastEpisode.api -> MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
    else -> if (browsable) MediaMetadata.MEDIA_TYPE_ALBUM else MediaMetadata.MEDIA_TYPE_MUSIC
}
