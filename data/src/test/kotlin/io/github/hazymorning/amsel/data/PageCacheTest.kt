package io.github.hazymorning.amsel.data

import io.github.hazymorning.amsel.api.MediaItem
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PageCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private var server: String? = "server-a"
    private val memory by lazy { PageCache(folder.root.resolve("pages")) { server } }

    private fun track(n: Int) = MediaItem("$n", "library", "Track $n", "library://track/$n", "track")

    private val album = MediaItem("4", "library", "First Light", "library://album/4", "album")

    @Test fun `a page reads back as it was kept, its rows up to a few screens`() = runTest {
        memory.write(album.uri, CachedPage(album, rows = List(PageCache.ROWS + 20, ::track)))
        val shown = memory.read(album.uri)
        assertEquals(album, shown?.item)
        assertEquals(List(PageCache.ROWS, ::track), shown?.rows)
    }

    @Test fun `each server keeps its own pages, and none is kept without one`() = runTest {
        memory.write(album.uri, CachedPage(album))
        server = "server-b"
        assertNull(memory.read(album.uri))
        server = null
        memory.write(album.uri, CachedPage(album))
        assertNull(memory.read(album.uri))
    }

    @Test fun `the pages shown longest ago go first`() = runTest {
        repeat(PageCache.PAGES + 1) { n ->
            memory.write("library://album/$n", CachedPage(album))
            folder.root.resolve("pages").walk().filter { it.isFile }.forEach { file ->
                if (file.lastModified() > 0) file.setLastModified(file.lastModified() - 1_000)
            }
        }
        assertNull(memory.read("library://album/0"))
        assertEquals(album, memory.read("library://album/${PageCache.PAGES}")?.item)
        assertEquals(PageCache.PAGES, pages().size)
    }

    @Test fun `a damaged page is not shown, and signing out forgets them all`() = runTest {
        memory.write(album.uri, CachedPage(album))
        pages().single().writeText("{")
        assertNull(memory.read(album.uri))

        memory.write(album.uri, CachedPage(album))
        memory.clear()
        assertNull(memory.read(album.uri))
    }

    private fun pages(): List<File> = folder.root.resolve("pages").walk().filter { it.isFile }.toList()
}
