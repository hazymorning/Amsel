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

/** Size of the seconds-skip glyphs of a book or an episode. */
private val PlayGlyph = 28.dp

private val PlayDisc = 44.dp

private val Inset = Keyline.Margin

/** The last control's glyph sits this far in from the screen edge, more than the margin: within thumb reach. */
private val ControlsInset = Space.S10

private val BeforePlay = Space.S6

/** Also shown when nothing plays, so a speaker can be chosen before the first song. */
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
    // Covers use surfaceContainerLow as their background, this band's own tone: raise it a step.
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
                modifier = Modifier.trimStartBearing(words, type.dockLine),
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
    val swipe = rememberSideSwipeState(state.key ?: state.title, enabled = state.canSkip, onGo = skip)
    // The empty space beside the last control's glyph, so the glyph itself sits [ControlsInset] in.
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
        // Primary, as on every page: the dock's one focal point, balancing the cover at the start.
        DockControl.PlayPause -> PlayButton(state.isPlaying, { onControl(PlayerControl.PlayPause) }, size = PlayDisc)

        // A book or an episode skips by seconds, as its notification does.
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

/** [song] (the swipe's offset) moves only the song line: a skip changes the song, not the speaker. */
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
            song.trimStartBearing(line, type.dockLine),
            runs = runs,
        )
    }
}
