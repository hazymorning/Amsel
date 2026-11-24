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

public val MaJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

public data class MaEvent(val name: String, val objectId: String?, val data: JsonElement?) {
    public fun <T> decode(serializer: KSerializer<T>): T? = data?.let { decodeLeniently(serializer, it) }
}

/**
 * A model the server changed drops one item, not the whole answer. SerializationException is an
 * IllegalArgumentException, as is a JSON element of an unexpected kind.
 */
internal fun <T> decodeLeniently(serializer: KSerializer<T>, element: JsonElement): T? = try {
    MaJson.decodeFromJsonElement(serializer, element)
} catch (e: IllegalArgumentException) {
    null
}

public sealed class MaException(message: String, cause: Throwable? = null) : Exception(message, cause)

public class MaCommandException(public val command: String, public val code: Int, public val details: String) :
    MaException("$command failed ($code): $details") {
    public val isAuthError: Boolean
        get() = code == ERROR_AUTHENTICATION_REQUIRED || code == ERROR_INVALID_TOKEN

    public val isPermissionError: Boolean get() = code == ERROR_INSUFFICIENT_PERMISSIONS

    public val isNotFound: Boolean get() = code == ERROR_MEDIA_NOT_FOUND

    /** The server has no users yet. */
    public val isSetupRequired: Boolean get() = code == ERROR_SETUP_REQUIRED

    /** A previous play action still holds the speaker. */
    public val isBusy: Boolean get() = code == ERROR_BUSY

    public val isSpeakerUnresponsive: Boolean
        get() = code == ERROR_PLAYER_UNAVAILABLE || code == ERROR_PLAYER_COMMAND_FAILED

    // music_assistant_models/errors.py
    public companion object {
        public const val ERROR_UNKNOWN: Int = 0
        public const val ERROR_MEDIA_NOT_FOUND: Int = 2
        public const val ERROR_PLAYER_UNAVAILABLE: Int = 10
        public const val ERROR_PLAYER_COMMAND_FAILED: Int = 11

        /** Unknown command (a plugin not loaded), or one the queue refuses in its state. */
        public const val ERROR_INVALID_COMMAND: Int = 12
        public const val ERROR_AUTHENTICATION_REQUIRED: Int = 20
        public const val ERROR_INSUFFICIENT_PERMISSIONS: Int = 22
        public const val ERROR_INVALID_TOKEN: Int = 23
        public const val ERROR_BUSY: Int = 24
        public const val ERROR_SETUP_REQUIRED: Int = 503
    }
}

public class MaConnectionException(message: String, cause: Throwable? = null) : MaException(message, cause)

/** Null for any [MaException]; cancellation still propagates. */
public inline fun <T> orNullOnFailure(call: () -> T): T? = try {
    call()
} catch (e: MaConnectionException) {
    null
} catch (e: MaCommandException) {
    null
}

internal sealed interface IncomingMessage {
    data class ServerInfoFrame(val info: ServerInfo) : IncomingMessage

    data class Result(val messageId: String, val result: JsonElement?, val partial: Boolean) : IncomingMessage

    data class Error(val messageId: String, val code: Int, val details: String) : IncomingMessage

    data class Event(val event: MaEvent) : IncomingMessage

    data object Unknown : IncomingMessage
}

internal fun parseIncoming(text: String): IncomingMessage {
    val obj = MaJson.parseToJsonElement(text) as? JsonObject ?: return IncomingMessage.Unknown
    val id = obj["message_id"]?.jsonPrimitive?.contentOrNull
    return when {
        id != null && obj.containsKey("error_code") -> IncomingMessage.Error(
            messageId = id,
            code = (obj["error_code"] as? JsonPrimitive)?.intOrNull ?: -1,
            details = (obj["details"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )

        id != null -> IncomingMessage.Result(
            messageId = id,
            result = obj["result"],
            partial = (obj["partial"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )

        obj.containsKey("event") -> IncomingMessage.Event(
            MaEvent(
                name = obj["event"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                objectId = (obj["object_id"] as? JsonPrimitive)?.contentOrNull,
                data = obj["data"],
            ),
        )

        obj.containsKey("server_id") && obj.containsKey("schema_version") ->
            IncomingMessage.ServerInfoFrame(MaJson.decodeFromJsonElement(ServerInfo.serializer(), obj))

        else -> IncomingMessage.Unknown
    }
}
