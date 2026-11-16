package io.github.hazymorning.amsel.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import io.github.hazymorning.amsel.ui.theme.Space

/** The sleep timer's ways, set once: how it ends, a shake to start it over, and starting with playback. */
@Composable
fun SleepTimerScreen(
    state: SettingsUiState,
    onFadeChange: (Int) -> Unit,
    onShakeChange: (Boolean) -> Unit,
    onSleepOnPlayChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var choosing by rememberSaveable { mutableStateOf(false) }
    MaterialScreen(modifier, bar = { PageTopBar(stringResource(R.string.settings_sleep), onBack) }) { insets ->
        ScrollEdges(scroll)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets).padding(bottom = Space.ListEnd)) {
            PageHint(stringResource(R.string.sleep_intro))
            SettingsBlock {
                row { shape ->
                    ValueRow(stringResource(R.string.settings_sleep_fade), fadeLabel(state.sleepFadeSec), shape) {
                        choosing = true
                    }
                }
                row { shape ->
                    ToggleRow(
                        stringResource(R.string.settings_sleep_shake),
                        stringResource(R.string.settings_sleep_shake_detail),
                        state.sleepShake,
                        shape,
                        onShakeChange,
                    )
                }
                row { shape ->
                    ToggleRow(
                        stringResource(R.string.settings_sleep_on_play),
                        stringResource(R.string.settings_sleep_on_play_detail),
                        state.sleepOnPlay,
                        shape,
                        onSleepOnPlayChange,
                    )
                }
            }
        }
    }
    if (choosing) {
        // A choice applies at once, under the sheet as it slides away.
        AmselSheet(onDismiss = { choosing = false }) {
            ChoiceSheet(
                stringResource(R.string.settings_sleep_fade),
                FADES,
                state.sleepFadeSec,
                label = { fadeLabel(it) },
                onPick = {
                    onFadeChange(it)
                    close()
                },
            )
        }
    }
}

@Composable
private fun fadeLabel(seconds: Int): String =
    if (seconds == 0) stringResource(R.string.sleep_off) else stringResource(R.string.settings_sleep_seconds, seconds)

private val FADES = listOf(0, 10, 15, 30, 60)

@Composable
fun SleepTimerRoute(navigator: Navigator) {
    val viewModel = rememberSettingsViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    SleepTimerScreen(
        state = state,
        onFadeChange = viewModel::onFadeChange,
        onShakeChange = viewModel::onShakeChange,
        onSleepOnPlayChange = viewModel::onSleepOnPlayChange,
        onBack = navigator::back,
    )
}
