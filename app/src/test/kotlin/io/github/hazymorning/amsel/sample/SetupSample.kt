package io.github.hazymorning.amsel.sample

import io.github.hazymorning.amsel.ui.screens.setup.FoundServerUi
import io.github.hazymorning.amsel.ui.screens.setup.ServerStep
import io.github.hazymorning.amsel.ui.screens.setup.ServerUiState
import io.github.hazymorning.amsel.ui.screens.setup.SignInUiState

object SetupSample {
    val servers = listOf(
        FoundServerUi("f13566951f1b40ccb6e676dbca8161c8", "Music Assistant", "192.168.1.20"),
        FoundServerUi("9a0c2e7d41b84f6f8e1d3c5b7a9f0e21", "Music Assistant (Studio)", "192.168.1.42"),
    )

    val list = ServerUiState(step = ServerStep.Choose, servers = servers)

    val signIn = SignInUiState(serverName = "Music Assistant", serverAddress = "192.168.1.20")
}
