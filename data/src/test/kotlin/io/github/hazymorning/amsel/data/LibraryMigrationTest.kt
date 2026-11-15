package io.github.hazymorning.amsel.data

import android.content.Context
import io.github.hazymorning.amsel.data.library.SyncStateEntity
import io.github.hazymorning.amsel.data.library.openLibraryDb
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** An index from before albums kept their later artists opens with its rows: only its albums sync again. */
@RunWith(RobolectricTestRunner::class)
class LibraryMigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun `version 10 keeps its rows and forgets only that its albums synced`() = runTest {
        context.openOrCreateDatabase("library.db", Context.MODE_PRIVATE, null).use { old ->
            VERSION_10.forEach(old::execSQL)
            old.execSQL(
                "INSERT INTO items (uri, mediaType, name, sortName, imageProxyId, favorite, addedRank, isEditable, " +
                    "artistUri, isSmart, smartPlugin, isDynamic, compilation, albumCovers) VALUES " +
                    "('$AURORA', 'artist', 'Aurora Tone', 'aurora tone', NULL, 1, 0, 0, NULL, 0, 0, 0, 0, 'wind'), " +
                    "('$SECOND_WIND', 'album', 'Second Wind', 'second wind', 'wind', 0, 0, 0, '$AURORA', 0, 0, 0, 0, NULL)",
            )
            old.execSQL("INSERT INTO sync_state VALUES ('album', 'server', 99), ('artist', 'server', 99)")
            old.version = 10
        }
        val db = openLibraryDb(context)
        try {
            val dao = db.dao()
            val artist = dao.get(AURORA)
            assertEquals("Aurora Tone", artist?.name)
            assertEquals(true, artist?.favorite)
            assertEquals("wind", artist?.albumCovers)
            assertNull(dao.get(SECOND_WIND)?.albumArtists)
            assertNull(dao.syncState("album"))
            assertEquals(SyncStateEntity("artist", "server", 99), dao.syncState("artist"))
            val album = dao.get(SECOND_WIND) ?: error("album gone")
            dao.upsert(listOf(album.copy(albumArtists = CIRRUS)))
            assertEquals(CIRRUS, dao.get(SECOND_WIND)?.albumArtists)
        } finally {
            db.close()
        }
    }

    private companion object {
        const val CIRRUS = "library://artist/1"
        const val AURORA = "library://artist/3"
        const val SECOND_WIND = "library://album/3"

        /** Version 10 as Room 2.8 wrote it. */
        val VERSION_10 = listOf(
            "CREATE TABLE IF NOT EXISTS `items` (`uri` TEXT NOT NULL, `mediaType` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL COLLATE NOCASE, `sortName` TEXT NOT NULL COLLATE NOCASE, `subtitle` TEXT, " +
                "`imageProxyId` TEXT, `year` INTEGER, `favorite` INTEGER NOT NULL, `addedRank` INTEGER NOT NULL, " +
                "`playedRank` INTEGER, `isEditable` INTEGER NOT NULL, `artistUri` TEXT, `isSmart` INTEGER NOT NULL, " +
                "`smartPlugin` INTEGER NOT NULL, `isDynamic` INTEGER NOT NULL, `compilation` INTEGER NOT NULL, " +
                "`durationSec` INTEGER, `resumeMs` INTEGER, `fullyPlayed` INTEGER, `narrators` TEXT, `altUri` TEXT, " +
                "`albumCovers` TEXT, PRIMARY KEY(`uri`))",
            "CREATE INDEX IF NOT EXISTS `index_items_mediaType_sortName` ON `items` (`mediaType`, `sortName`)",
            "CREATE INDEX IF NOT EXISTS `index_items_mediaType_addedRank` ON `items` (`mediaType`, `addedRank`)",
            "CREATE INDEX IF NOT EXISTS `index_items_artistUri` ON `items` (`artistUri`)",
            "CREATE INDEX IF NOT EXISTS `index_items_altUri` ON `items` (`altUri`)",
            "CREATE TABLE IF NOT EXISTS `sync_state` (`mediaType` TEXT NOT NULL, `serverId` TEXT NOT NULL, " +
                "`schema` INTEGER NOT NULL, PRIMARY KEY(`mediaType`))",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '7e6dd7eab8a9068d697f9064a337bf4b')",
        )
    }
}
