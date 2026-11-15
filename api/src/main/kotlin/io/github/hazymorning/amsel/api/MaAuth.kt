package io.github.hazymorning.amsel.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

@Serializable
public data class MaUser(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    val role: String = "user",
) {
    public val shownName: String get() = displayName?.takeIf { it.isNotBlank() } ?: username
}

/** A sign-in method the server offers (`builtin`, `homeassistant`). */
@Serializable
public data class AuthProvider(@SerialName("provider_type") val providerType: String)

/** What the Sign in screen needs to know about a server before anyone types anything. */
public data class ServerProbe(val info: ServerInfo, val providers: List<AuthProvider>) {
    public val offersHomeAssistant: Boolean get() = providers.any { it.providerType == "homeassistant" }
}

public sealed interface SignInResult {
    public data class Success(val token: String) : SignInResult

    public data object WrongPassword : SignInResult

    /** The server refuses this account for a while after failed attempts. */
    public data object TooManyAttempts : SignInResult

    public data object AccountDisabled : SignInResult

    public data object Failed : SignInResult
}

/** Where the Home Assistant sign-in returns: localhost is always trusted, so no consent page. */
public const val HA_RETURN_URL: String = "http://localhost/amsel-auth"

/** One-shot sign-in flows, each on a short-lived socket of its own. */
public class MaAuth(private val http: OkHttpClient) {
    /** Connects without credentials and reads the server's info and sign-in methods. */
    public suspend fun probe(address: ServerAddress): ServerProbe {
        val socket = MaSocket(http, address)
        try {
            val info = socket.open()
            val providers = try {
                (socket.call("auth/providers") as? JsonArray).orEmpty().map {
                    MaJson.decodeFromJsonElement(AuthProvider.serializer(), it)
                }
            } catch (e: MaCommandException) {
                // Only a server older than the app supports lacks the command; its schema says so.
                if (e.isSetupRequired) throw e else emptyList()
            }
            return ServerProbe(info, providers)
        } finally {
            socket.close()
        }
    }

    /** Who answers at [address] now, by its first frame; nothing is sent. */
    public suspend fun serverAt(address: ServerAddress): ServerInfo {
        val socket = MaSocket(http, address)
        try {
            return socket.open()
        } finally {
            socket.close()
        }
    }

    /**
     * The Home Assistant sign-in page for an in-app WebView. The server runs the OAuth exchange
     * itself and finally sends the browser to [HA_RETURN_URL] with the session token as `code`.
     * Null when the server cannot offer it right now.
     */
    public suspend fun homeAssistantUrl(address: ServerAddress): String? {
        val socket = MaSocket(http, address)
        return try {
            orNullOnFailure {
                socket.open()
                val result = socket.call(
                    "auth/authorization_url",
                    buildJsonObject {
                        put("provider_id", "homeassistant")
                        put("return_url", HA_RETURN_URL)
                    },
                ) as? JsonObject
                (result?.get("authorization_url") as? JsonPrimitive)?.contentOrNull
            }
        } finally {
            socket.close()
        }
    }

    /**
     * Username and password, then a long-lived token named after the device, so the app never
     * asks again. The password goes only to the server whose first frame names [serverId], the
     * one probed.
     */
    public suspend fun signIn(
        address: ServerAddress,
        serverId: String,
        username: String,
        password: String,
        deviceName: String,
    ): SignInResult {
        val socket = MaSocket(http, address)
        try {
            return orNullOnFailure {
                if (!socket.opensAt(serverId)) return SignInResult.Failed
                val login = socket.call(
                    "auth/login",
                    buildJsonObject {
                        put("username", username)
                        put("password", password)
                        put("device_name", deviceName)
                    },
                ) as? JsonObject ?: return SignInResult.Failed
                val ok = (login["success"] as? JsonPrimitive)?.booleanOrNull ?: false
                val accessToken = (login["access_token"] as? JsonPrimitive)?.contentOrNull
                if (!ok || accessToken == null) return refusal(login)
                exchange(socket, accessToken, deviceName)
            } ?: SignInResult.Failed
        } finally {
            socket.close()
        }
    }

    /**
     * Turns a short-lived access token (password or Home Assistant sign-in) into a long-lived one,
     * at the server whose first frame names [serverId].
     */
    public suspend fun exchangeToken(
        address: ServerAddress,
        serverId: String,
        accessToken: String,
        deviceName: String,
    ): SignInResult {
        val socket = MaSocket(http, address)
        return try {
            orNullOnFailure {
                if (!socket.opensAt(serverId)) return SignInResult.Failed
                exchange(socket, accessToken, deviceName)
            } ?: SignInResult.Failed
        } finally {
            socket.close()
        }
    }

    private suspend fun exchange(socket: MaSocket, accessToken: String, deviceName: String): SignInResult {
        val send: Command = { command, args -> socket.call(command, args) }
        authenticate(socket, accessToken)
        val longLived = try {
            createToken(send, deviceName)
        } catch (e: MaCommandException) {
            // A guest cannot create a long-lived token (error 22): keep the session token instead.
            if (e.isPermissionError) return SignInResult.Success(accessToken) else throw e
        } ?: return SignInResult.Failed
        // Switch this socket to the long-lived token, then retire the short-lived one. Signed in
        // either way: a session token the server keeps runs out by itself.
        orNullOnFailure {
            authenticate(socket, longLived)
            revokeToken(send, accessToken)
        }
        return SignInResult.Success(longLived)
    }
}

/** Opens the socket; false when another server answers there, which then gets nothing. */
private suspend fun MaSocket.opensAt(serverId: String): Boolean = open().serverId == serverId

/** Why `auth/login` said no: 2.11 names it with a translation_key, 2.10 only in English. */
private fun refusal(login: JsonObject): SignInResult {
    val key = (login["translation_key"] as? JsonPrimitive)?.contentOrNull
    val error = (login["error"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return when {
        key == "rate_limited" || error.startsWith("Too many failed attempts") -> SignInResult.TooManyAttempts
        key == "user_account_disabled" || error == "User account is disabled" -> SignInResult.AccountDisabled
        else -> SignInResult.WrongPassword
    }
}

/** Sends one command: on a short-lived sign-in socket, or on the app's connection. */
internal typealias Command = suspend (command: String, args: JsonObject?) -> JsonElement?

/** A long-lived token named [name] for whoever [send] is signed in as; null when none comes back. */
internal suspend fun createToken(send: Command, name: String): String? =
    (send("auth/token/create", buildJsonObject { put("name", name) }) as? JsonPrimitive)?.contentOrNull

/** Retires [token] on the server, which closes every socket signed in with it. */
internal suspend fun revokeToken(send: Command, token: String) {
    val jti = TokenClaims.parse(token)?.tokenId ?: return
    send("auth/token/revoke", buildJsonObject { put("token_id", jti) })
}
