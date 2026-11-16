package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.playback.R as PlaybackR
import io.github.hazymorning.amsel.ui.navigation.Arrangement
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.screens.library.LibraryKind
import io.github.hazymorning.amsel.ui.screens.library.icon
import io.github.hazymorning.amsel.ui.screens.library.isHeld
import io.github.hazymorning.amsel.ui.screens.library.label
import kotlinx.coroutines.launch

/**
 * What the Library shows as chips, and in what order. One always stays; one the library has none
 * of says it shows once there is some.
 */
@Composable
fun LibraryKindsScreen(
    kinds: Arrangement<LibraryKind>,
    /** The kinds the Library has anything of now (LibraryKind.isHeld). */
    held: Set<LibraryKind>,
    onChange: (Arrangement<LibraryKind>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val waits = stringResource(R.string.library_kind_waits)
    ArrangeScreen(
        title = stringResource(PlaybackR.string.library),
        hint = stringResource(R.string.library_kinds_hint),
        arrangement = kinds,
        key = LibraryKind::id,
        label = { stringResource(it.label) },
        icon = { it.icon },
        fixed = null,
        fixedLine = stringResource(R.string.library_kind_last_stays),
        onChange = onChange,
        onBack = onBack,
        modifier = modifier,
        note = { if (it in held) null else waits },
    )
}

@Composable
fun LibraryKindsRoute(navigator: Navigator) {
    val graph = LocalContext.current.graph
    val prefs = graph.data.prefs
    val stored by prefs.prefs.collectAsStateWithLifecycle()
    val types by graph.data.library.heldTypes.collectAsStateWithLifecycle(emptySet())
    val downloads by graph.downloading.rows.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    LibraryKindsScreen(
        kinds = LibraryKind.arrangement(stored?.libraryKinds),
        held = LibraryKind.entries.filter { it.isHeld(types, downloads.isNotEmpty()) }.toSet(),
        onChange = { kinds -> scope.launch { prefs.setLibraryKinds(kinds.tokens(LibraryKind::id)) } },
        onBack = navigator::back,
    )
}
