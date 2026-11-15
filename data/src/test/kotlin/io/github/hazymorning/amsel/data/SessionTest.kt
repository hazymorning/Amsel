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
    private val plainVault = object : TokenVault {
        override fun seal(token: String) = token

        override fun open(sealed: String) = sealed
    }

    private fun withSession(
        authError: Int? = null,
        storedId: String? = null,
        network: FakePhoneNetwork = FakePhoneNetwork(),
        block: suspend (Session, AppPrefs, FakeMaServer) -> Unit,
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
        val prefs = AppPrefs(RuntimeEnvironment.getApplication(), scope)
        prefs.saveServer(fake.address.toString(), storedId ?: fake.serverId, "Home", "token")
        // The preferences outlive a test: no network is checked from an earlier one.
        prefs.saveCheckedNetwork(null)
        val session = Session(prefs, plainVault, OkHttpClient(), scope, "Test", network, idleCloseMs = IDLE_MS)
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
        val prefs = AppPrefs(RuntimeEnvironment.getApplication(), scope)
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
        val noRoom = object : TokenVault {
            override fun seal(token: String) = if (token == fresh) throw GeneralSecurityException("Keystore") else token

            override fun open(sealed: String) = sealed
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = AppPrefs(RuntimeEnvironment.getApplication(), scope)
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
    }
}
