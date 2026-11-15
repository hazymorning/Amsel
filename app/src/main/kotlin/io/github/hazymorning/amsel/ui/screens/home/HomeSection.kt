package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.ui.components.Reorderable
import io.github.hazymorning.amsel.ui.components.SectionHeader
import io.github.hazymorning.amsel.ui.components.holdToMove
import io.github.hazymorning.amsel.ui.components.overhang
import io.github.hazymorning.amsel.ui.components.plus
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.displayName
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.spaced

@Immutable
internal data class SectionActions(
    val onOpen: (MediaUi) -> Unit,
    val onMore: (MediaUi) -> Unit,
    val onSeeAll: () -> Unit,
    val onStartEditing: () -> Unit,
    val onWhat: () -> Unit,
    val onHow: () -> Unit,
    val onSectionMore: () -> Unit,
    /** A new name for the section; blank gives back the option's own. */
    val onRename: (String) -> Unit = {},
    /** Continue listening's play button. */
    val onResume: (MediaUi) -> Unit = onOpen,
    /** TalkBack's moves, where there is a section to move past. */
    val onMoveUp: (() -> Unit)? = null,
    val onMoveDown: (() -> Unit)? = null,
    /** Pinned's new order, after a pin was moved. */
    val onArrange: (List<String>) -> Unit = {},
    /** Speakers: a tap makes the speaker the one in use and shows it in Now Playing; [play], a long press, plays there. */
    val onSpeaker: (speaker: SpeakerUi, play: Boolean) -> Unit = { _, _ -> },
)

/**
 * One section. Outside edit mode its title is a section header (long-press it to edit); in edit
 * mode its title and sentence take the header's place, the title's capitals as far under the
 * section before as a header's. A held press on them picks the section up, and while it is carried
 * it is lifted on a band in the lifted row's tone. Pinned's pins move the same way inside it.
 */
@Composable
internal fun Section(
    section: SectionUi,
    first: Boolean,
    editing: Boolean,
    lifted: Boolean,
    actions: SectionActions,
    reorder: Reorderable,
    modifier: Modifier = Modifier,
    home: ScrollRoom? = null,
) {
    val title = section.heading()
    if (!editing) {
        Column(modifier) {
            // Without its heading a section keeps the room one would take above it.
            if (!section.titled) {
                if (!first) Spacer(Modifier.height(with(LocalDensity.current) { spaced(Space.SectionToHeader) }))
            } else {
                SectionHeader(
                    title,
                    onSeeAll = actions.onSeeAll.takeIf { section.more },
                    above = if (first) null else 0.dp,
                    below = contentGap(section.layout),
                    onLongClick = actions.onStartEditing,
                    longClickLabel = stringResource(R.string.home_edit),
                )
            }
            SectionBody(section, actions)
        }
        return
    }
    val band = MaterialTheme.colorScheme.surfaceContainerLow
    val haptics = LocalHapticFeedback.current
    var renaming by remember { mutableStateOf(false) }
    val up = stringResource(R.string.move_up)
    val down = stringResource(R.string.move_down)
    val moves = listOfNotNull(
        actions.onMoveUp?.let { CustomAccessibilityAction(up, it.done()) },
        actions.onMoveDown?.let { CustomAccessibilityAction(down, it.done()) },
    )
    Column(modifier) {
        if (!first) Spacer(Modifier.height(with(LocalDensity.current) { spaced(Space.SectionToHeader) }))
        Column(
            Modifier
                .overhang(top = Space.BandText, bottom = Space.BandText)
                .then(if (lifted) Modifier.clip(LiftedBand).background(band, LiftedBand) else Modifier)
                .padding(vertical = Space.BandText),
        ) {
            Column(
                Modifier.holdToMove(
                    enabled = !renaming,
                    onStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        reorder.start(section.id)
                    },
                    onMove = { reorder.drag(it.y) },
                    onEnd = reorder::end,
                ),
            ) {
                SectionTitleLine(
                    title,
                    section.titled,
                    renaming,
                    onRenaming = { renaming = it },
                    actions.onRename,
                    moves,
                    onMore = actions.onSectionMore,
                )
                Spacer(Modifier.height(Space.Group))
                SectionSentence(section, onWhat = actions.onWhat, onHow = actions.onHow)
            }
            if (section.isEmpty) {
                Spacer(Modifier.height(Space.Sibling))
                EmptySectionNote(section.option)
            } else {
                Spacer(Modifier.height(contentGap(section.layout)))
                if (section.option == SectionOption.Pinned && !section.loading) {
                    PinArrangement(section, actions.onOpen, actions.onArrange, home)
                } else {
                    SectionBody(section, actions)
                }
            }
        }
    }
}

@Composable
private fun SectionBody(section: SectionUi, actions: SectionActions) {
    if (section.option == SectionOption.Speakers) {
        SpeakerShelf(section, actions.onSpeaker)
    } else if (section.loading) {
        SectionSkeleton(section)
    } else {
        SectionContent(
            section,
            actions.onOpen,
            actions.onMore,
            onResume = actions.onResume,
        )
    }
}

/** A row's band at section size: a tile's shape, as far past the margins as a text row's band. */
private object LiftedBand : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val inset = with(density) { (Keyline.Margin - Space.BandText).toPx() }
        val band = Shapes.Tile.createOutline(Size(size.width - inset * 2, size.height), layoutDirection, density)
        return Outline.Generic(
            Path().apply {
                addOutline(band)
                translate(Offset(inset, 0f))
            },
        )
    }
}

/** Its title: the person's own name for it, else its option's, with the speaker Played here shows after it. */
@Composable
internal fun SectionUi.heading(): String {
    val words = title ?: stringResource(sectionTitle(option, fallback))
    return heardOn?.takeIf { title == null }?.let { words + DOT_SEPARATOR + it.displayName() } ?: words
}
