package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.RepeatMode
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.playback.SKIP_BACK_MS
import io.github.hazymorning.amsel.playback.SKIP_FORWARD_MS
import io.github.hazymorning.amsel.ui.components.IconAction
import io.github.hazymorning.amsel.ui.components.PlayButton
import io.github.hazymorning.amsel.ui.components.TextLink
import io.github.hazymorning.amsel.ui.components.ToggleIconButton
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.endReach
import io.github.hazymorning.amsel.ui.components.glyphWidth
import io.github.hazymorning.amsel.ui.components.label
import io.github.hazymorning.amsel.ui.components.overhang
import io.github.hazymorning.amsel.ui.components.trimStartBearing
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.model.next
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Radius
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.controls
import kotlin.math.roundToInt

@Composable
internal fun TransportRow(state: NowPlayingUi, onControl: (PlayerControl) -> Unit, modifier: Modifier = Modifier) {
    val outer = when {
        state.spoken -> R.drawable.ic_skip_back to R.drawable.ic_skip_forward
        state.repeat == RepeatMode.One -> R.drawable.ic_shuffle to R.drawable.ic_repeat_one
        else -> R.drawable.ic_shuffle to R.drawable.ic_repeat
    }
    // In a mix the server fixes shuffle and repeat (docs/API-NOTES.md, "Extras"); TalkBack hears it as
    // their state.
    val fixed = if (state.mix != null) stringResource(R.string.endless_mix_fixed) else null
    Layout(
        content = {
            if (state.spoken) {
                val back = (SKIP_BACK_MS / 1000).toInt()
                SkipSeconds(
                    R.drawable.ic_skip_back,
                    back,
                    pluralStringResource(PlaybackR.plurals.back_seconds, back, back),
                    size = SideGlyph,
                ) {
                    onControl(PlayerControl.SeekBack)
                }
            } else {
                ToggleIconButton(
                    R.drawable.ic_shuffle,
                    stringResource(R.string.shuffle),
                    on = state.shuffle,
                    dot = true,
                    enabled = state.canShuffle,
                    onClick = { onControl(PlayerControl.SetShuffle(!state.shuffle)) },
                    modifier = if (fixed != null) Modifier.semantics { stateDescription = fixed } else Modifier,
                    size = SideGlyph,
                )
            }
            IconAction(
                R.drawable.ic_skip_previous,
                stringResource(state.previousLabel),
                { onControl(PlayerControl.Previous) },
                tint = MaterialTheme.colorScheme.onSurface,
                size = Sizes.IconLarge,
                enabled = state.canSkip,
            )
            PlayButton(state.isPlaying, { onControl(PlayerControl.PlayPause) }, size = Sizes.PlayButton)
            IconAction(
                R.drawable.ic_skip_next,
                stringResource(state.nextLabel),
                { onControl(PlayerControl.Next) },
                tint = MaterialTheme.colorScheme.onSurface,
                size = Sizes.IconLarge,
                enabled = state.canSkip,
            )
            if (state.spoken) {
                val forward = (SKIP_FORWARD_MS / 1000).toInt()
                SkipSeconds(
                    R.drawable.ic_skip_forward,
                    forward,
                    pluralStringResource(PlaybackR.plurals.forward_seconds, forward, forward),
                    size = SideGlyph,
                ) {
                    onControl(PlayerControl.SeekForward)
                }
            } else {
                val repeat = stringResource(
                    when (state.repeat) {
                        RepeatMode.Off -> R.string.player_repeat_off
                        RepeatMode.All -> R.string.player_repeat_all
                        RepeatMode.One -> R.string.player_repeat_one
                    },
                )
                ToggleIconButton(
                    if (state.repeat == RepeatMode.One) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                    stringResource(R.string.player_repeat),
                    on = state.repeat != RepeatMode.Off,
                    dot = true,
                    enabled = state.canShuffle,
                    onClick = { onControl(PlayerControl.SetRepeat(state.repeat.next())) },
                    modifier = Modifier.semantics { stateDescription = fixed ?: repeat },
                    size = SideGlyph,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val p = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val height = p.maxOf { it.height }
        val width = constraints.maxWidth
        // The outer glyphs themselves, not their touch targets, align with the words' keyline.
        val keyline = (Keyline.Margin + NowPlayingInset).toPx()
        val middle = width / 2f
        val reach = SkipReach.toPx()
        val centres = listOf(
            keyline + glyphWidth(outer.first, SideGlyph).toPx() / 2,
            middle - reach,
            middle,
            middle + reach,
            width - keyline - glyphWidth(outer.second, SideGlyph).toPx() / 2,
        )
        layout(width, height) {
            p.forEachIndexed { i, placeable ->
                placeable.placeRelative(
                    (centres[i] - placeable.width / 2f).roundToInt(),
                    (height - placeable.height) / 2,
                )
            }
        }
    }
}

// The block spans the title's cap top to the artist's last baseline.
@Composable
internal fun TrackInfo(
    title: String,
    artist: String,
    onArtist: (() -> Unit)?,
    linkLabel: String,
    liked: Boolean,
    onLike: (() -> Unit)?,
) {
    val ink = MaterialTheme.colorScheme.controls
    Layout(
        content = {
            TrackWords(title, artist, onArtist, linkLabel)
            Box {
                if (onLike != null) {
                    val heart = if (liked) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
                    ToggleIconButton(
                        heart,
                        stringResource(R.string.player_like),
                        on = liked,
                        onClick = onLike,
                        modifier = Modifier.overhang(end = endReach(heart, SideGlyph)),
                        offTint = ink,
                        size = SideGlyph,
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = Keyline.Margin + NowPlayingInset),
    ) { (words, actions), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val a = actions.measure(loose)
        val room = (constraints.maxWidth - a.width - Space.S2.roundToPx()).coerceAtLeast(0)
        val w = words.measure(loose.copy(maxWidth = room))
        layout(constraints.maxWidth, w.height) {
            w.placeRelative(0, 0)
            a.placeRelative(constraints.maxWidth - a.width, (w.height - a.height) / 2)
        }
    }
}

@Composable
private fun TrackWords(title: String, artist: String, onArtist: (() -> Unit)?, linkLabel: String) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val second = type.bodyLarge.copy(fontWeight = FontWeight.Normal)
    // Keyed on the words only, so an artist link arriving after the title does not animate them.
    AnimatedContent(
        title to artist,
        transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effectsFast()) },
        label = "track",
    ) { (words, by) ->
        Column(Modifier.fillMaxWidth()) {
            Words(
                words,
                type.headlineSmall,
                color = colors.onSurface,
                maxLines = 2,
                modifier = Modifier
                    .capToBaseline(type.headlineSmall)
                    .trimStartBearing(words, type.headlineSmall)
                    .semantics { heading() },
            )
            if (onArtist != null) {
                TextLink(
                    by,
                    linkLabel,
                    onArtist,
                    Modifier.paddingFromBaseline(top = TitleToArtist),
                    style = second,
                )
            } else {
                Words(
                    by,
                    second,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .paddingFromBaseline(top = TitleToArtist)
                        .capToBaseline(second, top = false)
                        .trimStartBearing(by, second),
                )
            }
        }
    }
}

// One size up from a list's glyphs, as Now Playing's words are.
private val SideGlyph = Sizes.IconLarge

// Words, line and controls start this far in from the margin, as words under a cover do.
internal val NowPlayingInset = Radius.under(Radius.Large)

private val SkipReach = 80.dp

/** The title's baseline to the artist's. */
private val TitleToArtist = 26.dp
