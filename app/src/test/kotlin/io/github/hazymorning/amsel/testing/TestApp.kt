package io.github.hazymorning.amsel.testing

import io.github.hazymorning.amsel.AmselApp

/** The app as unit tests run it: nothing starts with it, each test builds what it uses. */
class TestApp : AmselApp() {
    override fun startGraph() = Unit
}
