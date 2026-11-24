package io.github.hazymorning.amsel.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The plugin's commands exist only while it is loaded ([providers]); otherwise they answer error 12. */
public const val DJ_PLUGIN: String = "ai_radio"

@Serializable
public data class DjHost(val id: String, val name: String = id)

/** [onAir]: a show put the host there (`station_id` set), not a user. */
public data class QueueDj(val hostId: String, val onAir: Boolean = false)

/** Needs `config.providers.read`, which a guest lacks (error 22). */
public suspend fun MaApi.djHosts(): List<DjHost> = list("ai_radio/hosts/list", DjHost.serializer())

public suspend fun MaApi.queueDjs(): Map<String, QueueDj> = client.call("ai_radio/queue_dj/status").queueDjs()

/**
 * Null switches the DJ off. Answers every queue's DJ after the change. A break already buffered is
 * still spoken after an off. See docs/API-NOTES.md, "DJ changes".
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
