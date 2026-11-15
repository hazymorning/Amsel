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
import io.github.hazymorning.amsel.app.Choice
import io.github.hazymorning.amsel.app.GroupChoice
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.PlayingRing
import io.github.hazymorning.amsel.ui.components.PrimaryButton
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.SheetQuestion
import io.github.hazymorning.amsel.ui.components.label
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.glyph
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * The speakers, with the group step and the question in place of the list. A tap on a speaker
 * ([onSelect]) may ask first ([PlayOnUiState.question]): [onMove] and [onSwitch] are its answers.
 */
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
) {
    var grouping by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.question != null, onBack = onBack)
    BackHandler(enabled = grouping && state.question == null) { grouping = false }
    val step = when {
        state.question != null -> PlayOnStep.Question
        grouping && state.group.isNotEmpty() -> PlayOnStep.Group
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
        }
    }
}

private enum class PlayOnStep { List, Group, Question }

/**
 * Asked before music that plays would stop: on the speaker tapped, which plays something itself,
 * or on the one the person is leaving. The music goes there, or stays and the speaker only changes.
 */
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
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).selectableGroup().padding(bottom = Space.Group),
    ) {
        val members = state.group.count { it.member }.takeIf { state.group.isNotEmpty() }
        PlayOnHeader(members, onGrouping)
        Spacer(Modifier.height(Space.HeaderToContent))
        if (state.grouped) {
            // Letting the speakers go is one big tap, over the first of them.
            val haptics = LocalHapticFeedback.current
            PrimaryButton(
                stringResource(R.string.ungroup_all),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onUngroup()
                },
                modifier = Modifier.padding(horizontal = Keyline.Margin).fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.Group))
        }
        // A speaker out of reach could not be chosen anyway; only the one in use stays to say so.
        state.speakers.filter { it.available || it.id == state.currentId }.forEach { speaker ->
            SpeakerRow(speaker, selected = speaker.id == state.currentId, onClick = { onSelect(speaker) })
        }
    }
}

/** "Transfer playback": the other speakers in reach. The music moves to the one tapped and goes on there. */
@Composable
fun TransferSheetContent(speakers: List<SpeakerUi>, onTransfer: (SpeakerUi) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Space.Group)) {
        SheetHeader(stringResource(R.string.player_transfer))
        Spacer(Modifier.height(Space.HeaderToContent))
        speakers.forEach { speaker ->
            SpeakerRow(speaker, selected = false, role = Role.Button, onClick = { onTransfer(speaker) })
        }
    }
}

/** What [speaker] plays, rests on, or that it plays nothing or is out of reach, in a line. */
@Composable
internal fun speakerLine(speaker: SpeakerUi): String {
    val holds = speaker.song?.let { listOfNotNull(it, speaker.artist).joinToString(DOT_SEPARATOR) }
    // A speaker that rests on a song says so: the song alone would read as playing.
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
        // The ring that marks what plays in every list, before what the speaker plays.
        mark = if (plays) ({ PlayingRing(moving = true) }) else null,
        selected = selected,
        enabled = speaker.available,
        textMaxWidth = SpeakerTextMax,
        role = role,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        leading = {
            InkGlyph(
                speaker.glyph,
                tint = if (selected) colors.onSurface else colors.onSurfaceVariant,
            )
        },
    )
}

/** Speaker names are short in a home: their lines stay narrow, the tick close after them. */
internal val SpeakerTextMax = 240.dp

/**
 * The "Play on" sheet: every speaker with what it plays, in the order Playing lists them ("This
 * phone" first), a tick on [currentId].
 */
@Immutable
data class PlayOnUiState(
    val speakers: List<SpeakerUi> = emptyList(),
    val currentId: String? = null,
    /** Whom the group step is for: the leader of the group that exists, else the speaker in use. */
    val leader: SpeakerUi? = null,
    /** Who can play along with [leader]; empty when it cannot lead a group. */
    val group: List<GroupChoice> = emptyList(),
    /** Speakers play along with another, the one in use or not: Ungroup lets them all go. */
    val grouped: Boolean = false,
    val question: PlayOnQuestion? = null,
)

/** Whether [title], which plays on [from], goes to [target] or stays. */
@Immutable
data class PlayOnQuestion(val target: SpeakerUi, val title: String, val from: SpeakerUi)

/** The "Play on" sheet. [onDone] closes it. */
@Composable
fun PlayOnRoute(onDone: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val current by graph.playing.speaker.collectAsStateWithLifecycle()
    val groups by remember(current.id) { graph.groups.playOn(current.id) }
        .collectAsStateWithLifecycle(remember(current.id) { graph.groups.playOnNow(current.id) })
    var question by remember { mutableStateOf<PlayOnQuestion?>(null) }
    PlayOnSheetContent(
        state = PlayOnUiState(groups.speakers, current.id, groups.leader, groups.choices, groups.grouped, question),
        onSelect = { target ->
            scope.launch {
                if (graph.playOn.choose(target) == Choice.Ask) {
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
    )
}

/** "Transfer playback" from Now Playing's ⋯. The move runs in the app's scope ([PlayOn.moveTo]), so the sheet closes at once. */
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
