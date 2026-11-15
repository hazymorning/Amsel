package io.github.hazymorning.amsel.ui.screens.player

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.hazymorning.amsel.api.ConnectionState
import io.github.hazymorning.amsel.api.FakeMaServer
import io.github.hazymorning.amsel.api.MaApi
import io.github.hazymorning.amsel.api.MaClient
import io.github.hazymorning.amsel.api.MaCommandException
import io.github.hazymorning.amsel.api.MaEvent
import io.github.hazymorning.amsel.api.fixture
import io.github.hazymorning.amsel.app.Messages
import io.github.hazymorning.amsel.app.UpNextEdit
import io.github.hazymorning.amsel.data.SessionState
import io.github.hazymorning.amsel.data.Speakers
import io.github.hazymorning.amsel.ui.mapping.UiMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpNextViewModelTest {
    @Before fun main() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After fun reset() = Dispatchers.resetMain()

    @Test fun `loads, moves and removes with the server catching up`() = runBlocking {
        val fake = FakeMaServer(fixture("2.10", "server_info")["response"]!!) { command, _ ->
            when (command) {
                "auth" -> listOf(FakeMaServer.recorded("2.10", "auth"))
                "player_queues/items" -> listOf(FakeMaServer.recorded("2.10", "player_queues_items__after_add"))
                else -> listOf(FakeMaServer.result(null))
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MaClient(OkHttpClient(), scope)
        // Cleared at the end, so the ViewModel's scope and its stop timeout end with the test.
        val store = ViewModelStore()
        try {
            client.start(fake.address, "t", fake.serverId)
            withTimeout(5000) { client.state.filterIsInstance<ConnectionState.Connected>().first() }
            val api = MaApi(client)
            val beforePlay = mutableListOf<String>()
            val sheet = MutableSharedFlow<UpNextEdit>(extraBufferCapacity = 1)
            val factory = viewModelFactory {
                initializer {
                    UpNextViewModel(
                        "kitchen",
                        api,
                        Speakers(api, { 0L }),
                        MutableStateFlow(SessionState.Waiting(null)),
                        UiMapper { null },
                        Messages(MutableStateFlow(true)),
                        scope,
                        beforePlay::add,
                        sheet,
                        queueNext = {},
                    )
                }
            }
            val vm = ViewModelProvider.create(store, factory)[UpNextViewModel::class]
            // Shown: Up next loads while someone collects it.
            val shown = launch(Dispatchers.Unconfined) { vm.state.collect {} }
            val loaded = withTimeout(5000) { vm.state.first { it.items.isNotEmpty() } }
            assertFalse(loaded.loading)
            val ids = loaded.items.map { it.id }

            // Each edit shows before the server answers. The load resumed on the network's thread,
            // which may still be handing the state on, so it is awaited rather than read at once.
            suspend fun shows(seen: (UpNextUiState) -> Boolean) = withTimeout(5000) { vm.state.first(seen) }
            vm.move(ids.last(), 0)
            shows { it.items.first().id == ids.last() }
            vm.remove(ids.first())
            shows { state -> state.items.none { it.id == ids.first() } }
            // The ⋮ sheet's Remove from Up next shows at once too, made by Up next itself.
            sheet.emit(UpNextEdit.Remove(ids[2]))
            shows { state -> state.items.none { it.id == ids[2] } }
            withTimeout(5000) {
                while (fake.received.none {
                        it["command"]!!.jsonPrimitive.content == "player_queues/delete_item"
                    }
                ) {
                    kotlinx.coroutines.delay(50)
                }
            }
            val move = fake.received.first { it["command"]!!.jsonPrimitive.content == "player_queues/move_item" }
            assertEquals(-(ids.size - 1), move["args"]!!.jsonObject["pos_shift"]!!.jsonPrimitive.content.toInt())
            // Tapping a song releases This phone's hold before the server starts it.
            vm.play(ids[1])
            assertEquals(listOf("kitchen"), beforePlay)
            // The tapped row stays current until the server reports it, whatever else the queue says.
            assertEquals(ids[1], shows { state -> state.items.any { it.isCurrent } }.items.single { it.isCurrent }.id)
            shown.cancel()
        } finally {
            store.clear()
            client.stop()
            scope.cancel()
            fake.close()
        }
    }

    @Test fun `a removal lost with the connection comes back, and Retry sends it again`() = runBlocking {
        // The removal never arrives, and the server stays out of reach after: nothing can be read again.
        lateinit var fake: FakeMaServer
        var gone = false
        fake = FakeMaServer(fixture("2.10", "server_info")["response"]!!) { command, _ ->
            when (command) {
                "auth" -> listOf(
                    if (gone) {
                        FakeMaServer.error(
                            MaCommandException.ERROR_INVALID_TOKEN,
                            "Gone",
                        )
                    } else {
                        FakeMaServer.recorded("2.10", "auth")
                    },
                )

                "player_queues/items" -> listOf(FakeMaServer.recorded("2.10", "player_queues_items__after_add"))

                "player_queues/delete_item" -> emptyList<JsonObject>().also {
                    gone = true
                    fake.dropConnections()
                }

                else -> listOf(FakeMaServer.result(null))
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MaClient(OkHttpClient(), scope)
        val store = ViewModelStore()
        try {
            client.start(fake.address, "t", fake.serverId)
            withTimeout(5000) { client.state.filterIsInstance<ConnectionState.Connected>().first() }
            val api = MaApi(client)
            val messages = Messages(MutableStateFlow(true))
            val factory = viewModelFactory {
                initializer {
                    UpNextViewModel(
                        "kitchen",
                        api,
                        Speakers(api, { 0L }),
                        MutableStateFlow(SessionState.Waiting(null)),
                        UiMapper { null },
                        messages,
                        scope,
                        {},
                        MutableSharedFlow(),
                        queueNext = {},
                    )
                }
            }
            val vm = ViewModelProvider.create(store, factory)[UpNextViewModel::class]
            val shown = launch(Dispatchers.Unconfined) { vm.state.collect {} }
            val ids = withTimeout(5000) { vm.state.first { it.items.isNotEmpty() } }.items.map { it.id }
            vm.remove(ids.last())
            val said = withTimeout(5000) { messages.pending.first { it.isNotEmpty() } }
            assertEquals(ids, withTimeout(5000) { vm.state.first { it.items.size == ids.size } }.items.map { it.id })
            // Retry finds the row and sends the removal again, which fails again here.
            said.single().retry?.invoke()
            withTimeout(5000) { messages.pending.first { it.size == 2 } }
            shown.cancel()
        } finally {
            store.clear()
            client.stop()
            scope.cancel()
            fake.close()
        }
    }

    @Test fun `a long queue opens around the song that plays and loads the rest at either end`() = runBlocking {
        val total = 560
        fun item(i: Int) = buildJsonObject {
            put("queue_id", "kitchen")
            put("queue_item_id", "q$i")
            put("name", "Song $i")
        }
        val fake = FakeMaServer(fixture("2.10", "server_info")["response"]!!) { command, args ->
            when (command) {
                "auth" -> listOf(FakeMaServer.recorded("2.10", "auth"))

                "player_queues/items" -> {
                    val offset = args!!["offset"]!!.jsonPrimitive.int
                    val limit = args["limit"]!!.jsonPrimitive.int
                    val page = (offset until minOf(offset + limit, total)).map(::item)
                    listOf(FakeMaServer.result(JsonArray(page)))
                }

                else -> listOf(FakeMaServer.result(null))
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MaClient(OkHttpClient(), scope)
        val store = ViewModelStore()
        try {
            client.start(fake.address, "t", fake.serverId)
            withTimeout(5000) { client.state.filterIsInstance<ConnectionState.Connected>().first() }
            val api = MaApi(client)
            val speakers = Speakers(api, { 0L })
            val queue = buildJsonObject {
                put("queue_id", "kitchen")
                put("items", total)
                put("current_index", 520)
                put("current_item", item(520))
            }
            speakers.onEvent(MaEvent("queue_updated", "kitchen", queue))
            val factory = viewModelFactory {
                initializer {
                    UpNextViewModel(
                        "kitchen",
                        api,
                        speakers,
                        MutableStateFlow(SessionState.Waiting(null)),
                        UiMapper { null },
                        Messages(MutableStateFlow(true)),
                        scope,
                        beforePlay = {},
                        sheetEdits = MutableSharedFlow(),
                        queueNext = {},
                    )
                }
            }
            val vm = ViewModelProvider.create(store, factory)[UpNextViewModel::class]
            val shown = launch(Dispatchers.Unconfined) { vm.state.collect {} }
            val opened = withTimeout(5000) { vm.state.first { it.items.isNotEmpty() } }
            // Fifty played songs above the one that plays, and the rest of the queue under it.
            assertEquals(470, opened.start)
            assertEquals("q520", opened.items[opened.currentIndex].id)
            assertEquals(total - 470, opened.items.size)
            assertTrue(opened.hasEarlier)
            assertFalse(opened.hasLater)

            vm.loadEarlier()
            val whole = withTimeout(5000) { vm.state.first { it.start == 0 } }
            assertEquals((0 until total).map { "q$it" }, whole.items.map { it.id })
            assertEquals("q520", whole.items[whole.currentIndex].id)
            shown.cancel()
        } finally {
            store.clear()
            client.stop()
            scope.cancel()
            fake.close()
        }
    }
}
