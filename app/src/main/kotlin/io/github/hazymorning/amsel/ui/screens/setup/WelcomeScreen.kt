package io.github.hazymorning.amsel.ui.screens.setup

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.capToBaseline
import io.github.hazymorning.amsel.ui.components.trimStartBearing
import io.github.hazymorning.amsel.ui.theme.Keyline

@Composable
fun WelcomeScreen(onFindServer: () -> Unit, modifier: Modifier = Modifier) {
    SetupPage(
        modifier,
        action = { SetupButton(stringResource(R.string.setup_find_server), onFindServer) },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sentence = stringResource(R.string.setup_welcome)
            val style = MaterialTheme.typography.displaySmall
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = Keyline.Margin),
            ) {
                Spacer(Modifier.height(Keyline.Margin))
                Spacer(Modifier.weight(ABOVE))
                Text(
                    sentence,
                    style = style,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .capToBaseline(style)
                        .trimStartBearing(sentence, style)
                        .semantics { heading() },
                )
                Spacer(Modifier.weight(BELOW))
            }
        }
    }
}

/** Spacer weights: the sentence sits a little above the middle of the space over the button. */
private const val ABOVE = 0.42f
private const val BELOW = 0.58f
