package io.github.hazymorning.amsel.ui.screens.player

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.MemberVolume
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.EndValue
import io.github.hazymorning.amsel.ui.components.GroupLabel
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.SheetQuestion
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.label
import io.github.hazymorning.amsel.ui.components.trimStartBearing
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Space
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// With a [limit], a bar stops there and the sheet then asks: louder, or stay. A bar let past asks no
// more while the sheet is open.
@Composable
fun VolumeSheetContent(
    speaker: String,
    level: Int?,
    members: List<MemberVolume>,
    onLevel: (Int) -> Unit,
    onMember: (id: String, level: Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Above this a level is set only after the question; 0 for no limit. */
    limit: Int = 0,
) {
    val main = rememberLevel(level ?: 0)
    val levels = members.associate { member -> member.id to key(member.id) { rememberLevel(member.level) } }
    var asking by remember { mutableStateOf<OverLimitRequest?>(null) }
    val allowed = remember { mutableStateListOf<String>() }
    BackHandler(enabled = asking != null) { asking = null }
    AnimatedContent(
        targetState = asking,
        transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) using SizeTransform() },
        modifier = modifier.fillMaxWidth(),
        label = "volume",
    ) { ask ->
        if (ask != null) {
            val wanted = stringResource(R.string.player_volume_percent, ask.wanted)
            val stay = stringResource(R.string.player_volume_percent, limit)
            SheetQuestion(
                title = stringResource(R.string.player_volume),
                question = stringResource(R.string.player_volume_ask, ask.name, stay),
                primary = stringResource(R.string.player_volume_ask_louder, wanted),
                onPrimary = {
                    allowed += ask.bar
                    val bar = if (ask.bar == MAIN) main else levels[ask.bar]
                    bar?.change(ask.wanted)
                    if (ask.bar == MAIN) onLevel(ask.wanted) else onMember(ask.bar, ask.wanted)
                    asking = null
                },
                secondary = stringResource(R.string.player_volume_ask_stay, stay),
                onSecondary = { asking = null },
            )
            return@AnimatedContent
        }
        Column(Modifier.fillMaxWidth().padding(bottom = Space.SheetEdge)) {
            SheetHeader(stringResource(R.string.player_volume))
            Spacer(Modifier.height(Space.HeaderToContent))
            VolumeBar(
                speaker,
                main,
                enabled = level != null,
                onLevel = onLevel,
                limit = if (MAIN in allowed) 0 else limit,
                onPast = { asking = OverLimitRequest(MAIN, speaker, it) },
            )
            if (members.isNotEmpty()) {
                GroupLabel(
                    stringResource(R.string.player_volume_in_group),
                    above = Space.S8,
                    below = Space.LabelToContent,
                )
                members.forEachIndexed { index, member ->
                    val own = levels.getValue(member.id)
                    if (index > 0) Spacer(Modifier.height(Space.S2))
                    VolumeBar(
                        member.name,
                        own,
                        enabled = true,
                        onLevel = { onMember(member.id, it) },
                        limit = if (member.id in allowed) 0 else limit,
                        onPast = { asking = OverLimitRequest(member.id, member.name, it) },
                    )
                }
            }
        }
    }
}

/** [bar] is [MAIN] or a group member's id. */
@Immutable
private data class OverLimitRequest(val bar: String, val name: String, val wanted: Int)

private const val MAIN = "main"

/**
 * A [limit] stops a touch that starts at or under it; once the finger lets go, [onPast] gets the level
 * it asked for. The level keeps the width of "100 %", so nothing moves while it changes.
 */
@Composable
internal fun VolumeBar(
    name: String,
    level: LevelState,
    enabled: Boolean,
    onLevel: (Int) -> Unit,
    modifier: Modifier = Modifier,
    limit: Int = 0,
    onPast: (wanted: Int) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val touch = remember { VolumeDrag() }
    val start: () -> Unit = {
        touch.ceiling = if (limit > 0 && level.shown <= limit) limit else MAX_LEVEL
        touch.past = 0
    }
    val move: (Int) -> Unit = { target ->
        val wanted = target.coerceIn(0, MAX_LEVEL)
        touch.past = if (wanted > touch.ceiling) wanted else 0
        if (level.change(minOf(wanted, touch.ceiling))) {
            val end = level.shown == 0 || level.shown == touch.ceiling
            haptics.performHapticFeedback(
                if (end) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick,
            )
            onLevel(level.shown)
        }
    }
    val finish: () -> Unit = {
        val wanted = touch.past
        touch.past = 0
        if (wanted > 0) onPast(wanted)
    }
    val begin by rememberUpdatedState(start)
    val update by rememberUpdatedState(move)
    val end by rememberUpdatedState(finish)
    val state = if (enabled) {
        stringResource(R.string.player_volume_percent, level.shown)
    } else {
        stringResource(R.string.player_volume_fixed)
    }
    val track = colors.surfaceContainerHigh
    val fill = colors.onSurface.copy(alpha = FILL_INK)
    Row(
        modifier
            .padding(horizontal = Keyline.Margin)
            .fillMaxWidth()
            .height(BarHeight)
            .clip(Shapes.Pill)
            .drawBehind {
                drawRect(track)
                drawRect(fill, size = Size(size.width * level.shown / 100f, size.height))
            }
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures {
                                begin()
                                update((it.x / size.width * 100).roundToInt())
                                end()
                            }
                        }
                        .pointerInput(Unit) {
                            var at = 0f
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    begin()
                                    at = level.shown.toFloat()
                                },
                                onDragEnd = { end() },
                                onDragCancel = { touch.past = 0 },
                            ) { change, dx ->
                                change.consume()
                                at = (at + dx / size.width * 100f).coerceIn(0f, 100f)
                                update(at.roundToInt())
                            }
                        }
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = name
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(level.shown / 100f, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        begin()
                        update((target * 100).roundToInt())
                        end()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .padding(horizontal = BarHeight / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.bodyLarge
        Words(
            name,
            style,
            color = colors.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f).clearAndSetSemantics {}.trimStartBearing(name, style),
        )
        Spacer(Modifier.width(Space.Sibling))
        EndValue(
            volumeWords(level.shown),
            stringResource(R.string.player_volume_percent, 100),
            Modifier.clearAndSetSemantics {},
        )
    }
}

// [ceiling]: where this touch stops; [past]: the level asked beyond it, 0 for none.
private class VolumeDrag {
    var ceiling = MAX_LEVEL
    var past = 0
}

private const val MAX_LEVEL = 100

// A comfortable stretch for a thumb.
private val BarHeight = 56.dp

// Light enough to read the words over, strong enough to see where the level ends.
private const val FILL_INK = 0.14f

@Composable
fun VolumeRoute() {
    val graph = LocalContext.current.graph
    val now by graph.playing.nowPlaying.collectAsStateWithLifecycle()
    val players by graph.data.speakers.players.collectAsStateWithLifecycle()
    val np = now ?: return
    val members = remember(players, np.speaker.id) { graph.speakerVolumes.members(players, np.speaker.id) }
    val prefs by graph.data.prefs.prefs.collectAsStateWithLifecycle()
    VolumeSheetContent(
        speaker = np.speaker.label(),
        level = np.volume,
        members = members,
        onLevel = { graph.playback.control(PlayerControl.SetVolume(it)) },
        onMember = graph.speakerVolumes::set,
        limit = prefs?.volumeLimit ?: 0,
    )
}

@Composable
internal fun volumeWords(level: Int): String = if (level == 0) {
    stringResource(R.string.player_volume_still)
} else {
    stringResource(R.string.player_volume_percent, level)
}

// The level shown while the user changes it; the reported one takes over SETTLE_MS after the last touch.
@Stable
internal class LevelState(initial: Int) {
    var shown by mutableIntStateOf(initial)
        private set
    var touchedAt by mutableLongStateOf(0L)
        private set

    fun change(level: Int): Boolean {
        touchedAt = SystemClock.elapsedRealtime()
        if (level == shown) return false
        shown = level
        return true
    }

    fun settle(reported: Int) {
        shown = reported
    }
}

@Composable
internal fun rememberLevel(reported: Int): LevelState {
    val level = remember { LevelState(reported) }
    LaunchedEffect(reported, level.touchedAt) {
        val wait = level.touchedAt + SETTLE_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        level.settle(reported)
    }
    return level
}

private const val SETTLE_MS = 1_500L
