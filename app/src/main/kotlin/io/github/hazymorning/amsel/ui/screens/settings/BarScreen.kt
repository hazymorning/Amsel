package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.holdToMove
import io.github.hazymorning.amsel.ui.components.moved
import io.github.hazymorning.amsel.ui.components.rememberReorderable
import io.github.hazymorning.amsel.ui.navigation.BarPlace
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Which places the bottom bar shows, and in what order: each place a row with its glyph, its name
 * and a switch, those in the bar first, in its order. A held press on a row picks it up and moves
 * it, as in Edit Home; TalkBack moves it through its actions. Home has no switch: it always stays.
 * The bar under the page follows every change at once.
 */
@Composable
fun BarScreen(
    places: List<BarPlace>,
    onChange: (List<BarPlace>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val all = places + BarPlace.entries.filterNot { it in places }
    // The order while a drag swaps places; the stored one replaces it once the drop is saved.
    var order by remember(all) { mutableStateOf(all) }
    val shown = { list: List<BarPlace> -> list.filter { it in places } }
    val list = rememberLazyListState()
    val reorder = rememberReorderable(
        list,
        movable = { key -> BarPlace.entries.any { it.id == key } },
        onSwap = { from, to ->
            order = order.moved(order.indexOfFirst { it.id == from }, order.indexOfFirst { it.id == to })
        },
        onDrop = { onChange(shown(order)) },
    )
    val up = stringResource(R.string.move_up)
    val down = stringResource(R.string.move_down)
    MaterialScreen(modifier, bar = { PageTopBar(stringResource(R.string.settings_bar), onBack) }) { insets ->
        ScrollEdges(list)
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = insets) {
            item(key = HINT) {
                val style = MaterialTheme.typography.bodyMedium
                Words(
                    stringResource(R.string.bar_hint),
                    style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Keyline.Margin, end = Keyline.Margin, top = Space.S2, bottom = Space.Group),
                )
            }
            items(order, key = { it.id }) { place ->
                val index = order.indexOf(place)
                val lifted = reorder.draggingKey == place.id || reorder.settlingKey == place.id
                val inBar = place in places
                val moves = listOfNotNull(
                    CustomAccessibilityAction(up) {
                        onChange(shown(order.moved(index, index - 1)))
                        true
                    }.takeIf { index > 0 },
                    CustomAccessibilityAction(down) {
                        onChange(shown(order.moved(index, index + 1)))
                        true
                    }.takeIf { index < order.lastIndex },
                )
                ListRow(
                    title = stringResource(place.label),
                    modifier = Modifier
                        .then(
                            if (lifted) {
                                Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(place.id) }
                            } else {
                                Modifier.animateItem()
                            },
                        )
                        .holdToMove(
                            onStart = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                reorder.start(place.id)
                            },
                            onMove = { reorder.drag(it.y) },
                            onEnd = reorder::end,
                        )
                        .semantics {
                            customActions = moves
                            if (place != BarPlace.Home) toggleableState = ToggleableState(inBar)
                        },
                    subtitle = if (place == BarPlace.Home) stringResource(R.string.bar_home_stays) else null,
                    lead = RowLead.Glyph,
                    role = if (place == BarPlace.Home) Role.Button else Role.Switch,
                    onClick = if (place == BarPlace.Home) {
                        null
                    } else {
                        {
                            val next = if (inBar) places - place else shown(order) + place
                            onChange(order.filter { it in next })
                        }
                    },
                    leading = { InkGlyph(place.icon, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    // Off the very edge, as a switch stands in a Settings block.
                    trailing = if (place ==
                        BarPlace.Home
                    ) {
                        null
                    } else {
                        ({ Box(Modifier.padding(end = Space.S2)) { SettingSwitch(inBar) } })
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
        places = BarPlace.of(stored?.barPlaces),
        onChange = { places -> scope.launch { prefs.setBarPlaces(places.map { it.id }) } },
        onBack = navigator::back,
    )
}

private const val HINT = "hint"
