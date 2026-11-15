package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.DjHost
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.ChoiceRow
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.label
import io.github.hazymorning.amsel.ui.model.ActionsContext
import io.github.hazymorning.amsel.ui.theme.Space

/**
 * The DJ that talks between the songs on [speaker]: Off, then the server's [hosts] under the names
 * they have in Music Assistant, in its order. [current] is the host that talks, null when none does.
 */
@Composable
fun DjSheetContent(
    speaker: String,
    hosts: List<DjHost>,
    current: String?,
    onPick: (hostId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Space.Group)) {
        SheetHeader(stringResource(R.string.dj), name = speaker)
        Spacer(Modifier.height(Space.HeaderToContent))
        Column(Modifier.selectableGroup()) {
            ChoiceRow(stringResource(R.string.sleep_off), selected = current == null, onClick = { onPick(null) })
            hosts.forEach { host ->
                ChoiceRow(host.name, selected = host.id == current, onClick = { onPick(host.id) })
            }
        }
    }
}

/** The DJ of the speaker that plays, whose queue Up next shows; a pick applies and closes. */
@Composable
fun DjRoute(onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    val speaker by graph.playing.speaker.collectAsStateWithLifecycle()
    val offered by graph.data.extras.offered.collectAsStateWithLifecycle()
    val talking by graph.djs.hosts.collectAsStateWithLifecycle()
    DjSheetContent(
        speaker = speaker.label(),
        hosts = offered.djHosts,
        current = talking[speaker.id],
        onPick = { host ->
            graph.djs.set(speaker.id, host)
            onClose()
        },
    )
}

/**
 * Whether the server still has a DJ to choose, for the ⋮ sheet of [target]. Now Playing's reads
 * the hosts and each speaker's DJ again as it opens, so the DJ's sheet opens on what holds now:
 * 2.10 sends no event when either changes.
 */
@Composable
internal fun djOffered(target: ActionTarget): Boolean {
    val graph = LocalContext.current.graph
    LaunchedEffect(target) {
        val context = when (target) {
            is ActionTarget.Media -> target.context
            is ActionTarget.Track -> target.context
        }
        if (context == ActionsContext.NowPlaying) graph.djs.refresh()
    }
    val offered by graph.data.extras.offered.collectAsStateWithLifecycle()
    return offered.djHosts.isNotEmpty()
}
