package dev.sergio.lifeinsights.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.roundToInt

/**
 * These tests are the reason the insights engine is a plain Kotlin module with no Android
 * dependencies: the only way to know the analysis is honest is to feed it data whose true structure
 * is known, weeks before any real data exists.
 */
class InsightsEngineTest {

    private val start: LocalDate = LocalDate.of(2026, 1, 5) // a Monday

    @Test
    fun `withholds every claim until there is enough data`() {
        val rng = Random(11)
        val days = 10
        val input = InsightsInput(
            outcomes = listOf(series("mood", "Mood", moodScale(DoubleArray(days) { rng.nextGaussian() }))),
            predictors = listOf(series("sleep_minutes", "Sleep", DoubleArray(days) { 420 + rng.nextGaussian() * 50 })),
        )

        val report = engine().analyse(input)

        assertTrue("no claims from 10 days", report.insights.isEmpty())
        assertTrue(report.pending.any { it.id == "same_day" && it.daysRemaining == 4 })
        assertTrue(report.pending.any { it.id == "day_of_week" })
    }

    @Test
    fun `recovers an injected lagged sleep effect at the correct lag`() {
        val rng = Random(42)
        val days = 150
        val sleep = DoubleArray(days) { 420 + rng.nextGaussian() * 55 }
        val mood = DoubleArray(days) { t ->
            val driver = if (t >= 2) (sleep[t - 2] - 420) / 55.0 else 0.0
            1.1 * driver + 0.7 * rng.nextGaussian()
        }

        val input = InsightsInput(
            outcomes = listOf(series("mood", "Mood", moodScale(mood))),
            predictors = listOf(
                series("sleep_minutes", "Sleep", sleep),
                series("steps", "Steps", DoubleArray(days) { 6000 + rng.nextGaussian() * 2000 }),
            ),
        )

        val report = engine().analyse(input)
        val sleepInsight = report.insights.firstOrNull { it.id.contains("sleep_minutes") }

        assertTrue("expected a sleep insight, got ${report.insights.map { it.headline }}", sleepInsight != null)
        assertTrue(
            "expected the lag-2 relationship, got ${sleepInsight!!.id}",
            sleepInsight.id.endsWith(":lag2"),
        )
        assertTrue("expected a positive association", sleepInsight.effect > 0)
        assertEquals(Confidence.CLEAR, sleepInsight.confidence)
        assertTrue(sleepInsight.detail.contains("not evidence of cause"))
    }

    /**
     * The test that matters most. Roughly forty relationships get examined per run; without the
     * multiplicity adjustment, pure noise reliably produces a couple of confident-looking findings.
     */
    @Test
    fun `reports nothing confident when every series is noise`() {
        var clear = 0
        var tentative = 0
        for (seed in 1..6) {
            val rng = Random(seed.toLong())
            val days = 100
            val input = InsightsInput(
                outcomes = listOf(
                    series("mood", "Mood", moodScale(DoubleArray(days) { rng.nextGaussian() })),
                    series("energy", "Energy", moodScale(DoubleArray(days) { rng.nextGaussian() })),
                ),
                predictors = listOf(
                    series("sleep_minutes", "Sleep", DoubleArray(days) { 420 + rng.nextGaussian() * 50 }),
                    series("screen_minutes", "Screen time", DoubleArray(days) { 300 + rng.nextGaussian() * 90 }),
                    series("steps", "Steps", DoubleArray(days) { 7000 + rng.nextGaussian() * 2500 }),
                ),
            )
            val report = engine(replicates = 400).analyse(input)
            clear += report.insights.count { it.confidence == Confidence.CLEAR }
            tentative += report.insights.count { it.confidence == Confidence.TENTATIVE }
        }
        assertTrue("noise produced $clear confident findings across 6 runs", clear == 0)
        // With every null true, Benjamini-Hochberg reports anything at all only about q of the
        // time, so a handful of runs should produce at most one stray lead.
        assertTrue("noise produced $tentative tentative findings across 6 runs", tentative <= 1)
    }

    /**
     * Weekends move sleep, screen time and mood together. Uncontrolled, that shows up as a screen
     * time to mood relationship that does not exist. The engine should report the day-of-week
     * pattern that is real and stay silent about the pairing that is not.
     */
    @Test
    fun `does not mistake a weekend confounder for a screen time effect`() {
        val rng = Random(5)
        val days = 140
        val dates = (0 until days).map { start.plusDays(it.toLong()) }
        val isWeekend = DoubleArray(days) { if (dates[it].dayOfWeek.value >= 6) 1.0 else 0.0 }

        val screen = DoubleArray(days) { 250 + 170 * isWeekend[it] + rng.nextGaussian() * 40 }
        val mood = DoubleArray(days) { 1.3 * isWeekend[it] + 0.6 * rng.nextGaussian() }

        val input = InsightsInput(
            outcomes = listOf(series("mood", "Mood", moodScale(mood))),
            predictors = listOf(series("screen_minutes", "Screen time", screen)),
        )

        val report = engine().analyse(input)

        val screenClaims = report.insights.filter { it.kind != InsightKind.DAY_OF_WEEK }
        assertTrue(
            "should not attribute the weekend effect to screen time, got ${screenClaims.map { it.headline }}",
            screenClaims.isEmpty(),
        )
        assertTrue(
            "should still surface the real weekend pattern, got ${report.insights.map { it.headline }}",
            report.insights.any { it.kind == InsightKind.DAY_OF_WEEK },
        )
    }

    @Test
    fun `flags a tag that genuinely shifts mood`() {
        val rng = Random(9)
        val days = 120
        val dates = (0 until days).map { start.plusDays(it.toLong()) }
        // Irregular days, as a real tag would be -- roughly one in five, not every fifth.
        val tagRng = Random(77)
        val drinkingDays = dates.filter { tagRng.nextDouble() < 0.2 }.toSet()
        val mood = DoubleArray(days) { t ->
            (if (dates[t] in drinkingDays) -1.4 else 0.0) + 0.6 * rng.nextGaussian()
        }

        val input = InsightsInput(
            outcomes = listOf(series("mood", "Mood", moodScale(mood))),
            predictors = emptyList(),
            tagDays = mapOf("alcohol" to drinkingDays),
        )

        val report = engine().analyse(input)
        val tagInsight = report.insights.firstOrNull { it.kind == InsightKind.TAG }

        assertTrue("expected an alcohol insight, got ${report.insights.map { it.headline }}", tagInsight != null)
        assertTrue(tagInsight!!.effect < 0)
        assertTrue(tagInsight.headline.contains("alcohol"))
    }

    @Test
    fun `sleep debt accumulates shortfall against the personal baseline`() {
        val days = 40
        val values = LinkedHashMap<LocalDate, Double>()
        for (i in 0 until days) values[start.plusDays(i.toLong())] = 420.0
        // Three short nights at the end.
        values[start.plusDays(37)] = 300.0
        values[start.plusDays(38)] = 360.0
        values[start.plusDays(39)] = 420.0

        val sleep = MetricSeries("sleep_minutes", "Sleep", values)
        val debt = SeriesOps.sleepDebt(sleep, days = 3)

        // Baseline is 420 (the median). Shortfalls: 120 + 60 + 0.
        assertEquals(180.0, debt[start.plusDays(39)]!!, 1e-6)
        assertEquals(0.0, debt[start.plusDays(20)]!!, 1e-6)
    }

    @Test
    fun `sleep debt is absent when a night is missing rather than understated`() {
        val values = LinkedHashMap<LocalDate, Double>()
        for (i in 0 until 40) {
            if (i == 38) continue // no data for this night
            values[start.plusDays(i.toLong())] = 420.0
        }
        val debt = SeriesOps.sleepDebt(MetricSeries("sleep_minutes", "Sleep", values), days = 3)
        assertTrue(debt[start.plusDays(39)] == null)
    }

    @Test
    fun `missing days are excluded rather than treated as zero`() {
        val a = MetricSeries(
            "mood", "Mood",
            mapOf(start to 1.0, start.plusDays(1) to 2.0, start.plusDays(2) to 3.0),
        )
        val b = MetricSeries(
            "sleep_minutes", "Sleep",
            mapOf(start to 400.0, start.plusDays(2) to 500.0),
        )
        val aligned = SeriesOps.align(a, b, lagDays = 0)
        assertEquals(2, aligned.n)
        assertEquals(listOf(start, start.plusDays(2)), aligned.dates)
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun engine(replicates: Int = 600) = InsightsEngine(replicates = replicates)

    private fun series(key: String, label: String, values: DoubleArray): MetricSeries =
        MetricSeries(key, label, values.mapIndexed { i, v -> start.plusDays(i.toLong()) to v }.toMap())

    /** Snap a continuous driver onto the app's actual -3..+3 integer scale, ties and all. */
    private fun moodScale(raw: DoubleArray): DoubleArray =
        DoubleArray(raw.size) { raw[it].roundToInt().coerceIn(-3, 3).toDouble() }
}
