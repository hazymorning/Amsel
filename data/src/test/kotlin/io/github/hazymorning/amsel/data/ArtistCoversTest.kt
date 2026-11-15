package io.github.hazymorning.amsel.data

import androidx.room.Room
import io.github.hazymorning.amsel.data.library.LibraryDb
import io.github.hazymorning.amsel.data.library.LibraryItemEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** An artist without a picture is given the covers of its own albums. */
@RunWith(RobolectricTestRunner::class)
class ArtistCoversTest {
    private val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LibraryDb::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.dao()

    @After fun close() = db.close()

    @Test fun `four covers, newest first, compilations last, each once`() = runTest {
        dao.upsert(
            listOf(
                artist("a"),
                album("1", "a", "c1", year = 2001),
                album("2", "a", "c2", year = 2020),
                album("3", "a", "c3", year = 2010, compilation = true),
                album("4", "a", "c2", year = 2019),
                album("5", "a", "c5", year = null),
                album("6", "a", "c6", year = 2015),
                album("7", "a", null, year = 2024),
            ),
        )
        dao.coverArtists()
        assertEquals("c2\nc6\nc1\nc5", dao.get("library://artist/a")?.albumCovers)
    }

    @Test fun `an artist with a picture or without albums keeps none`() = runTest {
        dao.upsert(listOf(artist("a", picture = "own"), artist("b"), album("1", "a", "c1", year = 2000)))
        dao.coverArtists()
        assertNull(dao.get("library://artist/a")?.albumCovers)
        assertNull(dao.get("library://artist/b")?.albumCovers)
    }

    @Test fun `covers follow the albums when they change`() = runTest {
        dao.upsert(listOf(artist("a"), album("1", "a", "c1", year = 2000)))
        dao.coverArtists()
        dao.replaceType("album", listOf(album("2", "a", "c2", year = 2001)))
        dao.coverArtists()
        assertEquals("c2", dao.get("library://artist/a")?.albumCovers)
    }

    private fun artist(id: String, picture: String? = null) = entity("library://artist/$id", "artist", picture)

    private fun album(id: String, artist: String, cover: String?, year: Int?, compilation: Boolean = false) =
        entity("library://album/$id", "album", cover).copy(
            artistUri = "library://artist/$artist",
            year = year,
            compilation = compilation,
            addedRank = id.toInt(),
        )

    private fun entity(uri: String, type: String, cover: String?) = LibraryItemEntity(
        uri = uri,
        mediaType = type,
        name = uri,
        sortName = uri,
        subtitle = null,
        imageProxyId = cover,
        year = null,
        favorite = false,
        addedRank = 0,
        playedRank = null,
        isEditable = false,
        artistUri = null,
    )
}
