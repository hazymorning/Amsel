package io.github.hazymorning.amsel.data.library

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

// The ranks hold the server's orders, which cannot be computed locally.
@Entity(
    tableName = "items",
    indices = [
        Index("mediaType", "sortName"),
        Index("mediaType", "addedRank"),
        Index("artistUri"),
        Index("altUri"),
    ],
)
data class LibraryItemEntity(
    @PrimaryKey val uri: String,
    /** "album", "artist", "playlist", "radio", "audiobook" or "podcast". */
    val mediaType: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val sortName: String,
    val subtitle: String?,
    val imageProxyId: String?,
    val year: Int?,
    val favorite: Boolean,
    /** Lower = added more recently; an item an event adds goes below the lowest. */
    val addedRank: Int,
    /** 0 = played most recently; null when not among the recent ones. */
    val playedRank: Int?,
    val isEditable: Boolean,
    /** The first artist, or a book's first author. */
    val artistUri: String?,
    val isSmart: Boolean = false,
    val smartPlugin: Boolean = false,
    val isDynamic: Boolean = false,
    val compilation: Boolean = false,
    val durationSec: Int? = null,
    /** The signed-in user's position; null before they start the book. */
    val resumeMs: Long? = null,
    val fullyPlayed: Boolean? = null,
    val narrators: String? = null,
    /** The uri on its first music service, which a progress event can name instead of the library uri. */
    val altUri: String? = null,
    /**
     * For an artist without a picture: up to [ALBUM_COVERS] proxy ids, one per line. Its own albums come first,
     * then those it shares as a later artist, each newest first and compilations last.
     */
    val albumCovers: String? = null,
    /** An album's artists after the first ([artistUri]), one uri per line. */
    val albumArtists: String? = null,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val mediaType: String, val serverId: String, val schema: Int)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM items WHERE mediaType = :mediaType ORDER BY sortName, name")
    fun byName(mediaType: String): Flow<List<LibraryItemEntity>>

    @Query(
        "SELECT * FROM items WHERE mediaType = :mediaType ORDER BY addedRank, sortName",
    )
    fun byAdded(mediaType: String): Flow<List<LibraryItemEntity>>

    @Query(
        "SELECT * FROM items WHERE mediaType = :mediaType ORDER BY playedRank IS NULL, playedRank, sortName",
    )
    fun byPlayed(mediaType: String): Flow<List<LibraryItemEntity>>

    @Query(
        "SELECT * FROM items WHERE mediaType = :mediaType ORDER BY sortName, name LIMIT :limit",
    )
    suspend fun firstByName(mediaType: String, limit: Int): List<LibraryItemEntity>

    @Query(
        "SELECT * FROM items WHERE mediaType = :mediaType " +
            "ORDER BY addedRank, sortName LIMIT :limit",
    )
    suspend fun firstByAdded(mediaType: String, limit: Int): List<LibraryItemEntity>

    @Query(
        "SELECT * FROM items WHERE mediaType = :mediaType " +
            "ORDER BY playedRank IS NULL, playedRank, sortName LIMIT :limit",
    )
    suspend fun firstByPlayed(mediaType: String, limit: Int): List<LibraryItemEntity>

    @Query(
        "SELECT * FROM items WHERE mediaType = :mediaType AND favorite = 1 " +
            "ORDER BY sortName, name LIMIT :limit",
    )
    suspend fun favorites(mediaType: String, limit: Int): List<LibraryItemEntity>

    @Query("SELECT * FROM items WHERE mediaType = :mediaType ORDER BY RANDOM() LIMIT :limit")
    suspend fun random(mediaType: String, limit: Int): List<LibraryItemEntity>

    @Query(
        "SELECT * FROM items WHERE mediaType = 'playlist' AND isSmart = 1 AND (smartPlugin = 1 OR NOT :pluginOnly) " +
            "ORDER BY sortName, name LIMIT :limit",
    )
    suspend fun smartPlaylists(pluginOnly: Boolean, limit: Int): List<LibraryItemEntity>

    @Query(
        "SELECT * FROM items WHERE mediaType = 'playlist' AND isSmart = 0 " +
            "ORDER BY playedRank IS NULL, playedRank, sortName LIMIT :limit",
    )
    suspend fun ownPlaylists(limit: Int): List<LibraryItemEntity>

    @Query(
        "SELECT * FROM items WHERE mediaType = 'playlist' AND isEditable = 1 " +
            "ORDER BY sortName, name",
    )
    suspend fun editablePlaylists(): List<LibraryItemEntity>

    @Query("SELECT * FROM items WHERE uri = :uri")
    suspend fun get(uri: String): LibraryItemEntity?

    @Query("SELECT * FROM items")
    fun observeAll(): Flow<List<LibraryItemEntity>>

    @Query(
        "SELECT artistUri, albumArtists, imageProxyId FROM items WHERE mediaType = 'album' " +
            "AND artistUri IS NOT NULL AND imageProxyId IS NOT NULL " +
            "ORDER BY compilation, year IS NULL, year DESC, addedRank",
    )
    suspend fun albumCovers(): List<AlbumCover>

    @Query("UPDATE items SET albumCovers = :covers WHERE uri = :uri AND albumCovers IS NOT :covers")
    suspend fun setAlbumCovers(uri: String, covers: String?)

    @Query("SELECT uri FROM items WHERE mediaType = 'artist' AND imageProxyId IS NULL")
    suspend fun artistsWithoutPicture(): List<String>

    @Transaction
    suspend fun coverArtists() {
        val albums = albumCovers()
        val own = albums.groupBy({ it.artistUri }, { it.imageProxyId })
        val shared = albums.flatMap { album -> album.albumArtists?.lines().orEmpty().map { it to album.imageProxyId } }
            .groupBy({ it.first }, { it.second })
        for (artist in artistsWithoutPicture()) {
            val covers = (own[artist].orEmpty() + shared[artist].orEmpty()).distinct().take(ALBUM_COVERS)
            setAlbumCovers(artist, covers.joinToString("\n").ifEmpty { null })
        }
    }

    @Query("SELECT * FROM items WHERE uri IN (:uris)")
    suspend fun rowsIn(uris: List<String>): List<LibraryItemEntity>

    suspend fun rowsOf(uris: Collection<String>): Map<String, LibraryItemEntity> = uris.distinct()
        .chunked(URIS_PER_QUERY)
        .flatMap { rowsIn(it) }
        .associateBy { it.uri }

    @Query("SELECT uri, albumCovers FROM items WHERE uri IN (:uris) AND albumCovers IS NOT NULL")
    suspend fun heldCovers(uris: List<String>): List<IndexedAlbumCovers>

    suspend fun coversOf(uris: Collection<String>): Map<String, List<String>> = uris.distinct()
        .chunked(URIS_PER_QUERY)
        .flatMap { heldCovers(it) }
        .associate { it.uri to it.albumCovers.lines() }

    @Query("SELECT * FROM items WHERE mediaType = 'album' AND artistUri = :artistUri")
    suspend fun albumsBy(artistUri: String): List<LibraryItemEntity>

    @Query("SELECT MIN(addedRank) FROM items WHERE mediaType = :mediaType")
    suspend fun minAddedRank(mediaType: String): Int?

    @Query("SELECT COUNT(*) FROM items WHERE mediaType = :mediaType")
    suspend fun count(mediaType: String): Int

    @Query("SELECT DISTINCT mediaType FROM items")
    fun mediaTypes(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM items WHERE mediaType = :mediaType AND favorite = 1")
    suspend fun favoriteCount(mediaType: String): Int

    @Query("SELECT NOT EXISTS (SELECT 1 FROM items)")
    fun isEmpty(): Flow<Boolean>

    @Upsert
    suspend fun upsert(items: List<LibraryItemEntity>)

    @Query("UPDATE items SET favorite = :favorite WHERE uri = :uri AND favorite IS NOT :favorite")
    suspend fun setFavorite(uri: String, favorite: Boolean): Int

    // Writes only a change: a sync re-sends every progress row, and each write would refresh every list.
    @Query(
        "UPDATE items SET resumeMs = :resumeMs, fullyPlayed = :fullyPlayed WHERE (uri = :uri OR altUri = :uri) " +
            "AND (resumeMs IS NOT :resumeMs OR fullyPlayed IS NOT :fullyPlayed)",
    )
    suspend fun setProgress(uri: String, resumeMs: Long?, fullyPlayed: Boolean?): Int

    @Query("SELECT * FROM items WHERE uri = :uri")
    fun observeRow(uri: String): Flow<LibraryItemEntity?>

    @Query("DELETE FROM items WHERE uri = :uri")
    suspend fun delete(uri: String): Int

    @Query("DELETE FROM items WHERE mediaType = :mediaType")
    suspend fun clearType(mediaType: String)

    @Transaction
    suspend fun replaceType(mediaType: String, items: List<LibraryItemEntity>) {
        clearType(mediaType)
        upsert(items)
    }

    @Query("SELECT * FROM sync_state WHERE mediaType = :mediaType")
    suspend fun syncState(mediaType: String): SyncStateEntity?

    @Query("SELECT mediaType FROM sync_state")
    fun syncedTypes(): Flow<List<String>>

    @Upsert
    suspend fun setSyncState(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clearSyncState()
}

data class AlbumCover(val artistUri: String, val albumArtists: String?, val imageProxyId: String)

data class IndexedAlbumCovers(val uri: String, val albumCovers: String)

/** An artist's cover is two by two. */
const val ALBUM_COVERS = 4

/** Uris in one `IN`: SQLite on older Android takes at most 999 variables a statement. */
private const val URIS_PER_QUERY = 500

@Database(entities = [LibraryItemEntity::class, SyncStateEntity::class], version = 11, exportSchema = false)
abstract class LibraryDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
}

// The index is a cache: an upgrade without a migration drops it, and the next connect syncs it again.
internal fun openLibraryDb(context: Context): LibraryDb =
    Room.databaseBuilder(context, LibraryDb::class.java, "library.db")
        .addMigrations(MIGRATION_10_11)
        .fallbackToDestructiveMigration(true)
        .build()

// Only albums sync again to fill the new column: dropping the index would leave the Library empty until every
// kind had synced.
internal val MIGRATION_10_11 = Migration(10, 11) { db ->
    db.execSQL("ALTER TABLE items ADD COLUMN albumArtists TEXT")
    db.execSQL("DELETE FROM sync_state WHERE mediaType = 'album'")
}
