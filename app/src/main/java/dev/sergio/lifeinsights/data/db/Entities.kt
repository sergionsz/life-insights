package dev.sergio.lifeinsights.data.db

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
@Entity(tableName = "check_in", indices = [Index(value = ["localDate"])])
data class CheckInEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** UTC milliseconds. */
    val timestampUtc: Long,
    /** Zone the entry was made in, so the local day stays stable across travel and DST. */
    val zoneId: String,
    /** Local day this entry counts towards, as an epoch day. See DayBoundary. */
    val localDate: Long,
    val mood: Int,
    val energy: Int,
    val note: String? = null,
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
)

/**
 * One row per local day of automatically collected numbers.
 *
 * Every metric is nullable, and that is load-bearing: missing data is the normal case, especially
 * early on and on days a source was unavailable. A zero would be a lie that silently drags every
 * correlation towards it.
 */
@Entity(tableName = "daily_metric")
data class DailyMetricEntity(
    /** Epoch day. */
    @PrimaryKey val localDate: Long,

    val screenMinutes: Double? = null,
    val socialMediaMinutes: Double? = null,
    val lateNightScreenMinutes: Double? = null,
    val unlockCount: Int? = null,

    val sleepMinutes: Double? = null,
    /** One of SleepSource. */
    val sleepSource: String? = null,
    val sleepStartUtc: Long? = null,
    val sleepEndUtc: Long? = null,

    val steps: Double? = null,
    val exerciseMinutes: Double? = null,
    val restingHeartRate: Double? = null,
    val hrv: Double? = null,

    /** When this row was last recomputed, so a re-aggregation can be idempotent. */
    val updatedAtUtc: Long = 0,
)

enum class SleepSource { WEARABLE, PROXY, MANUAL }
