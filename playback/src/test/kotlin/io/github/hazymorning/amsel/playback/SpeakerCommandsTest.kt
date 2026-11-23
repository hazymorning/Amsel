package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaClient
import io.github.hazymorning.amsel.api.fixture
import io.github.hazymorning.amsel.data.Holding
import io.github.hazymorning.amsel.data.Place
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a speaker's commands send to the server, from the app, the notification, a headset or the car. */
class SpeakerCommandsTest {
    private val seeks = FakeSeeks(Holding(BOOK, playing = true))

    private class Phone(override val id: String?) : LocalOutput {
        val joined = CompletableDeferred<String>()
        val holds = mutableListOf<Boolean>()

        override fun hold(held: Boolean) {
            holds += held
        }

        override suspend fun awaitConnected(): String = joined.await()
    }

    /** The server holds its answer to these commands, as it does while it starts a play, until [answer]. */
    private var held = emptySet<String>()
    private var server: FakeMaServer? = null

    /** The server answers the [command] it held: the play has started. */
    private fun answer(command: String) {
        val fake = checkNotNull(server)
        val asked = fake.received.last { it["command"]?.jsonPrimitive?.content == command }
        fake.push(JsonObject(FakeMaServer.result(null) + ("message_id" to asked.getValue("message_id"))))
    }

    /** Runs [block] against a fake server; what it was sent, as "seek kitchen 985" and "play kitchen". */
    private fun withCommands(
        phone: LocalOutput = NoPhone,
        block: suspend CoroutineScope.(MaSpeakerCommands, sent: () -> List<String>) -> Unit,
    ) = runBlocking {
        val fake = FakeMaServer(fixture("2.11", "server_info").getValue("response")) { command, _ ->
            when (command) {
                "auth" -> listOf(FakeMaServer.recorded("2.11", "auth"))
                in held -> emptyList()
                else -> listOf(FakeMaServer.result(null))
            }
        }
        server = fake
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MaClient(OkHttpClient(), scope)
        try {
            client.start(fake.address, "t", fake.serverId)
            withTimeout(5000) { client.state.filterIsInstance<ConnectionState.Connected>().first() }
            val api = MaApi(client)
            val now = { System.nanoTime() / 1_000_000 }
            val lanes = SeekLanes(api, {}, seeks, phone, scope, now)
            val dispatcher = PlayDispatcher(api, phone, scope, starting = lanes::starting, elapsedRealtime = now)
            val commands = MaSpeakerCommands(api, dispatcher, {}, seeks, lanes, scope)
            block(commands) { fake.received.mapNotNull(::sentAs) }
        } finally {
            client.stop()
            scope.cancel()
            fake.close()
        }
    }

    /** A command the server received, as "seek kitchen 985"; null for one these tests do not look at. */
    private fun sentAs(message: JsonObject): String? {
        val args = message["args"]?.jsonObject
        val id = args?.get("player_id")?.jsonPrimitive?.content
        val media = args?.get("media")?.jsonArray?.single()?.jsonPrimitive?.content
        return when (message["command"]?.jsonPrimitive?.content) {
            "players/cmd/seek" -> "seek $id " + args?.get("position")?.jsonPrimitive?.content
            "players/cmd/play" -> "play $id"
            "players/cmd/pause" -> "pause $id"
            "player_queues/play_media" -> "play_media $media"
            else -> null
        }
    }

    private suspend fun (() -> List<String>).await(count: Int): List<String> = withTimeout(5000) {
        while (this@await().size < count) delay(10)
        this@await()
    }

    @Test fun aSeekGoesToTheSpeakerAndSaysWhereItGoesFirst() = withCommands { commands, sent ->
        commands.seek("kitchen", 985)
        assertEquals("fly 1 kitchen 985.0 $BOOK", seeks.calls.first())
        assertEquals(listOf("seek kitchen 985"), sent.await(1))
    }

    /** As the official app: a play is a play; the server knows where its queue is. */
    @Test fun aPlayIsAPlay() = withCommands { commands, sent ->
        commands.play("kitchen")
        assertEquals(listOf("play kitchen"), sent())
    }

    /** Except where the queue was put somewhere the server does not know of: there the play is a seek. */
    @Test fun aPlayStartsAParkedQueueWhereItWasPut() = withCommands { commands, sent ->
        seeks.holding = Holding(BOOK, playing = false)
        seeks.parked = Place(BOOK, 7230.6)
        commands.play("kitchen")
        assertEquals(listOf("seek kitchen 7230"), sent())
    }

    @Test fun aPausedSpeakerIsSentNoSeek() = withCommands { commands, sent ->
        commands.pause("kitchen")
        commands.seek("kitchen", 300)
        commands.seek("kitchen", 330, step = true)
        delay(SeekLane.SETTLE_MS + 300)
        assertEquals(listOf("pause kitchen"), sent())
        assertEquals(Place(BOOK, 330.0), seeks.parked)
    }

    /**
     * The server takes a pause while it starts a play, and the speaker plays all the same (2.10.9
     * and 2.11.0b7, live): once it has started, the pause goes out again.
     */
    @Test fun aPauseWhileAPlayStartsIsSentAgainOnceItHasStarted() {
        held = setOf("players/cmd/play")
        withCommands { commands, sent ->
            val play = launch { commands.play("kitchen") }
            sent.await(1)
            commands.pause("kitchen")
            delay(300)
            assertEquals(listOf("play kitchen", "pause kitchen"), sent())
            answer("players/cmd/play")
            assertEquals(listOf("play kitchen", "pause kitchen", "pause kitchen"), sent.await(3))
            play.join()
        }
    }

    @Test fun aPauseWhileAPlayFromAListStartsIsSentAgainOnceItHasStarted() {
        held = setOf("player_queues/play_media")
        withCommands { commands, sent ->
            commands.playMedia(PlayRequest("kitchen", listOf(BOOK)))
            sent.await(1)
            commands.pause("kitchen")
            delay(300)
            assertEquals(listOf("play_media $BOOK", "pause kitchen"), sent())
            answer("player_queues/play_media")
            assertEquals(listOf("play_media $BOOK", "pause kitchen", "pause kitchen"), sent.await(3))
        }
    }

    /** Paused, then something else tapped before the first had started: that one plays. */
    @Test fun aPlayFromAListAskedSinceLetsThatPauseGo() {
        held = setOf("player_queues/play_media")
        withCommands { commands, sent ->
            commands.playMedia(PlayRequest("kitchen", listOf(BOOK)))
            sent.await(1)
            commands.pause("kitchen")
            commands.playMedia(PlayRequest("kitchen", listOf(OTHER)))
            answer("player_queues/play_media")
            sent.await(3)
            delay(300)
            assertEquals(listOf("play_media $BOOK", "pause kitchen", "play_media $OTHER"), sent())
        }
    }

    /** A play someone else asked for, or one from Up next: the server says that it starts one. */
    @Test fun aPauseWhileTheServerSaysItStartsAPlayIsSentAgainOnceThatIsOver() = withCommands { commands, sent ->
        val starting = CompletableDeferred<Unit>()
        seeks.holding = Holding(BOOK, playing = true, starting = true)
        seeks.starting = starting
        commands.pause("kitchen")
        delay(300)
        assertEquals(listOf("pause kitchen"), sent())
        starting.complete(Unit)
        assertEquals(listOf("pause kitchen", "pause kitchen"), sent.await(2))
    }

    @Test fun aPauseOfASpeakerThatPlaysIsOnePause() = withCommands { commands, sent ->
        commands.pause("kitchen")
        delay(300)
        assertEquals(listOf("pause kitchen"), sent())
    }

    /** The server forgets a phone that left: its seek waits for the join, goes to the id it joined with, and is heard. */
    @Test fun aSeekForThisPhoneWaitsUntilItHasJoinedAndReleasesItsOutput() {
        val phone = Phone("phone-1")
        withCommands(phone) { commands, sent ->
            commands.seek("phone-1", 120)
            delay(200)
            assertEquals(emptyList<String>(), sent())
            phone.joined.complete("phone-1")
            assertEquals(listOf("seek phone-1 120"), sent.await(1))
            assertEquals(listOf(false), phone.holds)
        }
    }

    private companion object {
        const val BOOK = "library://audiobook/1"
        const val OTHER = "library://album/2"
    }
}
