package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.Artwork
import io.github.hazymorning.amsel.ui.components.PlayButton
import io.github.hazymorning.amsel.ui.components.RowLines
import io.github.hazymorning.amsel.ui.components.ShelfRow
import io.github.hazymorning.amsel.ui.components.SpeakerName
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.displayName
import io.github.hazymorning.amsel.ui.model.glyph
import io.github.hazymorning.amsel.ui.screens.player.speakerLine
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.rememberPress

/**
 * Speakers: those that play or rest on something, where music can go on, as a row of cards as
 * wide as Home's big tiles and as far apart, so two stand over the two columns below. The speaker
 * in use is among them: with every speaker grouped under it, the row shows that group.
 */
@Composable
internal fun SpeakerShelf(
    section: SectionUi,
    onSpeaker: (speaker: SpeakerUi, play: Boolean) -> Unit,
    onPlay: (SpeakerUi) -> Unit = {},
) {
    // A column and a half: the name, what it plays and its own play fit, and the next card peeks
    // in at the screen's edge, so the row says it slides.
    val width = Sizes.bigTile(LocalWindowInfo.current.containerDpSize.width) * CARD_COLUMNS
    if (section.loading) {
        // Until the server names its speakers, two blank cards keep the row's room: nothing under it moves.
        ShelfRow(BLANKS, key = { it.id }, Modifier.clearAndSetSemantics {}, gap = Space.Tiles) { blank ->
            SpeakerCard(blank, onClick = {}, onLongClick = {}, onPlay = {}, Modifier.width(width), blank = true)
        }
        return
    }
    ShelfRow(section.speakers, key = { it.id }, gap = Space.Tiles) { speaker ->
        SpeakerCard(
            speaker,
            onClick = { onSpeaker(speaker, false) },
            onLongClick = { onSpeaker(speaker, true) },
            onPlay = { onPlay(speaker) },
            modifier = Modifier.width(width),
        )
    }
}

/**
 * A speaker as a low card a breath lighter than Home's ground: the cover of what it holds, its name
 * over the song it plays or that it rests, and at its end its own play, as the mini player has one:
 * pause on a disc of ink while it plays, play on a quiet disc while it rests. That button plays or
 * pauses this speaker where it is ([onPlay]); a tap on the card makes it the speaker in use and
 * shows it in Now Playing, starting nothing; a long press plays there at once. [blank]: the card
 * alone, as tall as one with words, while the speakers are not known yet.
 */
@Composable
private fun SpeakerCard(
    speaker: SpeakerUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    blank: Boolean = false,
) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    // The cover shows what it holds: the words say what it plays, or that it rests.
    val holds = if (speaker.isPlaying) speakerLine(speaker) else stringResource(R.string.player_resting)
    val open = stringResource(R.string.open_now_playing)
    val resume = stringResource(R.string.resume)
    val card = onSurface(CARD_INK).compositeOver(colors.surfaceContainerLow)
    Row(
        modifier
            .clip(Shapes.Tile)
            .background(card)
            .then(
                if (blank) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        interactionSource = null,
                        indication = rememberPress(shape = null),
                        role = Role.Button,
                        onClickLabel = open,
                        onClick = onClick,
                        onLongClickLabel = resume,
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        },
                    )
                },
            )
            .padding(CardInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shown = if (blank) Modifier.alpha(0f) else Modifier
        Artwork(speaker.art, shown.size(CardArt), MediaKind.Track, glyph = speaker.glyph)
        // The name alone (the row says these are speakers, and a cover without a picture wears the
        // glyph) over what it plays, close: one thing in two lines, its ink centred on the cover.
        RowLines(
            type.titleSmall,
            besideCover = true,
            modifier = shown.weight(1f).padding(start = CardInset),
            secondStyle = type.bodyMedium,
            inset = 0.dp,
            baselines = NameToState,
        ) {
            SpeakerName(speaker, type.titleSmall, colors.onSurface)
            Words(
                holds,
                type.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.inkStart(holds, type.bodyMedium),
            )
        }
        Spacer(Modifier.width(CardInset))
        val name = speaker.displayName()
        PlayButton(
            speaker.isPlaying,
            onPlay,
            shown,
            size = CardDisc,
            label = stringResource(if (speaker.isPlaying) R.string.speaker_pause else R.string.speaker_play, name),
            // What plays holds the eye in ink; a speaker at rest offers its play quietly.
            tonal = !speaker.isPlaying,
        )
    }
}

/** From the name's baseline to the state's: close, a small card's two lines read as one thing. */
private val NameToState = 18.dp

/** The cover and the words stand this far from the card's edges and from each other. */
private val CardInset = Space.S3

/** How much ink lies on a card's paper: enough to lift it off Home's ground, no more. */
private const val CARD_INK = 0.03f

/** The cards that hold the row's room while the speakers are not known. */
private val BLANKS = listOf(SpeakerUi("blank-1", ""), SpeakerUi("blank-2", ""))

@Composable
private fun onSurface(alpha: Float): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)

private val CardArt = 44.dp

/** The card's play: a touch smaller than the mini player's, as its cover is. */
private val CardDisc = 40.dp

/** A card is this many of Home's big columns wide. */
private const val CARD_COLUMNS = 1.5f
