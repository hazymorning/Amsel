package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.home.SectionLayout
import io.github.hazymorning.amsel.data.home.SectionOption
import io.github.hazymorning.amsel.ui.components.ActionRow
import io.github.hazymorning.amsel.ui.components.ChoiceRow
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.sheetEnd
import io.github.hazymorning.amsel.ui.model.SpokenWord
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.spaced
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

/**
 * What a section shows: every choice the library offers, in groups parted by space ([WhatGroups]).
 * Nothing on the sheet but its title is anything other than a choice. It opens with the [current]
 * one in its middle, as far as the list scrolls, so a choice far down never opens at the sheet's edge.
 */
@Composable
internal fun WhatSheetContent(current: SectionOption, spoken: SpokenWord, onSelect: (SectionOption) -> Unit) {
    val scroll = rememberScrollState()
    val chosenMiddle = remember { mutableIntStateOf(UNPLACED) }
    LaunchedEffect(Unit) {
        val middle = snapshotFlow { chosenMiddle.intValue }.first { it != UNPLACED }
        scroll.scrollTo(middle - scroll.viewportSize / 2)
    }
    val between = with(LocalDensity.current) { spaced(Space.Group) }
    val groups = remember(current, spoken) {
        WhatGroups.map { group -> group.filter { it == current || it.isOffered(spoken) } }.filter { it.isNotEmpty() }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .selectableGroup()
            .padding(bottom = sheetEnd(RowLead.None, twoLines = false)),
    ) {
        SheetHeader(stringResource(R.string.home_edit_what))
        Spacer(Modifier.height(Space.HeaderToContent))
        groups.forEachIndexed { index, group ->
            if (index > 0) Spacer(Modifier.height(between))
            group.forEach { option ->
                ChoiceRow(
                    stringResource(option.choice),
                    selected = option == current,
                    onClick = { onSelect(option) },
                    modifier = if (option == current) {
                        Modifier.onPlaced {
                            chosenMiddle.intValue = (it.positionInParent().y + it.size.height / 2f).roundToInt()
                        }
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

private const val UNPLACED = -1

@Composable
internal fun HowSheetContent(current: SectionLayout, onSelect: (SectionLayout) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup().padding(bottom = sheetEnd(RowLead.None, twoLines = false))) {
        SheetHeader(stringResource(R.string.home_edit_how))
        Spacer(Modifier.height(Space.HeaderToContent))
        SectionLayout.entries.forEach { layout ->
            ChoiceRow(stringResource(layout.label), selected = layout == current, onClick = { onSelect(layout) })
        }
    }
}

/**
 * A section's ⋯ in edit mode: its heading hidden or shown on Home, and Remove; moving it is a held
 * press, or TalkBack's actions.
 */
@Composable
internal fun SectionActionsSheetContent(
    title: String,
    titled: Boolean,
    onTitled: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = sheetEnd(RowLead.Glyph, twoLines = false))) {
        SheetHeader(title)
        Spacer(Modifier.height(Space.HeaderToContent))
        ActionRow(
            if (titled) R.drawable.ic_title_off else R.drawable.ic_title,
            stringResource(if (titled) R.string.home_hide_title else R.string.home_show_title),
            onClick = { onTitled(!titled) },
        )
        ActionRow(R.drawable.ic_remove, stringResource(R.string.remove), onRemove)
    }
}

/** A choice applies at once, under the sheet as it slides away. */
@Composable
internal fun EditSheets(
    open: Pair<EditSheet, String>?,
    sections: List<SectionUi>,
    editor: HomeEditor,
    onClose: () -> Unit,
    spoken: SpokenWord,
) {
    val (kind, id) = open ?: return
    val section = sections.firstOrNull { it.id == id } ?: return
    AmselSheet(onDismiss = onClose) {
        fun done(change: () -> Unit) {
            change()
            close()
        }
        when (kind) {
            EditSheet.What -> WhatSheetContent(section.option, spoken, onSelect = { done { editor.setOption(id, it) } })

            EditSheet.How -> HowSheetContent(section.layout) { done { editor.setLayout(id, it) } }

            EditSheet.Actions -> SectionActionsSheetContent(
                title = section.heading(),
                titled = section.titled,
                onTitled = { done { editor.setTitled(id, it) } },
                onRemove = { done { editor.remove(id) } },
            )
        }
    }
}

internal enum class EditSheet { What, How, Actions }
