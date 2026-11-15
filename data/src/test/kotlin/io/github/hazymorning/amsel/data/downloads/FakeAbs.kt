package io.github.hazymorning.amsel.data.downloads

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An Audiobookshelf server on the loopback, as 2.37.2 answered live: sign-in with rotating
 * tokens, a book of [files], a podcast with one episode, ranged file downloads, and progress.
 */
internal class FakeAbs(val files: List<ByteArray> = listOf(bytes(300_000, 1), bytes(200_000, 2))) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    /** "GET /api/... Range: bytes=..." for every request, in order. */
    val requests = CopyOnWriteArrayList<String>()

    /** Access tokens the server takes; the first sign-in's is dropped by [expireAccess]. */
    private val accepted = mutableSetOf<String>()
    private var serial = 0
    var refreshToken = "refresh-0"

    /** A download of the first file stops after this many bytes, once. */
    var cutAfter: Int? = null

    var progress: String? = null

    /** The book is no longer on the server. */
    var gone = false

    /** A pause after each 64 KB sent, as a slow line has. */
    var chunkDelayMs = 0L

    /** The server refuses the files themselves, as for a user without the download right. */
    var refuseFiles = false

    /** What the book's files say their extension is. */
    var ext = ".mp3"

    init {
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (e: java.io.IOException) {
                // A cut connection ends here on purpose.
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    fun expireAccess() = accepted.clear()

    private fun handle(e: HttpExchange) {
        val path = e.requestURI.path
        requests += "${e.requestMethod} ${e.requestURI}" + (e.requestHeaders.getFirst("Range")?.let { " $it" } ?: "")
        when {
            path == "/login" -> e.json(200, login())

            path == "/auth/refresh" ->
                if (e.requestHeaders.getFirst("x-refresh-token") ==
                    refreshToken
                ) {
                    e.json(200, login())
                } else {
                    e.json(401, "{}")
                }

            !authorized(e) -> e.json(401, "{}")

            path == "/api/items/book1" -> if (gone) e.json(404, "{}") else e.json(200, book())

            path == "/api/items/pod1" -> e.json(200, podcast())

            path.startsWith("/api/items/") && path.endsWith("/cover") -> e.bytes(200, byteArrayOf(1, 2, 3))

            path.startsWith("/api/items/") && path.endsWith("/download") ->
                if (refuseFiles) e.json(403, "{}") else download(e, path.split('/')[5])

            path.startsWith("/api/me/progress/") && e.requestMethod == "PATCH" -> {
                progress = e.requestBody.readBytes().decodeToString()
                e.json(200, "{}")
            }

            path.startsWith("/api/me/progress/") ->
                progress?.let { e.json(200, """{"currentTime": 61.5, "duration": 100, "lastUpdate": 1794000000000}""") }
                    ?: e.json(404, "{}")

            else -> e.json(404, "{}")
        }
    }

    private fun login(): String {
        val access = "access-${++serial}"
        accepted += access
        refreshToken = "refresh-$serial"
        return """{"user": {"username": "root", "accessToken": "$access", "refreshToken": "$refreshToken"}}"""
    }

    private fun authorized(e: HttpExchange): Boolean =
        e.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ") in accepted

    private fun book(): String {
        var start = 0.0
        val tracks = files.mapIndexed { i, data ->
            val track = """{"startOffset": $start, "duration": 100.0, "contentUrl": "/api/items/book1/file/${i + 1}",
                "metadata": {"filename": "Teil ${i + 1}.mp3", "ext": "$ext", "size": ${data.size}}}"""
            start += 100.0
            track
        }
        return """{"id": "book1", "media": {"metadata": {"title": "Das dicke Buch", "authorName": "Testautorin"},
            "duration": $start, "tracks": [${tracks.joinToString()}],
            "chapters": [{"start": 0, "end": 150, "title": "Eins"}, {"start": 150, "end": $start, "title": "Zwei"}]}}"""
    }

    private fun podcast() = """{"id": "pod1", "media": {"metadata": {"title": "Der Podcast"}, "episodes": [
        {"id": "ep1", "title": "Folge 1", "audioTrack": {"startOffset": 0, "duration": 100.0,
         "contentUrl": "/api/items/pod1/file/1", "metadata": {"ext": ".m4a", "size": ${files[0].size}}}}]}}"""

    private fun download(e: HttpExchange, ino: String) {
        val data = files[ino.toInt() - 1]
        val from = e.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
        val cut = cutAfter?.takeIf { ino == "1" }
        if (cut != null) cutAfter = null
        val end = cut?.let { minOf(it, data.size) } ?: data.size
        if (from > 0) e.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
        e.sendResponseHeaders(if (from > 0) 206 else 200, (data.size - from).toLong())
        // A cut connection: fewer bytes than announced, then the socket closes.
        var at = from
        while (at < end) {
            val n = minOf(CHUNK, end - at)
            e.responseBody.write(data, at, n)
            e.responseBody.flush()
            at += n
            if (chunkDelayMs > 0) Thread.sleep(chunkDelayMs)
        }
    }

    override fun close() = server.stop(0)

    companion object {
        private const val CHUNK = 64 * 1024

        fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }
    }
}

private fun HttpExchange.json(code: Int, body: String) {
    responseHeaders.add("Content-Type", "application/json")
    bytes(code, body.toByteArray())
}

private fun HttpExchange.bytes(code: Int, body: ByteArray) {
    sendResponseHeaders(code, body.size.toLong())
    responseBody.write(body)
}

/** An account kept in memory. */
internal class MemoryAbsAccounts : AbsAccountStore {
    override val account = MutableStateFlow<AbsAccount?>(null)
    var token: String? = null

    override suspend fun refreshToken() = token

    override suspend fun save(account: AbsAccount, refreshToken: String) {
        this.account.value = account
        token = refreshToken
    }

    override suspend fun saveRefreshToken(token: String) {
        this.token = token
    }

    override suspend fun clear() {
        account.value = null
        token = null
    }
}

internal class FakeNetwork(online: Boolean = true, unmetered: Boolean = true) : NetworkState {
    override val online = MutableStateFlow(online)
    override val unmetered = MutableStateFlow(unmetered)
}
