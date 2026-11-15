package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.rememberPress
import io.github.hazymorning.amsel.ui.theme.secondLine
import io.github.hazymorning.amsel.ui.theme.spaced

/**
 * Something to pick up again, with no card around it: the cover alone, the title and what is left
 * under it, as any tile's caption ([TileCaption]). A tap resumes where the person stopped; a
 * long press opens its actions, the way to its page.
 */
@Composable
fun ListeningTile(
    item: MediaUi,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    width: Dp,
    modifier: Modifier = Modifier,
    remaining: String? = null,
) {
    val type = MaterialTheme.typography
    val colors = MaterialTheme.colorScheme
    val presses = remember { MutableInteractionSource() }
    val (toTitle, toSecond) = with(LocalDensity.current) { spaced(Space.Caption) to secondLine(type.bodyMedium) }
    Column(
        modifier
            .width(width)
            .combinedClickable(
                interactionSource = presses,
                indication = rememberPress(shape = null),
                role = Role.Button,
                onClickLabel = stringResource(R.string.resume),
                onClick = onPlay,
                onLongClickLabel = stringResource(R.string.more_options_for, item.title),
                onLongClick = onMore,
            ),
    ) {
        Artwork(
            item.art,
            Modifier.fillMaxWidth(),
            item.kind,
            interactionSource = presses,
            name = item.title,
        )
        val mark = downloadMarkGlyph(item.key)
        TileCaption(
            type.titleSmall,
            type.bodyMedium,
            title = { lines ->
                Words(
                    item.title,
                    type.titleSmall,
                    color = colors.onSurface,
                    maxLines = lines,
                    modifier = Modifier
                        .underCover()
                        .fillMaxWidth()
                        .padding(top = toTitle)
                        .textInk(type.titleSmall)
                        .inkStart(item.title, type.titleSmall),
                )
            },
            second = remaining?.let { left ->
                {
                    val words: @Composable () -> Unit = {
                        Words(
                            left,
                            type.bodyMedium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().inkStart(left, type.bodyMedium),
                        )
                    }
                    Box(
                        Modifier.underCover().paddingFromBaseline(top = toSecond).textInk(type.bodyMedium, top = false),
                    ) {
                        if (mark != null) MarkBefore(type.bodyMedium, mark, words) else words()
                    }
                }
            },
        )
    }
}
