package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.QueueDj
import io.github.hazymorning.amsel.api.withFakeApi
import io.github.hazymorning.amsel.data.ExtrasRepository
import io.github.hazymorning.amsel.playback.R as PlaybackR
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueDjControllerTest {
    private var loaded = true
    private var status = FakeMaServer.recorded(V, "ai_radio_queue_dj_status")
    private var set: () -> JsonObject = { FakeMaServer.recorded(V, "ai_radio_queue_dj_set") }

    private class Fixture(
        val djs: QueueDjController,
        val extras: ExtrasRepository,
        val messages: MessageController,
        val server: FakeMaServer,
    ) {
        suspend fun said(): Message =
            withTimeout(WAIT_MS) { messages.pending.first { it.isNotEmpty() } }.first().also(messages::seen)

        suspend fun talking(host: String?) = withTimeout(WAIT_MS) { djs.hosts.first { it[KITCHEN] == host } }

        fun asked(command: String): Int = server.received.count { it["command"]?.jsonPrimitive?.content == command }
    }

    private fun withDj(block: suspend Fixture.() -> Unit) =
        withFakeApi(V, { command, _ -> listOf(answer(command)) }) { api, server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val extras = ExtrasRepository(api).apply { load() }
                val messages = MessageController(MutableStateFlow(true))
                Fixture(QueueDjController(extras, messages, scope) { "Kitchen" }, extras, messages, server).block()
            } finally {
                scope.cancel()
            }
        }

    private fun answer(command: String): JsonObject = when (command) {
        "providers" -> providers()
        "ai_radio/hosts/list" -> FakeMaServer.recorded(V, "ai_radio_hosts_list")
        "ai_radio/queue_dj/status" -> status
        "ai_radio/queue_dj/set" -> set()
        else -> FakeMaServer.result(null)
    }

    private fun providers(): JsonObject {
        val plugin = buildJsonObject {
            put("type", "plugin")
            put("domain", "ai_radio")
            put("available", true)
        }
        val recorded = FakeMaServer.recorded(V, "providers").getValue("result").jsonArray
        return FakeMaServer.result(JsonArray(if (loaded) recorded + plugin else recorded))
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun kitchenHas(host: String) = FakeMaServer.result(
        buildJsonObject {
            put(KITCHEN, buildJsonObject { put("host_id", host) })
        },
    )

    @Test fun `a pick shows at once, and the server's answer takes its place with one message`() {
        status = FakeMaServer.result(JsonObject(emptyMap()))
        val answered = CountDownLatch(1)
        set = {
            answered.await(WAIT_MS, TimeUnit.MILLISECONDS)
            FakeMaServer.recorded(V, "ai_radio_queue_dj_set")
        }
        withDj {
            djs.set(KITCHEN, "music_nerd")
            talking("music_nerd")
            assertTrue(extras.djs.value.isEmpty())
            assertTrue(messages.pending.value.isEmpty())
            answered.countDown()
            val said = said()
            assertEquals(R.string.dj_on, said.text)
            assertNull(said.action)
            assertEquals(QueueDj("music_nerd"), extras.djs.value[KITCHEN])
            assertEquals("music_nerd", djs.hosts.value[KITCHEN])
            val sent = server.args(SET)
            assertEquals(KITCHEN to "music_nerd", sent.text("queue_id") to sent.text("host_id"))
        }
    }

    @Test fun `off is said too, and a pick of what already talks asks nothing`() {
        set = { FakeMaServer.recorded(V, "ai_radio_queue_dj_set__off") }
        withDj {
            talking("music_nerd")
            djs.set(KITCHEN, "music_nerd")
            djs.set(KITCHEN, null)
            assertEquals(R.string.dj_off, said().text)
            talking(null)
            assertEquals(1, asked(SET))
            assertEquals(JsonNull, server.args(SET).getValue("host_id"))
            assertTrue(messages.pending.value.isEmpty())
        }
    }

    @Test fun `a failure takes the pick back and offers Retry, which asks again`() {
        set = { FakeMaServer.error(0, "The engine did not answer.") }
        withDj {
            djs.set(KITCHEN, "party_host")
            val failed = said()
            assertNotNull(failed.retry)
            assertEquals("Kitchen", failed.arg)
            talking("music_nerd")
            assertEquals(1, asked("providers"))

            set = { kitchenHas("party_host") }
            failed.retry?.invoke()
            assertEquals(R.string.dj_on, said().text)
            talking("party_host")
            assertEquals(2, asked(SET))
        }
    }

    @Test fun `what asking again cannot mend has no Retry, and what the server offers is read again`() =
        listOf(3, 12, 22).forEach { code ->
            loaded = true
            set = { FakeMaServer.error(code, "Refused.") }
            withDj {
                assertFalse(extras.offered.value.djHosts.isEmpty())
                // The plugin was taken out of Music Assistant after the sheet opened.
                loaded = false
                djs.set(KITCHEN, "party_host")
                val said = said()
                assertNull("$code", said.action)
                assertEquals(if (code == 22) PlaybackR.string.error_not_allowed else R.string.dj_gone, said.text)
                withTimeout(WAIT_MS) { extras.offered.first { it.djHosts.isEmpty() } }
                talking(null)
                assertEquals(2, asked("providers"))
                assertEquals(1, asked(SET))
            }
        }

    @Test fun `the menu reads the hosts and each speaker's DJ again`() {
        status = FakeMaServer.result(JsonObject(emptyMap()))
        withDj {
            talking(null)
            status = FakeMaServer.recorded(V, "ai_radio_queue_dj_status")
            djs.refresh()
            talking("music_nerd")
            assertEquals(2, asked("ai_radio/hosts/list"))
        }
    }

    private companion object {
        const val V = "2.11"
        const val KITCHEN = "upe7049459"
        const val SET = "ai_radio/queue_dj/set"
        const val WAIT_MS = 5_000L
    }
}
