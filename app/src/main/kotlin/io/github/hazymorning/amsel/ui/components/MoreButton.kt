package io.github.hazymorning.amsel.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.theme.controls

@Composable
fun MoreButton(itemTitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconAction(
        R.drawable.ic_more,
        contentDescription = stringResource(R.string.more_options_for, itemTitle),
        onClick = onClick,
        modifier = modifier,
        edge = InkEdge.End,
        tint = MaterialTheme.colorScheme.controls,
    )
}
