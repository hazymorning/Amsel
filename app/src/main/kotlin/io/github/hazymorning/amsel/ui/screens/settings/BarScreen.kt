package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.navigation.BarItem
import io.github.hazymorning.amsel.ui.navigation.DockControl
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.OrderedToggles
import kotlinx.coroutines.launch

/** Settings out of the bar opens by a pull down at Home's top, which its row's note says. */
@Composable
fun BarScreen(
    places: OrderedToggles<BarItem>,
    onChange: (OrderedToggles<BarItem>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = ArrangeScreen(
    title = stringResource(R.string.settings_bar),
    hint = stringResource(R.string.bar_hint),
    arrangement = places,
    key = BarItem::id,
    label = { stringResource(it.label) },
    icon = BarItem::icon,
    fixed = BarItem.Home,
    fixedLine = stringResource(R.string.bar_home_stays),
    onChange = onChange,
    onBack = onBack,
    modifier = modifier,
    max = BarItem.MAX,
    fullLine = stringResource(R.string.bar_full),
    note = stringResource(R.string.bar_settings_pull).let { pull ->
        { place -> pull.takeIf { place == BarItem.Settings && place !in places.shown } }
    },
)

@Composable
fun DockScreen(
    controls: OrderedToggles<DockControl>,
    onChange: (OrderedToggles<DockControl>) -> Unit,
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

@Composable
fun BarRoute(navigator: Navigator) {
    val prefs = LocalContext.current.graph.data.prefs
    val stored by prefs.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    BarScreen(
        places = BarItem.arrangement(stored?.barPlaces),
        onChange = { places -> scope.launch { prefs.setBarPlaces(places.tokens(BarItem::id)) } },
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
