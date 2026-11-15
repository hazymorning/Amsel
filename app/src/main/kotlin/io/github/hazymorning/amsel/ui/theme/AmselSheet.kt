package io.github.hazymorning.amsel.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.hazymorning.amsel.R
import kotlinx.coroutines.launch

private val HandleWidth = 32.dp

/** What a sheet leaves of the screen behind it: by day the linen only greys a little; at night it sinks. */
private const val SHEET_DIM_DAY = 0.16f
private const val SHEET_DIM_NIGHT = 0.45f

/** What a sheet's content can do besides lay itself out. */
interface SheetScope : ColumnScope {
    /** Slides the sheet away; once it is gone, the sheet's onDismiss and then [then] run. */
    fun close(then: () -> Unit = {})
}

/**
 * Content starts 24 dp under the handle and puts its first ink at its own top. ModalBottomSheet is
 * experimental in Material 3; opt-ins live only in this package.
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
        scrimColor = MaterialTheme.colorScheme.run {
            scrim.copy(alpha = if (isNight) SHEET_DIM_NIGHT else SHEET_DIM_DAY)
        },
        dragHandle = { SheetHandle() },
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

/** TalkBack names it by what its action does: the sheet's handle closes the sheet. */
@Composable
private fun SheetHandle() {
    val close = stringResource(R.string.close)
    Box(Modifier.padding(top = Space.Sibling, bottom = Space.Group).semantics { contentDescription = close }) {
        Box(
            Modifier
                .size(HandleWidth, Space.S1)
                .clip(Shapes.Pill)
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}
