package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaClient
import io.github.hazymorning.amsel.api.fixture
import io.github.hazymorning.amsel.data.Seeks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After the 60 s idle close nothing else reopens the socket, so a transport command from the
 * lock screen and every car read must wake the session themselves.
 */
class WakeTest {
    private fun withClosedSocket(block: suspend (MaApi, wake: () -> Unit, FakeMaServer) -> Unit) = runBlocking {
        val fake = FakeMaServer(fixture("2.11", "server_info").getValue("response")) { command, _ ->
            listOf(
                if (command ==
                    "music/recently_played_items"
                ) {
                    FakeMaServer.recorded("2.11", "music_recently_played_items")
                } else {
                    FakeMaServer.result(null)
                },
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MaClient(OkHttpClient(), scope)
        try {
            assertEquals(ConnectionState.Idle, client.state.value)
            withTimeout(5_000) { block(MaApi(client), { client.start(fake.address, "token") }, fake) }
        } finally {
            client.stop()
            scope.cancel()
            fake.close()
        }
    }

    @Test fun aLockScreenCommandReopensTheSocket() = withClosedSocket { api, wake, fake ->
        val scope = CoroutineScope(Dispatchers.IO)
        val dispatcher = PlayDispatcher(api, NoPhone, scope) { 0L }
        val commands = MaSpeakerCommands(api, dispatcher, wake, Seeks.None, NoPhone, scope) { 0L }
        commands.next("kitchen")
        assertTrue(fake.received.any { it["command"].toString().contains("players/cmd/next") })
    }

    @Test fun aCarReadReopensTheSocket() = withClosedSocket { api, wake, _ ->
        val catalog = DataCatalog(api, wake, indexed = { false })
        assertTrue(catalog.recent(5).isNotEmpty())
    }
}

/** These commands go to speakers only. */
internal object NoPhone : LocalOutput {
    override val id: String? = null

    override fun hold(held: Boolean) = Unit

    override suspend fun awaitConnected(): String = throw PhoneNotReadyException()
}
