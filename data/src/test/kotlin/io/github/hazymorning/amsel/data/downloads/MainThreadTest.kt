package io.github.hazymorning.amsel.data.downloads

import io.github.hazymorning.amsel.api.MediaItem
import io.github.hazymorning.amsel.api.ProviderMapping
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android ends the app at any network read on its main thread, where the screens and the
 * download service call from: every answer from Audiobookshelf is read elsewhere.
 */
class MainThreadTest {
    private val abs = FakeAbs()
    private val root: File = Files.createTempDirectory("downloads").toFile()
    private val main = Executors.newSingleThreadExecutor { Thread(it, MAIN) }.asCoroutineDispatcher()

    /** The threads that finished reading an answer, without the coroutine a debug run names after them. */
    private val readers = CopyOnWriteArrayList<String>()
    private val listener = object : EventListener() {
        override fun responseBodyEnd(call: Call, byteCount: Long) {
            readers += Thread.currentThread().name.substringBefore(" @")
        }

        override fun responseFailed(call: Call, ioe: IOException) {
            readers += Thread.currentThread().name.substringBefore(" @")
        }
    }

    @After fun close() {
        abs.close()
        main.close()
        root.deleteRecursively()
    }

    @Test fun `signing in, downloading and progress read nothing on the calling thread`() = runBlocking(main) {
        val client = AbsClient(OkHttpClient.Builder().eventListener(listener).build(), MemoryAbsAccounts())
        val item = MediaItem(
            "book1",
            "library",
            "Das dicke Buch",
            BOOK,
            "audiobook",
            providerMappings = listOf(ProviderMapping("book1", "audiobookshelf", "audiobookshelf--a1")),
        )
        val downloads = Downloads(client, DownloadStore(root), FakeNetwork(), MutableStateFlow(false), { item }, {})
        client.signIn(abs.url.removePrefix("http://"), "root", "pw")
        abs.expireAccess()
        downloads.add(BOOK)
        downloads.work()
        client.saveProgress("book1", null, 61.5, 200.0, finished = false)
        client.progress("book1", null)
        assertEquals(DownloadState.Done, downloads.states.value[BOOK])
        assertTrue(readers.toString(), readers.isNotEmpty() && readers.none { it == MAIN })
    }

    private companion object {
        const val MAIN = "main"
        const val BOOK = "library://audiobook/3"
    }
}
