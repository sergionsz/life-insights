package dev.sergio.lifeinsights.sync

import kotlinx.serialization.Serializable

/**
 * The wire format shared by the phone and the server.
 *
 * This module is deliberately dependency-free and is compiled into both sides, so the client and
 * the server cannot drift apart on field names, nullability, or merge rules. If a field is added
 * here it exists on both ends or on neither.
 *
 * Every synced row carries [updatedAtUtc], set by whichever device last changed it. That timestamp
 * decides conflicts; it is never used for ordering the sync itself, because device clocks are not
 * trustworthy enough for that. Ordering is the server's job (see `seq` in the server schema).
 */
@Serializable
data class SyncCheckIn(
    /**
     * Stable, globally unique identity, minted once by the device that created the entry.
     *
     * The local Room row also has an autoincrement `id`, but that is a per-database counter: two
     * installs both mint `id = 5` for entirely different entries. Only [uid] is safe to send.
     */
    val uid: String,
    val timestampUtc: Long,
    val zoneId: String,
    /** Epoch day, under the app's 04:00 day boundary. */
    val localDate: Long,
    val mood: Int,
    val energy: Int,
    val note: String? = null,
    /** Carried with the check-in rather than as separate rows, so an entry syncs atomically. */
    val tags: List<String> = emptyList(),
    val updatedAtUtc: Long,
    /**
     * A tombstone. Deleted rows keep syncing rather than disappearing: without this, a delete on
     * one device is silently undone by the next pull from another.
     */
    val deleted: Boolean = false,
)

/**
 * A day of automatically collected numbers.
 *
 * Keyed by [localDate], which is already globally unique for one person, so unlike a check-in this
 * needs no separate identity. Every metric stays nullable all the way to the wire: null means "this
 * device has no reading", which is not the same as zero and must not be merged as if it were.
 *
 * The fields fall into three groups, each with its own timestamp, because each has its own writer:
 * usage stats, the sleep proxy, and Health Connect. Merging tracks those groups rather than the row
 * as a whole; see [Merge.dailyMetric] for why a single row timestamp is not enough.
 */
@Serializable
data class SyncDailyMetric(
    val localDate: Long,

    val screenMinutes: Double? = null,
    val socialMediaMinutes: Double? = null,
    val lateNightScreenMinutes: Double? = null,
    val unlockCount: Int? = null,
    /** When the usage group above was last computed. Zero means never. */
    val usageUpdatedAtUtc: Long = 0,

    val sleepMinutes: Double? = null,
    /** One of `SleepSource`: WEARABLE, PROXY or MANUAL. */
    val sleepSource: String? = null,
    val sleepStartUtc: Long? = null,
    val sleepEndUtc: Long? = null,
    /** When the sleep group above was last written. Zero means never. */
    val sleepUpdatedAtUtc: Long = 0,

    val steps: Double? = null,
    val exerciseMinutes: Double? = null,
    val restingHeartRate: Double? = null,
    val hrv: Double? = null,
    /** When the health group above was last read. Zero means never. */
    val healthUpdatedAtUtc: Long = 0,

    /** The latest of the three group timestamps. Used for change ordering, never for merging. */
    val updatedAtUtc: Long,
)

/** The user's editable tag palette. Keyed by name, which is already the local primary key. */
@Serializable
data class SyncTag(
    val name: String,
    val sortOrder: Int,
    val enabled: Boolean = true,
    val updatedAtUtc: Long,
    val deleted: Boolean = false,
)

/** A batch of rows moving in either direction. */
@Serializable
data class ChangeSet(
    val checkIns: List<SyncCheckIn> = emptyList(),
    val dailyMetrics: List<SyncDailyMetric> = emptyList(),
    val tags: List<SyncTag> = emptyList(),
) {
    val size: Int get() = checkIns.size + dailyMetrics.size + tags.size
    val isEmpty: Boolean get() = size == 0
}

@Serializable
data class PushRequest(
    /** Identifies the sending install. Used for logging and for spotting a clock that is far off. */
    val deviceId: String,
    val changes: ChangeSet,
)

@Serializable
data class PushResponse(
    /** The server's sequence after this batch. Not a cursor: the client still pulls from its own. */
    val serverSeq: Long,
    val applied: Int,
    /**
     * Rows the server kept in preference to the pushed version, by uid or key. The client should
     * clear its dirty flag for these anyway: the next pull carries the winning version.
     */
    val superseded: List<String> = emptyList(),
)

@Serializable
data class PullResponse(
    val changes: ChangeSet,
    /** Cursor to send as `since` on the next pull. Advances only over rows actually returned. */
    val nextSince: Long,
    val hasMore: Boolean,
)

@Serializable
data class SyncStatus(
    val serverSeq: Long,
    val checkIns: Int,
    val dailyMetrics: Int,
    val tags: Int,
)

/** Shape of every non-2xx body, so the client can show something better than a status code. */
@Serializable
data class SyncError(val error: String)
