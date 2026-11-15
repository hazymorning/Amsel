package io.github.hazymorning.amsel.ui.screens.player

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.MemberVolume
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.EndValue
import io.github.hazymorning.amsel.ui.components.InkEdge
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.inkHeight
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.components.inkWidth
import io.github.hazymorning.amsel.ui.components.label
import io.github.hazymorning.amsel.ui.components.overhang
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.rememberPress
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The volume of [speaker]: its level at the title's end and one slider. A group adds each of its
 * speakers under it ([members]), each with its own level and slider. A level keeps the room of
 * "100 %", so the name before it holds still while a finger changes it.
 */
@Composable
fun VolumeSheetContent(
    speaker: String,
    level: Int?,
    members: List<MemberVolume>,
    onLevel: (Int) -> Unit,
    onMember: (id: String, level: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val main = rememberLevel(level ?: 0)
    val label = stringResource(R.string.player_volume)
    val loudest = stringResource(R.string.player_volume_percent, 100)
    Column(modifier.fillMaxWidth().padding(bottom = Space.Group)) {
        SheetHeader(
            label,
            name = speaker,
            value = if (level != null) volumeWords(main.shown) else null,
            widestValue = loudest,
        )
        Spacer(Modifier.height(Space.Group))
        VolumeRow(main, enabled = level != null, label = label, onLevel = onLevel)
        members.forEachIndexed { index, member ->
            key(member.id) {
                val own = rememberLevel(member.level)
                // The group's own line stands apart from its speakers'.
                Spacer(Modifier.height(if (index == 0) Space.S10 else Space.Group))
                MemberName(member.name, volumeWords(own.shown), loudest)
                Spacer(Modifier.height(Space.S2))
                VolumeRow(own, enabled = true, label = member.name, onLevel = { onMember(member.id, it) })
            }
        }
    }
}

@Composable
private fun MemberName(name: String, value: String, widestValue: String) {
    val style = MaterialTheme.typography.bodyLarge
    Row(Modifier.fillMaxWidth().padding(horizontal = Keyline.Margin)) {
        Words(
            name,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).alignByBaseline().textInk(style).inkStart(name, style),
        )
        Spacer(Modifier.width(Space.Sibling))
        EndValue(value, widestValue, Modifier.alignByBaseline())
    }
}

/** The volume of the speaker that plays; a group's speakers each have their own under the group's. */
@Composable
fun VolumeRoute() {
    val graph = LocalContext.current.graph
    val now by graph.playing.nowPlaying.collectAsStateWithLifecycle()
    val players by graph.data.speakers.players.collectAsStateWithLifecycle()
    val np = now ?: return
    val members = remember(players, np.speaker.id) { graph.speakerVolumes.members(players, np.speaker.id) }
    VolumeSheetContent(
        speaker = np.speaker.label(),
        level = np.volume,
        members = members,
        onLevel = { graph.playback.control(PlayerControl.SetVolume(it)) },
        onMember = graph.speakerVolumes::set,
    )
}

/** A level in words: "40 %", and at nothing, "silent". */
@Composable
internal fun volumeWords(level: Int): String = if (level == 0) {
    stringResource(R.string.player_volume_still)
} else {
    stringResource(R.string.player_volume_percent, level)
}

/** A volume as shown while someone changes it: the reported level takes over once they have let it be. */
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

/**
 * − and + on the margins step by one; held, they repeat. The line between them is the one slider.
 * Disabled, for a speaker that sets its own volume, it shows at 38% and says so to TalkBack.
 */
@Composable
internal fun VolumeRow(
    level: LevelState,
    enabled: Boolean,
    label: String,
    onLevel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val set: (Int) -> Unit = { target ->
        if (level.change(target.coerceIn(0, 100))) {
            // Every step of the level is a detent the finger feels, and an end a firmer one.
            val end = level.shown == 0 || level.shown == 100
            haptics.performHapticFeedback(
                if (end) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick,
            )
            onLevel(level.shown)
        }
    }
    // Its layout is the + glyph's ink, so the distances around it are measured to what shows.
    val trim = (Sizes.TouchTarget - inkHeight(R.drawable.ic_add)) / 2
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Keyline.Margin)
            .overhang(top = trim, bottom = trim)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(R.drawable.ic_minus, stringResource(R.string.player_volume_down), InkEdge.Start, enabled) {
            set(level.shown - 1)
        }
        Spacer(Modifier.width(Space.Sibling))
        LineSlider(
            fraction = { level.shown / 100f },
            semanticValue = level.shown / 100f,
            label = label,
            stateDescription = if (enabled) {
                stringResource(R.string.player_volume_percent, level.shown)
            } else {
                stringResource(R.string.player_volume_fixed)
            },
            onChange = { set((it * 100).roundToInt()) },
            onChangeFinished = { set((it * 100).roundToInt()) },
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
        Spacer(Modifier.width(Space.Sibling))
        StepButton(R.drawable.ic_add, stringResource(R.string.player_volume_up), InkEdge.End, enabled) {
            set(level.shown + 1)
        }
    }
}

private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_FIRST_MS = 100L
private const val REPEAT_FASTEST_MS = 40L
private const val REPEAT_SPEEDUP_MS = 4L

/**
 * A step glyph whose ink stands on its margin, in a 48 dp target. A tap steps once; held, it steps
 * again after 400 ms at ten a second, rising to 25, until the finger lifts or the gesture is lost.
 */
@Composable
private fun StepButton(@DrawableRes icon: Int, label: String, edge: InkEdge, enabled: Boolean, step: () -> Unit) {
    val scope = rememberCoroutineScope()
    val stepNow by rememberUpdatedState(step)
    val active by rememberUpdatedState(enabled)
    val presses = remember { MutableInteractionSource() }
    val spare = (Sizes.TouchTarget - inkWidth(icon)) / 2
    Box(
        Modifier
            .overhang(
                start = if (edge == InkEdge.Start) spare else 0.dp,
                end = if (edge == InkEdge.End) spare else 0.dp,
            )
            .size(Sizes.TouchTarget)
            .indication(presses, rememberPress(Shapes.Circle))
            .semantics {
                contentDescription = label
                role = Role.Button
                if (enabled) {
                    onClick {
                        stepNow()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!active) return@awaitEachGesture
                    down.consume()
                    val press = PressInteraction.Press(down.position)
                    presses.tryEmit(press)
                    stepNow()
                    val repeat = scope.launch {
                        delay(REPEAT_AFTER_MS)
                        var every = REPEAT_FIRST_MS
                        while (active) {
                            stepNow()
                            delay(every)
                            every = (every - REPEAT_SPEEDUP_MS).coerceAtLeast(REPEAT_FASTEST_MS)
                        }
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        repeat.cancel()
                        presses.tryEmit(PressInteraction.Release(press))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        InkGlyph(icon, tint = MaterialTheme.colorScheme.onSurface)
    }
}
