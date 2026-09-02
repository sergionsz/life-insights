package dev.sergio.lifeinsights.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CheckInEntity::class,
        CheckInTagEntity::class,
        TagEntity::class,
        DailyMetricEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun checkInDao(): CheckInDao
    abstract fun dailyMetricDao(): DailyMetricDao
    abstract fun tagDao(): TagDao
    abstract fun syncStateDao(): SyncStateDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "life-insights.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Starting set from the spec; the user can edit the list in Settings. */
        val DEFAULT_TAGS = listOf(
            "caffeine", "alcohol", "social contact", "stress",
            "ate well", "outdoors", "exercise", "sick",
        )

        /**
         * Adds everything sync needs to rows that already exist.
         *
         * The interesting part is [uid]. Every existing check-in has only an autoincrement `id`,
         * which is a counter local to this one database: another install would hand out the same
         * numbers for entirely different entries. Backfilling a random identifier per row is what
         * makes those entries safe to send, and it has to happen before the first sync rather than
         * after, or the server would be asked to reconcile rows it cannot tell apart.
         *
         * The identifiers are generated in SQL rather than in Kotlin so that the whole thing stays
         * one statement inside Room's migration transaction, with no chance of a partially
         * identified table if the app is killed midway.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE check_in ADD COLUMN uid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE check_in ADD COLUMN updatedAtUtc INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE check_in ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE check_in ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")

                // A version 4 UUID assembled from random bytes. SQLite has randomblob and hex but
                // no uuid(), so the shape is spelled out.
                db.execSQL(
                    """
                    UPDATE check_in SET uid =
                        lower(
                            substr(hex(randomblob(4)), 1, 8) || '-' ||
                            substr(hex(randomblob(2)), 1, 4) || '-4' ||
                            substr(hex(randomblob(2)), 2, 3) || '-' ||
                            substr('89ab', (abs(random()) % 4) + 1, 1) ||
                            substr(hex(randomblob(2)), 2, 3) || '-' ||
                            substr(hex(randomblob(6)), 1, 12)
                        )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_check_in_uid ON check_in (uid)",
                )

                // Existing entries have never been edited since they were written, so the moment
                // they were written is the truthful "last changed".
                db.execSQL("UPDATE check_in SET updatedAtUtc = timestampUtc")

                db.execSQL("ALTER TABLE tag ADD COLUMN updatedAtUtc INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tag ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tag ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")

                db.execSQL(
                    "ALTER TABLE daily_metric ADD COLUMN usageUpdatedAtUtc INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE daily_metric ADD COLUMN sleepUpdatedAtUtc INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE daily_metric ADD COLUMN healthUpdatedAtUtc INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE daily_metric ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")

                // Existing rows were written by the usage aggregation and the sleep proxy, so both
                // groups inherit the row's timestamp. Leaving them at zero would make the first
                // pull from a fresh server look newer than data this phone actually holds.
                db.execSQL(
                    "UPDATE daily_metric SET usageUpdatedAtUtc = updatedAtUtc " +
                        "WHERE screenMinutes IS NOT NULL OR unlockCount IS NOT NULL",
                )
                db.execSQL(
                    "UPDATE daily_metric SET sleepUpdatedAtUtc = updatedAtUtc " +
                        "WHERE sleepMinutes IS NOT NULL",
                )
                db.execSQL(
                    "UPDATE daily_metric SET healthUpdatedAtUtc = updatedAtUtc " +
                        "WHERE steps IS NOT NULL OR exerciseMinutes IS NOT NULL " +
                        "OR restingHeartRate IS NOT NULL OR hrv IS NOT NULL",
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        deviceId TEXT NOT NULL,
                        lastSeenSeq INTEGER NOT NULL,
                        lastSyncAtUtc INTEGER NOT NULL,
                        lastError TEXT
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
