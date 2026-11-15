package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaClient
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaConnectionException
import io.github.hazymorning.amsel.api.QueueOption
import io.github.hazymorning.amsel.api.fixture
import io.github.hazymorning.amsel.data.Place
import io.github.hazymorning.amsel.data.Seeks
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayDispatcherTest {
    private class FakePhone : LocalOutput {
        var joined = CompletableDeferred<String>()
        val holds = mutableListOf<Boolean>()

        override val id: String? = null

        override fun hold(held: Boolean) {
            holds += held
        }

        override suspend fun awaitConnected(): String = joined.await()
    }

    /** How often a mix was about to start on This phone. */
    private var phoneMixes = 0

    /** The seeks the dispatcher handed on, for a book started at a chapter no start item leads to. */
    private val soughtIn = mutableListOf<Pair<String, Place>>()

    private fun withDispatcher(
        release: CountDownLatch,
        error: Int? = null,
        phone: LocalOutput = FakePhone(),
        seeks: Seeks = Seeks.None,
        /** How many songs the server's sample of a mix holds. */
        mixSongs: Int = 0,
        block: suspend CoroutineScope.(PlayDispatcher, FakeMaServer) -> Unit,
    ) = runBlocking {
        val sample = FakeMaServer.recorded("2.10", "music_playlists_playlist_tracks").getValue("result").jsonArray
        val fake = FakeMaServer(fixture("2.10", "server_info")["response"]!!) { command, _ ->
            when (command) {
                "auth" -> listOf(FakeMaServer.recorded("2.10", "auth"))

                "music/playlists/playlist_tracks" -> listOf(FakeMaServer.result(JsonArray(sample.take(mixSongs))))

                "player_queues/play_media" -> {
                    release.await(5, TimeUnit.SECONDS)
                    listOf(if (error != null) FakeMaServer.error(error, "busy") else FakeMaServer.result(null))
                }

                else -> listOf(FakeMaServer.result(null))
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MaClient(OkHttpClient(), scope)
        try {
            client.start(fake.address, "t")
            withTimeout(5000) { client.state.filterIsInstance<ConnectionState.Connected>().first() }
            val seekIn = { queueId: String, place: Place -> soughtIn += queueId to place }
            val dispatcher = PlayDispatcher(
                MaApi(client),
                phone,
                scope,
                seeks,
                seekIn,
                phoneMixStarts = { phoneMixes++ },
                elapsedRealtime = { 0L },
            )
            this.block(dispatcher, fake)
        } finally {
            client.stop()
            scope.cancel()
            fake.close()
        }
    }

    private fun FakeMaServer.playArgs() = received.filter {
        it["command"]!!.jsonPrimitive.content == "player_queues/play_media"
    }.map { it["args"]!!.jsonObject }

    private fun FakeMaServer.plays() = playArgs().map { it["media"]!!.jsonArray.first().jsonPrimitive.content }

    @Test fun `double taps are dropped and the latest waiting request wins`() {
        val release = CountDownLatch(1)
        withDispatcher(release) { dispatcher, fake ->
            val a = PlayRequest("kitchen", listOf("library://album/1"))
            dispatcher.play(a)
            delay(100)
            dispatcher.play(a)
            dispatcher.play(PlayRequest("kitchen", listOf("library://album/2")))
            dispatcher.play(PlayRequest("kitchen", listOf("library://album/3")))
            assertEquals("library://album/3", dispatcher.pending.value["kitchen"]?.request?.uris?.first())
            release.countDown()
            withTimeout(5000) { dispatcher.pending.first { it.isEmpty() } }
            assertEquals(listOf("library://album/1", "library://album/3"), fake.plays())
        }
    }

    @Test fun `every Add and Next waits its turn in order, and none shows as starting`() {
        val release = CountDownLatch(1)
        withDispatcher(release) { dispatcher, fake ->
            dispatcher.play(PlayRequest("kitchen", listOf("library://track/0"), QueueOption.Add))
            delay(100)
            assertEquals(emptyMap<String, PendingPlay>(), dispatcher.pending.value)
            dispatcher.play(PlayRequest("kitchen", listOf("library://album/1")))
            dispatcher.play(PlayRequest("kitchen", listOf("library://track/2"), QueueOption.Add))
            dispatcher.play(PlayRequest("kitchen", listOf("library://track/3"), QueueOption.Next))
            assertEquals("library://album/1", dispatcher.pending.value["kitchen"]?.request?.uris?.first())
            dispatcher.play(PlayRequest("kitchen", listOf("library://album/4")))
            release.countDown()
            withTimeout(5000) { dispatcher.pending.first { it.isEmpty() } }
            assertEquals(
                listOf("library://track/0", "library://track/2", "library://track/3", "library://album/4"),
                fake.plays(),
            )
        }
    }

    @Test fun `a play on This phone shows at once and goes out once the phone has joined`() {
        val phone = FakePhone()
        withDispatcher(CountDownLatch(0), phone = phone) { dispatcher, fake ->
            dispatcher.play(PlayRequest("phone-1", listOf("library://album/1"), onPhone = true))
            assertEquals("library://album/1", dispatcher.pending.value["phone-1"]?.request?.uris?.first())
            delay(100)
            assertEquals(emptyList<String>(), fake.plays())

            // The server may give the phone another id than the one remembered.
            phone.joined.complete("phone-2")
            withTimeout(5000) { dispatcher.pending.first { it.isEmpty() } }
            assertEquals(listOf("phone-2"), fake.playArgs().map { it["queue_id"]!!.jsonPrimitive.content })
            assertEquals(listOf(false), phone.holds)
        }
    }

    @Test fun `a mix is asked for its songs first and then replaces what plays`() {
        withDispatcher(CountDownLatch(0), mixSongs = 3) { dispatcher, fake ->
            val mix = "radio_playlist://playlist/library://album/3"
            dispatcher.play(PlayRequest("kitchen", listOf(mix), title = "Blue Hour"))
            assertEquals("Blue Hour", dispatcher.pending.value["kitchen"]?.request?.title)
            withTimeout(5000) { dispatcher.pending.first { it.isEmpty() } }
            val sent = fake.received.map { it.getValue("command").jsonPrimitive.content.substringAfterLast('/') }
            assertEquals(listOf("auth", "playlist_tracks", "repeat", "play_media"), sent)
            assertEquals(listOf(mix), fake.plays())
        }
    }

    @Test fun `a mix of nothing plays nothing, says so and offers no Retry`() {
        withDispatcher(CountDownLatch(0), mixSongs = 1) { dispatcher, fake ->
            val failure = async { dispatcher.failures.first() }
            delay(50)
            dispatcher.play(PlayRequest("kitchen", listOf("radio_playlist://playlist/library://track/5")))
            val failed = withTimeout(5000) { failure.await() }
            assertEquals(R.string.error_nothing_to_mix, failed.text)
            assertEquals(null, failed.retry)
            assertEquals(emptyList<String>(), fake.plays())
            withTimeout(5000) { dispatcher.pending.first { it.isEmpty() } }
        }
    }

    @Test fun `a mix on this phone is told of only once the server has songs for it`() {
        val mix = "radio_playlist://playlist/library://album/3"
        val joined = FakePhone().apply { joined.complete("phone-1") }
        withDispatcher(CountDownLatch(0), phone = joined, mixSongs = 3) { dispatcher, _ ->
            dispatcher.play(PlayRequest("phone-1", listOf(mix), onPhone = true))
            withTimeout(5000) { dispatcher.pending.first { it.isEmpty() } }
            assertEquals(1, phoneMixes)
            // On a speaker the phone's own playback is not the mix's business.
            dispatcher.play(PlayRequest("kitchen", listOf(mix)))
            withTimeout(5000) { dispatcher.pending.first { it.isEmpty() } }
            assertEquals(1, phoneMixes)
        }
        phoneMixes = 0
        withDispatcher(CountDownLatch(0), phone = joined, mixSongs = 1) { dispatcher, _ ->
            val failure = async { dispatcher.failures.first() }
            delay(50)
            dispatcher.play(PlayRequest("phone-1", listOf(mix), onPhone = true))
            withTimeout(5000) { failure.await() }
            assertEquals(0, phoneMixes)
        }
    }

    @Test fun `a phone that does not join fails the play, and Retry waits for it again`() {
        val phone = FakePhone().apply { joined.completeExceptionally(PhoneNotReadyException()) }
        withDispatcher(CountDownLatch(0), phone = phone) { dispatcher, fake ->
            val failure = async { dispatcher.failures.first() }
            delay(50)
            dispatcher.play(PlayRequest("phone-1", listOf("library://album/1"), onPhone = true))
            val failed = withTimeout(5000) { failure.await() }
            assertEquals(R.string.error_phone_not_ready, failed.text)
            assertEquals(emptyList<String>(), fake.plays())

            phone.joined = CompletableDeferred()
            failed.retry?.invoke()
            assertEquals("library://album/1", dispatcher.pending.value["phone-1"]?.request?.uris?.first())
            phone.joined.complete("phone-1")
            withTimeout(5000) { dispatcher.pending.first { it.isEmpty() } }
            assertEquals(listOf("library://album/1"), fake.plays())
        }
    }

    @Test fun `a book started at a chapter tells where it lands, on the id the phone joined with`() {
        val phone = FakePhone().apply { joined.complete("phone-2") }
        val told = FakeSeeks()
        withDispatcher(CountDownLatch(0), phone = phone, seeks = told) { dispatcher, fake ->
            val chapter25 = PlayRequest(
                "phone-1",
                listOf("library://audiobook/1"),
                startItem = "24",
                onPhone = true,
                startPositionMs = 960_000,
            )
            dispatcher.play(chapter25)
            withTimeout(5000) { dispatcher.pending.first { it.isEmpty() } }
            val landed = listOf("fly 1 phone-2 960.0 library://audiobook/1", "sent 1", "answered 1")
            assertEquals(landed, told.calls)
            assertEquals("24", fake.playArgs().single()["start_item"]!!.jsonPrimitive.content)
            // A play from the bookmark has no place of its own to land.
            dispatcher.play(PlayRequest("phone-1", listOf("library://audiobook/1"), onPhone = true))
            withTimeout(5000) { dispatcher.pending.first { it.isEmpty() } }
            assertEquals(landed, told.calls)
        }
    }

    @Test fun `a chapter no start item leads to is reached by starting the book and seeking`() {
        withDispatcher(CountDownLatch(0)) { dispatcher, fake ->
            dispatcher.play(PlayRequest("kitchen", listOf("library://audiobook/1"), startPositionMs = 960_400))
            withTimeout(5000) { dispatcher.pending.first { it.isEmpty() } }
            assertEquals(null, fake.playArgs().single()["start_item"])
            // Into the chapter's first second; the seek itself is the speaker's lane's to send.
            assertEquals(listOf("kitchen" to Place("library://audiobook/1", 961.0)), soughtIn)
        }
    }

    @Test fun `a chapter play that fails takes its place back`() {
        val told = FakeSeeks()
        withDispatcher(CountDownLatch(0), error = MaCommandException.ERROR_BUSY, seeks = told) { dispatcher, _ ->
            val failure = async { dispatcher.failures.first() }
            delay(50)
            dispatcher.play(
                PlayRequest("kitchen", listOf("library://audiobook/1"), startItem = "3", startPositionMs = 120_000),
            )
            withTimeout(5000) { failure.await() }
            assertEquals(listOf("fly 1 kitchen 120.0 library://audiobook/1", "sent 1", "failed 1"), told.calls)
        }
    }

    @Test fun `failures are reported in plain categories`() {
        val release = CountDownLatch(0)
        withDispatcher(release, error = MaCommandException.ERROR_BUSY) { dispatcher, _ ->
            val failure = async { dispatcher.failures.first() }
            delay(50)
            dispatcher.play(PlayRequest("kitchen", listOf("library://album/1")))
            assertEquals(R.string.error_speaker_busy, withTimeout(5000) { failure.await() }.text)
        }
    }

    @Test fun `failures read as plain sentences`() {
        fun refused(code: Int) = MaCommandException("players/cmd/play", code, "")
        assertEquals(R.string.error_phone_not_ready, failureText(PhoneNotReadyException()))
        assertEquals(R.string.error_not_connected, failureText(MaConnectionException("Not connected")))
        assertEquals(
            R.string.error_speaker_unavailable,
            failureText(refused(MaCommandException.ERROR_PLAYER_UNAVAILABLE)),
        )
        assertEquals(
            R.string.error_speaker_unavailable,
            failureText(refused(MaCommandException.ERROR_PLAYER_COMMAND_FAILED)),
        )
        assertEquals(
            R.string.error_not_allowed,
            failureText(refused(MaCommandException.ERROR_INSUFFICIENT_PERMISSIONS)),
        )
        assertEquals(R.string.error_generic, failureText(refused(999)))
        assertEquals(R.string.error_play_failed, failureText(refused(999), play = true))
    }
}
