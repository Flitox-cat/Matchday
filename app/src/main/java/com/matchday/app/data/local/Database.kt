package com.matchday.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val sportKey: String,
    val sportTitle: String,
    val homeTeam: String,
    val awayTeam: String,
    val commenceTime: Long,
    val oddsUpdatedAt: Long,
)

@Entity(tableName = "odds", indices = [Index("eventId"), Index("fetchedAt")])
data class OddsEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val eventId: String,
    val bookmakerKey: String,
    val bookmakerTitle: String,
    val market: String,
    val outcome: String,
    val point: Double?,
    val price: Double,
    val bookUpdatedAt: Long,
    val fetchedAt: Long,
)

@Entity(tableName = "scores")
data class ScoreEntity(
    @PrimaryKey val eventId: String,
    val sportKey: String,
    val sportTitle: String,
    val homeTeam: String,
    val awayTeam: String,
    val commenceTime: Long,
    val completed: Boolean,
    val homeScore: String?,
    val awayScore: String?,
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
interface EventDao {
    @Query("SELECT * FROM events WHERE commenceTime >= :since ORDER BY commenceTime")
    fun observeSince(since: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE commenceTime >= :since ORDER BY commenceTime")
    suspend fun since(since: Long): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun byId(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE id = :id")
    fun observeById(id: String): Flow<EventEntity?>

    @Upsert
    suspend fun upsert(events: List<EventEntity>)

    @Query("DELETE FROM events WHERE commenceTime < :before")
    suspend fun deleteBefore(before: Long)

    /** Removes upcoming events of a sport that no longer appear in the latest feed (postponed/removed). */
    @Query("DELETE FROM events WHERE sportKey = :sportKey AND commenceTime > :now AND id NOT IN (:keep)")
    suspend fun deleteMissing(sportKey: String, now: Long, keep: List<String>)
}

@Dao
interface OddsDao {
    @Insert
    suspend fun insertAll(rows: List<OddsEntity>)

    /** Latest snapshot for every event. */
    @Query(
        """SELECT * FROM odds WHERE fetchedAt = (
             SELECT MAX(o2.fetchedAt) FROM odds o2 WHERE o2.eventId = odds.eventId)"""
    )
    fun observeLatest(): Flow<List<OddsEntity>>

    @Query(
        """SELECT * FROM odds WHERE fetchedAt = (
             SELECT MAX(o2.fetchedAt) FROM odds o2 WHERE o2.eventId = odds.eventId)"""
    )
    suspend fun latest(): List<OddsEntity>

    @Query("SELECT * FROM odds WHERE eventId = :eventId ORDER BY fetchedAt DESC")
    fun observeForEvent(eventId: String): Flow<List<OddsEntity>>

    @Query("SELECT * FROM odds WHERE eventId = :eventId ORDER BY fetchedAt DESC")
    suspend fun forEvent(eventId: String): List<OddsEntity>

    @Query("DELETE FROM odds WHERE fetchedAt < :before")
    suspend fun deleteBefore(before: Long)
}

@Dao
interface ScoreDao {
    @Upsert
    suspend fun upsert(rows: List<ScoreEntity>)

    @Query("SELECT * FROM scores WHERE commenceTime >= :since ORDER BY commenceTime DESC")
    fun observeSince(since: Long): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM scores WHERE eventId = :id")
    fun observeById(id: String): Flow<ScoreEntity?>

    @Query("SELECT * FROM scores WHERE eventId = :id")
    suspend fun byId(id: String): ScoreEntity?

    @Query("DELETE FROM scores WHERE commenceTime < :before")
    suspend fun deleteBefore(before: Long)
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
    entities = [
        EventEntity::class, OddsEntity::class, ScoreEntity::class,
        FavouriteEntity::class, CacheEntity::class, NotifiedEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun odds(): OddsDao
    abstract fun scores(): ScoreDao
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
