package io.github.hazymorning.amsel.ui.screens.setup

import io.github.hazymorning.amsel.sample.SetupSample
import io.github.hazymorning.amsel.testing.ScreenshotTest
import io.github.hazymorning.amsel.testing.ShotVariant.LargeFont
import io.github.hazymorning.amsel.testing.ShotVariant.Light
import org.junit.Test

class SetupShots : ScreenshotTest() {
    @Test fun welcome() = snap("welcome") { WelcomeScreen(onFindServer = {}) }

    @Test fun serverList() = snap("server_list") {
        ServerScreen(
            SetupSample.list,
            onBack = {},
            onServerClick = {},
            onTypeAddress = {},
            onAddressChange = {},
            onConnect = {},
        )
    }

    @Test fun signIn() = snap("sign_in", listOf(Light, LargeFont)) {
        SignInScreen(
            SetupSample.signIn,
            onBack = {},
            onSignInWithHomeAssistant = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onPasswordVisibleChange = {},
            onSignIn = {},
        )
    }
}
