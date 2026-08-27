package dev.sergio.lifeinsights.data

import dev.sergio.lifeinsights.data.db.AppDatabase
import dev.sergio.lifeinsights.data.db.CheckInDao
import dev.sergio.lifeinsights.data.db.CheckInEntity
import dev.sergio.lifeinsights.data.db.CheckInWithTags
import dev.sergio.lifeinsights.data.db.DailyMetricDao
import dev.sergio.lifeinsights.data.db.DailyMetricEntity
import dev.sergio.lifeinsights.data.db.TagDao
import dev.sergio.lifeinsights.data.db.TagEntity
import dev.sergio.lifeinsights.insights.MetricSeries
import dev.sergio.lifeinsights.insights.SeriesOps
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A metric that can be charted and correlated, with the label used in insight copy. */
data class MetricDefinition(
    val key: String,
    val label: String,
    val unit: String,
    val select: (DailyMetricEntity) -> Double?,
)

class TrackerRepository(
    private val database: AppDatabase,
    private val checkInDao: CheckInDao = database.checkInDao(),
    private val dailyMetricDao: DailyMetricDao = database.dailyMetricDao(),
    private val tagDao: TagDao = database.tagDao(),
) {

    fun observeCheckIns(): Flow<List<CheckInWithTags>> = checkInDao.observeAll()

    fun observeCheckInsForDay(day: LocalDate): Flow<List<CheckInWithTags>> =
        checkInDao.observeForDay(day.toEpochDay())

    fun observeDayCount(): Flow<Int> = checkInDao.observeDayCount()

    fun observeTags(): Flow<List<TagEntity>> = tagDao.observeAll()

    fun observeMetricForDay(day: LocalDate): Flow<DailyMetricEntity?> =
        dailyMetricDao.observeForDay(day.toEpochDay())

    fun observeDailyMetrics(): Flow<List<DailyMetricEntity>> = dailyMetricDao.observeAll()

    suspend fun ensureDefaultTags() {
        if (tagDao.count() == 0) {
            tagDao.upsertAll(
                AppDatabase.DEFAULT_TAGS.mapIndexed { index, name -> TagEntity(name, index) },
            )
        }
    }

    suspend fun saveCheckIn(
        id: Long = 0,
        mood: Int,
        energy: Int,
        note: String?,
        tags: List<String>,
        at: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = checkInDao.save(
        CheckInEntity(
            id = id,
            timestampUtc = at.toEpochMilli(),
            zoneId = zone.id,
            localDate = DayBoundary.dayOf(at, zone).toEpochDay(),
            mood = mood,
            energy = energy,
            note = note?.takeIf { it.isNotBlank() },
        ),
        tags,
    )

    suspend fun deleteCheckIn(id: Long) = checkInDao.delete(id)

    suspend fun addTag(name: String) {
        val trimmed = name.trim().lowercase()
        if (trimmed.isNotEmpty()) tagDao.upsert(TagEntity(trimmed, Int.MAX_VALUE))
    }

    suspend fun removeTag(name: String) = tagDao.delete(name)

    suspend fun deleteAllData() {
        checkInDao.deleteAll()
        dailyMetricDao.deleteAll()
    }

    // --- series for charts and analysis --------------------------------------------------------

    /**
     * Collapses a day's check-ins into one value per day by averaging.
     *
     * Averaging a midday and an evening entry is the honest summary of the day; taking only the
     * latest would silently discard half the data from anyone who logs twice.
     */
    suspend fun outcomeSeries(): List<MetricSeries> {
        val entries = checkInDao.all()
        return listOf(
            buildOutcome("mood", "Mood", entries) { it.checkIn.mood.toDouble() },
            buildOutcome("energy", "Energy", entries) { it.checkIn.energy.toDouble() },
        )
    }

    private fun buildOutcome(
        key: String,
        label: String,
        entries: List<CheckInWithTags>,
        select: (CheckInWithTags) -> Double,
    ): MetricSeries {
        val byDay = entries.groupBy { LocalDate.ofEpochDay(it.checkIn.localDate) }
            .mapValues { (_, dayEntries) -> dayEntries.map(select).average() }
        return MetricSeries(key, label, byDay)
    }

    suspend fun predictorSeries(): List<MetricSeries> {
        val rows = dailyMetricDao.all()
        val base = METRIC_DEFINITIONS.mapNotNull { definition ->
            val values = rows.mapNotNull { row ->
                definition.select(row)?.let { LocalDate.ofEpochDay(row.localDate) to it }
            }.toMap()
            if (values.isEmpty()) null else MetricSeries(definition.key, definition.label, values)
        }

        // Derived: cumulative shortfall against the user's own recent sleep baseline. This is the
        // series that answers "is today's dip the accumulated cost of the last few nights".
        val sleep = base.firstOrNull { it.key == "sleep_minutes" }
        val derived = sleep
            ?.takeIf { it.size >= 14 }
            ?.let { listOf(SeriesOps.sleepDebt(it, days = 3)) }
            .orEmpty()

        return base + derived
    }

    suspend fun tagDays(): Map<String, Set<LocalDate>> {
        val entries = checkInDao.all()
        val result = mutableMapOf<String, MutableSet<LocalDate>>()
        for (entry in entries) {
            val day = LocalDate.ofEpochDay(entry.checkIn.localDate)
            for (tag in entry.tagNames) {
                result.getOrPut(tag) { mutableSetOf() }.add(day)
            }
        }
        return result
    }

    companion object {
        val METRIC_DEFINITIONS = listOf(
            MetricDefinition("sleep_minutes", "Sleep", "min") { it.sleepMinutes },
            MetricDefinition("screen_minutes", "Screen time", "min") { it.screenMinutes },
            MetricDefinition("social_media_minutes", "Social media", "min") { it.socialMediaMinutes },
            MetricDefinition("late_night_screen_minutes", "Late-night screen time", "min") {
                it.lateNightScreenMinutes
            },
            MetricDefinition("unlock_count", "Phone pickups", "") { it.unlockCount?.toDouble() },
            MetricDefinition("steps", "Steps", "") { it.steps },
            MetricDefinition("exercise_minutes", "Exercise", "min") { it.exerciseMinutes },
            MetricDefinition("resting_hr", "Resting heart rate", "bpm") { it.restingHeartRate },
            MetricDefinition("hrv", "Heart rate variability", "ms") { it.hrv },
        )
    }
}
