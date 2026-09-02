package dev.sergio.lifeinsights.sync

import dev.sergio.lifeinsights.data.db.CheckInEntity
import dev.sergio.lifeinsights.data.db.CheckInWithTags
import dev.sergio.lifeinsights.data.db.DailyMetricEntity
import dev.sergio.lifeinsights.data.db.TagEntity

/**
 * Translation between the local Room rows and the shared wire format.
 *
 * The two shapes are close but not identical, and the differences are the point: the wire format
 * has no local `id` and no `dirty` flag, because neither means anything on another device.
 */

fun CheckInWithTags.toWire(): SyncCheckIn = SyncCheckIn(
    uid = checkIn.uid,
    timestampUtc = checkIn.timestampUtc,
    zoneId = checkIn.zoneId,
    localDate = checkIn.localDate,
    mood = checkIn.mood,
    energy = checkIn.energy,
    note = checkIn.note,
    tags = tagNames.sorted(),
    updatedAtUtc = checkIn.updatedAtUtc,
    deleted = checkIn.deleted,
)

fun SyncCheckIn.toEntity(dirty: Boolean): CheckInEntity = CheckInEntity(
    // Left at zero on purpose. The local id is assigned by whichever row already carries this uid,
    // or by the insert; it is never carried across devices.
    id = 0,
    uid = uid,
    timestampUtc = timestampUtc,
    zoneId = zoneId,
    localDate = localDate,
    mood = mood,
    energy = energy,
    note = note,
    updatedAtUtc = updatedAtUtc,
    deleted = deleted,
    dirty = dirty,
)

fun DailyMetricEntity.toWire(): SyncDailyMetric = SyncDailyMetric(
    localDate = localDate,
    screenMinutes = screenMinutes,
    socialMediaMinutes = socialMediaMinutes,
    lateNightScreenMinutes = lateNightScreenMinutes,
    unlockCount = unlockCount,
    usageUpdatedAtUtc = usageUpdatedAtUtc,
    sleepMinutes = sleepMinutes,
    sleepSource = sleepSource,
    sleepStartUtc = sleepStartUtc,
    sleepEndUtc = sleepEndUtc,
    sleepUpdatedAtUtc = sleepUpdatedAtUtc,
    steps = steps,
    exerciseMinutes = exerciseMinutes,
    restingHeartRate = restingHeartRate,
    hrv = hrv,
    healthUpdatedAtUtc = healthUpdatedAtUtc,
    updatedAtUtc = updatedAtUtc,
)

fun SyncDailyMetric.toEntity(dirty: Boolean): DailyMetricEntity = DailyMetricEntity(
    localDate = localDate,
    screenMinutes = screenMinutes,
    socialMediaMinutes = socialMediaMinutes,
    lateNightScreenMinutes = lateNightScreenMinutes,
    unlockCount = unlockCount,
    usageUpdatedAtUtc = usageUpdatedAtUtc,
    sleepMinutes = sleepMinutes,
    sleepSource = sleepSource,
    sleepStartUtc = sleepStartUtc,
    sleepEndUtc = sleepEndUtc,
    sleepUpdatedAtUtc = sleepUpdatedAtUtc,
    steps = steps,
    exerciseMinutes = exerciseMinutes,
    restingHeartRate = restingHeartRate,
    hrv = hrv,
    healthUpdatedAtUtc = healthUpdatedAtUtc,
    updatedAtUtc = updatedAtUtc,
    dirty = dirty,
)

fun TagEntity.toWire(): SyncTag = SyncTag(
    name = name,
    sortOrder = sortOrder,
    enabled = enabled,
    updatedAtUtc = updatedAtUtc,
    deleted = deleted,
)

fun SyncTag.toEntity(dirty: Boolean): TagEntity = TagEntity(
    name = name,
    sortOrder = sortOrder,
    enabled = enabled,
    updatedAtUtc = updatedAtUtc,
    deleted = deleted,
    dirty = dirty,
)
