package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaClient
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.QueueDj
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrasTest {
    private val versions = listOf("2.10", "2.11")
    private var loaded = true

    /** A server that lists the plugin, [loaded] or not, beside what was recorded; [answers] go first. */
    private fun withPlugin(
        version: String,
        answers: Map<String, JsonObject> = emptyMap(),
        block: suspend (Extras, FakeMaServer) -> Unit,
    ) = withRecordedServer(
        version,
        { command, _ -> (answers[command] ?: providers(version).takeIf { command == "providers" })?.let(::listOf) },
    ) { api, server -> block(Extras(api), server) }

    private fun providers(version: String): JsonObject {
        val plugin = buildJsonObject {
            put("type", "plugin")
            put("domain", "ai_radio")
            put("available", loaded)
        }
        val recorded = FakeMaServer.recorded(version, "providers").getValue("result").jsonArray
        return FakeMaServer.result(JsonArray(recorded + plugin))
    }

    private fun FakeMaServer.asked(): List<String> =
        received.map { it.getValue("command").jsonPrimitive.content }.filter { it.startsWith("ai_radio/") }

    @Test fun `a server without the plugin is asked nothing about it and has no hosts`() = versions.forEach { v ->
        withRecordedServer(v) { api, server ->
            val dj = Extras(api)
            dj.load()
            assertTrue(dj.offered.value.djHosts.isEmpty())
            assertTrue(dj.djs.value.isEmpty())
            assertTrue(server.asked().isEmpty())
        }
    }

    @Test fun `a plugin that is listed but not loaded is not asked either`() {
        loaded = false
        withPlugin("2.10") { dj, server ->
            dj.load()
            assertTrue(dj.offered.value.djHosts.isEmpty())
            assertTrue(server.asked().isEmpty())
        }
    }

    @Test fun `with the plugin the hosts and each speaker's DJ are read`() = versions.forEach { v ->
        withPlugin(v) { dj, _ ->
            dj.load()
            val hosts = dj.offered.value.djHosts.joinToString { it.name }
            assertEquals("Minimal DJ, Morning show, Music nerd, Party host", hosts)
            assertEquals(listOf(QueueDj("music_nerd")), dj.djs.value.values.toList())
        }
    }

    @Test fun `a guest, who may not list the hosts, has no DJ to choose`() {
        val refused = mapOf("ai_radio/hosts/list" to FakeMaServer.error(22, "Insufficient permissions."))
        withPlugin("2.11", refused) { dj, server ->
            dj.load()
            assertTrue(dj.offered.value.djHosts.isEmpty())
            assertTrue(dj.djs.value.isEmpty())
            assertEquals(listOf("ai_radio/hosts/list"), server.asked())
        }
    }

    @Test fun `a pick is sent and the server's answer is the new state`() = versions.forEach { v ->
        withPlugin(v) { dj, server ->
            dj.load()
            val queue = dj.djs.value.keys.single()
            dj.setDj(queue, "music_nerd")
            assertEquals("music_nerd", server.args("ai_radio/queue_dj/set").getValue("host_id").jsonPrimitive.content)
            assertEquals(QueueDj("music_nerd"), dj.djs.value[queue])
        }
    }

    @Test fun `off is sent as null, never left out`() = versions.forEach { v ->
        val off = mapOf("ai_radio/queue_dj/set" to FakeMaServer.recorded(v, "ai_radio_queue_dj_set__off"))
        withPlugin(v, off) { dj, server ->
            dj.load()
            dj.setDj(dj.djs.value.keys.single(), null)
            assertEquals(JsonNull, server.args("ai_radio/queue_dj/set").getValue("host_id"))
            assertTrue(dj.djs.value.isEmpty())
        }
    }

    @Test fun `the plugin going away takes its hosts along`() = withPlugin("2.11") { dj, _ ->
        dj.load()
        assertFalse(dj.offered.value.djHosts.isEmpty())
        loaded = false
        dj.load()
        assertTrue(dj.offered.value.djHosts.isEmpty())
        assertTrue(dj.djs.value.isEmpty())
    }

    @Test fun `hosts and speakers are read again before they are shown`() {
        var hosts = FakeMaServer.recorded("2.10", "ai_radio_hosts_list")
        withRecordedServer(
            "2.10",
            { command, _ ->
                when (command) {
                    "providers" -> listOf(providers("2.10"))
                    "ai_radio/hosts/list" -> listOf(hosts)
                    else -> null
                }
            },
        ) { api, server ->
            val dj = Extras(api)
            dj.load()
            hosts = FakeMaServer.result(JsonArray(hosts.getValue("result").jsonArray.take(1)))
            dj.refreshDj()
            assertEquals(listOf("Minimal DJ"), dj.offered.value.djHosts.map { it.name })
            assertEquals(2, server.asked().count { it == "ai_radio/queue_dj/status" })
        }
    }

    @Test fun `a mix and similar songs are offered where a source finds similar songs`() = versions.forEach { v ->
        withRecordedServer(v) { api, _ ->
            val extras = Extras(api)
            assertEquals(ServerExtras(), extras.offered.value)
            extras.load()
            assertEquals(ServerExtras(mix = true, similarSongs = true, similarArtists = true), extras.offered.value)
        }
    }

    /** What [load] makes of a server that has everything recorded but [domain]. */
    private fun offeredWithout(domain: String): ServerExtras {
        val all = FakeMaServer.recorded("2.11", "providers").getValue("result").jsonArray
        val rest = all.filter { it.jsonObject.getValue("domain").jsonPrimitive.content != domain }
        var offered = ServerExtras()
        withRecordedServer(
            "2.11",
            { command, _ -> listOf(FakeMaServer.result(JsonArray(rest))).takeIf { command == "providers" } },
        ) { api, _ ->
            offered = Extras(api).apply { load() }.offered.value
        }
        return offered
    }

    @Test fun `without such a source, or without the mix plugin, there is no mix`() {
        assertEquals(ServerExtras(), offeredWithout("lastfm_recommendations"))
        assertEquals(ServerExtras(similarSongs = true, similarArtists = true), offeredWithout("radio_playlist"))
    }

    @Test fun `signing out forgets what the last server offered`() = withPlugin("2.11") { extras, _ ->
        extras.load()
        extras.clear()
        assertEquals(ServerExtras(), extras.offered.value)
        assertTrue(extras.djs.value.isEmpty())
    }

    @Test fun `a change of the server's plugins and the plugin's own events ask for a reload`() {
        val dj = Extras(MaApi(MaClient(OkHttpClient(), CoroutineScope(Job()))))
        assertTrue(dj.reloadsOn(MaEvent("providers_updated", null, null)))
        assertTrue(dj.reloadsOn(MaEvent("provider_event", "ai_radio", null)))
        assertFalse(dj.reloadsOn(MaEvent("provider_event", "spotify", null)))
        assertFalse(dj.reloadsOn(MaEvent("queue_updated", "ai_radio", null)))
    }
}
