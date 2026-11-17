package io.github.hazymorning.amsel.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The JSON configuration every part of the app uses for Music Assistant payloads. */
public val MaJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/** A server-pushed event: `{"event": ..., "object_id": ..., "data": ...}`. */
public data class MaEvent(val name: String, val objectId: String?, val data: JsonElement?) {
    /** [data] as [serializer] reads it; null when there is none or it does not decode. */
    public fun <T> decode(serializer: KSerializer<T>): T? = data?.let { decodeLeniently(serializer, it) }
}

/**
 * [element] as [serializer] reads it, or null when it does not decode: a model the server changed
 * leaves out one item, not the whole answer. SerializationException is an IllegalArgumentException,
 * as is a value of another JSON kind than expected.
 */
internal fun <T> decodeLeniently(serializer: KSerializer<T>, element: JsonElement): T? = try {
    MaJson.decodeFromJsonElement(serializer, element)
} catch (e: IllegalArgumentException) {
    null
}

/**
 * A request to the server that failed: the server refused it ([MaCommandException]) or it never got
 * there ([MaConnectionException]). Callers that treat both alike catch this.
 */
public sealed class MaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An error the server returned for a command (`error_code` + `details`). */
public class MaCommandException(public val command: String, public val code: Int, public val details: String) :
    MaException("$command failed ($code): $details") {
    /** True when the server rejected the token: sign in again. */
    public val isAuthError: Boolean
        get() = code == ERROR_AUTHENTICATION_REQUIRED || code == ERROR_INVALID_TOKEN

    /** True when the user's role lacks the scope for this command. */
    public val isPermissionError: Boolean get() = code == ERROR_INSUFFICIENT_PERMISSIONS

    public val isNotFound: Boolean get() = code == ERROR_MEDIA_NOT_FOUND

    /** The server has no users yet: finish setting it up in the web UI. */
    public val isSetupRequired: Boolean get() = code == ERROR_SETUP_REQUIRED

    /** A previous play action still holds the speaker. */
    public val isBusy: Boolean get() = code == ERROR_BUSY

    /** The speaker is gone, or its music service failed the command. */
    public val isSpeakerUnresponsive: Boolean
        get() = code == ERROR_PLAYER_UNAVAILABLE || code == ERROR_PLAYER_COMMAND_FAILED

    // music_assistant_models/errors.py
    public companion object {
        public const val ERROR_UNKNOWN: Int = 0
        public const val ERROR_MEDIA_NOT_FOUND: Int = 2
        public const val ERROR_PLAYER_UNAVAILABLE: Int = 10
        public const val ERROR_PLAYER_COMMAND_FAILED: Int = 11

        /** A command the server does not have (a plugin that is not loaded), or one it refuses in the queue's state. */
        public const val ERROR_INVALID_COMMAND: Int = 12
        public const val ERROR_AUTHENTICATION_REQUIRED: Int = 20
        public const val ERROR_INSUFFICIENT_PERMISSIONS: Int = 22
        public const val ERROR_INVALID_TOKEN: Int = 23
        public const val ERROR_BUSY: Int = 24
        public const val ERROR_SETUP_REQUIRED: Int = 503
    }
}

/** The connection dropped or never came up. */
public class MaConnectionException(message: String, cause: Throwable? = null) : MaException(message, cause)

/** [call]'s answer, or null when the server cannot be reached or refuses it; cancellation passes. */
public inline fun <T> orNullOnFailure(call: () -> T): T? = try {
    call()
} catch (e: MaConnectionException) {
    null
} catch (e: MaCommandException) {
    null
}

internal sealed interface Incoming {
    data class ServerInfoFrame(val info: ServerInfo) : Incoming

    data class Result(val messageId: String, val result: JsonElement?, val partial: Boolean) : Incoming

    data class Error(val messageId: String, val code: Int, val details: String) : Incoming

    data class Event(val event: MaEvent) : Incoming

    data object Unknown : Incoming
}

internal fun parseIncoming(text: String): Incoming {
    val obj = MaJson.parseToJsonElement(text) as? JsonObject ?: return Incoming.Unknown
    val id = obj["message_id"]?.jsonPrimitive?.contentOrNull
    return when {
        id != null && obj.containsKey("error_code") -> Incoming.Error(
            messageId = id,
            code = (obj["error_code"] as? JsonPrimitive)?.intOrNull ?: -1,
            details = (obj["details"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )

        id != null -> Incoming.Result(
            messageId = id,
            result = obj["result"],
            partial = (obj["partial"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )

        obj.containsKey("event") -> Incoming.Event(
            MaEvent(
                name = obj["event"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                objectId = (obj["object_id"] as? JsonPrimitive)?.contentOrNull,
                data = obj["data"],
            ),
        )

        obj.containsKey("server_id") && obj.containsKey("schema_version") ->
            Incoming.ServerInfoFrame(MaJson.decodeFromJsonElement(ServerInfo.serializer(), obj))

        else -> Incoming.Unknown
    }
}
