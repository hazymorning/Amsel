package io.github.hazymorning.amsel.ui.screens.player

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.AlignedGlyph
import io.github.hazymorning.amsel.ui.components.SpeakerPill
import io.github.hazymorning.amsel.ui.components.SpeakerPillLeast
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.footerWords
import io.github.hazymorning.amsel.ui.components.overhang
import io.github.hazymorning.amsel.ui.components.trimSideBearings
import io.github.hazymorning.amsel.ui.components.wordGlyph
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.PILL_ALPHA
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.controls
import io.github.hazymorning.amsel.ui.theme.rememberPress
import io.github.hazymorning.amsel.ui.theme.tabular

// Where the row is too short, the volume drops its level first, then the pill its caret, then the
// speaker's name once only a few letters would be left. Spoken items always show the chapters
// button, as the owner asked.
@Composable
internal fun Footer(
    state: NowPlayingUi,
    onSpeaker: () -> Unit,
    onBookmark: () -> Unit,
    onVolume: () -> Unit,
    onUpNext: () -> Unit,
) {
    val utilities: @Composable (Boolean) -> Unit = { level ->
        Utilities(state, level, onBookmark, onVolume, onUpNext)
    }
    SubcomposeLayout(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Keyline.Margin)
            .heightIn(min = Sizes.TouchTarget),
    ) { constraints ->
        val width = constraints.maxWidth
        val gap = PillToUtilities.roundToPx()
        val loose = Constraints()
        val pill = subcompose(FooterSlot.Pill) { SpeakerPill(state.speaker, onSpeaker) }.first()
        val wanted = pill.maxIntrinsicWidth(Sizes.TouchTarget.roundToPx())
        val full = subcompose(FooterSlot.Utilities) { utilities(true) }.first().measure(loose)
        val end = if (wanted + gap + full.width <= width) {
            full
        } else {
            subcompose(FooterSlot.Bare) { utilities(false) }.first().measure(loose)
        }
        val room = (width - gap - end.width).coerceAtLeast(0)
        val start = when {
            room >= wanted -> pill.measure(Constraints(maxWidth = room))

            // The caret goes before a letter of the name does.
            room >= SpeakerPillLeast.roundToPx() ->
                subcompose(FooterSlot.Plain) { SpeakerPill(state.speaker, onSpeaker, caret = false) }
                    .first()
                    .measure(Constraints(maxWidth = room))

            else -> subcompose(FooterSlot.Glyph) {
                SpeakerPill(state.speaker, onSpeaker, named = false)
            }.first().measure(loose)
        }
        val height = maxOf(constraints.minHeight, start.height, end.height)
        layout(width, height) {
            start.placeRelative(0, (height - start.height) / 2)
            end.placeRelative(width - end.width, (height - end.height) / 2)
        }
    }
}

private enum class FooterSlot { Pill, Plain, Glyph, Utilities, Bare }

private val PillToUtilities = Space.S3

private val GroundGap = 6.dp

/** [level] shows the volume level beside its glyph. */
@Composable
private fun Utilities(
    state: NowPlayingUi,
    level: Boolean,
    onBookmark: () -> Unit,
    onVolume: () -> Unit,
    onUpNext: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(GroundGap), verticalAlignment = Alignment.CenterVertically) {
        state.volume?.let { volume ->
            val description = stringResource(R.string.player_volume_now, volume)
            if (level) {
                val words = if (volume == 0) stringResource(R.string.player_volume_still) else volume.toString()
                VolumeLevel(words, description, onVolume)
            } else {
                GlyphGround(R.drawable.ic_volume, description, onVolume, wordGlyph(footerWords()), ground = false)
            }
        }
        if (state.spoken) {
            GlyphGround(R.drawable.ic_bookmark, stringResource(R.string.bookmark_add), onBookmark)
        }
        val chapters = state.spoken
        GlyphGround(
            if (chapters) R.drawable.ic_chapters else R.drawable.ic_queue,
            stringResource(if (chapters) R.string.player_chapters else R.string.player_up_next),
            onUpNext,
        )
    }
}

/** [ground] false leaves out the disc; the 48 dp target reaches past it either way. */
@Composable
private fun GlyphGround(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    glyph: Dp = Sizes.Icon,
    ground: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val reach = (Sizes.TouchTarget - Sizes.SpeakerPill) / 2
    Box(
        Modifier
            .overhang(start = reach, top = reach, end = reach, bottom = reach)
            .clickable(null, rememberPress(Shapes.Circle), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(reach)
            .size(Sizes.SpeakerPill)
            .clip(Shapes.Circle)
            .background(if (ground) colors.onSurface.copy(alpha = PILL_ALPHA) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        AlignedGlyph(icon, tint = colors.controls, size = glyph)
    }
}

@Composable
private fun VolumeLevel(level: String, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val words = footerWords().tabular()
    val reach = (Sizes.TouchTarget - Sizes.SpeakerPill) / 2
    Row(
        Modifier
            .overhang(top = reach, bottom = reach)
            .clickable(null, rememberPress(Shapes.Pill), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = reach)
            .height(Sizes.SpeakerPill)
            .padding(horizontal = LevelPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlignedGlyph(R.drawable.ic_volume, tint = colors.controls, size = wordGlyph(words))
        Spacer(Modifier.width(GlyphToLevel))
        Text(
            level,
            style = words,
            color = colors.controls,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {}.capToBaseline(words).trimSideBearings(level, words),
        )
    }
}

private val LevelPadding = 16.dp
private val GlyphToLevel = 6.dp
