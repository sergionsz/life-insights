package dev.sergio.lifeinsights.data.export

import dev.sergio.lifeinsights.data.db.AppDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * User-initiated export. Everything the app holds, in a form that outlives the app itself -- the
 * point of an on-device-only tool is that the user can walk away with their data.
 */
class DataExporter(private val database: AppDatabase) {

    @Serializable
    data class ExportedCheckIn(
        val timestampUtc: Long,
        val zoneId: String,
        val localDate: String,
        val mood: Int,
        val energy: Int,
        val note: String?,
        val tags: List<String>,
    )

    @Serializable
    data class ExportedDailyMetric(
        val localDate: String,
        val screenMinutes: Double?,
        val socialMediaMinutes: Double?,
        val lateNightScreenMinutes: Double?,
        val unlockCount: Int?,
        val sleepMinutes: Double?,
        val sleepSource: String?,
        val steps: Double?,
        val exerciseMinutes: Double?,
        val restingHeartRate: Double?,
        val hrv: Double?,
    )

    @Serializable
    data class Export(
        val schemaVersion: Int = 1,
        val exportedAtUtc: Long,
        val scale: String = "mood and energy are integers on a -3..+3 scale",
        val checkIns: List<ExportedCheckIn>,
        val dailyMetrics: List<ExportedDailyMetric>,
    )

    suspend fun toJson(): String {
        val checkIns = database.checkInDao().all().map { entry ->
            ExportedCheckIn(
                timestampUtc = entry.checkIn.timestampUtc,
                zoneId = entry.checkIn.zoneId,
                localDate = LocalDate.ofEpochDay(entry.checkIn.localDate).toString(),
                mood = entry.checkIn.mood,
                energy = entry.checkIn.energy,
                note = entry.checkIn.note,
                tags = entry.tagNames.sorted(),
            )
        }
        val metrics = database.dailyMetricDao().all().map { row ->
            ExportedDailyMetric(
                localDate = LocalDate.ofEpochDay(row.localDate).toString(),
                screenMinutes = row.screenMinutes,
                socialMediaMinutes = row.socialMediaMinutes,
                lateNightScreenMinutes = row.lateNightScreenMinutes,
                unlockCount = row.unlockCount,
                sleepMinutes = row.sleepMinutes,
                sleepSource = row.sleepSource,
                steps = row.steps,
                exerciseMinutes = row.exerciseMinutes,
                restingHeartRate = row.restingHeartRate,
                hrv = row.hrv,
            )
        }
        return json.encodeToString(
            Export(
                exportedAtUtc = System.currentTimeMillis(),
                checkIns = checkIns,
                dailyMetrics = metrics,
            ),
        )
    }

    /** One row per check-in, with tags collapsed into a semicolon-separated cell. */
    suspend fun toCsv(): String = buildString {
        appendLine("date,timestamp_utc,zone,mood,energy,tags,note")
        for (entry in database.checkInDao().all()) {
            append(LocalDate.ofEpochDay(entry.checkIn.localDate)).append(',')
            append(entry.checkIn.timestampUtc).append(',')
            append(entry.checkIn.zoneId).append(',')
            append(entry.checkIn.mood).append(',')
            append(entry.checkIn.energy).append(',')
            append(csvCell(entry.tagNames.sorted().joinToString(";"))).append(',')
            appendLine(csvCell(entry.checkIn.note.orEmpty()))
        }
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            '"' + value.replace("\"", "\"\"") + '"'
        } else {
            value
        }

    private companion object {
        val json = Json { prettyPrint = true; encodeDefaults = true }
    }
}
