package dev.sergio.lifeinsights.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class CheckInWithTags(
    @Embedded val checkIn: CheckInEntity,
    @Relation(parentColumn = "id", entityColumn = "checkInId")
    val tags: List<CheckInTagEntity>,
) {
    val tagNames: List<String> get() = tags.map { it.tag }
}

@Dao
interface CheckInDao {

    @Transaction
    @Query("SELECT * FROM check_in ORDER BY timestampUtc DESC")
    fun observeAll(): Flow<List<CheckInWithTags>>

    @Transaction
    @Query("SELECT * FROM check_in WHERE localDate = :epochDay ORDER BY timestampUtc DESC")
    fun observeForDay(epochDay: Long): Flow<List<CheckInWithTags>>

    @Transaction
    @Query("SELECT * FROM check_in WHERE localDate >= :fromEpochDay ORDER BY timestampUtc ASC")
    suspend fun since(fromEpochDay: Long): List<CheckInWithTags>

    @Transaction
    @Query("SELECT * FROM check_in ORDER BY timestampUtc ASC")
    suspend fun all(): List<CheckInWithTags>

    @Query("SELECT COUNT(DISTINCT localDate) FROM check_in")
    fun observeDayCount(): Flow<Int>

    @Insert
    suspend fun insert(checkIn: CheckInEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<CheckInTagEntity>)

    @Query("DELETE FROM check_in_tag WHERE checkInId = :checkInId")
    suspend fun clearTags(checkInId: Long)

    @Query("UPDATE check_in SET mood = :mood, energy = :energy, note = :note WHERE id = :id")
    suspend fun update(id: Long, mood: Int, energy: Int, note: String?)

    @Query("DELETE FROM check_in WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM check_in")
    suspend fun deleteAll()

    @Transaction
    suspend fun save(checkIn: CheckInEntity, tags: List<String>): Long {
        val id = if (checkIn.id == 0L) {
            insert(checkIn)
        } else {
            update(checkIn.id, checkIn.mood, checkIn.energy, checkIn.note)
            clearTags(checkIn.id)
            checkIn.id
        }
        insertTags(tags.map { CheckInTagEntity(id, it) })
        return id
    }
}

@Dao
interface DailyMetricDao {

    @Query("SELECT * FROM daily_metric ORDER BY localDate ASC")
    fun observeAll(): Flow<List<DailyMetricEntity>>

    @Query("SELECT * FROM daily_metric ORDER BY localDate ASC")
    suspend fun all(): List<DailyMetricEntity>

    @Query("SELECT * FROM daily_metric WHERE localDate = :epochDay")
    fun observeForDay(epochDay: Long): Flow<DailyMetricEntity?>

    @Query("SELECT * FROM daily_metric WHERE localDate = :epochDay")
    suspend fun find(epochDay: Long): DailyMetricEntity?

    @Upsert
    suspend fun upsert(metric: DailyMetricEntity)

    @Query("DELETE FROM daily_metric")
    suspend fun deleteAll()
}

@Dao
interface TagDao {

    @Query("SELECT * FROM tag ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT COUNT(*) FROM tag")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Query("DELETE FROM tag WHERE name = :name")
    suspend fun delete(name: String)
}
