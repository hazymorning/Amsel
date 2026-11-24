package io.github.hazymorning.amsel.data.downloads

import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.ProviderMapping
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRepositoryTest {
    private val abs = FakeAbs()
    private val root: File = Files.createTempDirectory("downloads").toFile()
    private val accounts = MemoryAbsAccounts()
    private val client = AbsClient(OkHttpClient(), accounts, reachesHome = { true })
    private val network = FakeNetwork()
    private val wifiOnly = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var started = 0

    private val items = mapOf(
        BOOK to item(BOOK, "audiobook", "book1"),
        EPISODE to item(EPISODE, "podcast_episode", "pod1 ep1"),
        TRACK to
            MediaItem(
                "7",
                "library",
                "Ein Lied",
                TRACK,
                "track",
                providerMappings = listOf(ProviderMapping("7", "filesystem_local")),
            ),
    )

    /** Music Assistant cannot be asked. */
    private var serverDown = false

    private val downloads =
        DownloadRepository(client, DownloadStore(root), network, wifiOnly, ::itemAt, { started++ }, { 1_000L })

    private fun itemAt(uri: String): MediaItem? {
        if (serverDown) throw MaConnectionException("offline")
        return items[uri]
    }

    @After fun close() {
        abs.close()
        root.deleteRecursively()
    }

    private fun item(uri: String, type: String, id: String) = MediaItem(
        id,
        "library",
        "Das dicke Buch",
        uri,
        type,
        duration = 200,
        providerMappings = listOf(ProviderMapping(id, "audiobookshelf", "audiobookshelf--a1")),
    )

    private fun signedIn(block: suspend CoroutineScope.() -> Unit) = runBlocking(Dispatchers.IO) {
        client.signIn(abs.url, "root", "pw")
        withTimeout(TIMEOUT_MS) { block() }
    }

    @Test fun `a book downloads whole, its files in order with chapters and cover`() = signedIn {
        assertEquals(DownloadRepository.Added.Yes, downloads.add(BOOK))
        assertEquals(1, started)
        downloads.work()
        assertEquals(DownloadState.Done, downloads.states.value[BOOK])
        val manifest = downloads.manifest(BOOK) ?: error("not done")
        assertEquals(listOf("001.mp3", "002.mp3"), manifest.files.map { it.name })
        assertEquals(listOf(0.0, 100.0), manifest.files.map { it.startSec })
        assertEquals(listOf("Eins", "Zwei"), manifest.chapters.map { it.title })
        assertEquals(200.0, manifest.durationSec, 0.0)
        assertTrue(manifest.complete)
        assertNotNull(downloads.cover(BOOK))
        manifest.files.forEachIndexed { i, file ->
            assertArrayEquals(abs.files[i], downloads.file(BOOK, file).readBytes())
        }
    }

    @Test fun `a connection cut mid-file goes on where it stopped`() = signedIn {
        abs.cutAfter = 120_000
        downloads.add(BOOK)
        downloads.work()
        val manifest = downloads.manifest(BOOK) ?: error("not done")
        assertArrayEquals(abs.files[0], downloads.file(BOOK, manifest.files[0]).readBytes())
        assertTrue(abs.requests.toString(), abs.requests.any { it.endsWith("/file/1/download bytes=120000-") })
    }

    @Test fun `a part left by a restart is not fetched again`() = signedIn {
        val part = File(DownloadStore(root).dir(BOOK), "001.mp3.part")
        part.parentFile?.mkdirs()
        part.writeBytes(abs.files[0].copyOf(250_000))
        downloads.add(BOOK)
        downloads.work()
        assertTrue(abs.requests.toString(), abs.requests.any { it.endsWith("/file/1/download bytes=250000-") })
        val manifest = downloads.manifest(BOOK) ?: error("not done")
        assertArrayEquals(abs.files[0], downloads.file(BOOK, manifest.files[0]).readBytes())
    }

    @Test fun `an access token that ran out is renewed, and the new refresh token kept`() = signedIn {
        abs.expireAccess()
        downloads.add(BOOK)
        downloads.work()
        assertEquals(DownloadState.Done, downloads.states.value[BOOK])
        assertEquals(abs.refreshToken, accounts.token)
        assertTrue(abs.requests.any { it == "POST /auth/refresh" })
    }

    @Test fun `a file the server refuses stops that download, and the account stays`() = signedIn {
        abs.refuseFiles = true
        downloads.add(BOOK)
        downloads.work()
        assertEquals(DownloadState.Failed(AbsException.Reason.Refused), downloads.states.value[BOOK])
        assertEquals(abs.url, accounts.account.value?.url)
    }

    @Test fun `on mobile data it waits for Wi-Fi`() = signedIn {
        wifiOnly.value = true
        network.unmetered.value = false
        downloads.add(BOOK)
        val work = async { downloads.work() }
        while (downloads.states.value[BOOK] != DownloadState.Waiting(forWifi = true)) delay(10)
        assertTrue(abs.requests.none { it.contains("/download") })
        network.unmetered.value = true
        work.await()
        assertEquals(DownloadState.Done, downloads.states.value[BOOK])
    }

    @Test fun `an episode downloads its own file`() = signedIn {
        downloads.add(EPISODE)
        downloads.work()
        val manifest = downloads.manifest(EPISODE) ?: error("not done")
        assertEquals("ep1", manifest.absEpisodeId)
        assertEquals("Folge 1", manifest.title)
        assertEquals(listOf("001.m4a"), manifest.files.map { it.name })
    }

    @Test fun `an extension that is no plain word names the file audio, in its item's folder`() = signedIn {
        val store = DownloadStore(root.resolve("no_backup/downloads"))
        val downloads = DownloadRepository(client, store, network, wifiOnly, ::itemAt, {}, { 1_000L })
        for (ext in listOf("../../x", ".mp3/../../../../x")) {
            abs.ext = ext
            downloads.add(BOOK)
            downloads.work()
            assertEquals(listOf("001.audio", "002.audio"), downloads.manifest(BOOK)?.files?.map { it.name })
            val outside = root.walk().filter { it.isFile && !it.startsWith(store.dir(BOOK)) }
            assertEquals(emptyList<File>(), outside.toList())
            downloads.remove(BOOK)
        }
    }

    @Test fun `a manifest naming a file outside its folder fetches and writes nothing`() = runBlocking {
        val store = DownloadStore(root)
        val file = DownloadFile("1", "001.mp3/../../x", 300_000, 0.0, 100.0)
        val stale = DownloadManifest(
            BOOK,
            "book1",
            title = "Das dicke Buch",
            mediaType = "audiobook",
            files = listOf(file),
        )
        store.save(stale)
        assertThrows(IOException::class.java) { store.file(BOOK, file) }
        val fetched = runCatching { DownloadTask(client, store).fetch(stale, file) {} }
        assertTrue(fetched.exceptionOrNull() is IOException)
        assertEquals(listOf("manifest.json"), root.walk().filter { it.isFile }.map { it.name }.toList())
        assertTrue(abs.requests.isEmpty())
    }

    @Test fun `every name a download is given stays readable, a thousandth file and Arabic digits too`() {
        val store = DownloadStore(root)
        for (name in listOf("001.mp3", "1000.m4b", "٠٠١.mp3")) {
            assertEquals(store.dir(BOOK).resolve(name), store.file(BOOK, DownloadFile("1", name, 1, 0.0, 1.0)))
        }
    }

    @Test fun `what is not from Audiobookshelf, or before signing in, is said so`() = runBlocking {
        assertEquals(DownloadRepository.Added.SignInFirst, downloads.add(BOOK))
        client.signIn(abs.url, "root", "pw")
        assertEquals(DownloadRepository.Added.NotFromAudiobookshelf, downloads.add(TRACK))
        assertEquals(0, started)
    }

    @Test fun `a removed download leaves nothing behind`() = signedIn {
        downloads.add(BOOK)
        downloads.work()
        downloads.remove(BOOK)
        assertEquals(null, downloads.states.value[BOOK])
        assertTrue(!DownloadStore(root).dir(BOOK).exists())
    }

    @Test fun `a stopped download goes on without asking Music Assistant again`() = signedIn {
        abs.gone = true
        downloads.add(BOOK)
        downloads.work()
        assertEquals(DownloadState.Failed(AbsException.Reason.NotFound), downloads.states.value[BOOK])
        abs.gone = false
        serverDown = true
        assertEquals(DownloadRepository.Added.Yes, downloads.add(BOOK))
        downloads.work()
        assertEquals(DownloadState.Done, downloads.states.value[BOOK])
        assertEquals("Das dicke Buch", downloads.entries.value[BOOK]?.title)
    }

    @Test fun `a download stopped with its service waits, and goes on at the next start`() = runBlocking(
        Dispatchers.IO,
    ) {
        FakeAbs(listOf(FakeAbs.bytes(2_000_000, 1))).use { slow ->
            slow.chunkDelayMs = 10
            val client = AbsClient(OkHttpClient(), MemoryAbsAccounts(), reachesHome = { true })
            client.signIn(slow.url, "root", "pw")
            val downloads = DownloadRepository(client, DownloadStore(root), network, wifiOnly, ::itemAt, {}, { 1_000L })
            downloads.add(BOOK)
            val work = launch { downloads.work() }
            withTimeout(TIMEOUT_MS) { while (downloads.states.value[BOOK] !is DownloadState.Running) delay(1) }
            work.cancelAndJoin()
            assertEquals(DownloadState.Waiting(), downloads.states.value[BOOK])
            slow.chunkDelayMs = 0
            downloads.work()
            assertEquals(DownloadState.Done, downloads.states.value[BOOK])
            assertTrue(slow.requests.toString(), slow.requests.any { it.contains("/download bytes=") })
        }
    }

    @Test fun `Music Assistant out of reach is said so, not a crash`() = signedIn {
        serverDown = true
        assertEquals(DownloadRepository.Added.NotFound, downloads.add(BOOK))
        assertEquals(null, downloads.states.value[BOOK])
    }

    @Test fun `signing out of Music Assistant leaves no download behind`() = signedIn {
        downloads.add(BOOK)
        downloads.add(EPISODE)
        downloads.work()
        downloads.clear()
        assertTrue(downloads.states.value.isEmpty() && downloads.entries.value.isEmpty())
        assertEquals(0L, downloads.used())
    }

    @Test fun `an address typed without a scheme is tried with https, then plain http`() = runBlocking(Dispatchers.IO) {
        // This test server answers a TLS hello with silence, where Audiobookshelf says 400 at once.
        val quick = AbsClient(OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build(), accounts) { true }
        quick.signIn(abs.url.removePrefix("http://"), "root", "pw")
        assertEquals(abs.url, accounts.account.value?.url)
    }

    @Test fun `progress goes to Audiobookshelf and comes back`() = signedIn {
        client.saveProgress("book1", null, 61.5, 200.0, finished = false)
        assertTrue(abs.progress.orEmpty().contains("\"currentTime\":61.5"))
        assertEquals(61.5, client.progress("book1", null)?.currentTime ?: 0.0, 0.0)
    }

    private companion object {
        const val BOOK = "library://audiobook/3"
        const val EPISODE = "library://podcast_episode/9"
        const val TRACK = "library://track/7"
        const val TIMEOUT_MS = 30_000L
    }
}
