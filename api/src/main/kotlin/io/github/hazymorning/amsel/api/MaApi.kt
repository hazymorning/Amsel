package io.github.hazymorning.amsel.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

public class MaApi(internal val client: MaClient) {
    private val send: Command = { command, args -> client.call(command, args) }

    /** A long-lived token named [name] for the signed-in person; null when none comes back. */
    public suspend fun createToken(name: String): String? = createToken(send, name)

    /** Retires [token] on the server, which closes every socket signed in with it. */
    public suspend fun revokeToken(token: String): Unit = revokeToken(send, token)

    /** Ends this connection's sign-in on the server: at most [timeout] for the answer, once connected. */
    public suspend fun logout(timeout: Duration) {
        client.call("auth/logout", timeout = timeout)
    }

    /** The connected server's schema, waiting for the connection as every command does. */
    internal suspend fun schema(): Int = client.server().schemaVersion

    // Transport goes through players/cmd/*, which also covers external sources.

    public suspend fun players(): List<Player> = list("players/all", Player.serializer())

    public suspend fun play(playerId: String): Unit = playerCmd("play", playerId, PLAY_TIMEOUT)

    public suspend fun pause(playerId: String): Unit = playerCmd("pause", playerId)

    public suspend fun stop(playerId: String): Unit = playerCmd("stop", playerId, PLAY_TIMEOUT)

    public suspend fun next(playerId: String): Unit = playerCmd("next", playerId, PLAY_TIMEOUT)

    public suspend fun previous(playerId: String): Unit = playerCmd("previous", playerId, PLAY_TIMEOUT)

    public suspend fun seek(playerId: String, positionSec: Int): Unit = playerCmd("seek", playerId, PLAY_TIMEOUT) {
        put("position", positionSec)
    }

    /**
     * The volume of the speaker as it plays: a group or a sync leader moves its members together,
     * keeping their balance; a speaker on its own takes it as `volume_set` (2.10 and 2.11 alike).
     */
    public suspend fun setVolume(playerId: String, level: Int): Unit =
        playerCmd("group_volume", playerId) { put("volume_level", level.coerceIn(0, 100)) }

    /** One speaker's own volume, also while it plays in a group (`volume_set`). */
    public suspend fun setOwnVolume(playerId: String, level: Int): Unit =
        playerCmd("volume_set", playerId) { put("volume_level", level.coerceIn(0, 100)) }

    /**
     * The server's own sleep timer (2.11 on): it stops the speaker [seconds] from now, whether or not
     * a phone is there to watch. Returns when, in Unix seconds.
     */
    public suspend fun setSleepTimer(playerId: String, seconds: Int): Double? {
        val answer = client.call(
            "players/sleep_timer/set",
            buildJsonObject {
                put("player_id", playerId)
                put("seconds", seconds)
            },
        )
        return (answer as? JsonPrimitive)?.content?.toDoubleOrNull()
    }

    public suspend fun clearSleepTimer(playerId: String) {
        client.call("players/sleep_timer/clear", buildJsonObject { put("player_id", playerId) })
    }

    /**
     * Adds speakers to [leaderId]'s group and takes others out; the leader keeps playing. A speaker
     * to take out that the server does not see in the group is passed over; a leader out of reach
     * is an error.
     */
    public suspend fun setMembers(leaderId: String, add: List<String>, remove: List<String>) {
        client.call(
            "players/cmd/set_members",
            buildJsonObject {
                put("target_player", leaderId)
                put("player_ids_to_add", JsonArray(add.map(::JsonPrimitive)))
                put("player_ids_to_remove", JsonArray(remove.map(::JsonPrimitive)))
            },
            PLAY_TIMEOUT,
        )
    }

    /**
     * The step the server's volume up and down take, as set in its Players settings: 1 to 10, or
     * null for its own ladder (1, 2 or 3 by level). Needs the right to read the server's settings.
     */
    public suspend fun volumeStep(): Int? {
        val answer = client.call(
            "config/core/get_value",
            buildJsonObject {
                put("domain", "players")
                put("key", "volume_step")
            },
        )
        return (answer as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
    }

    public suspend fun volumeUp(playerId: String): Unit = playerCmd("group_volume_up", playerId)

    public suspend fun volumeDown(playerId: String): Unit = playerCmd("group_volume_down", playerId)

    public suspend fun queues(): List<PlayerQueue> = list("player_queues/all", PlayerQueue.serializer())

    /** Up to [limit] of a queue's items from [offset], in play order, played ones included. */
    public suspend fun queueItems(queueId: String, offset: Int, limit: Int = PAGE): ItemPage<QueueItem> =
        page("player_queues/items", QueueItem.serializer(), offset, limit) { put("queue_id", queueId) }

    /**
     * Plays [uris] on a speaker. Returns once playback starts, which can take tens of seconds: send
     * from an app-wide scope.
     */
    public suspend fun playMedia(
        queueId: String,
        uris: List<String>,
        option: QueueOption = QueueOption.Replace,
        startItem: String? = null,
        shuffle: Boolean = false,
    ) {
        client.call(
            "player_queues/play_media",
            buildJsonObject {
                put("queue_id", queueId)
                putJsonArray("media") { uris.forEach { add(JsonPrimitive(it)) } }
                put("option", option.api)
                if (startItem != null) put("start_item", startItem)
                // Left out, the queue's own shuffle setting would still shuffle a playlist after Shuffle.
                if (option == QueueOption.Replace) put("shuffle", shuffle)
            },
            timeout = PLAY_TIMEOUT,
        )
    }

    /**
     * "Play on": moves Up next and its position to another speaker, which plays it when [play];
     * otherwise it rests there at the same place.
     */
    public suspend fun transfer(sourceQueueId: String, targetQueueId: String, play: Boolean = true) {
        client.call(
            "player_queues/transfer",
            buildJsonObject {
                put("source_queue_id", sourceQueueId)
                put("target_queue_id", targetQueueId)
                put("auto_play", play)
            },
            timeout = PLAY_TIMEOUT,
        )
    }

    /** Empties Up next; a queue that plays stops. */
    public suspend fun clearQueue(queueId: String): Unit = queueCmd("clear", queueId)

    public suspend fun playIndex(queueId: String, queueItemId: String): Unit =
        queueCmd("play_index", queueId, PLAY_TIMEOUT) {
            put("index", queueItemId)
        }

    /**
     * Positive moves down, negative up, 0 to the front of what is coming. Refused for an item the
     * speaker already holds (played or buffered); a move past either end does nothing.
     */
    public suspend fun moveItem(queueId: String, queueItemId: String, shift: Int): Unit =
        queueCmd("move_item", queueId) {
            put("queue_item_id", queueItemId)
            put("pos_shift", shift)
        }

    public suspend fun deleteItem(queueId: String, queueItemId: String): Unit =
        queueCmd("delete_item", queueId) { put("item_id_or_index", queueItemId) }

    public suspend fun setShuffle(queueId: String, enabled: Boolean): Unit = queueCmd("shuffle", queueId) {
        put("shuffle_enabled", enabled)
    }

    public suspend fun setRepeat(queueId: String, mode: RepeatMode): Unit =
        queueCmd("repeat", queueId) { put("repeat_mode", mode.api) }

    /** The server's clock in epoch seconds (`time`, schema 42), to extrapolate positions. */
    public suspend fun serverTime(): Double? = (client.call("time") as? JsonPrimitive)?.content?.toDoubleOrNull()

    /** Approves "This phone"'s Sendspin pairing over the trusted control socket (scope players.control). */
    public suspend fun pairWebPlayer(pairingToken: String) {
        client.call("sendspin/pair_web_player", buildJsonObject { put("pairing_token", pairingToken) })
    }

    /** `{role: [scope, ...]}` */
    public suspend fun scopes(): Map<String, List<String>> =
        client.call("auth/scopes").decodeOrNull(MapSerializer(String.serializer(), ListSerializer(String.serializer())))
            ?: emptyMap()

    private suspend fun playerCmd(
        name: String,
        playerId: String,
        timeout: Duration = MaSocket.CALL_TIMEOUT,
        extra: JsonObjectBuilder.() -> Unit = {},
    ) {
        client.call(
            "players/cmd/$name",
            buildJsonObject {
                put("player_id", playerId)
                extra()
            },
            timeout,
        )
    }

    private suspend fun queueCmd(
        name: String,
        queueId: String,
        timeout: Duration = MaSocket.CALL_TIMEOUT,
        extra: JsonObjectBuilder.() -> Unit = {},
    ) {
        client.call(
            "player_queues/$name",
            buildJsonObject {
                put("queue_id", queueId)
                extra()
            },
            timeout,
        )
    }

    internal suspend fun <T> list(
        command: String,
        serializer: KSerializer<T>,
        timeout: Duration = MaSocket.CALL_TIMEOUT,
        build: JsonObjectBuilder.() -> Unit = {},
    ): List<T> =
        (client.call(command, buildJsonObject(build), timeout = timeout) as? JsonArray)?.decodeAll(serializer).orEmpty()

    /**
     * Up to [limit] of a listing from [offset]. A page is counted before it is decoded: an item that
     * does not decode must not end the listing.
     */
    internal suspend fun <T> page(
        command: String,
        serializer: KSerializer<T>,
        offset: Int,
        limit: Int,
        timeout: Duration = MaSocket.CALL_TIMEOUT,
        build: JsonObjectBuilder.() -> Unit,
    ): ItemPage<T> {
        val page = client.call(
            command,
            buildJsonObject {
                build()
                put("offset", offset)
                put("limit", limit)
            },
            timeout = timeout,
        ) as? JsonArray ?: JsonArray(emptyList())
        return ItemPage(page.decodeAll(serializer), complete = page.size < limit, next = offset + page.size)
    }

    /** Every item of a listing, paged by [PAGE]. */
    internal suspend fun <T> listAll(
        command: String,
        serializer: KSerializer<T>,
        build: JsonObjectBuilder.() -> Unit,
    ): List<T> {
        val all = mutableListOf<T>()
        var page = ItemPage<T>(emptyList(), complete = false, next = 0)
        while (!page.complete) {
            page = page(command, serializer, page.next, PAGE, build = build)
            all += page.items
        }
        return all
    }

    public companion object {
        /** Items a listing asks for at a time; the server's own default. */
        public const val PAGE: Int = 500

        /**
         * The queue's play actions wait for its playback lock, up to 120 s for its group's and again
         * for its own, before they run or fail as busy; giving up sooner reports a failure for a
         * command the server still carries out.
         */
        private val PLAY_TIMEOUT = 250.seconds
    }
}

internal fun JsonObjectBuilder.ids(itemId: String, provider: String) {
    put("item_id", itemId)
    put("provider_instance_id_or_domain", provider)
}

/**
 * A long playlist or podcast takes a while: 2.10 streams all of it in partials, and 2.11 fetches
 * all of it from the provider before it answers the first page.
 */
internal val STREAM_TIMEOUT = 90.seconds

/** Decodes off the main thread, leaving out what does not decode: a page can hold hundreds of items. */
internal suspend fun <T> JsonArray.decodeAll(serializer: KSerializer<T>): List<T> =
    withContext(Dispatchers.Default) { mapNotNull { decodeLeniently(serializer, it) } }

/** Decodes off the main thread: a page's answer can hold hundreds of items. */
internal suspend fun <T> JsonElement?.decodeOrNull(serializer: KSerializer<T>): T? {
    if (this == null || this is JsonNull) return null
    val element = this
    return withContext(Dispatchers.Default) { decodeLeniently(serializer, element) }
}

/**
 * A page of a listing from an offset on; [complete] when nothing follows it. [next] is where the
 * following page starts, counted before an item that does not decode is left out.
 */
public data class ItemPage<T>(val items: List<T>, val complete: Boolean, val next: Int)

/** A listing that cannot page answers everything at once: from [offset] on, it is the last page. */
internal fun <T> List<T>.pageFrom(offset: Int): ItemPage<T> {
    val rest = drop(offset)
    return ItemPage(rest, complete = true, next = offset + rest.size)
}
