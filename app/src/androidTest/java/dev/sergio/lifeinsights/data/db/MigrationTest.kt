package dev.sergio.lifeinsights.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration that adds sync, run against a real version 1 database.
 *
 * This is the one piece of the sync work that can destroy data that already exists. Everything else
 * either writes new rows or can be retried; a migration that throws leaves the app unable to open
 * its own database at all, and one that quietly does the wrong thing corrupts months of history
 * that cannot be reconstructed.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val databaseName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate1To2_keepsExistingRowsAndGivesEachCheckInAnIdentity() {
        helper.createDatabase(databaseName, 1).use { db ->
            db.execSQL(
                "INSERT INTO check_in (timestampUtc, zoneId, localDate, mood, energy, note) " +
                    "VALUES (1700000000000, 'Europe/Madrid', 20000, 2, -1, 'slept badly')",
            )
            db.execSQL(
                "INSERT INTO check_in (timestampUtc, zoneId, localDate, mood, energy, note) " +
                    "VALUES (1700086400000, 'Europe/Madrid', 20001, 0, 1, NULL)",
            )
            db.execSQL("INSERT INTO tag (name, sortOrder, enabled) VALUES ('caffeine', 0, 1)")
            db.execSQL(
                "INSERT INTO daily_metric (localDate, screenMinutes, unlockCount, sleepMinutes, " +
                    "sleepSource, steps, updatedAtUtc) " +
                    "VALUES (20000, 274.0, 61, 430.0, 'PROXY', 8400.0, 1700000000000)",
            )
        }

        val db = helper.runMigrationsAndValidate(databaseName, 2, true, AppDatabase.MIGRATION_1_2)

        db.query("SELECT uid, mood, note, updatedAtUtc, deleted, dirty FROM check_in ORDER BY localDate")
            .use { rows ->
                assertTrue("both check-ins survived", rows.moveToFirst())
                assertEquals(2, rows.count)

                val firstUid = rows.getString(0)
                assertEquals(2, rows.getInt(1))
                assertEquals("slept badly", rows.getString(2))
                assertEquals(
                    "an untouched entry was last changed when it was written",
                    1_700_000_000_000L,
                    rows.getLong(3),
                )
                assertEquals("nothing starts deleted", 0, rows.getInt(4))
                assertEquals("existing rows have never been sent", 1, rows.getInt(5))
                assertTrue("uid looks like a uuid", firstUid.matches(UUID_SHAPE))

                rows.moveToNext()
                val secondUid = rows.getString(0)
                assertTrue(secondUid.matches(UUID_SHAPE))
                assertNotEquals("each row gets its own identity", firstUid, secondUid)
            }

        // The unique index is what stops two rows ever sharing an identity later.
        db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_check_in_uid'")
            .use { rows ->
                rows.moveToFirst()
                assertEquals(1, rows.getInt(0))
            }

        db.query(
            "SELECT screenMinutes, unlockCount, sleepMinutes, steps, usageUpdatedAtUtc, " +
                "sleepUpdatedAtUtc, healthUpdatedAtUtc FROM daily_metric",
        ).use { rows ->
            rows.moveToFirst()
            assertEquals(274.0, rows.getDouble(0), 0.001)
            assertEquals(61, rows.getInt(1))
            assertEquals(430.0, rows.getDouble(2), 0.001)
            assertEquals(8400.0, rows.getDouble(3), 0.001)

            // Each group inherits the row's timestamp only where that group actually holds a
            // reading. Left at zero, a first sync against an empty server would treat this phone's
            // real measurements as older than nothing at all.
            assertEquals(1_700_000_000_000L, rows.getLong(4))
            assertEquals(1_700_000_000_000L, rows.getLong(5))
            assertEquals(1_700_000_000_000L, rows.getLong(6))
        }

        db.query("SELECT name, sortOrder, deleted, dirty FROM tag").use { rows ->
            rows.moveToFirst()
            assertEquals("caffeine", rows.getString(0))
            assertEquals(0, rows.getInt(1))
            assertEquals(0, rows.getInt(2))
            assertEquals(1, rows.getInt(3))
        }

        db.query("SELECT COUNT(*) FROM sync_state").use { rows ->
            rows.moveToFirst()
            assertEquals("the cursor starts empty, not at zero", 0, rows.getInt(0))
        }
    }

    /** A day with no readings at all must not claim its groups were ever written. */
    @Test
    fun migrate1To2_leavesGroupTimestampsAtZeroWhereThereIsNoReading() {
        helper.createDatabase(databaseName, 1).use { db ->
            db.execSQL(
                "INSERT INTO daily_metric (localDate, screenMinutes, updatedAtUtc) " +
                    "VALUES (20000, 274.0, 1700000000000)",
            )
        }

        val db = helper.runMigrationsAndValidate(databaseName, 2, true, AppDatabase.MIGRATION_1_2)

        db.query(
            "SELECT usageUpdatedAtUtc, sleepUpdatedAtUtc, healthUpdatedAtUtc FROM daily_metric",
        ).use { rows ->
            rows.moveToFirst()
            assertEquals(1_700_000_000_000L, rows.getLong(0))
            assertEquals("no sleep was ever recorded for this day", 0L, rows.getLong(1))
            assertEquals("no health data was ever recorded for this day", 0L, rows.getLong(2))
        }
    }

    /** Tags carried through the migration must still be usable, not just present. */
    @Test
    fun migrate1To2_leavesTheDatabaseOpenableByRoom() {
        helper.createDatabase(databaseName, 1).use { db ->
            db.execSQL(
                "INSERT INTO check_in (timestampUtc, zoneId, localDate, mood, energy, note) " +
                    "VALUES (1700000000000, 'Europe/Madrid', 20000, 2, -1, NULL)",
            )
            db.execSQL("INSERT INTO check_in_tag (checkInId, tag) VALUES (1, 'caffeine')")
        }
        helper.runMigrationsAndValidate(databaseName, 2, true, AppDatabase.MIGRATION_1_2)

        // Opening through Room is what actually proves the migrated schema matches the entities;
        // a mismatch throws here rather than at some later query on the user's phone.
        val room = androidx.room.Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            databaseName,
        ).addMigrations(AppDatabase.MIGRATION_1_2).build()

        room.openHelper.writableDatabase
            .query("SELECT tag FROM check_in_tag WHERE checkInId = 1").use { rows ->
                rows.moveToFirst()
                assertEquals("caffeine", rows.getString(0))
            }
        room.close()
    }

    private companion object {
        val UUID_SHAPE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
