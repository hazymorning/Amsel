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

/** Each file is written to a temporary one and renamed, so a crash leaves the last complete file. */
class WidgetStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** Null until the widget's page saved one. */
    suspend fun setup(id: Int): WidgetSetup? = lock.withLock { readAll()[id] }

    suspend fun setSetup(id: Int, setup: WidgetSetup): Unit = lock.withLock { writeAll(readAll() + (id to setup)) }

    suspend fun forget(ids: Collection<Int>): Unit = lock.withLock { writeAll(readAll() - ids.toSet()) }

    suspend fun contents(): Map<String, WidgetContent> = lock.withLock {
        withContext(Dispatchers.IO) {
            read(looksFile)?.let { text -> decode { json.decodeFromString(ContentsSerializer, text) } }
                ?: read(lastLookFile)?.let { text ->
                    decode { mapOf(AMSELS to json.decodeFromString(WidgetContent.serializer(), text)) }
                }
                ?: emptyMap()
        }
    }

    suspend fun setContents(looks: Map<String, WidgetContent>): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            write(looksFile) { it.writeText(json.encodeToString(ContentsSerializer, looks)) }
            forgetEarlier()
        }
    }

    suspend fun forgetContents(): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            looksFile.delete()
            forgetEarlier()
        }
    }

    private val setupsFile get() = File(dir, "setups.json")
    private val looksFile get() = File(dir, "looks.json")

    /** Written by Amsel 2026.11.50, with cover.png and thumbs/: read once, then deleted. */
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
        // On failure the previous file stays; the next change rewrites it in full.
        if (!written) temp.delete()
    }

    companion object {
        /** The key for the speaker Amsel plays on. */
        const val AMSELS = ""

        private val ContentsSerializer = MapSerializer(String.serializer(), WidgetContent.serializer())
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
    /**
     * Drops buttons this version does not know. The default saved before 2026.11.56 becomes [DefaultSlots]:
     * in its order [standing] would drop previous and next first.
     */
    fun setup(): WidgetSetup = WidgetSetup(
        style = WidgetStyle.of(style),
        speaker = speaker,
        cells = WidgetCells.of(cells),
        words = WidgetWordsPosition.of(words),
        showsWords = showsWords,
        slots = if (slots.map { it.button } == EarlierDefault) {
            DefaultSlots
        } else {
            slots.take(MAX_BUTTONS).mapNotNull { stored ->
                val button = WidgetButton.of(stored.button) ?: return@mapNotNull null
                if (button == WidgetButton.PlayItem && stored.item == null) null else WidgetSlot(button, stored.item)
            }
        },
    )
}

private val EarlierDefault = listOf("previous", "play", "next", "favourite", "shuffle")

@Serializable
private data class StoredSlot(val button: String, val item: WidgetItem? = null)
