package io.github.hazymorning.amsel.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * A sheet's question, asked in place of its list rather than in a dialog over it: the sheet's title
 * (Back returns to the list), the question, then the two answers stacked, [primary] above [secondary].
 */
@Composable
fun SheetQuestion(
    title: String,
    question: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Space.Group)) {
        SheetHeader(title)
        Spacer(Modifier.height(Space.Group))
        Column(Modifier.padding(horizontal = Keyline.Margin)) {
            val style = MaterialTheme.typography.bodyLarge
            Text(
                question,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(max = Sizes.Measure).textInk(style).inkStart(question, style),
            )
            Spacer(Modifier.height(Space.Group))
            PrimaryButton(primary, onClick = onPrimary, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.Sibling))
            SecondaryButton(secondary, onClick = onSecondary, modifier = Modifier.fillMaxWidth())
        }
    }
}
