package io.github.hazymorning.amsel.data.downloads

import io.github.hazymorning.amsel.api.forUrl
import io.github.hazymorning.amsel.api.isCertificateError
import io.github.hazymorning.amsel.api.isLanHost
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class AbsAccount(val url: String, val username: String)

/** Implementations keep the refresh token sealed. */
interface AbsAccountStore {
    val account: StateFlow<AbsAccount?>

    suspend fun refreshToken(): String?

    suspend fun save(account: AbsAccount, refreshToken: String)

    suspend fun saveRefreshToken(token: String)

    suspend fun clear()
}

class AbsException(val reason: Reason, cause: Throwable? = null) : IOException(reason.name, cause) {
    /**
     * [Refused]: the server denies this file to the account, which stays signed in. [HttpsFailed]: https failed
     * for a name that is not a home address, so plain http must be typed. [AwayFromHome]: plain http off the
     * checked network.
     */
    enum class Reason {
        BadAddress,
        Unreachable,
        WrongLogin,
        SignedOut,
        NotFound,
        Refused,
        Server,
        Certificate,
        HttpsFailed,
        AwayFromHome,
    }
}

// Downloads use Audiobookshelf's own API: Music Assistant cannot hand out the files. The refresh token rotates
// with every refresh. See docs/API-NOTES.md, "Audiobookshelf for downloads".
class AbsClient(
    private val http: OkHttpClient,
    private val store: AbsAccountStore,
    // [io.github.hazymorning.amsel.data.Session.reachesHome]: whether plain http may go out; https needs no check.
    private val reachesHome: suspend () -> Boolean,
) {
    private val refreshing = Mutex()

    @Volatile private var access: String? = null

    val account: StateFlow<AbsAccount?> get() = store.account

    /**
     * Throws [AbsException]. Without a scheme, https is tried first, then plain http only for a home address by its
     * form, never after an untrusted certificate. See docs/API-NOTES.md, "Audiobookshelf for downloads".
     */
    suspend fun signIn(address: String, username: String, password: String) = withContext(Dispatchers.IO) {
        val typed = address.trim()
        if ("://" in typed) {
            signInAt(typed, username, password)
        } else {
            try {
                signInAt("https://$typed", username, password)
            } catch (e: AbsException) {
                if (e.reason != AbsException.Reason.Unreachable) throw e
                // DNS on a hostile network answers any other name with a private address.
                if (baseUrl(typed)?.host?.let(::isLanHost) != true) {
                    throw AbsException(AbsException.Reason.HttpsFailed, e)
                }
                signInAt("http://$typed", username, password)
            }
        }
    }

    private suspend fun signInAt(address: String, username: String, password: String) {
        val base = baseUrl(address) ?: throw AbsException(AbsException.Reason.BadAddress)
        if (!base.isHttps && !reachesHome()) throw AbsException(AbsException.Reason.AwayFromHome)
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
        }.toString().toRequestBody(JSON)
        val request = Request.Builder().url(base.at("login")).post(body).header("x-return-tokens", "true").build()
        val user = execute(base, request) { response ->
            if (response.code == 401) throw AbsException(AbsException.Reason.WrongLogin)
            response.decode(AbsLogin.serializer()).user
        }
        val keep = user.refreshToken ?: user.token ?: throw AbsException(AbsException.Reason.Server)
        access = user.accessToken ?: user.token
        store.save(AbsAccount(base.toString().trimEnd('/'), user.username.ifEmpty { username }), keep)
    }

    suspend fun signOut() {
        access = null
        store.clear()
    }

    internal suspend fun item(itemId: String): AbsItem =
        send("api/items/$itemId?expanded=1") { it.decode(AbsItem.serializer()) }
            ?: throw AbsException(AbsException.Reason.NotFound)

    /** Null when the item was never started there. */
    suspend fun progress(itemId: String, episodeId: String?): AbsProgress? =
        send(progressPath(itemId, episodeId)) { it.decode(AbsProgress.serializer()) }

    suspend fun saveProgress(itemId: String, episodeId: String?, seconds: Double, duration: Double, finished: Boolean) {
        val body = buildJsonObject {
            put("currentTime", seconds)
            put("duration", duration)
            put("progress", if (duration > 0) (seconds / duration).coerceIn(0.0, 1.0) else 0.0)
            put("isFinished", finished)
        }.toString().toRequestBody(JSON)
        send(progressPath(itemId, episodeId), verb = "PATCH", body = body) { }
    }

    /** From byte [from] on; the caller must close it and read it off the main thread. */
    internal suspend fun file(itemId: String, ino: String, from: Long): Response =
        authorized("api/items/$itemId/file/$ino/download") { if (from > 0) header("Range", "bytes=$from-") }

    internal suspend fun cover(itemId: String): Response = authorized("api/items/$itemId/cover")

    private fun progressPath(itemId: String, episodeId: String?) =
        "api/me/progress/$itemId" + (episodeId?.let { "/$it" } ?: "")

    private suspend fun <T> send(
        path: String,
        verb: String = "GET",
        body: RequestBody? = null,
        read: (Response) -> T,
    ): T? = withContext(Dispatchers.IO) {
        authorized(path) { method(verb, body) }.use { response ->
            when {
                response.code == 404 -> null
                !response.isSuccessful -> throw AbsException(AbsException.Reason.Server)
                else -> read(response)
            }
        }
    }

    private suspend fun authorized(path: String, build: Request.Builder.() -> Unit = {}): Response {
        val account = store.account.value ?: throw AbsException(AbsException.Reason.SignedOut)
        val base = baseUrl(account.url) ?: throw AbsException(AbsException.Reason.BadAddress)
        val url = base.at(path)
        repeat(2) { attempt ->
            val token = access ?: refresh(base, stale = null)
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").apply(build).build()
            val response = call(base, request)
            if (response.code != 401 || attempt == 1) return response
            response.close()
            refresh(base, stale = token)
        }
        throw AbsException(AbsException.Reason.SignedOut)
    }

    // Serialised, and a token another call already put in place of [stale] is reused: the refresh token rotates.
    private suspend fun refresh(base: HttpUrl, stale: String?): String = refreshing.withLock {
        access?.takeIf { it != stale }?.let { return it }
        val refreshToken = store.refreshToken() ?: throw AbsException(AbsException.Reason.SignedOut)
        val request = Request.Builder()
            .url(base.at("auth/refresh"))
            .post(ByteArray(0).toRequestBody(null))
            .header("x-refresh-token", refreshToken)
            .build()
        val user = execute(base, request) { response ->
            when (response.code) {
                // A server before 2.26 has no refresh: its one token is the access token.
                404 -> AbsUser(accessToken = refreshToken)

                401, 403 -> throw AbsException(AbsException.Reason.SignedOut)

                else -> response.decode(AbsLogin.serializer()).user
            }
        }
        user.refreshToken?.takeIf { it != refreshToken }?.let { store.saveRefreshToken(it) }
        val token = user.accessToken ?: user.token ?: throw AbsException(AbsException.Reason.SignedOut)
        access = token
        token
    }

    private suspend fun <T> execute(base: HttpUrl, request: Request, read: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            call(base, request).use { response ->
                if (response.code in SERVER_ERRORS) throw AbsException(AbsException.Reason.Server)
                read(response)
            }
        }

    // Responses are read on IO too: screens call from the main thread, where Android throws on any network read.
    private suspend fun call(base: HttpUrl, request: Request): Response {
        // Off the checked network a home address may reach any machine, which would get the token.
        if (!base.isHttps && !reachesHome()) throw AbsException(AbsException.Reason.AwayFromHome)
        return try {
            runInterruptible(Dispatchers.IO) { http.forUrl(base).newCall(request).execute() }
        } catch (e: IOException) {
            throw AbsException(
                if (e.isCertificateError()) AbsException.Reason.Certificate else AbsException.Reason.Unreachable,
                e,
            )
        }
    }

    private fun <T> Response.decode(serializer: KSerializer<T>): T {
        if (!isSuccessful) throw AbsException(AbsException.Reason.Server)
        return try {
            AbsJson.decodeFromString(serializer, body.string())
        } catch (e: IllegalArgumentException) {
            throw AbsException(AbsException.Reason.Server, e)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val SERVER_ERRORS = 500..599

        fun baseUrl(address: String): HttpUrl? {
            val trimmed = address.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            return "$withScheme/".toHttpUrlOrNull()
        }

        private fun HttpUrl.at(path: String): HttpUrl =
            resolve(path) ?: throw AbsException(AbsException.Reason.BadAddress)
    }
}
