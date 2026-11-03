package com.matchday.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Bookmaker prices for one match, decimal odds. "open" = price when the market opened,
 * "close" = current price. Stored as JSON inside [MatchEntity].
 */
@Serializable
data class OddsSnap(
    val provider: String,
    val homeOpen: Double? = null, val homeClose: Double? = null,
    val drawOpen: Double? = null, val drawClose: Double? = null,
    val awayOpen: Double? = null, val awayClose: Double? = null,
    val totalLine: Double? = null,
    val overOpen: Double? = null, val overClose: Double? = null,
    val underOpen: Double? = null, val underClose: Double? = null,
    val spreadHomeLine: String? = null, val spreadHomeOdds: Double? = null,
    val spreadAwayLine: String? = null, val spreadAwayOdds: Double? = null,
    val fetchedAt: Long = 0,
)

@Entity(tableName = "matches")
data class MatchEntity(
    @PrimaryKey val id: String,   // "<sport>-<espn id>"
    val eventId: String,          // raw ESPN event id (for the summary endpoint)
    val leagueKey: String,        // e.g. "soccer/eng.1"
    val leagueLogo: String?,
    val homeId: String?,
    val awayId: String?,
    val homeTeam: String,
    val awayTeam: String,
    val homeLogo: String?,
    val awayLogo: String?,
    val kickoff: Long,
    val state: String,            // pre | in | post
    val statusDetail: String?,    // "FT", "45'", "Q3 5:12", "Postponed"...
    val homeScore: String?,
    val awayScore: String?,
    val homeForm: String?,
    val awayForm: String?,
    val homeRecord: String?,
    val awayRecord: String?,
    val venue: String?,
    val city: String?,
    val oddsJson: String?,
    val updatedAt: Long,
)

@Entity(tableName = "favourites")
data class FavouriteEntity(
    @PrimaryKey val key: String,
    val type: String, // TEAM or COMPETITION
    val name: String,
    val addedAt: Long,
)

@Entity(tableName = "cache")
data class CacheEntity(
    @PrimaryKey val key: String,
    val body: String,
    val fetchedAt: Long,
)

@Entity(tableName = "notified")
data class NotifiedEntity(
    @PrimaryKey val key: String,
    val at: Long,
)

@Dao
interface MatchDao {
    @Query("SELECT * FROM matches WHERE kickoff >= :since ORDER BY kickoff")
    fun observeSince(since: Long): Flow<List<MatchEntity>>

    @Query("SELECT * FROM matches WHERE kickoff >= :since ORDER BY kickoff")
    suspend fun since(since: Long): List<MatchEntity>

    @Query("SELECT * FROM matches WHERE id = :id")
    suspend fun byId(id: String): MatchEntity?

    @Upsert
    suspend fun upsert(rows: List<MatchEntity>)

    @Query("DELETE FROM matches WHERE kickoff < :before")
    suspend fun deleteBefore(before: Long)

    /** Removes matches of a league inside a refreshed window that the feed no longer lists. */
    @Query("DELETE FROM matches WHERE leagueKey = :league AND kickoff BETWEEN :from AND :to AND id NOT IN (:keep)")
    suspend fun deleteMissing(league: String, from: Long, to: Long, keep: List<String>)
}

@Dao
interface FavouriteDao {
    @Query("SELECT * FROM favourites ORDER BY type, name")
    fun observeAll(): Flow<List<FavouriteEntity>>

    @Query("SELECT * FROM favourites")
    suspend fun all(): List<FavouriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fav: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE `key` = :key")
    suspend fun get(key: String): CacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CacheEntity)

    @Query("DELETE FROM cache WHERE fetchedAt < :before")
    suspend fun deleteBefore(before: Long)
}

@Dao
interface NotifiedDao {
    @Query("SELECT COUNT(*) FROM notified WHERE `key` = :key")
    suspend fun count(key: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(n: NotifiedEntity)

    @Query("DELETE FROM notified WHERE at < :before")
    suspend fun deleteBefore(before: Long)
}

@Database(
    entities = [MatchEntity::class, FavouriteEntity::class, CacheEntity::class, NotifiedEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun matches(): MatchDao
    abstract fun favourites(): FavouriteDao
    abstract fun cache(): CacheDao
    abstract fun notified(): NotifiedDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "matchday.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
