package io.github.hazymorning.amsel.playback

import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaAuth
import io.github.hazymorning.amsel.api.MaClient
import io.github.hazymorning.amsel.api.MediaType
import io.github.hazymorning.amsel.api.QueueOption
import io.github.hazymorning.amsel.api.ServerAddress
import io.github.hazymorning.amsel.api.SignInResult
import io.github.hazymorning.amsel.api.allLibraryItems
import io.github.hazymorning.amsel.data.QueueState
import io.github.hazymorning.amsel.data.Seeks
import io.github.hazymorning.amsel.data.Speakers
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Seeks and pauses against a real Music Assistant (scripts/test-server with the fake speaker
 * "Kitchen"): the speakers' positions and the seek lane as the app wires them, on what the server
 * really reports.
 * Skipped unless MA_URL is set; CI runs it on both supported servers. MA_SEEK_URI names another item
 * to seek in (a long book, by hand); the default is the library's longest song.
 */
class LiveSeekTest {
    private val url = System.getenv("MA_URL")
    private val http = OkHttpClient()

    private class Rig(val api: MaApi, val speakers: Speakers, val commands: SpeakerCommands, val sent: AtomicInteger) {
        lateinit var kitchen: String
        var length = 0

        fun state(): QueueState? = speakers.queues.value[kitchen]

        fun shown(): Double = state()?.secondsAt(now()) ?: -1.0
    }

    private fun live(block: suspend (Rig) -> Unit) = runBlocking {
        assumeTrue("MA_URL not set", url != null)
        val address = ServerAddress.parse(url!!)!!
        val signIn = MaAuth(http).signIn(address, "amsel", "amsel-test-pw", "Amsel (live test)")
        assertTrue("sign in: $signIn", signIn is SignInResult.Success)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MaClient(http, scope)
        try {
            client.start(address, (signIn as SignInResult.Success).token)
            withTimeout(15_000) { client.state.filterIsInstance<ConnectionState.Connected>().first() }
            val api = MaApi(client)
            val speakers = Speakers(api, ::now, scope)
            scope.launch { client.events.collect(speakers::onEvent) }
            // Counts the seeks that go out, where the lane tells of each.
            val sent = AtomicInteger()
            val counted = object : Seeks by speakers {
                override fun sent(queueId: String, ticket: Long) {
                    sent.incrementAndGet()
                    speakers.sent(queueId, ticket)
                }
            }
            val dispatcher = PlayDispatcher(api, NoPhone, scope, elapsedRealtime = ::now)
            val commands = MaSpeakerCommands(api, dispatcher, {}, counted, NoPhone, scope, ::now)
            val rig = Rig(api, speakers, commands, sent)
            rig.kitchen = waitFor(60_000) {
                api.players().firstOrNull { it.shownName == "Kitchen" && it.available }?.playerId
            }
            val uri = System.getenv("MA_SEEK_URI")
                ?: api.allLibraryItems(MediaType.Track).maxBy { it.duration ?: 0 }.uri
            speakers.refresh()
            api.playMedia(rig.kitchen, listOf(uri), QueueOption.Replace)
            waitFor { rig.state()?.takeIf { it.queue.isPlaying && it.queue.currentItem != null } }
            // A book resumes where it was left: every test starts from the item's first seconds.
            api.seek(rig.kitchen, 1)
            delay(1_500)
            rig.length = rig.state()?.queue?.currentItem?.let { it.mediaItem?.duration ?: it.duration } ?: 0
            assertTrue("an item of 15 s or more to seek in, was ${rig.length}", rig.length >= 15)
            try {
                block(rig)
            } finally {
                api.stop(rig.kitchen)
            }
        } finally {
            client.stop()
            scope.cancel()
        }
    }

    /**
     * The server reports the old stream's time added to the target for a moment (2.10.9 and
     * 2.11.0b7): four seconds played, a seek to 10 s, and it says 14. That never shows.
     */
    @Test fun `a seek shows its target at once, stands there, and runs on from it`() = live { rig ->
        delay(3_000)
        val target = (rig.length / 2).coerceAtMost(600)
        val started = now()
        rig.commands.seek(rig.kitchen, target)
        assertEquals(target.toDouble(), rig.shown(), 0.0)
        assertTrue(rig.state()?.seeking == true)
        var stood = 0
        while (now() - started < 3_000) {
            rig.assertNear(target, since = started)
            if (rig.state()?.seeking == true) stood++
            delay(20)
        }
        assertTrue("it stood at the target before it ran on", stood > 0)
        waitFor { rig.state()?.takeIf { !it.seeking } }
        assertEquals(1, rig.sent.get())
        val there = rig.shown()
        delay(1_200)
        assertTrue("it runs on: $there, then ${rig.shown()}", rig.shown() > there + 0.8)
        rig.assertNear(target, since = started)
    }

    @Test fun `taps that follow one another reach the server as one seek`() = live { rig ->
        val taps = (System.getenv("MA_SEEK_TAPS")?.toIntOrNull() ?: 5)
        val gap = (System.getenv("MA_SEEK_GAP_MS")?.toLongOrNull() ?: 150)
        // Spread over the item: far apart in a long book, where each seek needs a new stream on the server.
        val step = ((rig.length - if (rig.length > 600) 60 else 6) / taps).coerceAtLeast(1)
        var target = 2
        var tapped = now()
        repeat(taps) {
            target += step
            tapped = now()
            rig.commands.seek(rig.kitchen, target, step = true)
            assertEquals(target.toDouble(), rig.shown(), 0.0)
            delay(gap)
        }
        waitFor { rig.state()?.takeIf { !it.seeking && rig.sent.get() > 0 } }
        delay(1_000)
        assertEquals(1, rig.sent.get())
        rig.assertNear(target, since = tapped)
    }

    @Test fun `a paused speaker is sent no seek, stays paused, and plays from where it was put`() = live { rig ->
        rig.commands.pause(rig.kitchen)
        waitFor { rig.state()?.takeIf { !it.queue.isPlaying } }
        val target = (rig.length / 2).coerceAtMost(900)
        rig.commands.seek(rig.kitchen, target - 2)
        rig.commands.seek(rig.kitchen, target, step = true)
        delay(SeekLane.SETTLE_MS + 1_500)
        assertEquals(0, rig.sent.get())
        assertFalse(rig.state()?.queue?.isPlaying == true)
        assertEquals(target.toDouble(), rig.shown(), 0.0)
        val played = now()
        rig.commands.play(rig.kitchen)
        assertEquals(1, rig.sent.get())
        waitFor { rig.state()?.takeIf { it.queue.isPlaying && !it.seeking } }
        rig.assertNear(target, since = played)
    }

    @Test fun `a seek and a pause right after it end paused at the place sought`() = live { rig ->
        val target = (rig.length / 2).coerceAtMost(1_200)
        val started = now()
        rig.commands.seek(rig.kitchen, target)
        rig.commands.pause(rig.kitchen)
        waitFor { rig.state()?.takeIf { !it.queue.isPlaying && !it.seeking } }
        delay(1_500)
        assertFalse(rig.state()?.queue?.isPlaying == true)
        rig.assertNear(target, since = started)
    }

    /**
     * The server takes a pause while it starts a play, and the speaker plays all the same (2.10.9
     * and 2.11.0b7): play, pause a moment later, and it played on.
     */
    @Test fun `a pause while the speaker starts to play again ends paused`() = live { rig ->
        rig.commands.pause(rig.kitchen)
        waitFor { rig.state()?.takeIf { !it.queue.isPlaying } }
        delay(1_000)
        coroutineScope {
            launch { rig.commands.play(rig.kitchen) }
            delay(150)
            rig.commands.pause(rig.kitchen)
        }
        delay(4_000)
        assertFalse("it plays on", rig.state()?.queue?.isPlaying == true)
    }

    @Test fun `a pause while a stopped speaker starts an item ends paused`() = live { rig ->
        val uri = checkNotNull(rig.state()?.queue?.currentItem?.mediaItem?.uri)
        rig.api.stop(rig.kitchen)
        waitFor { rig.state()?.takeIf { !it.queue.isPlaying } }
        delay(1_500)
        rig.commands.playMedia(PlayRequest(rig.kitchen, listOf(uri)))
        delay(150)
        rig.commands.pause(rig.kitchen)
        delay(4_000)
        assertFalse("it plays on", rig.state()?.queue?.isPlaying == true)
    }

    /** At [target], or past it by no more than the time [since] the seek was asked for: never the old stream's time on top. */
    private fun Rig.assertNear(target: Int, since: Long) {
        val shown = shown()
        val passed = (now() - since) / 1000.0
        assertTrue(
            "shown $shown, ${passed}s after a seek to $target",
            shown >= target && shown <= target + passed + 0.5,
        )
    }

    private suspend fun <T : Any> waitFor(timeoutMs: Long = 20_000, check: suspend () -> T?): T =
        withTimeout(timeoutMs) {
            var value = check()
            while (value == null) {
                delay(100)
                value = check()
            }
            value
        }

    private companion object {
        fun now(): Long = System.nanoTime() / 1_000_000
    }
}
