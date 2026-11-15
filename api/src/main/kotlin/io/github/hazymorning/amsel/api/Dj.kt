package io.github.hazymorning.amsel.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The plugin whose DJ speaks between songs. Its commands exist only while it is loaded ([providers]). */
public const val DJ_PLUGIN: String = "ai_radio"

/** One of the plugin's hosts, as the admin named it in Music Assistant. It has nothing else to show. */
@Serializable
public data class DjHost(val id: String, val name: String = id)

/** The host that speaks on a queue, and whether a show put it there ([onAir]) rather than a person. */
public data class QueueDj(val hostId: String, val onAir: Boolean = false)

/** Needs `config.providers.read`, which a guest lacks: for a guest this answers error 22. */
public suspend fun MaApi.djHosts(): List<DjHost> = list("ai_radio/hosts/list", DjHost.serializer())

/** The DJ of every queue the user may use, by queue id; a queue without one is left out. */
public suspend fun MaApi.queueDjs(): Map<String, QueueDj> = client.call("ai_radio/queue_dj/status").queueDjs()

/**
 * Sets [queueId]'s DJ to [hostId], or switches it off with null, which is always sent: left out,
 * the server reads it as off. The answer is every queue's DJ after the change. A break the speaker
 * has already buffered is still spoken after an off.
 */
public suspend fun MaApi.setQueueDj(queueId: String, hostId: String?): Map<String, QueueDj> = client.call(
    "ai_radio/queue_dj/set",
    buildJsonObject {
        put("queue_id", queueId)
        put("host_id", hostId)
    },
).queueDjs()

/** 2.11 answers `{queue: {host_id, station_id}}`, 2.10 `{queue: "host_id"}`. */
internal fun JsonElement?.queueDjs(): Map<String, QueueDj> = (this as? JsonObject).orEmpty().mapNotNull { (queue, dj) ->
    val host = when (dj) {
        is JsonObject -> (dj["host_id"] as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> dj.contentOrNull
        else -> null
    }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val station = ((dj as? JsonObject)?.get("station_id") as? JsonPrimitive)?.contentOrNull
    queue to QueueDj(host, onAir = !station.isNullOrEmpty())
}.toMap()
