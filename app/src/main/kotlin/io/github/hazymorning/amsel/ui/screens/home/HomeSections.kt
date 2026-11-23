package io.github.hazymorning.amsel.ui.screens.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.zIndex
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.Tip
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.TextAction
import io.github.hazymorning.amsel.ui.components.baselineFrame
import io.github.hazymorning.amsel.ui.components.moved
import io.github.hazymorning.amsel.ui.components.plus
import io.github.hazymorning.amsel.ui.components.rememberReorderable
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.capHeight

/**
 * Home's sections in their order, under a tip while there is one to give; in edit mode each can
 * be carried to another place, and Add section stands under the last. A long press on a free spot
 * edits Home, as one on the phone's own home screen does, so Home stays editable with every
 * heading hidden; a cover's, a card's or a heading's own long press comes first.
 */
@Composable
internal fun Sections(
    state: HomeUiState,
    onOpen: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    onSeeAll: (SectionUi) -> Unit,
    editor: HomeEditor,
    onResume: (MediaUi) -> Unit,
    insets: PaddingValues,
    modifier: Modifier,
    tip: Tip? = null,
    onTipGone: (Tip) -> Unit = {},
    onSearch: () -> Unit = {},
) {
    val list = rememberLazyListState()
    ScrollEdges(list)
    // What the list shows between the bar and the dock, so a carried pin scrolls it at those edges.
    var listBox by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val shown by rememberUpdatedState(
        with(density) { insets.calculateTopPadding().toPx() to insets.calculateBottomPadding().toPx() },
    )
    // One for the list's life: a new one at each recomposition would keep every section from skipping.
    val home = remember(list) {
        DragScrollArea(list, vertical = true) {
            listBox.copy(top = listBox.top + shown.first, bottom = listBox.bottom - shown.second)
        }
    }
    val byId = remember(state.visible) { state.visible.associateBy { it.id } }
    val ids = remember(byId) { byId.keys.toList() }
    // The order while a drag swaps sections; the stored order replaces it once the drop is saved.
    var order by remember(ids) { mutableStateOf(ids) }
    val reorder = rememberReorderable(
        list,
        movable = { it in byId },
        onSwap = { from, to -> order = order.moved(order.indexOf(from), order.indexOf(to)) },
        onDrop = { key -> editor.move(key as String, order.indexOf(key)) },
    )
    var sheet by rememberSaveable { mutableStateOf<Pair<EditSheet, String>?>(null) }
    val loading = stringResource(R.string.loading)
    val sections = order.mapNotNull(byId::get)
    val haptics = LocalHapticFeedback.current
    LazyColumn(
        modifier
            .onGloballyPositioned { listBox = it.boundsInWindow() }
            .then(if (state.loading) Modifier.clearAndSetSemantics { contentDescription = loading } else Modifier)
            .then(
                if (state.editing) {
                    Modifier
                } else {
                    Modifier.pointerInput(editor) {
                        detectTapGestures(
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                editor.startEditing()
                            },
                        )
                    }
                },
            ),
        state = list,
        contentPadding = insets.plus(top = Space.Group),
        userScrollEnabled = !state.loading,
    ) {
        if (tip != null && !state.editing && !state.loading) tip(tip, onTipGone)
        itemsIndexed(sections, key = { _, section -> section.id }, contentType = { _, s -> s.layout }) { i, section ->
            val lifted = reorder.draggingKey == section.id || reorder.settlingKey == section.id
            val placement = if (lifted) {
                Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(section.id) }
            } else {
                Modifier.animateItem()
            }
            Section(
                section = section,
                first = i == 0,
                editing = state.editing,
                lifted = lifted,
                actions = SectionActions(
                    onOpen = onOpen,
                    onMore = onMore,
                    onSeeAll = { onSeeAll(section) },
                    onStartEditing = editor::startEditing,
                    onWhat = { sheet = EditSheet.What to section.id },
                    onHow = { sheet = EditSheet.How to section.id },
                    onSectionMore = { sheet = EditSheet.Actions to section.id },
                    onRename = { editor.setTitle(section.id, it) },
                    onResume = onResume,
                    // In edit mode the field is a section to arrange, not a way out of Home.
                    onSearch = if (state.editing) ({}) else onSearch,
                    onMoveUp = { editor.moveUp(section.id) }.takeIf { i > 0 },
                    onMoveDown = { editor.moveDown(section.id) }.takeIf { i < sections.lastIndex },
                    onArrange = editor::arrangePins,
                ),
                reorder = reorder,
                modifier = placement,
                home = home,
            )
        }
        if (!state.loading) {
            item(key = FOOTER, contentType = FOOTER) {
                // Home ends a section's distance above the dock, as if the dock were the next section.
                if (state.editing) {
                    EditFooter(editor, onReset = { sheet = EditSheet.Reset to "" })
                } else {
                    Spacer(Modifier.height(Space.SectionToHeader))
                }
            }
        }
    }
    EditSheets(sheet, sections, editor, onClose = { sheet = null }, spoken = state.spoken)
}

/**
 * In edit mode, Add section, centred under the last section a section's distance down, and under
 * it, quieter, the way back to a new person's Home ([onReset] asks first).
 */
@Composable
private fun EditFooter(editor: HomeEditor, onReset: () -> Unit) {
    val cap = with(LocalDensity.current) { capHeight(MaterialTheme.typography.bodyLarge) }
    Column(
        Modifier.fillMaxWidth().baselineFrame(top = Space.SectionToHeader + cap, bottom = Space.ListEnd),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextAction(stringResource(R.string.home_edit_add_section), editor::add, icon = R.drawable.ic_add)
        Spacer(Modifier.height(Space.S8))
        TextAction(
            stringResource(R.string.home_reset),
            onReset,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val FOOTER = "footer"

/** An accessibility action that does this and says it was done. */
internal fun (() -> Unit).done(): () -> Boolean = {
    this()
    true
}
