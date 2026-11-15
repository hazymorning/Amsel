package io.github.hazymorning.amsel.ui.screens.setup

import io.github.hazymorning.amsel.api.HA_RETURN_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeAssistantCodeTest {
    @Test fun `the return URL hands over its code`() {
        assertEquals("abc123", homeAssistantCode("$HA_RETURN_URL?code=abc123"))
    }

    @Test fun `a return without a code is a cancel`() {
        assertNull(homeAssistantCode(HA_RETURN_URL))
    }

    @Test fun `any other page is left to load`() {
        assertNull(homeAssistantCode("http://homeassistant.local:8123/auth/authorize?code=abc123"))
    }
}
