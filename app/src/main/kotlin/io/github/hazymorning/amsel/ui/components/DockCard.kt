package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.playback.SKIP_FORWARD_MS
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.byline
import io.github.hazymorning.amsel.ui.screens.player.SkipSeconds
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space

/** The dock's play glyph, a step up from the next beside it. */
private val PlayGlyph = 28.dp

/**
 * Around what plays on the dock: the page's margin, so its cover stands on the margin as a page's
 * covers do, as far from its band's edges as in from the screen's.
 */
private val Inset = Keyline.Margin

/** Between the disc and play: the speaker reads as its own control, apart from play and skip. */
private val DiscToPlay = Space.S6

/** The speaker's disc stands this far inside its 48 dp target. */
private val DiscReach = 4.dp

/**
 * What plays, on the dock's own surface, with no card around it: the cover on the margin, the
 * speaker's name over what plays, the speaker's button, play and a skip, the skip's ink on the
 * margin across. It stays when nothing plays, keeping the speaker's button: where music goes can be
 * chosen before the first song. A cover here, and one still empty, stands a tone above the dock, as
 * a cover does above the page.
 */
@Composable
internal fun PlayerCard(
    nowPlaying: NowPlayingUi?,
    speaker: SpeakerUi,
    covered: Boolean,
    onOpen: () -> Unit,
    onControl: (PlayerControl) -> Unit,
    onSpeaker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = remember { mutableStateOf(nowPlaying) }
    SideEffect { if (nowPlaying != null) last.value = nowPlaying }
    val colors = MaterialTheme.colorScheme
    val raised = remember(colors) { colors.copy(surfaceContainerLow = colors.surfaceContainerHigh) }
    MaterialTheme(colorScheme = raised) {
        Box(modifier.fillMaxWidth()) {
            Crossfade(nowPlaying != null, animationSpec = Motion.effects(), label = "player") { playing ->
                val shown = nowPlaying ?: last.value
                if (playing && shown != null) {
                    PlayerRow(shown, speaker, covered, onOpen, onControl, onSpeaker)
                } else {
                    IdleRow(speaker, onSpeaker)
                }
            }
        }
    }
}

/**
 * Nothing plays: an empty cover, the speaker's name over "Not playing", and the speaker's button.
 * A tap anywhere on the card opens the speaker choice, the one thing to do here; TalkBack reads
 * the words and finds the button.
 */
@Composable
private fun IdleRow(speaker: SpeakerUi, onSpeaker: () -> Unit) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val words = stringResource(R.string.player_not_playing)
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(onSpeaker) { detectTapGestures { onSpeaker() } }
            .padding(start = Inset, top = Inset, end = Inset - DiscReach, bottom = Inset)
            .heightIn(min = Sizes.DockArt),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(null, Modifier.size(Sizes.DockArt), MediaKind.Track)
        Spacer(Modifier.width(Space.Sibling))
        RowLines(type.labelLarge, besideCover = true, modifier = Modifier.weight(1f)) {
            SpeakerName(speaker, type.labelLarge, colors.onSurface)
            Words(
                words,
                type.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.inkStart(words, type.bodyMedium),
            )
        }
        Spacer(Modifier.width(Space.S2))
        SpeakerDisc(speaker, onSpeaker)
    }
}

/**
 * Cover and words ([PlayerWords]), then the speaker's button, play and the skip: next for music,
 * forward by seconds for a book or an episode, as the notification has them. A tap or a drag up
 * opens Now Playing; a sideways swipe goes to the next or the one before, the song's line
 * following the finger.
 */
@Composable
private fun PlayerRow(
    state: NowPlayingUi,
    speaker: SpeakerUi,
    covered: Boolean,
    onOpen: () -> Unit,
    onControl: (PlayerControl) -> Unit,
    onSpeaker: () -> Unit,
) {
    val openLabel = stringResource(R.string.open_now_playing)
    val nextLabel = stringResource(state.nextLabel)
    val previousLabel = stringResource(state.previousLabel)
    val haptics = LocalHapticFeedback.current
    val skip: (Boolean) -> Unit = { forward ->
        onControl(if (forward) PlayerControl.Next else PlayerControl.Previous)
    }
    val swipe = rememberSideSwipe(state.key ?: state.title, enabled = state.canSkip, onGo = skip)
    val skipGlyph = if (state.spoken) R.drawable.ic_skip_forward else R.drawable.ic_skip_next
    val skipSize = if (state.spoken) PlayGlyph else Sizes.Icon
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(null, indication = null, role = Role.Button, onClick = onOpen)
            .then(swipe.detect)
            .semantics {
                onClick(label = openLabel) {
                    onOpen()
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(nextLabel) {
                        skip(true)
                        true
                    },
                    CustomAccessibilityAction(previousLabel) {
                        skip(false)
                        true
                    },
                )
            }
            .padding(
                start = Inset,
                top = Inset,
                end = (Inset - endReach(skipGlyph, skipSize)).coerceAtLeast(0.dp),
                bottom = Inset,
            )
            .heightIn(min = Sizes.DockArt),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            state.art,
            Modifier.size(Sizes.DockArt),
            if (state.spoken) state.kind else MediaKind.Track,
            name = state.album ?: state.title,
        )
        Spacer(Modifier.width(Space.Sibling))
        PlayerWords(state, speaker, Modifier.weight(1f), song = swipe.follow, runs = !covered)
        Spacer(Modifier.width(Space.S2))
        SpeakerDisc(speaker, onSpeaker)
        Spacer(Modifier.width(DiscToPlay))
        val ink = MaterialTheme.colorScheme.onSurface
        IconAction(
            if (state.isPlaying) R.drawable.ic_pause_slim else R.drawable.ic_play,
            stringResource(if (state.isPlaying) R.string.pause else R.string.play),
            onClick = {
                haptics.performHapticFeedback(
                    if (state.isPlaying) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                )
                onControl(PlayerControl.PlayPause)
            },
            tint = ink,
            size = PlayGlyph,
        )
        if (state.spoken) {
            val seconds = (SKIP_FORWARD_MS / 1000).toInt()
            SkipSeconds(
                R.drawable.ic_skip_forward,
                seconds,
                pluralStringResource(PlaybackR.plurals.forward_seconds, seconds, seconds),
                size = PlayGlyph,
            ) {
                onControl(PlayerControl.SeekForward)
            }
        } else {
            IconAction(
                R.drawable.ic_skip_next,
                nextLabel,
                { onControl(PlayerControl.Next) },
                tint = ink,
                enabled = state.canSkip,
            )
        }
    }
}

/**
 * The speaker it plays on, and under it what plays: title and artist ([byline]) on one line that
 * glides back and forth while it is longer than its room. [song] moves that line with a sideways
 * swipe; the speaker's name stays, for the speaker does.
 */
@Composable
private fun PlayerWords(state: NowPlayingUi, speaker: SpeakerUi, modifier: Modifier, song: Modifier, runs: Boolean) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val artist = state.byline()
    val line = remember(state.title, artist) {
        listOf(state.title, artist).filter { it.isNotBlank() }.joinToString(DOT_SEPARATOR)
    }
    RowLines(type.labelLarge, besideCover = true, modifier = modifier) {
        SpeakerName(speaker, type.labelLarge, colors.onSurface)
        MarqueeText(
            line,
            type.bodyMedium,
            colors.onSurfaceVariant,
            song.inkStart(line, type.bodyMedium),
            runs = runs,
        )
    }
}
