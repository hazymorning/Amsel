package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.holdToMove
import io.github.hazymorning.amsel.ui.components.rememberReorderable
import io.github.hazymorning.amsel.ui.components.reorderHandle
import io.github.hazymorning.amsel.ui.navigation.Arrangement
import io.github.hazymorning.amsel.ui.navigation.BarPlace
import io.github.hazymorning.amsel.ui.navigation.DockControl
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.screens.library.LibraryKind
import io.github.hazymorning.amsel.ui.screens.library.icon
import io.github.hazymorning.amsel.ui.screens.library.isHeld
import io.github.hazymorning.amsel.ui.screens.library.label
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Sizes
import kotlinx.coroutines.launch

/** Which places the bottom bar shows, five at most, and in what order. Home always stays. */
@Composable
fun BarScreen(
    places: Arrangement<BarPlace>,
    onChange: (Arrangement<BarPlace>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = ArrangeScreen(
    title = stringResource(R.string.settings_bar),
    hint = stringResource(R.string.bar_hint),
    arrangement = places,
    key = BarPlace::id,
    label = { stringResource(it.label) },
    icon = BarPlace::icon,
    fixed = BarPlace.Home,
    fixedLine = stringResource(R.string.bar_home_stays),
    onChange = onChange,
    onBack = onBack,
    modifier = modifier,
    max = BarPlace.MAX,
    fullLine = stringResource(R.string.bar_full),
)

/** Which controls the mini player shows beside what plays, and in what order. Play always stays. */
@Composable
fun DockScreen(
    controls: Arrangement<DockControl>,
    onChange: (Arrangement<DockControl>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = ArrangeScreen(
    title = stringResource(R.string.settings_dock),
    hint = stringResource(R.string.dock_hint),
    arrangement = controls,
    key = DockControl::id,
    label = { stringResource(it.label) },
    icon = DockControl::icon,
    fixed = DockControl.PlayPause,
    fixedLine = stringResource(R.string.dock_play_stays),
    onChange = onChange,
    onBack = onBack,
    modifier = modifier,
    max = DockControl.MAX,
    fullLine = stringResource(R.string.dock_full),
)

/**
 * What the Library shows as chips, and in what order. One always stays; one the library has none
 * of says it shows once there is some.
 */
@Composable
fun LibraryKindsScreen(
    kinds: Arrangement<LibraryKind>,
    /** The kinds the Library has anything of now (LibraryKind.isHeld). */
    held: Set<LibraryKind>,
    onChange: (Arrangement<LibraryKind>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val waits = stringResource(R.string.library_kind_waits)
    ArrangeScreen(
        title = stringResource(PlaybackR.string.library),
        hint = stringResource(R.string.library_kinds_hint),
        arrangement = kinds,
        key = LibraryKind::id,
        label = { stringResource(it.label) },
        icon = { it.icon },
        fixed = null,
        fixedLine = stringResource(R.string.library_kind_last_stays),
        onChange = onChange,
        onBack = onBack,
        modifier = modifier,
        note = { if (it in held) null else waits },
    )
}

/**
 * A page that arranges things, the bar's places, the mini player's controls or the Library's
 * kinds: each a row with its glyph, its name, a switch and a handle, in the person's order, which a
 * switch never changes, so nothing jumps. The handle picks a row up at once, a held press anywhere
 * on it too, and moves it; TalkBack moves it through its actions. [fixed] has no switch: it always
 * stays ([fixedLine] under it). With none fixed, the last one shown stays, its switch dimmed and a
 * tap answered with a no; TalkBack hears [fixedLine]. With [max] shown, those left out wait the same
 * way until another goes; TalkBack hears why ([fullLine]). [note] says something under a row.
 */
@Composable
private fun <T> ArrangeScreen(
    title: String,
    hint: String,
    arrangement: Arrangement<T>,
    key: (T) -> String,
    label: @Composable (T) -> String,
    /** The glyph's drawable for each thing. */
    icon: (T) -> Int,
    fixed: T?,
    fixedLine: String,
    onChange: (Arrangement<T>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    max: Int = Int.MAX_VALUE,
    fullLine: String? = null,
    note: (T) -> String? = { null },
) {
    val haptics = LocalHapticFeedback.current
    // The order while a drag swaps rows; the stored one replaces it once the drop is saved.
    var order by remember(arrangement.order) { mutableStateOf(arrangement.order) }
    val list = rememberLazyListState()
    val keys = arrangement.order.map(key).toSet()
    val reorder = rememberReorderable(
        list,
        movable = { it in keys },
        onSwap = { from, to ->
            val a = order.indexOfFirst { key(it) == from }
            val b = order.indexOfFirst { key(it) == to }
            if (a >= 0 && b >= 0) order = order.toMutableList().apply { add(b, removeAt(a)) }
        },
        onDrop = {
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            onChange(arrangement.copy(order = order))
        },
    )
    val up = stringResource(R.string.move_up)
    val down = stringResource(R.string.move_down)
    val full = arrangement.shown.size >= max
    MaterialScreen(modifier, bar = { PageTopBar(title, onBack) }) { insets ->
        ScrollEdges(list)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = insets) {
            item(key = HINT) { PageHint(hint) }
            items(order, key = key) { item ->
                val id = key(item)
                val index = order.indexOf(item)
                val lifted = reorder.draggingKey == id || reorder.settlingKey == id
                val on = item in arrangement.shown
                val waits = !on && full
                val last = fixed == null && on && arrangement.shown.size == 1
                val held = waits || last
                val moves = listOfNotNull(
                    CustomAccessibilityAction(up) {
                        onChange(arrangement.moved(index, index - 1))
                        true
                    }.takeIf { index > 0 },
                    CustomAccessibilityAction(down) {
                        onChange(arrangement.moved(index, index + 1))
                        true
                    }.takeIf { index < order.lastIndex },
                )
                ListRow(
                    title = label(item),
                    modifier = Modifier
                        .then(
                            if (lifted) {
                                Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(id) }
                            } else {
                                Modifier.animateItem()
                            },
                        )
                        .holdToMove(
                            onStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                reorder.start(id)
                            },
                            onMove = { reorder.drag(it.y) },
                            onEnd = reorder::end,
                        )
                        .semantics {
                            customActions = moves
                            if (item != fixed) toggleableState = ToggleableState(on)
                            if (waits && fullLine != null) stateDescription = fullLine
                            if (last) stateDescription = fixedLine
                        },
                    subtitle = if (item == fixed) fixedLine else note(item),
                    lead = RowLead.Glyph,
                    role = if (item == fixed) Role.Button else Role.Switch,
                    onClick = when {
                        item == fixed -> null

                        // Full, or the last one shown: the row stays a switch, says why, and answers a tap with a no.
                        held -> ({ haptics.performHapticFeedback(HapticFeedbackType.Reject) })

                        else -> ({ onChange(arrangement.with(item, !on)) })
                    },
                    leading = { InkGlyph(icon(item), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Those that wait, and the last one shown, have their switch dimmed; the hint says why.
                            if (item != fixed) {
                                Box(Modifier.alpha(if (held) DISABLED_ALPHA else 1f)) { SettingSwitch(on) }
                            }
                            // The handle: a press on it picks the row up at once.
                            Box(
                                Modifier
                                    .size(Sizes.TouchTarget)
                                    .reorderHandle(reorder, id)
                                    .clearAndSetSemantics {},
                                contentAlignment = Alignment.Center,
                            ) {
                                InkGlyph(R.drawable.ic_drag, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun BarRoute(navigator: Navigator) {
    val prefs = LocalContext.current.graph.data.prefs
    val stored by prefs.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    BarScreen(
        places = BarPlace.arrangement(stored?.barPlaces),
        onChange = { places -> scope.launch { prefs.setBarPlaces(places.tokens(BarPlace::id)) } },
        onBack = navigator::back,
    )
}

@Composable
fun LibraryKindsRoute(navigator: Navigator) {
    val graph = LocalContext.current.graph
    val prefs = graph.data.prefs
    val stored by prefs.prefs.collectAsStateWithLifecycle()
    val types by graph.data.library.heldTypes.collectAsStateWithLifecycle(emptySet())
    val downloads by graph.downloading.rows.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    LibraryKindsScreen(
        kinds = LibraryKind.arrangement(stored?.libraryKinds),
        held = LibraryKind.entries.filter { it.isHeld(types, downloads.isNotEmpty()) }.toSet(),
        onChange = { kinds -> scope.launch { prefs.setLibraryKinds(kinds.tokens(LibraryKind::id)) } },
        onBack = navigator::back,
    )
}

@Composable
fun DockRoute(navigator: Navigator) {
    val prefs = LocalContext.current.graph.data.prefs
    val stored by prefs.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    DockScreen(
        controls = DockControl.arrangement(stored?.dockControls),
        onChange = { controls -> scope.launch { prefs.setDockControls(controls.tokens(DockControl::id)) } },
        onBack = navigator::back,
    )
}

private const val HINT = "hint"
