package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.Artwork
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.RingBefore
import io.github.hazymorning.amsel.ui.components.ShelfRow
import io.github.hazymorning.amsel.ui.components.SpeakerName
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.glyph
import io.github.hazymorning.amsel.ui.screens.player.speakerLine
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.rememberPress

/**
 * Speakers: the others that play or rest on something, where music can go on, as a row of cards
 * as wide as Home's big tiles and as far apart, so two stand over the two columns below. The
 * speaker in use is the dock's, so it is not among them.
 */
@Composable
internal fun SpeakerShelf(section: SectionUi, onSpeaker: (speaker: SpeakerUi, play: Boolean) -> Unit) {
    val width = Sizes.bigTile(LocalWindowInfo.current.containerDpSize.width)
    ShelfRow(section.speakers, key = { it.id }, gap = Space.Tiles) { speaker ->
        SpeakerCard(
            speaker,
            onClick = { onSpeaker(speaker, false) },
            onLongClick = { onSpeaker(speaker, true) },
            modifier = Modifier.width(width),
        )
    }
}

/**
 * A speaker as a low card: the cover of what it holds, then its glyph and name, and under them
 * the song it plays, or that it rests. A tap makes it the speaker in use and shows it in Now
 * Playing, starting nothing; a long press plays there at once.
 */
@Composable
private fun SpeakerCard(
    speaker: SpeakerUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    // The cover shows what it holds: the words say what it plays, or that it rests.
    val holds = if (speaker.isPlaying) speakerLine(speaker) else stringResource(R.string.player_resting)
    Row(
        modifier
            .clip(Shapes.Tile)
            .background(colors.surfaceContainerLow)
            .combinedClickable(
                interactionSource = null,
                indication = rememberPress(shape = null),
                role = Role.Button,
                onClickLabel = stringResource(R.string.open_now_playing),
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.resume),
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(CardInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(speaker.art, Modifier.size(CardArt), MediaKind.Track, glyph = speaker.glyph)
        Column(Modifier.weight(1f).padding(start = CardInset)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InkGlyph(speaker.glyph, tint = colors.onSurfaceVariant, size = Sizes.IconSmall)
                Spacer(Modifier.width(Space.S1))
                SpeakerName(speaker, type.titleSmall, colors.onSurface)
            }
            val line: @Composable () -> Unit = {
                Words(
                    holds,
                    type.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.inkStart(holds, type.bodyMedium),
                )
            }
            if (speaker.isPlaying) RingBefore(type.bodyMedium, line) else line()
        }
    }
}

/** The cover and the words stand this far from the card's edges and from each other. */
private val CardInset = Space.S3

private val CardArt = 44.dp
