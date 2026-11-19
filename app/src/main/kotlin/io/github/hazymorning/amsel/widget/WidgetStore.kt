package io.github.hazymorning.amsel.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What the widgets keep on this phone: each one's buttons by its id, and what they showed last with
 * its blurred cover and the buttons' covers, so a restart draws them again without the server. Each
 * file is written whole through a temporary one, so a crash midway leaves the last good one.
 */
class WidgetStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    /** Widget [id]'s buttons; the defaults until its page saved others. */
    suspend fun slots(id: Int): List<WidgetSlot> = lock.withLock { readAll()[id] } ?: DefaultSlots

    suspend fun setSlots(id: Int, slots: List<WidgetSlot>): Unit = lock.withLock { writeAll(readAll() + (id to slots)) }

    /** Widgets the launcher removed; their covers go once no widget names them. */
    suspend fun forget(ids: Collection<Int>): Unit = lock.withLock {
        val kept = readAll() - ids.toSet()
        writeAll(kept)
        val named = kept.values.flatten().mapNotNull { it.item?.art }.map(::thumbName).toSet()
        withContext(Dispatchers.IO) { thumbs.listFiles()?.filter { it.name !in named }?.forEach { it.delete() } }
    }

    suspend fun look(): WidgetLook? = lock.withLock {
        withContext(Dispatchers.IO) {
            read(lookFile)?.let { text -> decode { json.decodeFromString(WidgetLook.serializer(), text) } }
        }
    }

    /** What the widgets show now. */
    suspend fun setLook(look: WidgetLook): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            write(lookFile) { it.writeText(json.encodeToString(WidgetLook.serializer(), look)) }
        }
    }

    /** The blurred cover they show it over; null for none. */
    suspend fun setCover(cover: Bitmap?): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            if (cover == null) {
                coverFile.delete()
            } else {
                write(coverFile) { file ->
                    file.outputStream().use { cover.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
        }
    }

    /** Signing out: what the last person played is not left on the home screen. */
    suspend fun forgetLook(): Unit = lock.withLock {
        withContext(Dispatchers.IO) {
            lookFile.delete()
            coverFile.delete()
        }
    }

    suspend fun cover(): Bitmap? = withContext(Dispatchers.IO) { decodeFile(coverFile) }

    suspend fun thumb(art: String): Bitmap? = withContext(Dispatchers.IO) { decodeFile(File(thumbs, thumbName(art))) }

    suspend fun setThumb(art: String, bitmap: Bitmap): Unit = withContext(Dispatchers.IO) {
        thumbs.mkdirs()
        write(File(thumbs, thumbName(art))) { file ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private val setupsFile get() = File(dir, "setups.json")
    private val lookFile get() = File(dir, "look.json")
    private val coverFile get() = File(dir, "cover.png")
    private val thumbs get() = File(dir, "thumbs")

    private suspend fun readAll(): Map<Int, List<WidgetSlot>> = withContext(Dispatchers.IO) {
        val text = read(setupsFile) ?: return@withContext emptyMap()
        decode { json.decodeFromString(ListSerializer(StoredWidget.serializer()), text) }.orEmpty()
            .associate { stored -> stored.id to stored.slots() }
    }

    private suspend fun writeAll(setups: Map<Int, List<WidgetSlot>>): Unit = withContext(Dispatchers.IO) {
        val stored = setups.map { (id, slots) -> StoredWidget(id, slots.map { StoredSlot(it.button.id, it.item) }) }
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

    private fun decodeFile(file: File): Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path) else null

    private fun thumbName(art: String) = "${art.hashCode().toUInt()}.png"
}

@Serializable
private data class StoredWidget(val id: Int, val slots: List<StoredSlot>) {
    /** Four, each known; one a later Amsel no longer offers takes the default's place. */
    fun slots(): List<WidgetSlot> = DefaultSlots.mapIndexed { index, default ->
        val stored = slots.getOrNull(index) ?: return@mapIndexed default
        val button = WidgetButton.of(stored.button) ?: return@mapIndexed default
        if (button == WidgetButton.PlayItem && stored.item == null) default else WidgetSlot(button, stored.item)
    }
}

@Serializable
private data class StoredSlot(val button: String, val item: WidgetItem? = null)
