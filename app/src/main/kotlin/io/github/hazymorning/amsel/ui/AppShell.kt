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
import io.github.hazymorning.amsel.ui.navigation.BarPlace
import io.github.hazymorning.amsel.ui.navigation.DockControl
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Opening
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.navigation.Tab
import io.github.hazymorning.amsel.ui.theme.LocalReducedMotion
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Space
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Content the shell hosts. Each slot is a screen the app wires to its ViewModel. */
class ShellSlots(
    /** A tab destination. */
    val screen: @Composable (route: Route, navigator: Navigator) -> Unit,
    /** Full-screen Now Playing; [onClose] brings it down. */
    val nowPlaying: @Composable (onClose: () -> Unit, navigator: Navigator) -> Unit,
    /** Sheets that may lead to a page, on the shown tab. */
    val sheets: @Composable (navigator: Navigator) -> Unit,
)

/**
 * A message's lower edge above the system bar while Now Playing is open. Now Playing's footer ends
 * 16 above it, so the footer's ink lies under the bar's straight edges, never beside a corner.
 */
private val OverFooter = Space.S1

/**
 * A tab opens at its top: the pages opened in a tab close when the person leaves it or taps it
 * again, so nothing of them keeps running. Back from another tab's top goes to Home, then leaves
 * the app. Pages run the full height of the screen, under the dock. [layer] is where Now Playing is.
 */
@Composable
fun AppShell(
    nowPlaying: NowPlayingUi?,
    speaker: SpeakerUi,
    onSpeaker: () -> Unit,
    slots: ShellSlots,
    /** The speaker picker at its group step: a widget's Group button. */
    onGroup: () -> Unit = onSpeaker,
    modifier: Modifier = Modifier,
    onControl: (PlayerControl) -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    layer: PlayerLayerState = rememberPlayerLayerState(),
    opening: Opening? = null,
    onOpened: () -> Unit = {},
    offer: Offer? = null,
    onTakeOffer: (Offer) -> Unit = {},
    onDismissOffer: (Offer) -> Unit = {},
    /** The places the bottom bar shows, in its order. */
    bar: List<BarPlace> = BarPlace.Default,
    /** The mini player's controls beside what plays, in the person's order. */
    dock: List<DockControl> = DockControl.Default.visible,
    /** The mini player's controls that open something or change it: the heart, volume, sleep, the speaker. */
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
            Opening.Search -> {
                tabs.popToRoot(Tab.Search)
                tab = Tab.Search
                newSearch++
                layer.close()
            }

            // Opened cold, the speaker reports a moment after the app shows; if nothing plays by
            // then, Home stays, rather than Now Playing rising over it later out of nowhere.
            Opening.NowPlaying, Opening.UpNext -> withTimeoutOrNull(NOW_PLAYING_WAIT_MS) {
                snapshotFlow { playing }.first { it != null }
                layer.open()
                if (asked == Opening.UpNext) upNext.value = true
            }

            Opening.Speakers -> onSpeaker()

            Opening.Group -> onGroup()
        }
        onOpened()
    }
    val dockHeight = remember { mutableIntStateOf(0) }
    val stack = tabs.stack(tab)
    // A place taken out of the bar from its own pages keeps them; back at its top, which nothing opens
    // any more, the person is Home. Search may stand outside the bar, as Home's field opens it.
    val stranded = tab != Tab.Home && tab != Tab.Search && bar.none { it.tab == tab }
    LaunchedEffect(stranded, stack.size) { if (stranded && stack.size == 1) tab = Tab.Home }
    // Nothing left to show: the layer folds away rather than leaving its scrim behind.
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
            // The dock stands a little above the page, its short shadow on what scrolls under it.
            DockShadow(Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, -dockHeight.intValue) })
            Dock(
                nowPlaying = nowPlaying,
                speaker = speaker,
                onOpen = openPlayer,
                onControl = onControl,
                onSpeaker = onSpeaker,
                controls = dock,
                onDockControl = onDockControl,
                // A long press on what plays arranges its controls, as one on the bar arranges the bar.
                onArrange = { tabs.navigator(tab).open(Route.Dock) },
                modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { dockHeight.intValue = it.height },
                openDrag = Modifier.layerDrag(layer),
                covered = layer.isOpen,
            ) {
                // The places the person chose, in their order; a long press on any arranges them.
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
            // Back from a non-Home tab's top goes to Home first (NavDisplay only sees a 1-entry stack).
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
            // Over Now Playing too, where most of what can fail is tapped. They move with the layer
            // from 16 above the dock, or the keyboard that covers the dock, to Now Playing's footer,
            // which they cover whole; a message stands over an offer.
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

/**
 * The current tab's pages. A page that comes and one that goes cross-fade, as content that changes
 * does; only the back gesture moves a page, with the finger. Another tab is simply there.
 */
@Composable
private fun TabPages(tabs: TabStacks, tab: Tab, modifier: Modifier = Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val travel = with(LocalDensity.current) { Motion.AxisTravel.roundToPx() } * if (rtl) -1 else 1
    // With less motion asked for, the back gesture is a crossfade too.
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
        // Leaving a page for another tab looks like a pop to NavDisplay; it is a tab change all the same.
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

/** Opens Now Playing: what a tap on something already playing does, rather than nothing. */
val LocalOpenNowPlaying = staticCompositionLocalOf<() -> Unit> { {} }

/** Counts the searches a shortcut or a link asked for: each one empties Search and brings the keyboard up. */
val LocalNewSearch = compositionLocalOf { 0 }

/** Goes to Search, emptied, the keyboard up: Home's Search field. */
val LocalStartSearch = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Set once a link to Up next (a widget's button) has opened Now Playing, which then opens Up next, or
 * the chapters ([AnswerUpNext]), and clears it.
 */
val LocalUpNextAsked = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

/**
 * Now Playing's answer to a link that asked for Up next: [open], once per ask. It runs in the
 * screen's own scope: clearing the ask restarts the effect that saw it, which would cut the opening
 * short and leave Up next closed.
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

/** How long a Now Playing link waits for the speaker to say what plays. */
private const val NOW_PLAYING_WAIT_MS = 5_000L
