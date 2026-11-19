package io.github.hazymorning.amsel.widget

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * What the widgets keep on this phone: each one's setup by its id, and what they showed last on each
 * speaker, so a restart draws them again without the server; their pictures come from Amsel's own
 * cover cache. Each file is written whole through a temporary one, so a crash midway leaves the last
 * good one.
 */
class WidgetStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** Widget [id]'s setup; null until its page saved one. */
    suspend fun setup(id: Int): WidgetSetup? = lock.withLock { readAll()[id] }

    suspend fun setSetup(id: Int, setup: WidgetSetup): Unit = lock.withLock { writeAll(readAll() + (id to setup)) }

    /** Widgets the launcher removed. */
    suspend fun forget(ids: Collection<Int>): Unit = lock.withLock { writeAll(readAll() - ids.toSet()) }

    /** What the widgets showed last, by speaker: [AMSELS] for the one Amsel plays on. */
    suspend fun looks(): Map<String, WidgetLook> = lock.withLock {
        withContext(Dispatchers.IO) {
            read(looksFile)?.let { text -> decode { json.decodeFromString(LooksSerializer, text) } }
                ?: read(lastLookFile)?.let { text ->
                    decode { mapOf(AMSELS to json.decodeFromString(WidgetLook.serializer(), text)) }
                }
                ?: emptyMap()
        }
    }

    suspend fun setLooks(looks: Map<String, WidgetLook>): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            write(looksFile) { it.writeText(json.encodeToString(LooksSerializer, looks)) }
            forgetEarlier()
        }
    }

    /** Signing out: what the last person played is not left on the home screen. */
    suspend fun forgetLooks(): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            looksFile.delete()
            forgetEarlier()
        }
    }

    private val setupsFile get() = File(dir, "setups.json")
    private val looksFile get() = File(dir, "looks.json")

    /** Amsel 2026.11.50 kept one look, its blurred cover and the buttons' covers here. */
    private val lastLookFile get() = File(dir, "look.json")

    private fun forgetEarlier() {
        lastLookFile.delete()
        File(dir, "cover.png").delete()
        File(dir, "thumbs").deleteRecursively()
    }

    private suspend fun readAll(): Map<Int, WidgetSetup> = withContext(Dispatchers.IO) {
        val text = read(setupsFile) ?: return@withContext emptyMap()
        decode { json.decodeFromString(ListSerializer(StoredWidget.serializer()), text) }.orEmpty()
            .associate { stored -> stored.id to stored.setup() }
    }

    private suspend fun writeAll(setups: Map<Int, WidgetSetup>): Unit = withContext(Dispatchers.IO) {
        val stored = setups.map { (id, setup) ->
            StoredWidget(
                id,
                setup.slots.map { StoredSlot(it.button.id, it.item) },
                setup.style.id,
                setup.speaker,
                setup.cells.id,
                setup.words.id,
                setup.showsWords,
            )
        }
        write(setupsFile) { it.writeText(json.encodeToString(ListSerializer(StoredWidget.serializer()), stored)) }
    }

    private fun read(file: File): String? = try {
        if (file.exists()) file.readText() else null
    } catch (e: IOException) {
        null
    }

    private fun <T> decode(block: () -> T): T? = try {
        block()
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun write(file: File, block: (File) -> Unit) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        val written = try {
            block(temp)
            temp.renameTo(file)
        } catch (e: IOException) {
            false
        }
        // Unwritten, the last good file stays, and the next change writes it whole.
        if (!written) temp.delete()
    }

    companion object {
        /** The looks' key for the speaker Amsel plays on. */
        const val AMSELS = ""

        private val LooksSerializer = MapSerializer(String.serializer(), WidgetLook.serializer())
    }
}

@Serializable
private data class StoredWidget(
    val id: Int,
    val slots: List<StoredSlot>,
    val style: String? = null,
    val speaker: String? = null,
    val cells: String? = null,
    val words: String? = null,
    val showsWords: Boolean = true,
) {
    /** Each button known; one a later Amsel no longer offers is left out. */
    fun setup(): WidgetSetup = WidgetSetup(
        style = WidgetStyle.of(style),
        speaker = speaker,
        cells = WidgetCells.of(cells),
        words = WidgetWordsPlace.of(words),
        showsWords = showsWords,
        slots = slots.take(MAX_BUTTONS).mapNotNull { stored ->
            val button = WidgetButton.of(stored.button) ?: return@mapNotNull null
            if (button == WidgetButton.PlayItem && stored.item == null) null else WidgetSlot(button, stored.item)
        },
    )
}

@Serializable
private data class StoredSlot(val button: String, val item: WidgetItem? = null)
