package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.displayName
import io.github.hazymorning.amsel.ui.theme.PILL_ALPHA
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.controls
import io.github.hazymorning.amsel.ui.theme.rememberPress

/**
 * Where the music plays, at the start of Now Playing's footer: a pill as wide as its words, the
 * speaker's glyph ([SpeakerButtonGlyph]), as large as the name ([wordGlyph]), the name, and a
 * caret, for it opens the speaker choice. Roomier than the quiet pills and set in a larger, bolder type: choosing where
 * music plays is half of what the app is for. Where the room is short the pill gives up its [caret] before a
 * letter of the name; a name still longer ends in an ellipsis, and where there is no room for a
 * name at all, the pill is its glyph and caret ([named] false). TalkBack hears
 * "Speaker: Kitchen. Choose speaker", and "playing" while it plays.
 */
@Composable
fun SpeakerPill(
    speaker: SpeakerUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    named: Boolean = true,
    caret: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val style = footerWords()
    val description = stringResource(
        if (speaker.isPlaying) R.string.speaker_button_playing else R.string.speaker_button_description,
        speaker.displayName(),
    )
    val reach = (Sizes.TouchTarget - Sizes.SpeakerPill) / 2
    Row(
        modifier
            .overhang(top = reach, bottom = reach)
            .clickable(null, rememberPress(Shapes.Pill), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = reach)
            .heightIn(min = Sizes.SpeakerPill)
            .clip(Shapes.Pill)
            .background(colors.onSurface.copy(alpha = PILL_ALPHA))
            .padding(start = PillStart, end = PillEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlignedGlyph(SpeakerButtonGlyph, tint = colors.controls, size = wordGlyph(style))
        if (named) {
            Spacer(Modifier.width(GlyphGap))
            SpeakerName(
                speaker,
                style,
                colors.controls,
                Modifier.weight(1f, fill = false).clearAndSetSemantics {}.capToBaseline(style),
            )
        }
        if (caret) {
            Spacer(Modifier.width(CaretGap))
            AlignedGlyph(R.drawable.ic_dropdown, tint = colors.onSurfaceVariant, size = wordCaret(style))
        }
    }
}

/**
 * What the button that opens the speaker choice carries, on the dock and in Now Playing, whatever
 * it plays on: one button, one glyph, so it is known again. What it plays on is its name.
 */
@DrawableRes
internal val SpeakerButtonGlyph = R.drawable.ic_speaker

/** The words of Now Playing's footer: the speaker's name and the volume's level. */
@Composable
@ReadOnlyComposable
fun footerWords(): TextStyle = MaterialTheme.typography.titleSmall.copy(
    fontSize = PillText,
    lineHeight = PillLine,
    letterSpacing = 0.sp,
    fontWeight = FontWeight.Bold,
)

/** The speaker's name, and how many more play with it ("Kitchen +2"). */
@Composable
fun SpeakerUi.label(): String = displayName().let { if (others > 0) "$it +$others" else it }

/**
 * The speaker's name in [color], and after it in the secondary tone how many more play with it
 * ("Kitchen +2"), so the name stays in front. The count stays whole where the name is cut: it says
 * the speaker leads a group.
 */
@Composable
internal fun SpeakerName(
    speaker: SpeakerUi,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    /** After the name in the secondary tone, as the group's "+2": a level on Home's speakers. */
    after: String? = null,
) {
    val name = speaker.displayName()
    Row(modifier) {
        Words(
            name,
            style,
            color = color,
            modifier = Modifier.weight(1f, fill = false).alignByBaseline().trimStartBearing(name, style),
        )
        val tail = buildString {
            if (speaker.others > 0) append("\u00A0+").append(speaker.others)
            if (after != null) append("\u2002").append(after)
        }
        if (tail.isNotEmpty()) {
            Text(
                tail,
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

/** Under this, a pill with a name is only a few letters and an ellipsis: better none. */
val SpeakerPillLeast = 112.dp

private val PillStart = 20.dp
private val PillEnd = 18.dp
private val GlyphGap = 8.dp
private val CaretGap = 10.dp
private val PillText = 16.sp
private val PillLine = 22.sp
