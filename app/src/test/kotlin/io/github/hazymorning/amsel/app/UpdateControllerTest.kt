package io.github.hazymorning.amsel.app

import io.github.hazymorning.amsel.R
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** An update downloads in the app and opens Android's installer on it, as Schmeckts does. */
class UpdateControllerTest {
    @get:Rule val temp = TemporaryFolder()

    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Test fun anUpdateDownloadsAndOpensTheInstaller() = updating(github()) {
        val found = find()
        updates.install(found)
        assertArrayEquals(apk, opened.receive().readBytes())
        assertEquals(found, updates.state.value)
    }

    @Test fun theMessageAtStartInstallsAtATap() = updating(github()) {
        updates.checkAtStart { true }
        val offer = messages.pending.first { it.isNotEmpty() }.single()
        assertEquals(R.string.update_out, offer.text)
        checkNotNull(offer.action).run()
        assertArrayEquals(apk, opened.receive().readBytes())
    }

    @Test fun aDownloadCutShortOpensNothingAndOffersAnotherTry() = updating(github(file = apk.copyOf(1_000))) {
        updates.install(find())
        val said = messages.pending.first { it.isNotEmpty() }.single()
        assertEquals(R.string.update_not_downloaded, said.text)
        assertNotNull(said.retry)
        assertTrue(opened.tryReceive().isFailure)
        assertFalse(temp.root.resolve("update/amsel.apk").exists())
    }

    @Test fun aDownloadFromAnywhereButAmselsReleasesIsNeverFetched() =
        updating(github(release(url = "https://example.com/amsel.apk"))) {
            updates.install(find())
            assertEquals(R.string.update_not_downloaded, messages.pending.first { it.isNotEmpty() }.single().text)
            assertTrue(asked.none { it.startsWith("https://example.com") })
            assertTrue(opened.tryReceive().isFailure)
        }

    @Test fun aReleaseAddressThatLeadsElsewhereIsNeverFetched() = updating(
        // Its text starts as Amsel's releases do; resolved, it is another repository's.
        github(release(url = "$RELEASES../../../../evil/r/releases/download/v1/x.apk")),
    ) {
        updates.install(find())
        assertEquals(R.string.update_not_downloaded, messages.pending.first { it.isNotEmpty() }.single().text)
        assertTrue(asked.none { "/evil/" in it })
        assertTrue(opened.tryReceive().isFailure)
    }

    @Test fun anUpdateNotSignedAsAmselIsNeverInstalled() = updating(github(), ours = false) {
        updates.install(find())
        assertEquals(R.string.update_not_ours, messages.pending.first { it.isNotEmpty() }.single().text)
        assertTrue(opened.tryReceive().isFailure)
        assertFalse(temp.root.resolve("update/amsel.apk").exists())
    }

    @Test fun theInstallerWaitsUntilAmselIsInFront() = updating(github(), inFront = false) {
        val found = find()
        updates.install(found)
        updates.state.first { it == found }
        delay(200)
        assertTrue(opened.tryReceive().isFailure)
        front.value = true
        assertArrayEquals(apk, opened.receive().readBytes())
    }

    @Test fun anotherTapAfterTheInstallerWasLeftOpensItWithoutDownloadingAgain() = updating(github()) {
        val found = find()
        updates.install(found)
        opened.receive()
        updates.install(found)
        opened.receive()
        assertEquals(1, asked.count { it == ASSET })
    }

    @Test fun whatTheLastRunDownloadedGoesAtTheNextStart() = updating(github()) {
        val left = temp.root.resolve("update/amsel.apk").apply {
            parentFile?.mkdirs()
            writeBytes(apk)
        }
        updates.checkAtStart { false }
        while (left.exists()) delay(10)
    }

    private class Run(
        val updates: UpdateController,
        val front: MutableStateFlow<Boolean>,
        val messages: MessageController,
        val opened: Channel<File>,
    ) {
        suspend fun find(): UpdateState.Available {
            updates.checkNow()
            return updates.state.first { it is UpdateState.Available } as UpdateState.Available
        }
    }

    private fun updating(
        http: OkHttpClient,
        inFront: Boolean = true,
        ours: Boolean = true,
        block: suspend Run.() -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val front = MutableStateFlow(inFront)
        val messages = MessageController(MutableStateFlow(true))
        val opened = Channel<File>(Channel.UNLIMITED)
        val updates =
            UpdateController(http, "2026.11.33", messages, scope, front, temp.root.resolve("update"), { ours }) {
                opened.trySend(it).isSuccess
            }
        try {
            withTimeout(10_000) { Run(updates, front, messages, opened).block() }
        } finally {
            scope.cancel()
        }
    }

    /** GitHub, played by an interceptor: [release] at its API, [file] at the release's download. */
    private fun github(release: String = release(), file: ByteArray = apk): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val url = chain.request().url.toString()
            asked += url
            val body = if (url == ASSET) file.toResponseBody(APK) else release.toResponseBody(JSON)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
        }
        .build()

    private fun release(url: String = ASSET) =
        """{"tag_name":"v2026.12.1","assets":[{"name":"amsel-v2026.12.1.apk","browser_download_url":"$url",""" +
            """"size":${apk.size}}]}"""

    private companion object {
        const val RELEASES = "https://github.com/hazymorning/Amsel/releases/download/"
        const val ASSET = "${RELEASES}v2026.12.1/amsel-v2026.12.1.apk"
        val APK = "application/vnd.android.package-archive".toMediaType()
        val JSON = "application/json".toMediaType()
    }

    /** Counted from 1.0 now: the versions counted by the calendar before come before every one since. */
    @Test fun numbersSince1Point0ComeAfterTheCalendarsVersions() {
        assertTrue(isNewer("1.0", "2026.11.57"))
        assertFalse(isNewer("2026.11.57", "1.0"))
        assertTrue(isNewer("1.1", "1.0"))
        assertTrue(isNewer("1.10", "1.9"))
        assertTrue(isNewer("2026.11.57", "2026.11.9"))
        assertFalse(isNewer("1.0", "1.0"))
    }
}
