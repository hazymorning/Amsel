package io.github.hazymorning.amsel.ui.screens.home

import android.view.accessibility.AccessibilityManager
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.InkGlyph
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.inkSides
import io.github.hazymorning.amsel.ui.components.wordGlyph
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.rememberPress
import kotlin.math.roundToInt

/**
 * Home's top, where a pull down at the first section uncovers Settings: a band in the second
 * surface across the width, running up under the status bar ([top]), with the gear and the word in
 * the middle of what shows of it. Home opens on its sections. The band
 * follows the finger and tints in at once; its lower edge comes down over words that already stand
 * where they will rest, and they come in over the second half of the way. Let go, it opens whole
 * past its middle and closes otherwise; scrolling the list on closes it first. A fling never opens
 * it, only a pull. TalkBack cannot pull, so with TalkBack on it starts open.
 */
@Composable
internal fun SettingsPull(
    onSettings: () -> Unit,
    top: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val (full, quick) = with(LocalDensity.current) { Open.toPx() to QuickFling.toPx() }
    var shown by remember { mutableFloatStateOf(0f) }
    val talkBack = touchExploration()
    LaunchedEffect(talkBack, enabled) { shown = if (talkBack && enabled) full else 0f }
    val pull = remember(full, quick, enabled) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f || shown <= 0f) return Offset.Zero
                val used = maxOf(available.y, -shown)
                shown += used
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!enabled || available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                val used = minOf(available.y, full - shown)
                shown += used
                return Offset(0f, used)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (shown <= 0f || shown >= full) return Velocity.Zero
                val target = when {
                    available.y > quick -> full
                    available.y < -quick -> 0f
                    shown > full / 2 -> full
                    else -> 0f
                }
                animate(shown, target, animationSpec = Motion.spatialFast()) { value, _ -> shown = value }
                return available
            }
        }
    }
    Box(modifier.nestedScroll(pull)) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = shown }, content = content)
        SettingsBand({ shown }, full, top, onSettings)
    }
}

/**
 * The band over what the list leaves at the top: from the screen's top down to [shown] under the
 * status bar. Once it is half open, all of it opens Settings.
 */
@Composable
private fun SettingsBand(shown: () -> Float, full: Float, top: Dp, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val open by remember(full) { derivedStateOf { shown() >= full / 2 } }
    Box(
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val height = top.roundToPx() + shown().roundToInt()
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            // At rest it would tint the status bar alone, so it tints in over the first steps of a pull.
            .drawBehind {
                drawRect(colors.surfaceContainerLow, alpha = (shown() / Space.Group.toPx()).coerceIn(0f, 1f))
            }
            .clickable(
                interactionSource = null,
                indication = rememberPress(null),
                enabled = open,
                role = Role.Button,
                onClick = onSettings,
            )
            .clipToBounds(),
        contentAlignment = Alignment.TopCenter,
    ) {
        GlyphAndWord(
            R.drawable.ic_settings,
            stringResource(R.string.settings),
            colors.onSurfaceVariant,
            Modifier
                // Where the words rest: the middle of the open band under the status bar.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Int.MAX_VALUE))
                    val y = top.roundToPx() + ((full - placeable.height) / 2).roundToInt()
                    layout(placeable.width, y + placeable.height) { placeable.place(0, y) }
                }
                .graphicsLayer {
                    val words = ((shown() / full - WORDS_FROM) / (1f - WORDS_FROM)).coerceIn(0f, 1f)
                    alpha = words
                    scaleX = WORDS_START_SCALE + (1f - WORDS_START_SCALE) * words
                    scaleY = scaleX
                },
        )
    }
}

/** The gear and the word, as a quiet action shows them; the band around them takes the tap. */
@Composable
private fun GlyphAndWord(@DrawableRes icon: Int, text: String, color: Color, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        InkGlyph(icon, tint = color, size = wordGlyph(style))
        Spacer(Modifier.width(Space.Part))
        Words(text, style = style, color = color, modifier = Modifier.inkSides(text, style))
    }
}

/** Whether a screen reader that explores by touch (TalkBack) is on, following changes. */
@Composable
internal fun touchExploration(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java) ?: return false
    var on by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/** How far the band opens: as much band under the status bar as the list's gap above its first section and 88 dp. */
private val Open = Space.Group + 88.dp

/** A release this fast opens or closes the band whichever way it goes, wherever it is. */
private val QuickFling = 400.dp

/** The words come in over the second half of the band's way down. */
private const val WORDS_FROM = 0.5f
private const val WORDS_START_SCALE = 0.94f
