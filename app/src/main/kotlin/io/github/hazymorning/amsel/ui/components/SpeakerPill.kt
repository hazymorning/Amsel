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

/** Where room is short, the caller drops [caret] first, then the name ([named]) below [SpeakerPillLeast]. */
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

/** One glyph whatever the speaker type, so the speaker button is always recognisable. */
@DrawableRes
internal val SpeakerButtonGlyph = R.drawable.ic_speaker

@Composable
@ReadOnlyComposable
fun footerWords(): TextStyle = MaterialTheme.typography.titleSmall.copy(
    fontSize = PillText,
    lineHeight = PillLine,
    letterSpacing = 0.sp,
    fontWeight = FontWeight.Bold,
)

@Composable
fun SpeakerUi.label(): String = displayName().let { if (others > 0) "$it +$others" else it }

/** Only the name is cut; the "+2" stays whole, as it shows that the speaker leads a group. */
@Composable
internal fun SpeakerName(
    speaker: SpeakerUi,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    /** More text after the count, such as a volume level on Home's speakers. */
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

/** Below this width a name shows only a few letters and an ellipsis, so the pill goes without it. */
val SpeakerPillLeast = 112.dp

private val PillStart = 20.dp
private val PillEnd = 18.dp
private val GlyphGap = 8.dp
private val CaretGap = 10.dp
private val PillText = 16.sp
private val PillLine = 22.sp
