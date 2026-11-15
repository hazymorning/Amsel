package io.github.hazymorning.amsel.app

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A list kept in one small JSON file on this phone. Changes apply in order, each after the file
 * has been read, so one made while it loads never writes over what was stored. Each write replaces
 * the file whole through a temporary one, so a crash midway leaves the last good list.
 */
internal class JsonListStore<T>(private val file: File, serializer: KSerializer<T>, private val scope: CoroutineScope) {
    private val list = ListSerializer(serializer)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var read = false
    private val _items = MutableStateFlow<List<T>>(emptyList())

    val items: StateFlow<List<T>> = _items.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { lock.withLock { load() } }
    }

    fun change(edit: (List<T>) -> List<T>) {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                load()
                val next = edit(_items.value)
                // Written first: whoever sees the change can find it on the phone.
                save(next)
                _items.value = next
            }
        }
    }

    private fun load() {
        if (read) return
        read = true
        _items.value = try {
            if (file.exists()) json.decodeFromString(list, file.readText()) else emptyList()
        } catch (e: IOException) {
            emptyList()
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    private fun save(items: List<T>) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        val written = try {
            temp.writeText(json.encodeToString(list, items))
            temp.renameTo(file)
        } catch (e: IOException) {
            false
        }
        // Unwritten, the list stays in memory and the next change writes it whole.
        if (!written) temp.delete()
    }
}
