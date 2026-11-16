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
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.navigation.rememberOpenItem
import io.github.hazymorning.amsel.ui.navigation.rememberResume
import io.github.hazymorning.amsel.ui.rememberRetry
import kotlinx.coroutines.flow.combine

/**
 * Home: up to six sections, each a sentence the person sets, "Show [what] as [how]". Long-press a
 * section's title, or Edit Home at the end, to change them in place; Done or Back leaves.
 */
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
    /** Continue listening's play button: resume where the person stopped. */
    onResume: (MediaUi) -> Unit = onOpen,
    onSettings: () -> Unit = {},
    /** What to tell above the first section, put away by [onTipGone]. */
    tip: Tip? = null,
    onTipGone: (Tip) -> Unit = {},
    /** Search's field: opens Search, the keyboard up. */
    onSearch: () -> Unit = {},
) {
    BackHandler(enabled = state.editing) { editor.doneEditing() }
    // Home needs no header: content begins under the status bar, and only edit mode, a place of its
    // own, names itself in a slim bar with its way out.
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
                    onSettings,
                    insets.calculateTopPadding(),
                    Modifier.fillMaxSize(),
                    enabled = !state.editing,
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
                        onSettings,
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

            // Sections that found nothing are hidden; Edit Home is the way back to them.
            state.libraryEmpty == false -> EmptyState(
                R.drawable.ic_home,
                stringResource(R.string.home_no_sections),
                body,
                actionLabel = stringResource(R.string.home_edit),
                onAction = editor::startEditing,
            )

            // Until the index answers, nothing rather than a sentence that may be wrong.
            else -> Spacer(body)
        }
    }
}

/**
 * Home, wired to the Home repository; or, [editOnly], its edit mode as a page of its own from
 * Settings, which goes back once Done or Back ends it.
 */
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
            combine(graph.playing.speakers, graph.playing.speaker) { all, inUse -> HomeSpeakers(all, inUse) },
            editOnly = editOnly,
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (editOnly) {
        LaunchedEffect(Unit) { graph.tips.seen(Tip.EditHome) }
        LaunchedEffect(state.editing) { if (!state.editing) navigator.back() }
    }
    val actions = LocalAppActions.current
    val tips = graph.tips
    val tip by tips.current.collectAsStateWithLifecycle()
    // TalkBack names every long press as it reads, and finds Settings open: it needs no tips.
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
        },
        tip = tip.takeUnless { told },
        onTipGone = tips::seen,
        onSearch = LocalStartSearch.current,
        modifier = modifier,
    )
}
