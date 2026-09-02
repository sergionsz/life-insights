package dev.sergio.lifeinsights.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A subjective check-in. Mood and energy are tracked separately on purpose: they come apart
 * (tired but content, wired but anxious) and collapsing them into one number would throw away the
 * distinction this app exists to surface.
 *
 * Both are integers on a -3..+3 scale. Seven points keeps a single tap fast while leaving enough
 * distinct values that correlations are not swamped by ties.
 */
@Entity(
    tableName = "check_in",
    indices = [
        Index(value = ["localDate"]),
        Index(value = ["uid"], unique = true),
    ],
)
data class CheckInEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /**
     * Stable identity for sync, minted once when the entry is created.
     *
     * [id] cannot serve: it is a per-database counter, so a reinstall or a second phone mints the
     * same number for a completely different entry. The empty default exists only because SQLite
     * requires one when adding a NOT NULL column to an existing table; the migration fills every
     * row in, and Room writes this column on every insert, so nothing keeps it.
     */
    @ColumnInfo(defaultValue = "") val uid: String = "",
    /** UTC milliseconds. */
    val timestampUtc: Long,
    /** Zone the entry was made in, so the local day stays stable across travel and DST. */
    val zoneId: String,
    /** Local day this entry counts towards, as an epoch day. See DayBoundary. */
    val localDate: Long,
    val mood: Int,
    val energy: Int,
    val note: String? = null,

    /** When this row last changed. Decides which version wins when two devices disagree. */
    @ColumnInfo(defaultValue = "0") val updatedAtUtc: Long = 0,
    /**
     * A tombstone rather than a removed row.
     *
     * A hard delete cannot be synced: the other device has no way to tell "this was deleted" from
     * "you have not seen this yet", so the next pull would quietly bring the entry back. Every read
     * query filters these out.
     */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /** Set on every local change, cleared once the server has accepted the row. */
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true,
)

@Entity(
    tableName = "check_in_tag",
    primaryKeys = ["checkInId", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = CheckInEntity::class,
            parentColumns = ["id"],
            childColumns = ["checkInId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tag"])],
)
data class CheckInTagEntity(
    val checkInId: Long,
    val tag: String,
)

/** The user-editable tag list shown as chips on the check-in screen. */
@Entity(tableName = "tag")
data class TagEntity(
    @PrimaryKey val name: String,
    val sortOrder: Int,
    val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val updatedAtUtc: Long = 0,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true,
)

/**
 * One row per local day of automatically collected numbers.
 *
 * Every metric is nullable, and that is load-bearing: missing data is the normal case, especially
 * early on and on days a source was unavailable. A zero would be a lie that silently drags every
 * correlation towards it.
 *
 * The metrics fall into three groups, each with its own timestamp, because each has its own writer
 * running on its own schedule: usage stats, the sleep proxy, and Health Connect. Sync merges those
 * groups separately so that a device with no usage access does not erase the screen time another
 * device recorded for the same day. See `Merge.dailyMetric` in the shared sync module.
 */
@Entity(tableName = "daily_metric")
data class DailyMetricEntity(
    /** Epoch day. */
    @PrimaryKey val localDate: Long,

    val screenMinutes: Double? = null,
    val socialMediaMinutes: Double? = null,
    val lateNightScreenMinutes: Double? = null,
    val unlockCount: Int? = null,
    @ColumnInfo(defaultValue = "0") val usageUpdatedAtUtc: Long = 0,

    val sleepMinutes: Double? = null,
    /** One of SleepSource. */
    val sleepSource: String? = null,
    val sleepStartUtc: Long? = null,
    val sleepEndUtc: Long? = null,
    @ColumnInfo(defaultValue = "0") val sleepUpdatedAtUtc: Long = 0,

    val steps: Double? = null,
    val exerciseMinutes: Double? = null,
    val restingHeartRate: Double? = null,
    val hrv: Double? = null,
    @ColumnInfo(defaultValue = "0") val healthUpdatedAtUtc: Long = 0,

    /** When this row was last recomputed, so a re-aggregation can be idempotent. */
    val updatedAtUtc: Long = 0,
    @ColumnInfo(defaultValue = "1") val dirty: Boolean = true,
)

enum class SleepSource { WEARABLE, PROXY, MANUAL }

/**
 * Where this install has got to with the server. One row, always.
 *
 * [lastSeenSeq] is a server sequence number, not a timestamp, and that is deliberate: it is the
 * server's own counter, so it does not care whether this phone's clock is right.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Identifies this install to the server. Regenerated if the local data is ever wiped. */
    val deviceId: String,
    val lastSeenSeq: Long = 0,
    val lastSyncAtUtc: Long = 0,
    /** Last failure, kept so Settings can say what went wrong rather than just "not synced". */
    val lastError: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
