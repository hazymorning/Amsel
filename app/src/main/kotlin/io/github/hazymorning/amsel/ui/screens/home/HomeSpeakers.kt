package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.Artwork
import io.github.hazymorning.amsel.ui.components.RowLines
import io.github.hazymorning.amsel.ui.components.ShelfRow
import io.github.hazymorning.amsel.ui.components.SpeakerName
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.glyph
import io.github.hazymorning.amsel.ui.screens.player.speakerLine
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Radius
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.SmoothShape
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.dockLine
import io.github.hazymorning.amsel.ui.theme.rememberPress

/**
 * Speakers: those that play or rest on something, where music can go on, as a row of cards tiles'
 * gap apart. The speaker in use is among them: with every speaker grouped under it, the row shows
 * that group.
 */
@Composable
internal fun SpeakerShelf(section: SectionUi, onSpeaker: (speaker: SpeakerUi, play: Boolean) -> Unit) {
    val width = cardWidth()
    if (section.loading) {
        // Until the server names its speakers, two blank cards keep the row's room: nothing under it moves.
        ShelfRow(BLANKS, key = { it.id }, Modifier.clearAndSetSemantics {}, gap = Space.Tiles) { blank ->
            SpeakerCard(blank, onClick = {}, onLongClick = {}, Modifier.width(width), blank = true)
        }
        return
    }
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
 * Fourteen of the body's ems: a room's name with its group ("Arbeitszimmer +3") beside the cover,
 * whole. With larger text the card grows with it, never past the margins.
 */
@Composable
private fun cardWidth(): Dp {
    val em = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.fontSize.toDp() }
    val screen = LocalWindowInfo.current.containerDpSize.width
    return min(max(em * CARD_EMS, CardWidth), screen - Keyline.Margin * 2)
}

/**
 * A speaker as a card a breath off Home's ground, as tall as a list row: the cover of what it holds
 * in a frame of 8, the card's corners the cover's plus that frame, so the two nest. Beside it the
 * room's name over what it plays, or that it rests, as the mini player speaks. While it plays, the
 * cover wears the moss dot. A tap makes it the speaker in use and shows it in Now Playing, starting
 * nothing; a long press plays there at once. [blank]: the card alone, as tall as one with words,
 * while the speakers are not known yet.
 */
@Composable
private fun SpeakerCard(
    speaker: SpeakerUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    blank: Boolean = false,
) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val holds = speakerLine(speaker)
    val open = stringResource(R.string.open_now_playing)
    val resume = stringResource(R.string.resume)
    // The dot shows it; TalkBack says it, as the line says it for a speaker at rest.
    val plays = stringResource(R.string.player_playing)
    val card = colors.onSurface.copy(alpha = CARD_INK).compositeOver(colors.surfaceContainerLow)
    Row(
        modifier
            .clip(CardShape)
            .background(card)
            .then(
                if (blank) {
                    Modifier
                } else {
                    Modifier
                        .combinedClickable(
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
                        .semantics { if (speaker.isPlaying) stateDescription = plays }
                },
            )
            .padding(Frame),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shown = if (blank) Modifier.alpha(0f) else Modifier
        PlayingCover(speaker, playing = speaker.isPlaying && !blank, ring = card, modifier = shown)
        RowLines(
            type.titleMedium,
            besideCover = true,
            modifier = shown.weight(1f).padding(start = Space.S3, end = Space.S3 - Frame),
            secondStyle = type.dockLine,
            inset = Space.S1,
        ) {
            SpeakerName(speaker, type.titleMedium, colors.onSurface)
            Words(
                holds,
                type.dockLine,
                color = colors.onSurfaceVariant,
                modifier = Modifier.inkStart(holds, type.dockLine),
            )
        }
    }
}

/**
 * The cover of what [speaker] holds and, while it plays, the moss dot the bar's speaker wears: on
 * the outline of the cover's top end corner, cut free of it by a ring of the card's tone ([ring]).
 */
@Composable
private fun PlayingCover(speaker: SpeakerUi, playing: Boolean, ring: Color, modifier: Modifier = Modifier) {
    val moss = MaterialTheme.colorScheme.tertiary
    val dot by animateFloatAsState(if (playing) 1f else 0f, Motion.effects(), label = "speaker dot")
    Box(
        modifier.drawWithContent {
            drawContent()
            if (dot > 0f) {
                val inset = DotInset.toPx()
                val at = Offset(if (layoutDirection == LayoutDirection.Ltr) size.width - inset else inset, inset)
                drawCircle(ring, Dot.toPx() / 2 + DotRing.toPx(), at, alpha = dot)
                drawCircle(moss, Dot.toPx() / 2, at, alpha = dot)
            }
        },
    ) {
        Artwork(speaker.art, Modifier.size(Sizes.RowArt), MediaKind.Track, glyph = speaker.glyph)
    }
}

/** The bar's speaker dot, the same on a card's cover: 8 across, with a ring of 2 of the card's tone. */
private val Dot = 8.dp
private val DotRing = 2.dp

/** The dot's centre lies on the outline of the cover's rounded corner (8 dp), at its middle. */
private val DotInset = 2.4.dp

/** The cover's frame inside the card. */
private val Frame = Space.S2

/** The card's corners: the cover's and its frame, so the two curves run side by side. */
private val CardShape = SmoothShape(Radius.Thumb + Frame)

private const val CARD_EMS = 14f

/** The width the card keeps at the usual text size. */
private val CardWidth = 224.dp

/** How much ink lies on a card's paper: enough to lift it off Home's ground, no more. */
private const val CARD_INK = 0.03f

/** The cards that hold the row's room while the speakers are not known. */
private val BLANKS = listOf(SpeakerUi("blank-1", ""), SpeakerUi("blank-2", ""))
