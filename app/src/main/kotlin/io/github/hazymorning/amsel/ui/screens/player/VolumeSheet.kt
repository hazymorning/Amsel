package io.github.hazymorning.amsel.ui.screens.player

import android.os.SystemClock
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.components.label
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.model.PlayerControl
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Space
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Volume: a bar for the speaker in use and, for a group, one for each of its speakers under a quiet
 * line, all alike and a step apart. A bar is the slider itself ([VolumeBar]), so the sheet holds
 * nothing but the speakers and how loud each one plays.
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
    Column(modifier.fillMaxWidth().padding(bottom = Space.Group)) {
        SheetHeader(stringResource(R.string.player_volume))
        Spacer(Modifier.height(Space.HeaderToContent))
        VolumeBar(speaker, main, enabled = level != null, onLevel = onLevel)
        if (members.isNotEmpty()) {
            Spacer(Modifier.height(Space.Group))
            val style = MaterialTheme.typography.bodyMedium
            val words = stringResource(R.string.player_volume_in_group)
            Words(
                words,
                style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Keyline.Margin).textInk(style).inkStart(words, style),
            )
            members.forEach { member ->
                key(member.id) {
                    val own = rememberLevel(member.level)
                    Spacer(Modifier.height(Space.S2))
                    VolumeBar(member.name, own, enabled = true, onLevel = { onMember(member.id, it) })
                }
            }
        }
    }
}

/**
 * One speaker's volume as a pill of the second tone that fills with ink as far as the level: its
 * name at the start and the level at the end stand on it, each on the centre of its round end. A
 * drag anywhere along it moves the level, a tap sets it where it lands, and every step is a detent
 * the finger feels, an end a firmer one. The level keeps the room of "100 %", so nothing moves while
 * it changes. TalkBack hears the speaker, its level, and can set it. A speaker that sets its own
 * volume shows at 38% and says so.
 */
@Composable
internal fun VolumeBar(
    name: String,
    level: LevelState,
    enabled: Boolean,
    onLevel: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val set: (Int) -> Unit = { target ->
        if (level.change(target.coerceIn(0, 100))) {
            val end = level.shown == 0 || level.shown == 100
            haptics.performHapticFeedback(
                if (end) HapticFeedbackType.SegmentTick else HapticFeedbackType.SegmentFrequentTick,
            )
            onLevel(level.shown)
        }
    }
    val update by rememberUpdatedState(set)
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
                        .pointerInput(Unit) { detectTapGestures { update((it.x / size.width * 100).roundToInt()) } }
                        .pointerInput(Unit) {
                            var at = 0f
                            detectHorizontalDragGestures(onDragStart = { at = level.shown.toFloat() }) { change, dx ->
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
                        update((target * 100).roundToInt())
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
            modifier = Modifier.weight(1f).clearAndSetSemantics {}.inkStart(name, style),
        )
        Spacer(Modifier.width(Space.Sibling))
        EndValue(
            volumeWords(level.shown),
            stringResource(R.string.player_volume_percent, 100),
            Modifier.clearAndSetSemantics {},
        )
    }
}

/** A bar's height, as tall as the sheet's widest button, and a comfortable stretch for a thumb. */
private val BarHeight = 56.dp

/** How much ink fills a bar as far as the level: plain to read the words over, plain to see where it ends. */
private const val FILL_INK = 0.14f

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
