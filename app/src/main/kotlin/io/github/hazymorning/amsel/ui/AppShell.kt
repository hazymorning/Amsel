package io.github.hazymorning.amsel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.Offer
import io.github.hazymorning.amsel.ui.components.Dock
import io.github.hazymorning.amsel.ui.components.DockShadow
import io.github.hazymorning.amsel.ui.components.LocalDockInset
import io.github.hazymorning.amsel.ui.components.MessageHost
import io.github.hazymorning.amsel.ui.components.NavBarItem
import io.github.hazymorning.amsel.ui.components.NavBarSpeaker
import io.github.hazymorning.amsel.ui.components.OfferHost
import io.github.hazymorning.amsel.ui.components.PreloadCover
import io.github.hazymorning.amsel.ui.model.NowPlayingUi
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.navigation.BarItem
import io.github.hazymorning.amsel.ui.navigation.DeepLink
import io.github.hazymorning.amsel.ui.navigation.DockControl
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.navigation.Tab
import io.github.hazymorning.amsel.ui.theme.LocalReducedMotion
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Space
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ShellSlots(
    val screen: @Composable (route: Route, navigator: Navigator) -> Unit,
    val nowPlaying: @Composable (onClose: () -> Unit, navigator: Navigator) -> Unit,
    val sheets: @Composable (navigator: Navigator) -> Unit,
)

/**
 * A message's bottom edge above the system bar while Now Playing is open. Now Playing's footer ends
 * 16 dp above the bar, so the message covers it with its straight sides, clear of its rounded corners.
 */
private val OverFooter = Space.S1

@Composable
fun AppShell(
    nowPlaying: NowPlayingUi?,
    speaker: SpeakerUi,
    onSpeaker: () -> Unit,
    slots: ShellSlots,
    modifier: Modifier = Modifier,
    onGroup: () -> Unit = onSpeaker,
    onControl: (PlayerControl) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    layer: PlayerLayerState = rememberPlayerLayerState(),
    opening: DeepLink? = null,
    onOpened: () -> Unit = {},
    offer: Offer? = null,
    onTakeOffer: (Offer) -> Unit = {},
    onDismissOffer: (Offer) -> Unit = {},
    bar: List<BarItem> = BarItem.Default,
    dock: List<DockControl> = DockControl.Default.visible,
    /** Favorite, Volume, Sleep and Speaker; the dock's other controls go to [onControl]. */
    onDockControl: (DockControl) -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val scope = rememberCoroutineScope()
    val closePlayer: () -> Unit = { scope.launch { layer.close() } }
    val tabs = rememberTabStacks(closePlayer, slots)
    var newSearch by rememberSaveable { mutableIntStateOf(0) }
    val upNext = remember { mutableStateOf(false) }
    val playing by rememberUpdatedState(nowPlaying)
    LaunchedEffect(opening) {
        val asked = opening ?: return@LaunchedEffect
        when (asked) {
            DeepLink.Search -> {
                tabs.popToRoot(Tab.Search)
                tab = Tab.Search
                newSearch++
                layer.close()
            }

            // Opened cold, the speaker reports shortly after the app shows; if nothing plays within the
            // wait, Home stays rather than Now Playing opening later unasked.
            DeepLink.NowPlaying, DeepLink.UpNext -> withTimeoutOrNull(NOW_PLAYING_WAIT_MS) {
                snapshotFlow { playing }.first { it != null }
                layer.open()
                if (asked == DeepLink.UpNext) upNext.value = true
            }

            DeepLink.Speakers -> onSpeaker()

            DeepLink.Group -> onGroup()
        }
        onOpened()
    }
    val dockHeight = remember { mutableIntStateOf(0) }
    val stack = tabs.stack(tab)
    // A tab removed from the bar keeps its open pages; back at its root, which nothing reaches any more,
    // the user is taken Home. Search is exempt: Home's field opens it.
    val stranded = tab != Tab.Home && tab != Tab.Search && bar.none { it.tab == tab }
    LaunchedEffect(stranded, stack.size) { if (stranded && stack.size == 1) tab = Tab.Home }
    // PlayerLayer leaves composition when nothing plays; close its state too, or it stays open.
    val nothingPlays = nowPlaying == null
    LaunchedEffect(nothingPlays) { if (nothingPlays) layer.close() }
    val density = LocalDensity.current
    val dockInset = remember(density) { derivedStateOf { with(density) { dockHeight.intValue.toDp() } } }

    val openPlayer: () -> Unit = { scope.launch { layer.open() } }
    val startSearch: () -> Unit = {
        tabs.popToRoot(Tab.Search)
        tab = Tab.Search
        newSearch++
    }
    CompositionLocalProvider(
        LocalOpenNowPlaying provides openPlayer,
        LocalNewSearch provides newSearch,
        LocalStartSearch provides startSearch,
        LocalUpNextAsked provides upNext,
    ) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            CompositionLocalProvider(LocalDockInset provides dockInset) {
                TabPages(tabs, tab, Modifier.fillMaxSize())
            }
            DockShadow(Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, -dockHeight.intValue) })
            Dock(
                nowPlaying = nowPlaying,
                speaker = speaker,
                onOpen = openPlayer,
                onControl = onControl,
                onSpeaker = onSpeaker,
                controls = dock,
                onDockControl = onDockControl,
                onArrange = { tabs.navigator(tab).open(Route.Dock) },
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { dockHeight.intValue = it.height },
                openDrag = Modifier.layerDrag(layer),
                covered = layer.isOpen,
            ) {
                val arrange = stringResource(R.string.bar_arrange)
                val onArrange = { tabs.navigator(tab).open(Route.Bar) }
                bar.forEach { place ->
                    val t = place.tab
                    if (t == null) {
                        NavBarSpeaker(speaker, nowPlaying?.isPlaying == true, onSpeaker, onArrange, arrange)
                    } else {
                        NavBarItem(
                            selected = t == tab,
                            onClick = {
                                // Leaving a tab, or tapping it again, closes its pages, so none keeps running.
                                tabs.popToRoot(tab)
                                tab = t
                            },
                            label = stringResource(t.label),
                            icon = t.icon,
                            selectedIcon = t.selectedIcon,
                            onLongClick = onArrange,
                            longClickLabel = arrange,
                        )
                    }
                }
            }
            // NavDisplay cannot pop a one-entry stack, so back from another tab's root goes Home here.
            BackHandler(enabled = !layer.isOpen && stack.size == 1 && tab != Tab.Home) { tab = Tab.Home }

            if (nowPlaying != null) {
                PlayerLayer(layer, onClose = closePlayer) {
                    Surface(Modifier.fillMaxSize().layerDrag(layer), color = MaterialTheme.colorScheme.surface) {
                        slots.nowPlaying(closePlayer, tabs.navigator(tab))
                    }
                }
                PreloadCover(nowPlaying.art)
            }
            slots.sheets(tabs.navigator(tab))
            // Over Now Playing too, where most failing actions are tapped. They follow the layer from
            // above the dock (or the keyboard covering it) to over Now Playing's footer.
            val systemBars = WindowInsets.safeDrawing
            val keyboard = WindowInsets.ime
            val overDock: Density.() -> Int = {
                val closed = maxOf(dockHeight.intValue, keyboard.getBottom(this)) + Space.Sibling.roundToPx()
                val open = systemBars.getBottom(this) + OverFooter.roundToPx()
                (closed + (open - closed) * layer.fraction).roundToInt()
            }
            val offerHeight = remember { mutableIntStateOf(0) }
            OfferHost(
                offer,
                speaker,
                onTake = onTakeOffer,
                onDismiss = onDismissOffer,
                onSpeaker = onSpeaker,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset { IntOffset(0, -overDock()) }
                    .onSizeChanged { offerHeight.intValue = it.height },
            )
            MessageHost(
                snackbarHostState,
                Modifier.align(Alignment.BottomCenter).offset {
                    val offerRoom = offerHeight.intValue.let { if (it > 0) it + Space.S2.roundToPx() else 0 }
                    IntOffset(0, -(overDock() + offerRoom))
                },
            )
        }
    }
}

@Composable
private fun TabPages(tabs: TabStacks, tab: Tab, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val travel = with(LocalDensity.current) { Motion.AxisTravel.roundToPx() } * if (rtl) -1 else 1
    val still = LocalReducedMotion.current
    NavDisplay(
        entries = tabs.entries(tab),
        modifier = modifier,
        onBack = { tabs.navigator(tab).back() },
        transitionSpec = {
            when {
                initialState.root() != targetState.root() -> Motion.none()
                else -> Motion.crossfade()
            }
        },
        // Switching tabs from a page can look like a pop to NavDisplay; it is still a tab change.
        popTransitionSpec = {
            when {
                initialState.root() != targetState.root() -> Motion.none()
                else -> Motion.crossfade()
            }
        },
        predictivePopTransitionSpec = { edge ->
            if (still) {
                Motion.crossfade()
            } else {
                Motion.predictiveBack(travel, towardRight = edge == NavigationEvent.EDGE_LEFT)
            }
        },
    )
}

val LocalOpenNowPlaying = staticCompositionLocalOf<() -> Unit> { {} }

/** Bumped for each fresh search asked for; every change empties Search and brings up the keyboard. */
val LocalNewSearch = compositionLocalOf { 0 }

val LocalStartSearch = staticCompositionLocalOf<() -> Unit> { {} }

/** Set once an Up next link has opened Now Playing; [AnswerUpNext] answers and clears it. */
val LocalUpNextAsked = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

/**
 * Runs [open] once per Up next link, in the screen's own scope: clearing the ask restarts the effect,
 * which would cancel [open] midway and leave Up next closed.
 */
@Composable
fun AnswerUpNext(open: suspend () -> Unit) {
    val asked = LocalUpNextAsked.current
    val scope = rememberCoroutineScope()
    val answer by rememberUpdatedState(open)
    LaunchedEffect(asked.value) {
        if (!asked.value) return@LaunchedEffect
        asked.value = false
        scope.launch { answer() }
    }
}

private const val NOW_PLAYING_WAIT_MS = 5_000L
