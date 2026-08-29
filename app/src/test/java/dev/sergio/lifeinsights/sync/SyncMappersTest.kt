package dev.sergio.lifeinsights.sync

import dev.sergio.lifeinsights.data.db.CheckInEntity
import dev.sergio.lifeinsights.data.db.CheckInTagEntity
import dev.sergio.lifeinsights.data.db.CheckInWithTags
import dev.sergio.lifeinsights.data.db.DailyMetricEntity
import dev.sergio.lifeinsights.data.db.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round trips through the wire format.
 *
 * A field quietly dropped here is invisible: the app keeps working, sync keeps reporting success,
 * and the value simply never reaches the other device. These assert on whole objects rather than
 * on individual fields so that a column added later without a mapping fails the test.
 */
class SyncMappersTest {

    @Test
    fun `a check-in survives the round trip with its tags`() {
        val entity = CheckInEntity(
            id = 12,
            uid = "3f1a0d2c-0000-4000-8000-000000000001",
            timestampUtc = 1_700_000_000_000,
            zoneId = "Europe/Madrid",
            localDate = 20_000,
            mood = 2,
            energy = -1,
            note = "long walk",
            updatedAtUtc = 1_700_000_100_000,
            deleted = false,
            dirty = true,
        )
        val local = CheckInWithTags(
            entity,
            listOf(CheckInTagEntity(12, "outdoors"), CheckInTagEntity(12, "caffeine")),
        )

        val wire = local.toWire()
        assertEquals(listOf("caffeine", "outdoors"), wire.tags)

        val back = wire.toEntity(dirty = true)
        // The local id is not carried: it belongs to this database and is reassigned on arrival.
        assertEquals(entity.copy(id = 0), back)
    }

    @Test
    fun `a tombstone stays a tombstone`() {
        val wire = CheckInWithTags(
            CheckInEntity(
                uid = "u",
                timestampUtc = 1,
                zoneId = "UTC",
                localDate = 1,
                mood = 0,
                energy = 0,
                updatedAtUtc = 5,
                deleted = true,
            ),
            emptyList(),
        ).toWire()

        assertTrue(wire.deleted)
        assertTrue(wire.toEntity(dirty = false).deleted)
    }

    @Test
    fun `every daily metric field survives the round trip`() {
        val entity = DailyMetricEntity(
            localDate = 20_000,
            screenMinutes = 274.5,
            socialMediaMinutes = 61.25,
            lateNightScreenMinutes = 18.0,
            unlockCount = 61,
            usageUpdatedAtUtc = 111,
            sleepMinutes = 430.0,
            sleepSource = "MANUAL",
            sleepStartUtc = 1_000,
            sleepEndUtc = 26_800,
            sleepUpdatedAtUtc = 222,
            steps = 8_400.0,
            exerciseMinutes = 35.0,
            restingHeartRate = 54.0,
            hrv = 62.5,
            healthUpdatedAtUtc = 333,
            updatedAtUtc = 333,
            dirty = true,
        )
        assertEquals(entity, entity.toWire().toEntity(dirty = true))
    }

    /** Null must stay null all the way across: it means "no reading", never zero. */
    @Test
    fun `absent readings stay absent`() {
        val entity = DailyMetricEntity(localDate = 20_000, updatedAtUtc = 5, dirty = false)
        val back = entity.toWire().toEntity(dirty = false)

        assertNull(back.screenMinutes)
        assertNull(back.unlockCount)
        assertNull(back.sleepMinutes)
        assertNull(back.sleepSource)
        assertNull(back.steps)
        assertNull(back.hrv)
        assertEquals(entity, back)
    }

    @Test
    fun `a tag survives the round trip`() {
        val entity = TagEntity(
            name = "caffeine",
            sortOrder = 3,
            enabled = false,
            updatedAtUtc = 99,
            deleted = true,
            dirty = false,
        )
        assertEquals(entity, entity.toWire().toEntity(dirty = false))
    }
}
