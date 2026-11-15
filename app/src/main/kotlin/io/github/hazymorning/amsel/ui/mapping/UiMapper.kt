package io.github.hazymorning.amsel.ui.mapping

import io.github.hazymorning.amsel.api.DJ_PLUGIN
import io.github.hazymorning.amsel.api.Images
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.MediaItemImage
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.Player
import io.github.hazymorning.amsel.api.PlayerQueue
import io.github.hazymorning.amsel.api.QueueItem
import io.github.hazymorning.amsel.api.endlessMixOf
import io.github.hazymorning.amsel.api.listening
import io.github.hazymorning.amsel.data.downloads.downloadable
import io.github.hazymorning.amsel.data.home.HomeItem
import io.github.hazymorning.amsel.data.library.ALBUM_COVERS
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import io.github.hazymorning.amsel.playback.QueueEntry
import io.github.hazymorning.amsel.playback.SpeakerSnapshot
import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.QueueItemUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import kotlin.math.roundToLong

/** Turns server and index data into the UI's immutable models. Pure functions, no I/O. */
class UiMapper(private val images: () -> Images?) {
    fun art(image: MediaItemImage?): ArtRef? = images()?.url(image)?.let(::ArtRef)

    fun artFromProxyId(proxyId: String?): ArtRef? = proxyId?.let { art(MediaItemImage(proxyId = it)) }

    /** An artist without a picture wears its albums: four two by two, else the newest one. */
    private fun albumsArt(covers: String?): ArtRef? {
        val parts = covers?.lines()?.mapNotNull(::artFromProxyId).orEmpty()
        return parts.firstOrNull()?.copy(quarters = parts.takeIf { it.size == ALBUM_COVERS }.orEmpty())
    }

    /** What an item is to the UI; one of a type the app does not list (a stream, an announcement) plays as a track. */
    private fun kind(mediaType: String?): MediaKind = when (mediaType) {
        MediaType.Album.api -> MediaKind.Album
        MediaType.Artist.api -> MediaKind.Artist
        MediaType.Playlist.api -> MediaKind.Playlist
        MediaType.Radio.api -> MediaKind.Radio
        MediaType.Audiobook.api -> MediaKind.Audiobook
        MediaType.Podcast.api -> MediaKind.Podcast
        MediaType.PodcastEpisode.api -> MediaKind.Episode
        else -> MediaKind.Track
    }

    fun media(item: MediaItem): MediaUi {
        val where = listening(item.duration, item.resumePositionMs, item.fullyPlayed == true)
        return MediaUi(
            key = item.uri,
            kind = kind(item.mediaType),
            title = item.name,
            subtitle = item.subtitle,
            art = art(item.cover)?.copy(mosaic = item.isSmartPlaylist),
            favorite = item.favorite == true,
            progress = where?.first,
            remainingSec = where?.second,
            resumeMs = item.resumePositionMs,
            fullyPlayed = item.fullyPlayed == true,
            parentKey = item.podcast?.uri,
            artistKey = item.artistUri,
            downloadable = item.downloadable,
            dynamic = item.isDynamic,
        )
    }

    fun media(entity: LibraryItemEntity): MediaUi {
        val where = listening(entity.durationSec, entity.resumeMs, entity.fullyPlayed == true)
        return MediaUi(
            key = entity.uri,
            kind = kind(entity.mediaType),
            title = entity.name,
            subtitle = entity.subtitle,
            art = artFromProxyId(entity.imageProxyId)?.copy(mosaic = entity.isSmart) ?: albumsArt(entity.albumCovers),
            favorite = entity.favorite,
            progress = where?.first,
            remainingSec = where?.second,
            resumeMs = entity.resumeMs,
            fullyPlayed = entity.fullyPlayed == true,
            artistKey = entity.artistUri,
            dynamic = entity.isDynamic,
        )
    }

    fun media(item: HomeItem): MediaUi {
        val where = listening(item.durationSec, item.resumeMs, item.fullyPlayed)
        return MediaUi(
            key = item.uri,
            kind = kind(item.mediaType),
            title = item.name,
            subtitle = item.subtitle,
            art = art(item.image)?.copy(mosaic = item.smart),
            favorite = item.favorite,
            progress = where?.first,
            remainingSec = where?.second,
            resumeMs = item.resumeMs,
            parentKey = item.parentUri,
            fullyPlayed = item.fullyPlayed,
            artistKey = item.artistUri,
            dynamic = item.dynamic,
        )
    }

    fun track(item: MediaItem): TrackUi = TrackUi(
        key = item.uri,
        title = item.name,
        artist = item.artistNames,
        album = albumBesides(item.name, item.album?.name),
        art = art(item.cover),
        durationSec = item.duration ?: 0,
        number = item.trackNumber,
        favorite = item.favorite == true,
        albumKey = item.album?.uri,
        artistKey = item.artists.firstOrNull()?.uri,
        djBreak = item.isDjBreak,
    )

    fun queueItem(item: QueueItem, currentId: String?): QueueItemUi {
        val track = item.mediaItem?.let(::track)
            ?: TrackUi(key = item.queueItemId, title = item.name, artist = "", durationSec = item.duration ?: 0)
        return QueueItemUi(
            item.queueItemId,
            track.copy(art = track.art ?: art(item.image)),
            isCurrent = item.queueItemId == currentId,
        )
    }

    /** A speaker row: what it plays, from its current media; [others] is how many play in step with it. */
    fun speaker(player: Player, isPhone: Boolean, others: Int = 0): SpeakerUi {
        val media = player.currentMedia
        return SpeakerUi(
            id = player.playerId,
            name = player.shownName,
            isPhone = isPhone,
            song = media?.title?.ifBlank { null },
            artist = media?.artist?.ifBlank { null },
            isPlaying = player.isPlaying,
            available = player.available,
            others = others,
            volume = player.volume.takeIf { player.hasVolume && !isPhone },
            art = images()?.rebase(media?.imageUrl)?.let(::ArtRef),
        )
    }

    /**
     * Now Playing for a speaker: what plays and the transport as its [snapshot] has them (the same
     * the notification shows), and from the queue's item, when that is what plays, its links.
     */
    fun nowPlaying(player: Player, queue: PlayerQueue?, snapshot: SpeakerSnapshot, speaker: SpeakerUi): NowPlayingUi? {
        val current = snapshot.current ?: return null
        val item = queue?.currentItem?.takeIf { it.queueItemId == current.queueItemId }
        val shown = playing(current, item, snapshot, speaker, queue?.endlessMixOf).onAir(player)
        return if (shown.art !=
            null
        ) {
            shown
        } else {
            shown.copy(art = images()?.rebase(player.currentMedia?.imageUrl)?.let(::ArtRef))
        }
    }

    /** Now Playing once a skip reaches the queue's next item, from its start; null when none follows. */
    fun nowPlayingNext(queue: PlayerQueue?, snapshot: SpeakerSnapshot, speaker: SpeakerUi): NowPlayingUi? {
        val next = snapshot.next ?: return null
        val item = queue?.nextItem?.takeIf { it.queueItemId == next.queueItemId }
        return playing(next, item, snapshot, speaker, queue?.endlessMixOf).copy(positionMs = 0)
    }

    private fun playing(
        entry: QueueEntry,
        item: QueueItem?,
        snapshot: SpeakerSnapshot,
        speaker: SpeakerUi,
        mix: String?,
    ): NowPlayingUi {
        val media = item?.mediaItem
        return NowPlayingUi(
            title = entry.title,
            artist = entry.artist.orEmpty(),
            album = albumBesides(entry.title, entry.album),
            art = art(media?.cover ?: item?.image),
            durationMs = (entry.durationSeconds ?: 0) * 1000L,
            positionMs = (snapshot.elapsedSeconds * 1000).roundToLong(),
            positionAtMs = snapshot.elapsedAtMs,
            // A seek starts the speaker playing, whatever it did before.
            isPlaying = snapshot.playing || snapshot.seeking,
            arriving = snapshot.seeking,
            canSkip = snapshot.canSkip && snapshot.available,
            canSeek = snapshot.canSeek && snapshot.available,
            shuffle = snapshot.shuffle,
            canShuffle = snapshot.canShuffle && snapshot.available,
            repeat = snapshot.repeat,
            speaker = speaker,
            volume = snapshot.volumeLevel?.takeIf { snapshot.canSetVolume },
            albumKey = media?.album?.uri,
            artistKey = media?.artistUri,
            key = media?.uri,
            kind = kind(entry.mediaType),
            favorite = media?.favorite == true,
            speed = snapshot.speed.toFloat(),
            chapters = media?.let(::chapterList).orEmpty(),
            parentKey = media?.podcast?.uri,
            fullyPlayed = media?.fullyPlayed == true,
            // Another source on the speaker is not the mix its queue still holds.
            mix = mix.takeIf { item != null },
            djBreak = media?.isDjBreak == true,
        )
    }
}

/** What the DJ says between two songs: its plugin puts each as an item of its own into the queue. */
private val MediaItem.isDjBreak: Boolean get() = mediaType == SOUND_EFFECT && provider == DJ_PLUGIN

private const val SOUND_EFFECT = "sound_effect"

/**
 * A song's album, or null for a single: an album named as the song is, a "- Single" or "(EP)" after
 * it aside, would only say the title again.
 */
internal fun albumBesides(title: String, album: String?): String? {
    val name = album?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return name.takeUnless { it.withoutRelease().equals(title.trim().withoutRelease(), ignoreCase = true) }
}

private fun String.withoutRelease(): String = replace(RELEASE, "").trim()

private val RELEASE = Regex("""\s*(?:[-–—]\s*(?:single|ep)|[(\[](?:single|ep)[)\]])\s*$""", RegexOption.IGNORE_CASE)

/**
 * A station names the song on air, where the speaker reports one: the song is the title and the
 * station stands under it, after the song's artist. Without one the station keeps the title.
 */
internal fun NowPlayingUi.onAir(player: Player): NowPlayingUi {
    if (kind != MediaKind.Radio) return this
    val media = player.currentMedia
    val song = media?.title?.trim()?.takeIf { it.isNotEmpty() && !it.equals(title, ignoreCase = true) } ?: return this
    val by = listOfNotNull(media.artist?.trim()?.ifEmpty { null }, title).joinToString(DOT_SEPARATOR)
    return copy(title = song, artist = by)
}
