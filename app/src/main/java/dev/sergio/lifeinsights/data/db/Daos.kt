package dev.sergio.lifeinsights.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class CheckInWithTags(
    @Embedded val checkIn: CheckInEntity,
    @Relation(parentColumn = "id", entityColumn = "checkInId")
    val tags: List<CheckInTagEntity>,
) {
    val tagNames: List<String> get() = tags.map { it.tag }
}

/**
 * Every read here filters out tombstones.
 *
 * A deleted check-in stays in the table so the deletion can reach the server, but it must be
 * invisible to the rest of the app: it should not appear in the list, count towards the day total,
 * or feed the insights engine.
 */
@Dao
interface CheckInDao {

    @Transaction
    @Query("SELECT * FROM check_in WHERE NOT deleted ORDER BY timestampUtc DESC")
    fun observeAll(): Flow<List<CheckInWithTags>>

    @Transaction
    @Query("SELECT * FROM check_in WHERE localDate = :epochDay AND NOT deleted ORDER BY timestampUtc DESC")
    fun observeForDay(epochDay: Long): Flow<List<CheckInWithTags>>

    @Transaction
    @Query("SELECT * FROM check_in WHERE localDate >= :fromEpochDay AND NOT deleted ORDER BY timestampUtc ASC")
    suspend fun since(fromEpochDay: Long): List<CheckInWithTags>

    @Transaction
    @Query("SELECT * FROM check_in WHERE NOT deleted ORDER BY timestampUtc ASC")
    suspend fun all(): List<CheckInWithTags>

    @Query("SELECT COUNT(DISTINCT localDate) FROM check_in WHERE NOT deleted")
    fun observeDayCount(): Flow<Int>

    @Insert
    suspend fun insert(checkIn: CheckInEntity): Long

    @Update
    suspend fun updateRow(checkIn: CheckInEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<CheckInTagEntity>)

    @Query("DELETE FROM check_in_tag WHERE checkInId = :checkInId")
    suspend fun clearTags(checkInId: Long)

    @Query(
        "UPDATE check_in SET mood = :mood, energy = :energy, note = :note, " +
            "updatedAtUtc = :updatedAtUtc, dirty = 1, deleted = 0 WHERE id = :id",
    )
    suspend fun updateExisting(id: Long, mood: Int, energy: Int, note: String?, updatedAtUtc: Long)

    /**
     * Marks an entry deleted instead of removing it.
     *
     * Removing the row would sync as "nothing here", which the other end cannot tell apart from
     * "you have not seen this yet", and the next pull would bring the entry back.
     */
    @Query("UPDATE check_in SET deleted = 1, dirty = 1, updatedAtUtc = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    /** A local reset. Tombstones would be pointless here; the sync cursor is cleared alongside. */
    @Query("DELETE FROM check_in")
    suspend fun deleteAll()

    @Transaction
    suspend fun save(checkIn: CheckInEntity, tags: List<String>): Long {
        val id = if (checkIn.id == 0L) {
            insert(checkIn)
        } else {
            // Deliberately not a whole-row update: it would need the caller to carry `uid` around,
            // and an edit that dropped it would orphan the entry from its synced twin.
            updateExisting(
                checkIn.id,
                checkIn.mood,
                checkIn.energy,
                checkIn.note,
                checkIn.updatedAtUtc,
            )
            clearTags(checkIn.id)
            checkIn.id
        }
        insertTags(tags.map { CheckInTagEntity(id, it) })
        return id
    }

    // --- sync ------------------------------------------------------------------------------------

    @Transaction
    @Query("SELECT * FROM check_in WHERE dirty ORDER BY updatedAtUtc ASC LIMIT :limit")
    suspend fun dirty(limit: Int): List<CheckInWithTags>

    @Query("SELECT COUNT(*) FROM check_in WHERE dirty")
    fun observeDirtyCount(): Flow<Int>

    @Query("SELECT id FROM check_in WHERE uid = :uid")
    suspend fun idForUid(uid: String): Long?

    /**
     * Deliberately does not filter out tombstones, unlike every read above.
     *
     * Sync has to see a deleted row to know the deletion already happened. Hiding it here would
     * make an incoming tombstone look like a brand new entry, and the merge would resurrect it.
     */
    @Transaction
    @Query("SELECT * FROM check_in WHERE uid = :uid")
    suspend fun findByUid(uid: String): CheckInWithTags?

    /**
     * Clears the pending flag, but only if the row still looks the way it did when it was sent.
     *
     * Without the timestamp check, an edit made while the push was in flight would have its flag
     * cleared by the response to the older version and would never be sent at all.
     */
    @Query("UPDATE check_in SET dirty = 0 WHERE uid = :uid AND updatedAtUtc = :updatedAtUtc")
    suspend fun clearDirty(uid: String, updatedAtUtc: Long)

    @Query("UPDATE check_in SET dirty = 1")
    suspend fun markAllDirty()

    /** Writes a row the server won, keyed on [CheckInEntity.uid] rather than the local id. */
    @Transaction
    suspend fun applyRemote(checkIn: CheckInEntity, tags: List<String>) {
        val existingId = idForUid(checkIn.uid)
        val id = if (existingId == null) {
            insert(checkIn.copy(id = 0))
        } else {
            updateRow(checkIn.copy(id = existingId))
            existingId
        }
        clearTags(id)
        insertTags(tags.map { CheckInTagEntity(id, it) })
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

    @Query("SELECT * FROM daily_metric WHERE dirty ORDER BY localDate ASC LIMIT :limit")
    suspend fun dirty(limit: Int): List<DailyMetricEntity>

    @Query("SELECT COUNT(*) FROM daily_metric WHERE dirty")
    fun observeDirtyCount(): Flow<Int>

    @Query("UPDATE daily_metric SET dirty = 0 WHERE localDate = :localDate AND updatedAtUtc = :updatedAtUtc")
    suspend fun clearDirty(localDate: Long, updatedAtUtc: Long)

    @Query("UPDATE daily_metric SET dirty = 1")
    suspend fun markAllDirty()
}

@Dao
interface TagDao {

    @Query("SELECT * FROM tag WHERE NOT deleted ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT COUNT(*) FROM tag WHERE NOT deleted")
    suspend fun count(): Int

    @Query("SELECT * FROM tag WHERE name = :name")
    suspend fun find(name: String): TagEntity?

    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Query("UPDATE tag SET deleted = 1, dirty = 1, updatedAtUtc = :now WHERE name = :name")
    suspend fun softDelete(name: String, now: Long)

    @Query("DELETE FROM tag")
    suspend fun deleteAll()

    @Query("SELECT * FROM tag WHERE dirty ORDER BY name ASC LIMIT :limit")
    suspend fun dirty(limit: Int): List<TagEntity>

    @Query("UPDATE tag SET dirty = 0 WHERE name = :name AND updatedAtUtc = :updatedAtUtc")
    suspend fun clearDirty(name: String, updatedAtUtc: Long)

    @Query("UPDATE tag SET dirty = 1")
    suspend fun markAllDirty()
}

@Dao
interface SyncStateDao {

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observe(): Flow<SyncStateEntity?>

    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}
