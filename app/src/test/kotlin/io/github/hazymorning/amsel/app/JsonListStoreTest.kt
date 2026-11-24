package io.github.hazymorning.amsel.app

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonListStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun aChangeIsOnThePhoneByTheTimeAnyoneSeesIt() = runBlocking {
        val file = File(temp.root, "list.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = JsonListStore(file, String.serializer(), scope)
            val onDisk = CompletableDeferred<String>()
            // Unconfined: the file is read the moment the change shows, before the writer goes on.
            scope.launch(Dispatchers.Unconfined) {
                store.items.first { it.isNotEmpty() }
                onDisk.complete(if (file.exists()) file.readText() else "")
            }
            store.change { listOf("Lanterns") }
            assertEquals("[\"Lanterns\"]", withTimeout(5_000) { onDisk.await() })
        } finally {
            scope.cancel()
        }
    }
}
