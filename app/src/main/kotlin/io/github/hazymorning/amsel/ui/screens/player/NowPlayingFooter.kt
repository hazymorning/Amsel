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
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.SpeakerPill
import io.github.hazymorning.amsel.ui.components.SpeakerPillLeast
import io.github.hazymorning.amsel.ui.components.footerWords
import io.github.hazymorning.amsel.ui.components.inkSides
import io.github.hazymorning.amsel.ui.components.overhang
import io.github.hazymorning.amsel.ui.components.textInk
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

/**
 * The speaker's pill at the start, as wide as its words; at the end, in a row, the volume with its
 * level, a bookmark for a book or an episode, and last Up next, or a book's or an episode's
 * chapters (always, as the owner asked). The pill and the glyphs that open something stand on a
 * quiet ground of one height: the grounds' outer edges are on the page's margins, under the
 * artwork's edges, and exactly as far from the screen's foot as from its sides; each glyph stands
 * in the middle of its ground. The volume is a reading more than a place to go and stands bare.
 *
 * The speaker's name comes first: where the row is too short for it, the volume gives up its
 * level, then the pill its caret, and where even then only a few letters would be left, its name.
 */
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
        val pill = subcompose(Part.Pill) { SpeakerPill(state.speaker, onSpeaker) }.first()
        val wanted = pill.maxIntrinsicWidth(Sizes.TouchTarget.roundToPx())
        val full = subcompose(Part.Utilities) { utilities(true) }.first().measure(loose)
        val end = if (wanted + gap + full.width <= width) {
            full
        } else {
            subcompose(Part.Bare) { utilities(false) }.first().measure(loose)
        }
        val room = (width - gap - end.width).coerceAtLeast(0)
        val start = when {
            room >= wanted -> pill.measure(Constraints(maxWidth = room))

            // The caret goes before a letter of the name does.
            room >= SpeakerPillLeast.roundToPx() ->
                subcompose(Part.Plain) { SpeakerPill(state.speaker, onSpeaker, caret = false) }
                    .first()
                    .measure(Constraints(maxWidth = room))

            else -> subcompose(Part.Glyph) {
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

private enum class Part { Pill, Plain, Glyph, Utilities, Bare }

private val PillToUtilities = Space.S3

/** Between two grounds at the footer's end. */
private val GroundGap = 6.dp

/** What stands at the footer's end; [level] sets the volume's level beside its glyph. */
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

/**
 * A glyph on a quiet disc as high as the speaker's pill, or bare in the disc's place ([ground]
 * false); its 48 dp target reaches past the disc.
 */
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
        InkGlyph(icon, tint = colors.controls, size = glyph)
    }
}

/** The volume's glyph and its level, bare, as high as the speaker's pill and in the footer's words. */
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
        InkGlyph(R.drawable.ic_volume, tint = colors.controls, size = wordGlyph(words))
        Spacer(Modifier.width(GlyphToLevel))
        Text(
            level,
            style = words,
            color = colors.controls,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics {}.textInk(words).inkSides(level, words),
        )
    }
}

private val LevelPadding = 16.dp
private val GlyphToLevel = 6.dp
