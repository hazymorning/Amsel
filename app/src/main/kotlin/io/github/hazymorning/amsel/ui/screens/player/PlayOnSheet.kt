package io.github.hazymorning.amsel.ui.screens.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.GroupCandidate
import io.github.hazymorning.amsel.app.PlayOnAction
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.AlignedGlyph
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.PlayBars
import io.github.hazymorning.amsel.ui.components.PrimaryButton
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SecondaryButton
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.SheetQuestion
import io.github.hazymorning.amsel.ui.components.label
import io.github.hazymorning.amsel.ui.components.rowGlyphBottom
import io.github.hazymorning.amsel.ui.components.sheetEnd
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.glyph
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.launch

/** [onSelect] may ask first ([PlayOnUiState.question]); [onMove] and [onSwitch] are the answers. */
@Composable
fun PlayOnSheetContent(
    state: PlayOnUiState,
    onSelect: (SpeakerUi) -> Unit,
    modifier: Modifier = Modifier,
    onGroup: (leaderId: String, add: List<String>, remove: List<String>) -> Unit = { _, _, _ -> },
    onUngroup: () -> Unit = {},
    onMove: (SpeakerUi) -> Unit = {},
    onSwitch: (SpeakerUi) -> Unit = {},
    onBack: () -> Unit = {},
    /** Opens at the group step, where there are speakers to group. */
    startGrouping: Boolean = false,
) {
    var grouping by rememberSaveable { mutableStateOf(startGrouping) }
    BackHandler(enabled = state.question != null, onBack = onBack)
    BackHandler(enabled = grouping && state.question == null) { grouping = false }
    val step = when {
        state.question != null -> PlayOnStep.Question

        grouping && state.group.isNotEmpty() -> PlayOnStep.Group

        // Opened to group while Amsel still connects: it waits for the group step, not the list.
        grouping && !state.known -> PlayOnStep.Waiting

        else -> PlayOnStep.List
    }
    AnimatedContent(
        targetState = step,
        transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) using SizeTransform() },
        modifier = modifier.fillMaxWidth(),
        label = "play-on",
    ) { shown ->
        when (shown) {
            PlayOnStep.List -> SpeakerList(
                state,
                onSelect,
                onGrouping = { grouping = true },
                onUngroup = onUngroup,
            )

            PlayOnStep.Group -> GroupList(state) { leaderId, add, remove ->
                grouping = false
                onGroup(leaderId, add, remove)
            }

            PlayOnStep.Question -> state.question?.let { MoveQuestion(it, onMove, onSwitch) }

            PlayOnStep.Waiting -> Column(Modifier.fillMaxWidth().padding(bottom = Space.SheetEdge)) {
                SheetHeader(stringResource(R.string.group))
            }
        }
    }
}

private enum class PlayOnStep { List, Group, Question, Waiting }

// Asked where a switch would stop music: on the busy speaker tapped, or on the one being left.
@Composable
private fun MoveQuestion(question: PlayOnQuestion, onMove: (SpeakerUi) -> Unit, onSwitch: (SpeakerUi) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val target = question.target
    val there = target.label()
    val busy = target.isPlaying
    val sentence = when {
        busy && target.song != null -> stringResource(R.string.player_ask_busy, there, target.song, question.title)
        busy -> stringResource(R.string.player_ask_busy_unknown, there, question.title)
        else -> stringResource(R.string.player_ask_leaving, question.title, question.from.label())
    }
    SheetQuestion(
        title = stringResource(R.string.player_play_on),
        question = sentence,
        primary = stringResource(if (busy) R.string.player_play_there else R.string.player_take_along),
        onPrimary = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onMove(target)
        },
        secondary = stringResource(if (busy) R.string.player_show_playing else R.string.player_leave_there),
        onSecondary = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onSwitch(target)
        },
    )
}

@Composable
private fun SpeakerList(
    state: PlayOnUiState,
    onSelect: (SpeakerUi) -> Unit,
    onGrouping: () -> Unit,
    onUngroup: () -> Unit,
) {
    val members = state.group.count { it.member }.takeIf { state.group.isNotEmpty() }
    val buttons = members != null || state.grouped
    val rowsEnd = sheetEnd(RowLead.Glyph, twoLines = true)
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .selectableGroup()
            .padding(bottom = if (buttons) Space.SheetEdge else rowsEnd),
    ) {
        val haptics = LocalHapticFeedback.current
        SheetHeader(stringResource(R.string.player_play_on))
        Spacer(Modifier.height(Space.HeaderToRows))
        // An unavailable speaker cannot be chosen; only the one in use stays, to show it is unavailable.
        state.speakers.filter { it.available || it.id == state.currentId }.forEach { speaker ->
            SpeakerRow(speaker, selected = speaker.id == state.currentId, onClick = { onSelect(speaker) })
        }
        if (!buttons) return@Column
        // Group is the one primary button, Ungroup a secondary one under it, so the sheet keeps one
        // focal point.
        Spacer(Modifier.height((Space.BeforeList - rowGlyphBottom(RowLead.Glyph, twoLines = true)).coerceAtLeast(0.dp)))
        val wide = Modifier.padding(horizontal = Keyline.Margin).fillMaxWidth()
        if (members != null) {
            PrimaryButton(
                // With a group, the button changes it: plain words, not a count.
                stringResource(if (members > 0) R.string.group_change else R.string.group),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onGrouping()
                },
                modifier = wide,
            )
        }
        if (state.grouped) {
            if (members != null) Spacer(Modifier.height(Space.S2))
            SecondaryButton(
                stringResource(R.string.ungroup_all),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onUngroup()
                },
                modifier = wide,
            )
        }
    }
}

@Composable
fun TransferSheetContent(speakers: List<SpeakerUi>, onTransfer: (SpeakerUi) -> Unit, modifier: Modifier = Modifier) {
    val end = sheetEnd(RowLead.Glyph, twoLines = true)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = end)) {
        SheetHeader(stringResource(R.string.player_transfer))
        Spacer(Modifier.height(Space.HeaderToRows))
        speakers.forEach { speaker ->
            SpeakerRow(speaker, selected = false, role = Role.Button, onClick = { onTransfer(speaker) })
        }
    }
}

@Composable
internal fun speakerLine(speaker: SpeakerUi): String {
    val holds = speaker.song?.let { listOfNotNull(it, speaker.artist).joinToString(DOT_SEPARATOR) }
    // A paused speaker says so: the song alone would read as playing.
    return when {
        !speaker.available -> stringResource(R.string.player_not_available)
        speaker.isPlaying -> holds ?: stringResource(R.string.player_playing)
        holds != null -> stringResource(R.string.player_paused, holds)
        else -> stringResource(R.string.player_not_playing)
    }
}

@Composable
private fun SpeakerRow(speaker: SpeakerUi, selected: Boolean, onClick: () -> Unit, role: Role = Role.RadioButton) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val plays = speaker.available && speaker.isPlaying
    val subtitle = speakerLine(speaker)
    ListRow(
        // A group shows as one speaker, named as the speaker button names it: "Office +2".
        title = speaker.label(),
        subtitle = subtitle,
        lead = RowLead.Glyph,
        playing = plays,
        mark = if (plays) ({ PlayBars(moving = true) }) else null,
        selected = selected,
        enabled = speaker.available,
        textMaxWidth = SpeakerTextMax,
        role = role,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        leading = {
            AlignedGlyph(
                speaker.glyph,
                tint = if (selected) colors.onSurface else colors.onSurfaceVariant,
            )
        },
    )
}

// Speaker names are short, so lines stay narrow and the check stays close after them.
internal val SpeakerTextMax = 240.dp

/** [speakers] in Playing's order, This phone first. */
@Immutable
data class PlayOnUiState(
    val speakers: List<SpeakerUi> = emptyList(),
    val currentId: String? = null,
    /** Whom the group step is for: the leader of the group that exists, else the speaker in use. */
    val leader: SpeakerUi? = null,
    /** Who can play along with [leader]; empty when it cannot lead a group. */
    val group: List<GroupCandidate> = emptyList(),
    /** Any speaker is grouped, the one in use or not; Ungroup releases them all. */
    val grouped: Boolean = false,
    val question: PlayOnQuestion? = null,
    /** False while Amsel has not read the server's speakers yet. */
    val known: Boolean = true,
)

/** Whether [title], which plays on [from], goes to [target] or stays. */
@Immutable
data class PlayOnQuestion(val target: SpeakerUi, val title: String, val from: SpeakerUi)

@Composable
fun PlayOnRoute(onDone: () -> Unit, grouping: Boolean = false) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val current by graph.playing.speaker.collectAsStateWithLifecycle()
    val groups by remember(current.id) { graph.groups.playOn(current.id) }
        .collectAsStateWithLifecycle(remember(current.id) { graph.groups.playOnNow(current.id) })
    var question by remember { mutableStateOf<PlayOnQuestion?>(null) }
    PlayOnSheetContent(
        state = PlayOnUiState(
            groups.speakers,
            current.id,
            groups.leader,
            groups.choices,
            groups.grouped,
            question,
            groups.known,
        ),
        onSelect = { target ->
            scope.launch {
                if (graph.playOn.choose(target) == PlayOnAction.Ask) {
                    question = PlayOnQuestion(target, graph.playing.nowPlaying.value?.title.orEmpty(), current)
                } else {
                    onDone()
                }
            }
        },
        onGroup = graph.groups::set,
        onUngroup = graph.groups::ungroupAll,
        onMove = { target ->
            graph.playOn.moveTo(target)
            onDone()
        },
        onSwitch = { target ->
            scope.launch {
                graph.playOn.select(target)
                onDone()
            }
        },
        onBack = { question = null },
        startGrouping = grouping,
    )
}

// The move runs in the app's scope ([PlayOnController.moveTo]), so the sheet closes at once.
@Composable
fun TransferRoute(onDone: () -> Unit) {
    val graph = LocalContext.current.graph
    val speakers by graph.playOn.transferTargets.collectAsStateWithLifecycle()
    TransferSheetContent(
        speakers,
        onTransfer = { target ->
            graph.playOn.moveTo(target)
            onDone()
        },
    )
}
