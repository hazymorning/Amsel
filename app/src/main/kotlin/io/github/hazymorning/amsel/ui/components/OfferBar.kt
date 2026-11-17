package io.github.hazymorning.amsel.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.Offer
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.glyph
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.LocalReducedMotion
import io.github.hazymorning.amsel.ui.theme.Motion
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import io.github.hazymorning.amsel.ui.theme.rememberPress
import kotlinx.coroutines.delay

/** How long an offer waits for its button before it goes. */
private const val OFFER_MS = 8_000L

/**
 * The [offer] over the dock: it rises into place, and goes after a while, with a swipe aside, or
 * once taken. [speaker] is the one music plays on, named where the offer has no line of its own;
 * a tap on it opens the speakers ([onSpeaker]).
 */
@Composable
fun OfferHost(
    offer: Offer?,
    speaker: SpeakerUi,
    onTake: (Offer) -> Unit,
    onDismiss: (Offer) -> Unit,
    onSpeaker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accessibility = LocalAccessibilityManager.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(offer) {
        val shown = offer ?: return@LaunchedEffect
        delay(
            accessibility?.calculateRecommendedTimeoutMillis(OFFER_MS, containsText = true, containsControls = true)
                ?: OFFER_MS,
        )
        onDismiss(shown)
    }
    // The last offer stays drawn while it goes.
    var last by remember { mutableStateOf(offer) }
    if (offer != null) last = offer
    val still = LocalReducedMotion.current
    AnimatedVisibility(
        visible = offer != null,
        modifier = modifier,
        enter = if (still) {
            fadeIn(Motion.effects())
        } else {
            slideInVertically(Motion.spatialFast()) { it / 2 } + fadeIn(Motion.effects())
        },
        exit = if (still) {
            fadeOut(Motion.effectsFast())
        } else {
            slideOutVertically(Motion.spatialFast()) { it / 2 } + fadeOut(Motion.effectsFast())
        },
        label = "offer",
    ) {
        Crossfade(last, animationSpec = Motion.effects(), label = "offer content") { shown ->
            if (shown == null) return@Crossfade
            OfferBar(
                title = shown.title,
                button = shown.buttonArg?.let { stringResource(shown.button, it) } ?: stringResource(shown.button),
                onTake = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onTake(shown)
                },
                modifier = Modifier
                    .swipeAway { onDismiss(shown) }
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        dismiss {
                            onDismiss(shown)
                            true
                        }
                    },
                cover = {
                    Artwork(
                        shown.art,
                        Modifier.size(Sizes.DockArt),
                        shown.kind,
                        name = shown.title.takeIf { shown.glyph == null },
                        glyph = shown.glyph,
                    )
                },
                line = shown.line?.let { line -> stringResource(line, shown.lineArg.orEmpty()) },
                speaker = speaker.takeIf { shown.line == null },
                onSpeaker = onSpeaker,
            )
        }
    }
}

/**
 * An offer: the cover of what it is about, its title, and under it the speaker it plays on or a
 * short [line]; one big [button] at the end. A bar on the margins in the second surface tone, as a
 * message is, set apart by tone rather than a line or a shadow.
 */
@Composable
fun OfferBar(
    title: String,
    button: String,
    onTake: () -> Unit,
    modifier: Modifier = Modifier,
    cover: (@Composable () -> Unit)? = null,
    line: String? = null,
    speaker: SpeakerUi? = null,
    onSpeaker: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val pick = stringResource(R.string.offer_pick_speaker)
    Surface(
        modifier
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = Keyline.Margin)
            .fillMaxWidth(),
        shape = Shapes.Tile,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
    ) {
        Row(Modifier.padding(OfferInset), verticalAlignment = Alignment.CenterVertically) {
            if (cover != null) {
                cover()
                Spacer(Modifier.width(Space.S3))
            }
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = Sizes.TouchTarget)
                    .then(
                        if (speaker != null) {
                            Modifier.clickable(
                                null,
                                rememberPress(shape = null),
                                role = Role.Button,
                                onClickLabel = pick,
                                onClick = onSpeaker,
                            )
                        } else {
                            Modifier
                        },
                    ),
                verticalArrangement = Arrangement.Center,
            ) {
                Words(
                    title,
                    type.titleSmall,
                    color = colors.onSurface,
                    modifier = Modifier.inkStart(title, type.titleSmall),
                )
                if (speaker != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InkGlyph(speaker.glyph, tint = colors.onSurfaceVariant, size = Sizes.IconSmall)
                        Spacer(Modifier.width(Space.S1))
                        SpeakerName(speaker, type.bodyMedium, colors.onSurfaceVariant)
                    }
                } else if (line != null) {
                    Words(
                        line,
                        type.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.inkStart(line, type.bodyMedium),
                    )
                }
            }
            Spacer(Modifier.width(Space.S3))
            PrimaryButton(button, onTake)
        }
    }
}

/** The bar's content stands this far in: its cover as far from the edge as a row's from the margin's. */
private val OfferInset = Space.S3
