package dev.sergio.lifeinsights.data

import dev.sergio.lifeinsights.data.db.AppDatabase
import dev.sergio.lifeinsights.data.db.CheckInEntity
import dev.sergio.lifeinsights.data.db.DailyMetricEntity
import dev.sergio.lifeinsights.data.db.SleepSource
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Generates a plausible history so the Trends and Insights screens can be exercised before months
 * of real logging exist. Debug builds only.
 *
 * The data is not random noise: it has a deliberate structure so the analysis can be checked
 * against a known answer. Sleep two nights ago genuinely drives energy, weekends genuinely lift
 * mood, and alcohol genuinely lowers next-day energy. Screen time is deliberately unrelated to
 * anything, so if the Insights screen ever reports it, something is wrong.
 */
object DebugSeed {

    suspend fun seed(database: AppDatabase, days: Int = 120, seed: Long = 20260827L) {
        val rng = Random(seed)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val checkInDao = database.checkInDao()
        val metricDao = database.dailyMetricDao()

        val sleepByDay = HashMap<LocalDate, Double>()
        val alcoholDays = HashSet<LocalDate>()

        for (offset in days downTo 0) {
            val day = today.minusDays(offset.toLong())
            val isWeekend = day.dayOfWeek.value >= 6

            val drank = isWeekend && rng.nextDouble() < 0.55 || rng.nextDouble() < 0.08
            if (drank) alcoholDays.add(day)

            val sleep = 420 + gaussian(rng) * 55 + (if (isWeekend) 35 else 0) -
                (if (day.minusDays(1) in alcoholDays) 25 else 0)
            sleepByDay[day] = sleep

            // Energy responds to the sleep from two nights ago, plus yesterday's drinking.
            val sleepDriver = ((sleepByDay[day.minusDays(2)] ?: 420.0) - 420.0) / 55.0
            val energyRaw = 0.9 * sleepDriver -
                (if (day.minusDays(1) in alcoholDays) 1.1 else 0.0) + 0.7 * gaussian(rng)
            val moodRaw = 0.4 * sleepDriver + (if (isWeekend) 1.0 else 0.0) + 0.8 * gaussian(rng)

            val at = day.atTime(LocalTime.of(21, 0)).atZone(zone).toInstant()
            val id = checkInDao.insert(
                CheckInEntity(
                    timestampUtc = at.toEpochMilli(),
                    zoneId = zone.id,
                    localDate = day.toEpochDay(),
                    mood = clamp(moodRaw),
                    energy = clamp(energyRaw),
                    note = null,
                ),
            )
            val tags = buildList {
                if (drank) add("alcohol")
                if (rng.nextDouble() < 0.4) add("caffeine")
                if (rng.nextDouble() < 0.3) add("exercise")
                if (isWeekend && rng.nextDouble() < 0.6) add("social contact")
            }
            checkInDao.insertTags(
                tags.map { dev.sergio.lifeinsights.data.db.CheckInTagEntity(id, it) },
            )

            metricDao.upsert(
                DailyMetricEntity(
                    localDate = day.toEpochDay(),
                    sleepMinutes = sleep,
                    sleepSource = SleepSource.PROXY.name,
                    // Unrelated to mood and energy by construction: a control series.
                    screenMinutes = 300 + gaussian(rng) * 80 + (if (isWeekend) 60 else 0),
                    socialMediaMinutes = 70 + gaussian(rng) * 30,
                    lateNightScreenMinutes = (20 + gaussian(rng) * 15).coerceAtLeast(0.0),
                    unlockCount = (70 + gaussian(rng) * 20).roundToInt().coerceAtLeast(0),
                    steps = (7000 + gaussian(rng) * 2500).coerceAtLeast(0.0),
                    exerciseMinutes = if ("exercise" in tags) 35 + gaussian(rng) * 12 else 0.0,
                    updatedAtUtc = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun clamp(raw: Double): Int = raw.roundToInt().coerceIn(Scale.MIN, Scale.MAX)

    /** Box-Muller; kotlin.random has no Gaussian. */
    private fun gaussian(rng: Random): Double {
        val u1 = rng.nextDouble().coerceAtLeast(1e-12)
        val u2 = rng.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) *
            kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
