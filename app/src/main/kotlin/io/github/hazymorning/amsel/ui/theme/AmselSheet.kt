package io.github.hazymorning.amsel.ui.theme

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// Darker at night: the page is near black there, and only real black still sets the sheet apart.
private const val SHEET_DIM_DAY = 0.32f
private const val SHEET_DIM_NIGHT = 0.6f

interface SheetScope : ColumnScope {
    /** Hides the sheet, then runs the sheet's onDismiss, then [then]. */
    fun close(then: () -> Unit = {})
}

/**
 * The handle is a [Space.SheetEdge] spacer, so content starts at its own top and ends with [sheetEnd]
 * padding, framing the sheet evenly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmselSheet(onDismiss: () -> Unit, content: @Composable SheetScope.() -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss by rememberUpdatedState(onDismiss)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = Shapes.Sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(
            alpha = if (MaterialTheme.colorScheme.isNight) SHEET_DIM_NIGHT else SHEET_DIM_DAY,
        ),
        dragHandle = { Spacer(Modifier.height(Space.SheetEdge)) },
    ) {
        val sheet = remember(this) {
            object : SheetScope, ColumnScope by this {
                private var closing = false

                override fun close(then: () -> Unit) {
                    if (closing) return
                    closing = true
                    scope.launch {
                        state.hide()
                        dismiss()
                        then()
                    }
                }
            }
        }
        sheet.content()
    }
}
