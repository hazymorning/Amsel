package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaJson
import io.github.hazymorning.amsel.api.ServerInfo
import io.github.hazymorning.amsel.api.fixture
import java.security.GeneralSecurityException
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The socket's lifecycle outside the app: a transport command from the notification or a car
 * library read must reopen a socket the idle close shut, and keep it only as long as it is used.
 */
@RunWith(RobolectricTestRunner::class)
class SessionTest {
    private val plainVault = object : TokenCipher {
        override fun seal(token: String) = token

        override fun open(sealed: String) = sealed
    }

    private fun withSession(
        authError: Int? = null,
        storedId: String? = null,
        network: FakePhoneNetwork = FakePhoneNetwork(),
        staleAfterMs: Long = Long.MAX_VALUE,
        block: suspend (Session, PrefsStore, FakeMaServer) -> Unit,
    ) = runBlocking {
        val fake =
            FakeMaServer(fixture("2.11", "server_info").getValue("response")) { command, _ ->
                if (authError != null && command == "auth") {
                    listOf(FakeMaServer.error(authError, "Refused"))
                } else {
                    listOf(FakeMaServer.result(null))
                }
            }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PrefsStore(RuntimeEnvironment.getApplication(), scope)
        prefs.saveServer(fake.address.toString(), storedId ?: fake.serverId, "Home", "token")
        // The preferences outlive a test: no network is checked from an earlier one.
        prefs.saveCheckedNetwork(null)
        val session = Session(prefs, plainVault, OkHttpClient(), scope, "Test", network, IDLE_MS, staleAfterMs)
        try {
            block(session, prefs, fake)
        } finally {
            session.client.stop()
            scope.cancel()
            fake.close()
        }
    }

    private suspend fun Session.awaitState(predicate: (ConnectionState) -> Boolean) =
        withTimeout(5_000) { client.state.first(predicate) }

    @Test fun `a refused token is forgotten, so a restart shows Setup`() =
        forgetsTheToken(MaCommandException.ERROR_INVALID_TOKEN)

    @Test fun `a server set up anew is signed out of, not retried`() =
        forgetsTheToken(MaCommandException.ERROR_SETUP_REQUIRED)

    private fun forgetsTheToken(error: Int) = withSession(authError = error) { session, prefs, _ ->
        withTimeout(5_000) { prefs.prefs.first { it?.sealedToken != null } }
        session.restore()
        session.setForeground(true)
        withTimeout(5_000) { session.state.first { it == SessionState.NeedsSetup } }
        withTimeout(5_000) { prefs.prefs.first { it?.sealedToken == null } }
    }

    @Test fun `a server set up anew where it answered before is signed out of, as for a refused token`() =
        withSession(storedId = "before-the-new-setup") { session, prefs, fake ->
            // It answered as itself on this network in an earlier run of the app.
            prefs.saveCheckedNetwork(FakePhoneNetwork.HOME.text)
            session.restore()
            session.setForeground(true)
            withTimeout(5_000) { session.state.first { it == SessionState.NeedsSetup } }
            withTimeout(5_000) { prefs.prefs.first { it?.sealedToken == null } }
            assertTrue(fake.received.toString(), fake.received.isEmpty())
        }

    @Test fun `another server at the address on another network gets no token, and the session stays`() =
        withSession(storedId = "ours", network = FakePhoneNetwork(FakePhoneNetwork.CAFE)) { session, prefs, fake ->
            prefs.saveCheckedNetwork(FakePhoneNetwork.HOME.text)
            session.restore()
            session.setForeground(true)
            withTimeout(5_000) { while (fake.server.requestCount < 2) delay(20) }
            assertEquals(SessionState.Offline("Home"), session.state.value)
            assertEquals("token", prefs.current().sealedToken)
            assertTrue(fake.received.toString(), fake.received.isEmpty())
        }

    @Test fun `the network where the server answered as itself is home until the phone moves`() {
        val network = FakePhoneNetwork()
        withSession(network = network) { session, _, fake ->
            session.restore()
            // Closed in the background: the server is asked for its first frame alone.
            assertTrue(session.reachesHome())
            withTimeout(5_000) { session.checkedHere.first { it } }
            assertTrue(session.reachesHome())
            assertEquals(1, fake.server.requestCount)
            network.current.value = FakePhoneNetwork.CAFE
            withTimeout(5_000) { session.checkedHere.first { !it } }
            assertTrue(session.reachesHome())
            assertEquals(2, fake.server.requestCount)
            assertTrue(fake.received.toString(), fake.received.isEmpty())
        }
    }

    @Test fun `plain http never goes home where another server answers`() =
        withSession(storedId = "ours") { session, _, fake ->
            session.restore()
            assertFalse(session.reachesHome())
            assertFalse(session.checkedHere.value)
            assertTrue(fake.received.isEmpty())
        }

    @Test fun `a wake opens the closed socket and the idle close shuts it again`() = withSession { session, _, _ ->
        session.restore()
        // In the background with nothing playing, restoring does not connect.
        delay(100)
        assertEquals(ConnectionState.Idle, session.client.state.value)

        session.wake()
        session.awaitState { it is ConnectionState.Connected }
        session.awaitState { it == ConnectionState.Idle }
    }

    @Test fun `a wake before the restore connects once the session is known`() = withSession { session, _, _ ->
        // Android Auto can bind the service in a cold process and browse before the restore ends.
        session.wake()
        session.restore()
        session.awaitState { it is ConnectionState.Connected }
    }

    @Test fun `a wake does not close the socket while the app is in front`() = withSession { session, _, _ ->
        session.restore()
        session.setForeground(true)
        session.awaitState { it is ConnectionState.Connected }
        session.wake()
        delay(IDLE_MS * 2)
        assertEquals(true, session.client.state.value is ConnectionState.Connected)
    }

    @Test fun `music paused on the phone keeps the socket open in the background`() = withSession { session, _, _ ->
        // Closed, the phone's player leaves the server, which drops its queue and brings back an old save.
        session.restore()
        session.setHolding(true)
        session.awaitState { it is ConnectionState.Connected }
        delay(IDLE_MS * 2)
        assertEquals(true, session.client.state.value is ConnectionState.Connected)
        session.setHolding(false)
        session.awaitState { it == ConnectionState.Idle }
    }

    @Test fun `a speaker heard playing keeps the socket only until the server has been out of reach a while`() =
        withSession(staleAfterMs = STALE_MS) { session, _, fake ->
            session.restore()
            session.setPlaying(true)
            session.setHolding(true)
            session.awaitState { it is ConnectionState.Connected }
            leaveHome(fake)
            session.awaitState { it == ConnectionState.Offline }
            session.awaitState { it == ConnectionState.Idle }
            assertTrue(session.stale.value)
            val tries = fake.server.requestCount
            delay(STALE_MS + IDLE_MS)
            assertEquals(tries, fake.server.requestCount)
        }

    @Test fun `This phone's own audio keeps the socket while the server is out of reach`() =
        withSession(staleAfterMs = STALE_MS) { session, _, fake ->
            session.restore()
            session.setAudible(true)
            session.awaitState { it is ConnectionState.Connected }
            leaveHome(fake)
            withTimeout(5_000) { session.stale.first { it } }
            delay(IDLE_MS * 2)
            assertEquals(ConnectionState.Offline, session.client.state.value)
            session.setAudible(false)
            session.awaitState { it == ConnectionState.Idle }
        }

    @Test fun `a socket closed while the server was out of reach opens again when a network comes`() =
        withSession(staleAfterMs = STALE_MS) { session, _, fake ->
            session.restore()
            session.setHolding(true)
            session.awaitState { it is ConnectionState.Connected }
            val home = leaveHome(fake)
            session.awaitState { it == ConnectionState.Idle }
            fake.server.dispatcher = home
            session.reconnect()
            session.awaitState { it is ConnectionState.Connected }
            withTimeout(5_000) { session.stale.first { !it } }
            // Closed with nothing to keep it, it waits for the next use.
            session.setHolding(false)
            session.awaitState { it == ConnectionState.Idle }
            val requests = fake.server.requestCount
            session.reconnect()
            assertEquals(ConnectionState.Idle, session.client.state.value)
            assertEquals(requests, fake.server.requestCount)
        }

    @Test fun `a socket that gave up on a speaker playing opens again on a new network by itself`() {
        val network = FakePhoneNetwork()
        withSession(network = network, staleAfterMs = STALE_MS) { session, _, fake ->
            session.restore()
            session.setPlaying(true)
            session.awaitState { it is ConnectionState.Connected }
            val home = leaveHome(fake)
            session.awaitState { it == ConnectionState.Idle }
            assertFalse(session.upToDate.value)
            fake.server.dispatcher = home
            network.current.value = FakePhoneNetwork.CAFE
            session.awaitState { it is ConnectionState.Connected }
            assertTrue(session.upToDate.value)
        }
    }

    @Test fun `a socket nothing wants stays closed when a network comes, away from home too`() {
        val network = FakePhoneNetwork()
        withSession(network = network, staleAfterMs = STALE_MS) { session, _, fake ->
            session.restore()
            session.setHolding(true)
            session.awaitState { it is ConnectionState.Connected }
            leaveHome(fake)
            session.awaitState { it == ConnectionState.Idle }
            session.setHolding(false)
            val tries = fake.server.requestCount
            network.current.value = FakePhoneNetwork.CAFE
            session.tryAgain()
            delay(IDLE_MS)
            assertEquals(ConnectionState.Idle, session.client.state.value)
            assertEquals(tries, fake.server.requestCount)
        }
    }

    /** A widget's look: the server is read and let go, and nothing the app does on a connect comes with it. */
    @Test fun `a glance reads the server on a socket of its own and leaves the app's closed`() =
        withSession { session, _, fake ->
            session.restore()
            assertFalse(session.upToDate.value)
            assertEquals(true, session.readOnOwnSocket { api -> api.serverTime() == null })
            assertEquals(listOf("auth", "time"), fake.received.map { it["command"]?.jsonPrimitive?.content })
            assertEquals(ConnectionState.Idle, session.client.state.value)
            assertTrue(session.upToDate.value)
        }

    @Test fun `a glance that cannot reach the server says what it last said no longer holds`() =
        withSession { session, _, fake ->
            session.restore()
            session.setForeground(true)
            session.awaitState { it is ConnectionState.Connected }
            session.setForeground(false)
            session.awaitState { it == ConnectionState.Idle }
            // Closed on purpose: what the server said still holds.
            assertTrue(session.upToDate.value)
            leaveHome(fake)
            assertEquals(null, session.readOnOwnSocket { true })
            assertFalse(session.upToDate.value)
        }

    @Test fun `a network that keeps coming and going does not put off the close`() =
        withSession(staleAfterMs = STALE_MS) { session, _, fake ->
            session.restore()
            session.setHolding(true)
            session.awaitState { it is ConnectionState.Connected }
            leaveHome(fake)
            withTimeout(5_000) { session.stale.first { it } }
            repeat(10) {
                if (session.client.state.value != ConnectionState.Idle) session.reconnect()
                delay(IDLE_MS / 5)
            }
            assertEquals(ConnectionState.Idle, session.client.state.value)
        }

    /** The phone leaves home: the server's address answers nothing a socket can use. Returns home's answers. */
    private fun leaveHome(fake: FakeMaServer): Dispatcher {
        val home = fake.server.dispatcher
        fake.server.dispatcher = Unreachable
        fake.dropConnections()
        return home
    }

    private object Unreachable : Dispatcher() {
        override fun dispatch(request: RecordedRequest) = MockResponse(code = 503)
    }

    @Test fun `signing in again after the server refused the token connects`() = runBlocking<Unit> {
        val info = fixture("2.11", "server_info").getValue("response")
        val fake = FakeMaServer(info) { command, args ->
            val refused = command == "auth" && (args?.get("token") as? JsonPrimitive)?.content == "refused"
            when {
                refused -> listOf(FakeMaServer.error(MaCommandException.ERROR_INVALID_TOKEN, "Invalid token"))

                command == "auth/login" -> listOf(
                    FakeMaServer.result(
                        buildJsonObject {
                            put("success", true)
                            put("access_token", "short")
                        },
                    ),
                )

                command == "auth/token/create" -> listOf(FakeMaServer.result(JsonPrimitive("fresh")))

                else -> listOf(FakeMaServer.result(null))
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PrefsStore(RuntimeEnvironment.getApplication(), scope)
        prefs.saveServer(fake.address.toString(), fake.serverId, "Home", "refused")
        val session = Session(prefs, plainVault, OkHttpClient(), scope, "Test", FakePhoneNetwork())
        try {
            session.restore()
            session.setForeground(true)
            // Setup, as the app shows it: the refused socket itself is stopped again at once.
            withTimeout(5_000) { session.state.first { it == SessionState.NeedsSetup } }
            session.signIn(fake.address, MaJson.decodeFromJsonElement(ServerInfo.serializer(), info), "anna", "pw")
            session.awaitState { it is ConnectionState.Connected }
        } finally {
            session.client.stop()
            scope.cancel()
            fake.close()
        }
    }

    @Test fun `a fresh token that cannot be stored is revoked, not left live`() = runBlocking<Unit> {
        val now = System.currentTimeMillis() / 1000
        val old = jwt("old", exp = now + 10 * 24 * 3600)
        val fresh = jwt("fresh", exp = now + 365 * 24 * 3600)
        val fake = FakeMaServer(fixture("2.11", "server_info").getValue("response")) { command, _ ->
            if (command == "auth/token/create") {
                listOf(FakeMaServer.result(JsonPrimitive(fresh)))
            } else {
                listOf(FakeMaServer.result(null))
            }
        }
        val noRoom = object : TokenCipher {
            override fun seal(token: String) = if (token == fresh) throw GeneralSecurityException("Keystore") else token

            override fun open(sealed: String) = sealed
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PrefsStore(RuntimeEnvironment.getApplication(), scope)
        prefs.saveServer(fake.address.toString(), fake.serverId, "Home", old)
        val session = Session(prefs, noRoom, OkHttpClient(), scope, "Test", FakePhoneNetwork())
        try {
            session.restore()
            session.setForeground(true)
            withTimeout(5_000) {
                while (fake.received.none { it["command"]?.jsonPrimitive?.content == "auth/token/revoke" }) delay(20)
            }
            assertEquals(JsonPrimitive("fresh"), fake.args("auth/token/revoke")["token_id"])
            assertEquals(old, prefs.current().sealedToken)
        } finally {
            session.client.stop()
            scope.cancel()
            fake.close()
        }
    }

    /** A long-lived token [id] that expires at [exp]; only its claims are read. */
    private fun jwt(id: String, exp: Long): String {
        val claims = """{"jti":"$id","exp":$exp,"is_long_lived":true}"""
        return "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".s"
    }

    private companion object {
        const val IDLE_MS = 1_000L
        const val STALE_MS = 1_000L
    }
}
