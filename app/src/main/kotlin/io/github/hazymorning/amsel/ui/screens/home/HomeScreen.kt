package io.github.hazymorning.amsel.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.app.Tip
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.LocalStartSearch
import io.github.hazymorning.amsel.ui.components.EmptyState
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.TextAction
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.navigation.BarItem
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.navigation.rememberOpenItem
import io.github.hazymorning.amsel.ui.navigation.rememberResume
import io.github.hazymorning.amsel.ui.rememberRetry
import kotlinx.coroutines.flow.combine

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpen: (MediaUi) -> Unit,
    onMore: (MediaUi) -> Unit,
    onSeeAll: (SectionUi) -> Unit,
    onOpenWebUi: () -> Unit,
    modifier: Modifier = Modifier,
    editor: HomeEditor = HomeEditor.None,
    onRetry: () -> Unit = {},
    /** Continue listening's play button. */
    onResume: (MediaUi) -> Unit = onOpen,
    /** Settings by a pull down at Home's top; null while Settings has its own place in the bar. */
    onSettings: (() -> Unit)? = {},
    tip: Tip? = null,
    onTipGone: (Tip) -> Unit = {},
    onSearch: () -> Unit = {},
) {
    BackHandler(enabled = state.editing) { editor.doneEditing() }
    // Only edit mode has a top bar; otherwise content starts under the status bar.
    val bar: (@Composable BoxScope.() -> Unit)? = if (state.editing) {
        {
            PageTopBar(
                stringResource(R.string.home_edit),
                onBack = null,
                shorterTitle = stringResource(R.string.home_edit_short),
            ) {
                TextAction(stringResource(R.string.home_edit_done), editor::doneEditing)
            }
        }
    } else {
        null
    }
    MaterialScreen(modifier, bar = bar) { insets ->
        val body = Modifier.fillMaxSize().padding(insets)
        when {
            state.editing || state.visible.isNotEmpty() ->
                SettingsPull(
                    onSettings ?: {},
                    insets.calculateTopPadding(),
                    Modifier.fillMaxSize(),
                    enabled = onSettings != null && !state.editing,
                ) {
                    Sections(
                        state,
                        onOpen,
                        onMore,
                        onSeeAll,
                        editor,
                        onResume,
                        insets,
                        Modifier.fillMaxSize(),
                        tip,
                        onTipGone,
                        onSearch,
                    )
                }

            state.offline -> EmptyState(
                R.drawable.ic_offline,
                stringResource(PlaybackR.string.error_not_connected),
                body,
                actionLabel = stringResource(R.string.retry),
                onAction = onRetry,
            )

            state.sections.isEmpty() -> EmptyState(
                R.drawable.ic_home,
                stringResource(R.string.home_no_sections),
                body,
                actionLabel = stringResource(R.string.home_edit_add_section),
                onAction = {
                    editor.startEditing()
                    editor.add()
                },
            )

            state.libraryEmpty == true -> EmptyState(
                R.drawable.ic_library,
                stringResource(R.string.add_music),
                body,
                actionLabel = stringResource(R.string.open_music_assistant),
                onAction = onOpenWebUi,
            )

            // Sections without items are hidden; edit mode still shows them.
            state.libraryEmpty == false -> EmptyState(
                R.drawable.ic_home,
                stringResource(R.string.home_no_sections),
                body,
                actionLabel = stringResource(R.string.home_edit),
                onAction = editor::startEditing,
            )

            // Until the index answers, nothing rather than a message that may be wrong.
            else -> Spacer(body)
        }
    }
}

/** [editOnly]: edit mode as a page of its own, opened from Settings, that goes back when editing ends. */
@Composable
fun HomeRoute(navigator: Navigator, modifier: Modifier = Modifier, editOnly: Boolean = false) {
    val graph = LocalContext.current.graph
    val viewModel = viewModel {
        val data = graph.data
        HomeViewModel(
            createSavedStateHandle(),
            data.home,
            data.session.state,
            data.library.syncing,
            data.library.synced,
            data.library.isEmpty,
            graph.mapper,
            graph.messages,
            graph.spokenWord,
            combine(graph.playing.speakers, graph.playing.speaker, HomeSpeakers::of),
            editOnly = editOnly,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs by graph.data.prefs.prefs.collectAsStateWithLifecycle()
    if (editOnly) {
        LaunchedEffect(Unit) { graph.tips.seen(Tip.EditHome) }
        LaunchedEffect(state.editing) { if (!state.editing) navigator.back() }
    }
    val actions = LocalAppActions.current
    val tips = graph.tips
    val tip by tips.current.collectAsStateWithLifecycle()
    // TalkBack announces every long-press action and starts with the Settings band open: no tips.
    val told = touchExploration()
    val editor = remember(viewModel, tips) {
        object : HomeEditor by viewModel {
            override fun startEditing() {
                tips.seen(Tip.EditHome)
                viewModel.startEditing()
            }
        }
    }
    ReportDrawnWhen { state.hasContent }
    HomeScreen(
        state = state,
        onOpen = rememberOpenItem(navigator, asks = true),
        onMore = {
            tips.seen(Tip.More)
            actions.more(ActionTarget.Media(it))
        },
        onSeeAll = { section -> navigator.open(Route.SeeAll(section.option, section.title)) },
        onOpenWebUi = { actions.openWebUi() },
        editor = editor,
        onRetry = rememberRetry(),
        onResume = rememberResume(asks = true),
        onSettings = {
            tips.seen(Tip.Settings)
            navigator.open(Route.Settings)
        }.takeIf { BarItem.Settings !in BarItem.of(prefs?.barPlaces) },
        tip = tip.takeUnless { told },
        onTipGone = tips::seen,
        onSearch = LocalStartSearch.current,
        modifier = modifier,
    )
}
