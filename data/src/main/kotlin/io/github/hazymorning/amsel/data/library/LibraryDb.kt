package io.github.hazymorning.amsel.data.library

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One album, artist, playlist, radio station, audiobook or podcast in the library index. Lists
 * paint from here instantly; the server only refreshes it. Ranks hold the server's orders that
 * cannot be computed locally.
 */
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
    /** Artists for an album, owner for a playlist, authors for a book, publisher for a podcast. */
    val subtitle: String?,
    val imageProxyId: String?,
    val year: Int?,
    val favorite: Boolean,
    /** Lower = added more recently; an item an event adds goes below the lowest. */
    val addedRank: Int,
    /** 0 = played most recently; null when not among the recent ones. */
    val playedRank: Int?,
    val isEditable: Boolean,
    /** First artist (a book's first author), for "Go to artist" and an artist page's first paint. */
    val artistUri: String?,
    /** A smart playlist (MediaItem.isSmartPlaylist). */
    val isSmart: Boolean = false,
    /** Made by the Smart Playlists plugin (MediaItem.isPluginSmartPlaylist). */
    val smartPlugin: Boolean = false,
    /** A playlist the server fills anew each time it plays (MediaItem.isDynamic). */
    val isDynamic: Boolean = false,
    /** An album the server calls a compilation (MediaItem.isCompilation). */
    val compilation: Boolean = false,
    /** A book's length in seconds. */
    val durationSec: Int? = null,
    /** Where the signed-in person stopped in a book; null before they start it. */
    val resumeMs: Long? = null,
    val fullyPlayed: Boolean? = null,
    /** A book's narrators. */
    val narrators: String? = null,
    /**
     * The item's uri on its first music service. Progress events name the uri a play referenced,
     * which an Audiobookshelf sync gives as the provider's.
     */
    val altUri: String? = null,
    /**
     * For an artist without a picture: up to four covers of its own albums ([ALBUM_COVERS]), one per
     * line, newest first and compilations last, which its cover is made of.
     */
    val albumCovers: String? = null,
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

    /** Smart playlists A-Z: only those the Smart Playlists plugin made ([pluginOnly]), or all. */
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

    /** Every album's first artist and cover, for the covers of artists without a picture. */
    @Query(
        "SELECT artistUri, imageProxyId FROM items WHERE mediaType = 'album' AND artistUri IS NOT NULL " +
            "AND imageProxyId IS NOT NULL ORDER BY compilation, year IS NULL, year DESC, addedRank",
    )
    suspend fun albumCovers(): List<AlbumCover>

    @Query("UPDATE items SET albumCovers = :covers WHERE uri = :uri AND albumCovers IS NOT :covers")
    suspend fun setAlbumCovers(uri: String, covers: String?)

    @Query("SELECT uri FROM items WHERE mediaType = 'artist' AND imageProxyId IS NULL")
    suspend fun artistsWithoutPicture(): List<String>

    /** Gives each artist without a picture the covers of its own albums. */
    @Transaction
    suspend fun coverArtists() {
        val covers = albumCovers().groupBy({ it.artistUri }, { it.imageProxyId })
        for (artist in artistsWithoutPicture()) {
            val own = covers[artist].orEmpty().distinct().take(ALBUM_COVERS)
            setAlbumCovers(artist, own.joinToString("\n").ifEmpty { null })
        }
    }

    /** The albums whose first artist is [artistUri], for an artist page before the server answers. */
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

    // Only a change writes: a sync re-sends every progress row, and each write would refresh every list.
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

data class AlbumCover(val artistUri: String, val imageProxyId: String)

/** How many album covers make an artist's cover: two by two. */
const val ALBUM_COVERS = 4

// The index is a cache: an upgrade drops it (fallbackToDestructiveMigration) and the next
// connect syncs it again.
@Database(entities = [LibraryItemEntity::class, SyncStateEntity::class], version = 10, exportSchema = false)
abstract class LibraryDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
}
