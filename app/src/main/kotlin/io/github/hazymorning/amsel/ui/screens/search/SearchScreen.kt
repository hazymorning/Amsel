package io.github.hazymorning.amsel.ui.screens.search

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.search
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.app.RecentItem
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.LocalNewSearch
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.SearchField
import io.github.hazymorning.amsel.ui.components.TabHeader
import io.github.hazymorning.amsel.ui.components.withTop
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.model.TrackUi
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.rememberOpenItem
import io.github.hazymorning.amsel.ui.rememberRetry
import kotlinx.coroutines.flow.map

/**
 * An empty field shows what was found lately. While a search is on its way the field's glyph turns into a
 * spinner, so nothing under the field moves; the previous results stay until the answer arrives.
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onPlayTrack: (TrackUi) -> Unit,
    onOpen: (MediaUi) -> Unit,
    onTrackMore: (TrackUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    recent: List<RecentItem> = emptyList(),
    onClearRecent: () -> Unit = {},
    focus: FocusRequester = remember { FocusRequester() },
    /** A slim bar in place of the tab's title: the widget's page picks with Search. */
    bar: (@Composable BoxScope.() -> Unit)? = null,
    /**
     * What shows while the field is empty, in place of what was found lately, with the screen's room;
     * the field then waits for a tap before the keyboard comes up.
     */
    browse: (@Composable (PaddingValues) -> Unit)? = null,
) {
    MaterialScreen(modifier, bar = bar) { insets ->
        Column(Modifier.fillMaxSize().padding(top = insets.calculateTopPadding())) {
            if (bar == null) TabHeader(stringResource(R.string.tab_search))
            QueryField(state.query, busy = state.searching, onQueryChange, focus, typeAtOnce = browse == null)
            val results = state.results
            val rest = Modifier.fillMaxSize().padding(insets.withTop(0.dp))
            val actions = remember(onPlayTrack, onOpen, onTrackMore, onMore) {
                ResultActions(onPlayTrack, onOpen, onTrackMore, onMore)
            }
            when {
                state.query.isBlank() && browse != null -> browse(insets.withTop(0.dp))

                state.query.isBlank() -> if (recent.isNotEmpty()) {
                    RecentList(recent, state.playingKey, actions, onClearRecent, insets)
                }

                state.failure != null -> EmptyState(
                    R.drawable.ic_offline,
                    stringResource(state.failure),
                    rest,
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetry,
                )

                results == null -> Unit

                results.isEmpty -> EmptyState(
                    R.drawable.ic_search,
                    stringResource(R.string.search_nothing_found, results.query),
                    rest,
                )

                else -> ResultList(results, state.playingKey, actions, insets)
            }
        }
    }
}

/** Takes focus when Search opens with an empty field: tapping the tab means you want to type. */
@Composable
private fun QueryField(
    query: String,
    busy: Boolean,
    onQueryChange: (String) -> Unit,
    focus: FocusRequester,
    typeAtOnce: Boolean,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (typeAtOnce && query.isEmpty()) focus.requestFocus() }
    SearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.search_placeholder),
        clearLabel = stringResource(R.string.search_clear),
        onClear = {
            onQueryChange("")
            focus.requestFocus()
        },
        modifier = Modifier.focusRequester(focus),
        busy = busy,
        busyLabel = stringResource(R.string.search_searching),
        onSearch = { keyboard?.hide() },
    )
}

/**
 * The Search tab, wired to Music Assistant's search. A song or a radio station plays when tapped;
 * anything else opens.
 */
@Composable
fun SearchRoute(navigator: Navigator, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val actions = LocalAppActions.current
    val haptics = LocalHapticFeedback.current
    val viewModel = viewModel {
        SearchViewModel(
            handle = createSavedStateHandle(),
            search = { graph.data.session.api.search(it) },
            mapper = graph.mapper,
            playing = graph.playing.playingUri,
            library = graph.data.library.everything.map { items -> items.map(graph.mapper::media) },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by graph.recentSearches.items.collectAsStateWithLifecycle()
    val open = rememberOpenItem(navigator)
    // A Search shortcut or link: a new search, the field empty and the keyboard up. Search shown
    // for the first time is that already.
    val focus = remember { FocusRequester() }
    val asked = LocalNewSearch.current
    var answered by rememberSaveable { mutableIntStateOf(asked) }
    LaunchedEffect(asked) {
        if (asked == answered) return@LaunchedEffect
        answered = asked
        viewModel.onQueryChange("")
        focus.requestFocus()
    }
    fun play(key: String, title: String) {
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        graph.playback.play(listOf(key), title = title)
    }
    SearchScreen(
        state = state,
        onQueryChange = viewModel::onQueryChange,
        onPlayTrack = {
            graph.recentSearches.add(it)
            play(it.key, it.title)
        },
        onOpen = {
            graph.recentSearches.add(it)
            open(it)
        },
        onTrackMore = { actions.more(ActionTarget.Track(it)) },
        onMore = { actions.more(ActionTarget.Media(it)) },
        onRetry = rememberRetry(viewModel::retry),
        modifier = modifier,
        recent = recent,
        onClearRecent = graph.recentSearches::clear,
        focus = focus,
    )
}
