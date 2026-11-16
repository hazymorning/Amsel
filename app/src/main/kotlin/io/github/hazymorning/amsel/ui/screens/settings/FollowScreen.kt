package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.theme.DISABLED_ALPHA
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.launch

/** A speaker following the music may pick. */
@Immutable
data class FollowSpeaker(val id: String, val name: String)

/**
 * Follow the music, a page of its own as Suggestions has: one switch for following, then, while it
 * is on, every speaker on a row of its own with its switch. Leaving all but one out follows that one
 * alone; the last one in stays, its switch dimmed and a tap answered with a no.
 */
@Composable
fun FollowScreen(
    on: Boolean,
    speakers: List<FollowSpeaker>,
    skipped: Set<String>,
    onFollowChange: (Boolean) -> Unit,
    onSkippedChange: (Set<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val haptics = LocalHapticFeedback.current
    val followed = speakers.count { it.id !in skipped }
    val stays = stringResource(R.string.follow_last_stays)
    MaterialScreen(modifier, bar = { PageTopBar(stringResource(R.string.settings_follow), onBack) }) { insets ->
        ScrollEdges(scroll)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets).padding(bottom = Space.ListEnd)) {
            PageHint(stringResource(R.string.follow_intro))
            SettingsBlock {
                row { shape ->
                    ToggleRow(
                        stringResource(R.string.settings_follow),
                        stringResource(R.string.settings_follow_detail),
                        on,
                        shape,
                        onFollowChange,
                    )
                }
            }
            if (on && speakers.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.follow_speakers), above = Space.S8) {
                    speakers.forEach { speaker ->
                        val followsIt = speaker.id !in skipped
                        val last = followsIt && followed == 1
                        row { shape ->
                            SettingRow(
                                speaker.name,
                                shape,
                                modifier = Modifier.semantics {
                                    toggleableState = ToggleableState(followsIt)
                                    if (last) stateDescription = stays
                                },
                                role = Role.Switch,
                                onClick = {
                                    when {
                                        !followsIt -> onSkippedChange(skipped - speaker.id)
                                        last -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
                                        else -> onSkippedChange(skipped + speaker.id)
                                    }
                                },
                                trailing = {
                                    Box(Modifier.alpha(if (last) DISABLED_ALPHA else 1f)) { SettingSwitch(followsIt) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What Settings says under Follow the music: off, every speaker, or those it follows by name. */
@Composable
internal fun followLine(on: Boolean, speakers: List<FollowSpeaker>, skipped: Set<String>): String {
    val followed = speakers.filter { it.id !in skipped }
    return when {
        !on -> stringResource(R.string.follow_off)
        speakers.isEmpty() -> stringResource(R.string.follow_on)
        followed.size == speakers.size -> stringResource(R.string.follow_all)
        else -> followed.joinToString(", ") { it.name }
    }
}

/** Every speaker, in a group or not, by name: following can pick it whenever it plays, or plays along. */
@Composable
internal fun rememberFollowSpeakers(): List<FollowSpeaker> {
    val graph = LocalContext.current.graph
    val players by graph.data.speakers.players.collectAsStateWithLifecycle()
    val phoneId by graph.phone.playerId.collectAsStateWithLifecycle()
    return remember(players, phoneId) {
        players.values
            .filter { it.playerId != phoneId && it.copy(syncedTo = null, activeGroup = null).isShownSpeaker(phoneId) }
            .sortedBy { it.shownName.lowercase() }
            .map { FollowSpeaker(it.playerId, it.shownName) }
    }
}

@Composable
fun FollowRoute(navigator: Navigator) {
    val prefs = LocalContext.current.graph.data.prefs
    val stored by prefs.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    FollowScreen(
        on = stored?.followPlaying ?: false,
        speakers = rememberFollowSpeakers(),
        skipped = stored?.followSkipped.orEmpty(),
        onFollowChange = { on -> scope.launch { prefs.setFollowPlaying(on) } },
        onSkippedChange = { skipped -> scope.launch { prefs.setFollowSkipped(skipped) } },
        onBack = navigator::back,
    )
}
