package io.github.hazymorning.amsel.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.Hints
import io.github.hazymorning.amsel.data.HINTS_ALL
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.launch

/** A kind of hint the person can turn off: when it comes, and what it suggests then. */
internal enum class HintKind(val key: String, @StringRes val situation: Int, @StringRes val suggests: Int) {
    Liked(Hints.HINT_LIKED, R.string.hints_liked, R.string.hints_liked_offer),
    Headphones(Hints.HINT_HEADPHONES, R.string.hints_headphones, R.string.hints_to_phone),
    Away(Hints.HINT_AWAY, R.string.hints_away, R.string.hints_to_phone),
    Home(Hints.HINT_HOME, R.string.hints_home, R.string.hints_home_offer),
    Silent(Hints.HINT_SILENT, R.string.hints_silent, R.string.hints_silent_offer),
    Ended(Hints.HINT_ENDED, R.string.hints_ended, R.string.hints_ended_offer),
    Sleep(Hints.HINT_SLEEP, R.string.hints_sleep, R.string.hints_sleep_offer),
}

/** How many kinds of hint are on, given those [off]: all, none, or a count. */
internal fun hintsOn(off: Set<String>): Int = if (HINTS_ALL in off) 0 else HintKind.entries.count { it.key !in off }

/**
 * Suggestions: one switch for all of them, then one for each moment Amsel may suggest something,
 * its words the moment ("Headphones connected") over what it suggests then ("Bring the music to the
 * phone"), so each reads as a plain when-then. Turned off, a moment never brings its bar again;
 * the switch for all keeps each one's choice for when it is on again.
 */
@Composable
fun HintsScreen(off: Set<String>, onChange: (Set<String>) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val all = HINTS_ALL !in off
    MaterialScreen(modifier, bar = { PageTopBar(stringResource(R.string.hints), onBack) }) { insets ->
        ScrollEdges(scroll)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets).padding(bottom = Space.ListEnd)) {
            val style = MaterialTheme.typography.bodyMedium
            Words(
                stringResource(R.string.hints_intro),
                style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Keyline.Margin, end = Keyline.Margin, top = Space.S2, bottom = Space.Group),
            )
            SettingsBlock {
                row { shape ->
                    ToggleRow(
                        stringResource(R.string.hints_all),
                        stringResource(R.string.hints_all_detail),
                        all,
                        shape,
                    ) { on -> onChange(if (on) off - HINTS_ALL else off + HINTS_ALL) }
                }
            }
            if (all) {
                SettingsGroup(stringResource(R.string.hints_when), above = Space.S8) {
                    HintKind.entries.forEach { kind ->
                        row { shape ->
                            ToggleRow(
                                stringResource(kind.situation),
                                stringResource(kind.suggests),
                                kind.key !in off,
                                shape,
                            ) { on -> onChange(if (on) off - kind.key else off + kind.key) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HintsRoute(navigator: Navigator) {
    val prefs = LocalContext.current.graph.data.prefs
    val stored by prefs.prefs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    HintsScreen(
        off = stored?.hintsOff.orEmpty(),
        onChange = { off -> scope.launch { prefs.setHintsOff(off) } },
        onBack = navigator::back,
    )
}
