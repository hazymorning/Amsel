package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.model.SpeakerUi
import io.github.hazymorning.amsel.ui.model.displayName
import io.github.hazymorning.amsel.ui.theme.Shapes
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.rememberPress

private val Disc = 40.dp

/**
 * The speaker's button on the dock: the speaker's glyph in ink, as play's, on a disc in the tone of
 * the places' band under it. A tap opens the speaker choice. TalkBack hears "Speaker: Kitchen.
 * Choose speaker", and "playing" while it plays.
 */
@Composable
internal fun SpeakerDisc(speaker: SpeakerUi, onSpeaker: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(
        if (speaker.isPlaying) R.string.speaker_button_playing else R.string.speaker_button_description,
        speaker.displayName(),
    )
    Box(
        Modifier
            .size(Sizes.TouchTarget)
            .clickable(null, rememberPress(Shapes.Circle), role = Role.Button, onClick = onSpeaker)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Disc)
                .clip(Shapes.Circle)
                .background(colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            InkGlyph(SpeakerButtonGlyph, tint = colors.onSurface)
        }
    }
}
