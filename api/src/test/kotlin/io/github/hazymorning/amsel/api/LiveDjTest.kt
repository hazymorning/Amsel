package io.github.hazymorning.amsel.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Skipped unless MA_URL is set and the plugin is loaded, which takes an AI and a speech engine; CI's
 * stock server has neither, so CI does not run it.
 */
class LiveDjTest {
    private fun withDj(block: suspend (MaApi) -> Unit) = live { api, _ ->
        assumeTrue("AI Radio is not loaded", api.providers().any { it.domain == DJ_PLUGIN && it.isLoadedPlugin })
        block(api)
    }

    @Test fun `every host has an id and a name`() = withDj { api ->
        val hosts = api.djHosts()
        assertTrue(hosts.isNotEmpty())
        assertTrue(hosts.all { it.id.isNotBlank() && it.name.isNotBlank() })
    }

    @Test fun `a host goes on a speaker's queue and off again`() = withDj { api ->
        val kitchen = waitForSpeakers(api).first { it.shownName == "Kitchen" }.playerId
        val host = api.djHosts().first()
        try {
            assertEquals(QueueDj(host.id), api.setQueueDj(kitchen, host.id)[kitchen])
            assertEquals(QueueDj(host.id), api.queueDjs()[kitchen])
            assertNull(api.setQueueDj(kitchen, null)[kitchen])
            assertNull(api.queueDjs()[kitchen])
        } finally {
            api.setQueueDj(kitchen, null)
        }
    }
}
