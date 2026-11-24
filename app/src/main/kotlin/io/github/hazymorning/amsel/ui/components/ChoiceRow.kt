package io.github.hazymorning.amsel.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

@Composable
fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListRow(
        title = title,
        modifier = modifier,
        selected = selected,
        role = Role.RadioButton,
        titleLines = 2,
        onClick = onClick,
    )
}

@Composable
fun ActionRow(@DrawableRes icon: Int, label: String, onClick: () -> Unit, lead: RowLead = RowLead.Glyph) {
    ListRow(
        title = label,
        lead = lead,
        titleLines = 2,
        onClick = onClick,
        leading = { AlignedGlyph(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}
