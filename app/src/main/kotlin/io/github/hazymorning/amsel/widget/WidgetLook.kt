package io.github.hazymorning.amsel.widget

import androidx.compose.runtime.Immutable
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import kotlinx.serialization.Serializable

/** What every widget shows: the speaker in use and what plays there. Kept on the phone between runs. */
@Immutable
@Serializable
data class WidgetLook(
    val signedIn: Boolean = true,
    /** As the dock names it: "This phone" in the person's language, a group's "+2" after it. */
    val speaker: String = "",
    /** 0 to 100; null for This phone and a speaker whose volume is fixed. */
    val volume: Int? = null,
    /** This phone: its volume is the phone's own. */
    val phone: Boolean = false,
    /** Null while nothing plays there: "Not playing". */
    val title: String? = null,
    val subtitle: String? = null,
    /** The cover's address on the server, at [COVER_PX]. */
    val art: String? = null,
    val playing: Boolean = false,
    /** A book or an episode: the skips are 30 s forward and 15 s back, as on the dock. */
    val spoken: Boolean = false,
    /** A tap that did not reach the speaker, said where the artist stands until the next change. */
    val failure: String? = null,
)

/** The size the cover is asked for: it is blurred down to [WidgetArt.SIDE] anyway. */
const val COVER_PX = 128

/**
 * What the widgets show for [speaker], named [name], and what plays there ([nowPlaying]). Unless the
 * connection is up the music rests as last heard: nothing says it still plays.
 */
fun widgetLook(speaker: SpeakerUi, name: String, nowPlaying: NowPlayingUi?, session: SessionState): WidgetLook {
    val signedIn = session != SessionState.NeedsSetup && session != SessionState.Unsupported
    val connected = session is SessionState.Ready
    val label = if (speaker.others > 0) "$name +${speaker.others}" else name
    return WidgetLook(
        signedIn = signedIn,
        speaker = label,
        volume = (nowPlaying?.volume ?: speaker.volume).takeUnless { speaker.isPhone },
        phone = speaker.isPhone,
        title = nowPlaying?.title?.ifBlank { null },
        subtitle = nowPlaying?.let { np -> np.artist.ifBlank { np.album.orEmpty() }.ifBlank { null } },
        art = nowPlaying?.art?.sized(COVER_PX),
        playing = connected && nowPlaying?.isPlaying == true,
        spoken = nowPlaying?.spoken == true,
    )
}
