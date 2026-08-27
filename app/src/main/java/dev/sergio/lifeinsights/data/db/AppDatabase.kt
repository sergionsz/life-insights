package dev.sergio.lifeinsights.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CheckInEntity::class,
        CheckInTagEntity::class,
        TagEntity::class,
        DailyMetricEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun checkInDao(): CheckInDao
    abstract fun dailyMetricDao(): DailyMetricDao
    abstract fun tagDao(): TagDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "life-insights.db")
                .build()

        /** Starting set from the spec; the user can edit the list in Settings. */
        val DEFAULT_TAGS = listOf(
            "caffeine", "alcohol", "social contact", "stress",
            "ate well", "outdoors", "exercise", "sick",
        )
    }
}
