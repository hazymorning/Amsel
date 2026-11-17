package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import io.github.hazymorning.amsel.playback.SKIP_BACK_MS
import io.github.hazymorning.amsel.playback.SKIP_FORWARD_MS
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.byline
import io.github.hazymorning.amsel.ui.navigation.DockControl
import io.github.hazymorning.amsel.ui.screens.player.SkipSeconds
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.dockLine

/** The dock's play glyph, a step up from the next beside it. */
private val PlayGlyph = 28.dp

/** The disc play stands on: the dock's own measure, under its cover's 48. */
private val PlayDisc = 44.dp

/**
 * Around what plays on the dock: the page's margin, so its cover stands on the margin as a page's
 * covers do, as far from its band's edges as in from the screen's.
 */
private val Inset = Keyline.Margin

/**
 * The skip's ink stands this far in from the screen's edge, off it, where a thumb finds it: the
 * controls are held, the cover only looked at.
 */
private val ControlsInset = Space.S10

/** From the words to play's touch room: play stands apart from what it plays. */
private val BeforePlay = Space.S6

/**
 * What plays, on the dock's own surface, with no card around it: the cover on the margin, the
 * speaker's name over what plays, play and a skip, the skip's ink on the margin across. It stays
 * when nothing plays: where music goes can be chosen before the first song, and the speaker's
 * button stays where the person put it. A cover here, and one still empty, stands a tone above the
 * dock, as a cover does above the page.
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
    controls: List<DockControl> = DockControl.Default.visible,
    onDockControl: (DockControl) -> Unit = {},
    onArrange: () -> Unit = {},
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
                    PlayerRow(shown, speaker, covered, onOpen, onControl, controls, onDockControl, onArrange)
                } else {
                    IdleRow(speaker, onSpeaker, speakerButton = DockControl.Speaker in controls)
                }
            }
        }
    }
}

/**
 * Nothing plays: an empty cover and the speaker's name over "Not playing". A tap anywhere on the
 * card opens the speaker choice, the one thing to do here, and TalkBack offers the card as that
 * button; the speaker's own button stays where the person keeps one in the mini player
 * ([speakerButton]). Play and the rest wait for something to play.
 */
@Composable
private fun IdleRow(speaker: SpeakerUi, onSpeaker: () -> Unit, speakerButton: Boolean) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val words = stringResource(R.string.player_not_playing)
    val end = if (speakerButton) (ControlsInset - endReach(DockControl.Speaker.icon)).coerceAtLeast(0.dp) else Inset
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = stringResource(R.string.dock_speaker),
                role = Role.Button,
                onClick = onSpeaker,
            )
            .padding(start = Inset, top = Inset, end = end, bottom = Inset)
            .heightIn(min = Sizes.DockArt),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(null, Modifier.size(Sizes.DockArt), MediaKind.Track)
        Spacer(Modifier.width(Space.Sibling))
        RowLines(type.titleMedium, besideCover = true, Modifier.weight(1f), type.dockLine, Space.S1) {
            SpeakerName(speaker, type.titleMedium, colors.onSurface)
            Words(
                words,
                type.dockLine,
                color = colors.onSurfaceVariant,
                modifier = Modifier.inkStart(words, type.dockLine),
            )
        }
        if (speakerButton) {
            Spacer(Modifier.width(BeforePlay))
            IconAction(
                DockControl.Speaker.icon,
                stringResource(DockControl.Speaker.label),
                onSpeaker,
                tint = colors.onSurface,
            )
        }
    }
}

/**
 * Cover and words ([PlayerWords]), then the [controls] the person chose, in their order: play, and
 * as they like next and previous (by seconds for a book or an episode, as the notification has
 * them), the heart, the speaker, volume, the sleep timer or shuffle. A tap or a drag up opens Now
 * Playing; a sideways swipe goes to the next or the one before, the song's line following the
 * finger; a long press arranges the controls ([onArrange]).
 */
@Composable
private fun PlayerRow(
    state: NowPlayingUi,
    speaker: SpeakerUi,
    covered: Boolean,
    onOpen: () -> Unit,
    onControl: (PlayerControl) -> Unit,
    controls: List<DockControl>,
    onDockControl: (DockControl) -> Unit,
    onArrange: () -> Unit,
) {
    val openLabel = stringResource(R.string.open_now_playing)
    val nextLabel = stringResource(state.nextLabel)
    val previousLabel = stringResource(state.previousLabel)
    val skip: (Boolean) -> Unit = { forward ->
        onControl(if (forward) PlayerControl.Next else PlayerControl.Previous)
    }
    val swipe = rememberSideSwipe(state.key ?: state.title, enabled = state.canSkip, onGo = skip)
    // The last control's ink stands [ControlsInset] in from the screen's edge, whichever it is.
    val endSpare = when (val end = controls.lastOrNull() ?: DockControl.PlayPause) {
        DockControl.PlayPause -> (Sizes.TouchTarget - PlayDisc) / 2
        DockControl.Next -> if (state.spoken) endReach(R.drawable.ic_skip_forward, PlayGlyph) else endReach(end.icon)
        DockControl.Previous -> if (state.spoken) endReach(R.drawable.ic_skip_back, PlayGlyph) else endReach(end.icon)
        else -> endReach(end.icon)
    }
    val arrangeLabel = stringResource(R.string.dock_arrange)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                null,
                indication = null,
                role = Role.Button,
                onClick = onOpen,
                onLongClickLabel = arrangeLabel,
                onLongClick = onArrange,
            )
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
                end = (ControlsInset - endSpare).coerceAtLeast(0.dp),
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
        Spacer(Modifier.width(BeforePlay))
        controls.forEach { control -> DockButton(control, state, onControl, onDockControl) }
    }
}

/** One of the mini player's controls, as [PlayerRow] lines them up. */
@Composable
private fun DockButton(
    control: DockControl,
    state: NowPlayingUi,
    onControl: (PlayerControl) -> Unit,
    onDockControl: (DockControl) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ink = colors.onSurface
    when (control) {
        // In ink, as every page's play: the dock's one focal point, which balances the cover at its start.
        DockControl.PlayPause -> PlayButton(state.isPlaying, { onControl(PlayerControl.PlayPause) }, size = PlayDisc)

        DockControl.Next -> if (state.spoken) {
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
                stringResource(state.nextLabel),
                { onControl(PlayerControl.Next) },
                tint = ink,
                enabled = state.canSkip,
            )
        }

        DockControl.Previous -> if (state.spoken) {
            val seconds = (SKIP_BACK_MS / 1000).toInt()
            SkipSeconds(
                R.drawable.ic_skip_back,
                seconds,
                pluralStringResource(PlaybackR.plurals.back_seconds, seconds, seconds),
                size = PlayGlyph,
            ) {
                onControl(PlayerControl.SeekBack)
            }
        } else {
            IconAction(
                R.drawable.ic_skip_previous,
                stringResource(state.previousLabel),
                { onControl(PlayerControl.Previous) },
                tint = ink,
                enabled = state.canSkip,
            )
        }

        // Only a song is a favourite here; a station, a book or an episode leaves the heart dimmed.
        DockControl.Favorite -> IconAction(
            if (state.favorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite,
            stringResource(
                if (state.favorite) R.string.player_action_remove_favorite else R.string.player_action_add_favorite,
            ),
            { onDockControl(DockControl.Favorite) },
            tint = ink,
            enabled = state.kind == MediaKind.Track && state.key != null && !state.djBreak,
        )

        DockControl.Shuffle -> IconAction(
            control.icon,
            stringResource(control.label),
            { onControl(PlayerControl.SetShuffle(!state.shuffle)) },
            tint = if (state.shuffle) ink else colors.onSurfaceVariant,
            enabled = state.canShuffle,
        )

        DockControl.Volume -> IconAction(
            control.icon,
            stringResource(control.label),
            { onDockControl(control) },
            tint = ink,
            enabled = state.volume != null,
        )

        DockControl.Speaker, DockControl.Sleep -> IconAction(
            control.icon,
            stringResource(control.label),
            { onDockControl(control) },
            tint = ink,
        )
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
    RowLines(type.titleMedium, besideCover = true, modifier, type.dockLine, Space.S1) {
        SpeakerName(speaker, type.titleMedium, colors.onSurface)
        MarqueeText(
            line,
            type.dockLine,
            colors.onSurfaceVariant,
            song.inkStart(line, type.dockLine),
            runs = runs,
        )
    }
}
