package io.github.hazymorning.amsel.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue

/** Set by scripts/test-server/live.sh. */
internal val liveUrl: String? = System.getenv("MA_URL")

internal fun live(block: suspend (MaApi, ServerInfo) -> Unit) = runBlocking {
    assumeTrue("MA_URL not set", liveUrl != null)
    val address = ServerAddress.parse(liveUrl!!)!!
    val http = OkHttpClient()
    val auth = MaAuth(http)
    val probe = auth.probe(address)
    val id = probe.info.serverId
    val signIn = auth.signIn(address, id, "amsel", "amsel-test-pw", "Amsel (live test)")
    assertTrue("sign in: $signIn", signIn is SignInResult.Success)
    assertEquals(SignInResult.WrongPassword, auth.signIn(address, id, "amsel", "not-the-password", "Amsel (live test)"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val client = MaClient(http, scope)
    try {
        client.start(address, (signIn as SignInResult.Success).token, id)
        withTimeout(15_000) { client.state.filterIsInstance<ConnectionState.Connected>().first() }
        block(MaApi(client), probe.info)
    } finally {
        client.stop()
        scope.cancel()
    }
}

internal suspend fun MaApi.queue(queueId: String): PlayerQueue? = queues().firstOrNull { it.queueId == queueId }

/** The fake speakers appear only seconds after they connect. See docs/API-NOTES.md, "Test server recipe". */
internal suspend fun waitForSpeakers(api: MaApi): List<Player> = waitFor(60_000) {
    api.players().filter {
        it.isShownSpeaker(null) && it.available
    }.takeIf { list -> list.map { it.shownName }.containsAll(listOf("Kitchen", "Living Room")) }
}

internal suspend fun <T : Any> waitFor(timeoutMs: Long = 20_000, check: suspend () -> T?): T = withTimeout(timeoutMs) {
    var value = check()
    while (value == null) {
        delay(500)
        value = check()
    }
    value
}
