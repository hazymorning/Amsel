package io.github.hazymorning.amsel.widget

import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import kotlin.math.abs
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class WidgetContent(
    val signedIn: Boolean = true,
    /** Null until the speaker is known. */
    val speakerId: String? = null,
    val speaker: String = "",
    /** 0 to 100; null for This phone and a speaker without volume control. */
    val volume: Int? = null,
    val phone: Boolean = false,
    /** Null while nothing plays. */
    val title: String? = null,
    val subtitle: String? = null,
    /** At [COVER_PX], for the blurred background. */
    val art: String? = null,
    /** At [ART_PX], for covers shown sharp and the Darkened background. */
    val artLarge: String? = null,
    val playing: Boolean = false,
    /** A book or an episode. */
    val spoken: Boolean = false,
    /** A failed tap's message, shown in place of the subtitle until the content changes. */
    val failure: String? = null,
    /** The item's uri. */
    val key: String? = null,
    /** Null where the item cannot be a favourite or the user may not change it. */
    val favourite: Boolean? = null,
    /** Null, as [repeat], for a book, an episode, a station, or where shuffle is unavailable. */
    val shuffle: Boolean? = null,
    val repeat: RepeatMode? = null,
    /** 0 where unknown, as for a station. */
    val durationMs: Long = 0,
    /**
     * [android.os.SystemClock.elapsedRealtime] to the second, for a Chronometer: a song's virtual
     * start, or a book's or an episode's end at its speed. Null while not playing.
     */
    val clockMs: Long? = null,
    /** While not playing: a song's position, or the time a book or an episode has left at its speed. */
    val restMs: Long? = null,
) {
    val hasTime: Boolean get() = signedIn && title != null && durationMs > 0 && (clockMs != null || restMs != null)

    /** For stored content read after a restart: not playing, position unknown. */
    fun atRest(): WidgetContent = copy(playing = false, failure = null, clockMs = null, restMs = null)
}

/** Small: the cover is scaled down to [WidgetArt.SIDE] for the blur. */
const val COVER_PX = 128

/** For covers shown sharp: a 4 × 3 player on a 411 dp phone shows one of 162 dp, 486 px at xxhdpi. */
const val ART_PX = 512

/**
 * Without [current] (connected, closed on purpose, or refreshed since) the last known state shows
 * as not playing and without a position: it may be stale.
 */
fun widgetContent(
    speaker: SpeakerUi,
    name: String,
    nowPlaying: NowPlayingUi?,
    session: SessionState,
    canWrite: Boolean = false,
    current: Boolean = session is SessionState.Ready,
): WidgetContent {
    val signedIn = session != SessionState.NeedsSetup && session != SessionState.Unsupported
    val connected = signedIn && current
    val label = if (speaker.others > 0) "$name +${speaker.others}" else name
    val runs = connected && nowPlaying?.runs == true
    val music = nowPlaying?.takeIf { it.canShuffle && !it.spoken && it.durationMs > 0 }
    return WidgetContent(
        signedIn = signedIn,
        speakerId = speaker.id,
        speaker = label,
        volume = (nowPlaying?.volume ?: speaker.volume).takeUnless { speaker.isPhone },
        phone = speaker.isPhone,
        title = nowPlaying?.title?.ifBlank { null },
        subtitle = nowPlaying?.let { np -> np.artist.ifBlank { np.album.orEmpty() }.ifBlank { null } },
        art = nowPlaying?.art?.sized(COVER_PX),
        artLarge = nowPlaying?.art?.sized(ART_PX),
        playing = connected && nowPlaying?.isPlaying == true,
        spoken = nowPlaying?.spoken == true,
        key = nowPlaying?.key,
        favourite = nowPlaying?.heart(canWrite),
        shuffle = music?.shuffle,
        repeat = music?.repeat,
        durationMs = nowPlaying?.durationMs?.coerceAtLeast(0) ?: 0,
        clockMs = nowPlaying?.takeIf { runs && it.durationMs > 0 }?.let { np ->
            if (np.spoken) {
                np.positionAtMs + ((np.durationMs - np.positionMs) / np.pace).toLong()
            } else {
                np.positionAtMs - np.positionMs
            }.let(::toSecond)
        },
        restMs = nowPlaying?.takeIf { connected && !runs && it.durationMs > 0 }?.let { np ->
            if (np.spoken) ((np.durationMs - np.positionMs) / np.pace).toLong() else np.positionMs
        },
    )
}

private val NowPlayingUi.pace: Double get() = speed.toDouble().takeIf { it > 0 } ?: 1.0

/** Rounded so the jitter of position reports during playback does not redraw the widgets. */
private fun toSecond(atMs: Long): Long = Math.floorDiv(atMs + SECOND_MS / 2, SECOND_MS) * SECOND_MS

private const val SECOND_MS = 1000L

/** Keeps a clock that moved by at most CLOCK_SLACK_MS since [shown]: position reports jitter, and each change redraws. */
internal fun steadyClocks(
    looks: Map<String, WidgetContent>,
    shown: Map<String, WidgetContent>?,
): Map<String, WidgetContent> = looks.mapValues { (key, look) ->
    val before = shown?.get(key)?.clockMs
    val now = look.clockMs
    if (before != null && now != null && abs(before - now) <= CLOCK_SLACK_MS) look.copy(clockMs = before) else look
}

private const val CLOCK_SLACK_MS = 1_500L
