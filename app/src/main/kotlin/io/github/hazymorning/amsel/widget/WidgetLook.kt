package io.github.hazymorning.amsel.widget

import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import kotlin.math.abs
import kotlinx.serialization.Serializable

/** What a widget shows: a speaker and what plays there. Kept on the phone between runs. */
@Immutable
@Serializable
data class WidgetLook(
    val signedIn: Boolean = true,
    /** The speaker's player id; null before Amsel knows one. */
    val speakerId: String? = null,
    /** As the dock names it: "This phone" in the person's language, a group's "+2" after it. */
    val speaker: String = "",
    /** 0 to 100; null for This phone and a speaker whose volume is fixed. */
    val volume: Int? = null,
    /** This phone: its volume is the phone's own. */
    val phone: Boolean = false,
    /** Null while nothing plays there: "Not playing". */
    val title: String? = null,
    val subtitle: String? = null,
    /** The cover's address on the server, at [COVER_PX], for the blurred ground. */
    val art: String? = null,
    /** The same cover at [ART_PX], for a bar's, a player's or a tile's cover, and the Darkened look's ground. */
    val artLarge: String? = null,
    val playing: Boolean = false,
    /** A book or an episode: the skips are 30 s forward and 15 s back, as on the dock. */
    val spoken: Boolean = false,
    /** A tap that did not reach the speaker, said where the artist stands until the next change. */
    val failure: String? = null,
    /** The item's uri, for its heart. */
    val key: String? = null,
    /** Whether it is a favourite; null where it cannot be one or the person may not change that. */
    val favourite: Boolean? = null,
    /** Null, as [repeat], where what plays is a book, an episode or a station. */
    val shuffle: Boolean? = null,
    val repeat: RepeatMode? = null,
    /** Its length; 0 where none is known, a station's. */
    val durationMs: Long = 0,
    /**
     * Its time, on the clock since the phone started ([android.os.SystemClock.elapsedRealtime]), to
     * the second: while a song plays, when it would have begun; while a book or an episode plays,
     * when it ends at its speed. Null while it rests.
     */
    val clockMs: Long? = null,
    /** While it rests: where a song stands, or how long a book or an episode has left at its speed. */
    val restMs: Long? = null,
) {
    /** Whether its time can show: it has a length and Amsel knows where it stands. */
    val hasTime: Boolean get() = signedIn && title != null && durationMs > 0 && (clockMs != null || restMs != null)

    /** As it shows from the phone's files at the next start: at rest, where it stood unknown. */
    fun atRest(): WidgetLook = copy(playing = false, failure = null, clockMs = null, restMs = null)
}

/** The size the cover is asked for: it is blurred down to [WidgetArt.SIDE] anyway. */
const val COVER_PX = 128

/** A cover shown sharp: a player's, four cells by two, is about 140 dp, 420 px. */
const val ART_PX = 512

/**
 * What the widgets show for [speaker], named [name], and what plays there ([nowPlaying]). Unless
 * what the server last said still holds ([current]: connected, closed on purpose since, or read on
 * a glance since) the music rests as last heard: nothing says it still plays.
 */
fun widgetLook(
    speaker: SpeakerUi,
    name: String,
    nowPlaying: NowPlayingUi?,
    session: SessionState,
    canWrite: Boolean = false,
    current: Boolean = session is SessionState.Ready,
): WidgetLook {
    val signedIn = session != SessionState.NeedsSetup && session != SessionState.Unsupported
    val connected = signedIn && current
    val label = if (speaker.others > 0) "$name +${speaker.others}" else name
    val runs = connected && nowPlaying?.runs == true
    val music = nowPlaying?.takeIf { it.canShuffle && !it.spoken && it.durationMs > 0 }
    return WidgetLook(
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

/** To the second, so the reports a playing song sends on its way do not draw the widgets again. */
private fun toSecond(atMs: Long): Long = Math.floorDiv(atMs + SECOND_MS / 2, SECOND_MS) * SECOND_MS

private const val SECOND_MS = 1000L

/**
 * [looks], each clock that moved by a second and a half or less since [shown] kept as shown: the
 * reports a playing song sends on its way jitter, and the widgets would be drawn again for nothing.
 */
internal fun steadyClocks(looks: Map<String, WidgetLook>, shown: Map<String, WidgetLook>?): Map<String, WidgetLook> =
    looks.mapValues { (key, look) ->
        val before = shown?.get(key)?.clockMs
        val now = look.clockMs
        if (before != null && now != null && abs(before - now) <= CLOCK_SLACK_MS) look.copy(clockMs = before) else look
    }

private const val CLOCK_SLACK_MS = 1_500L
