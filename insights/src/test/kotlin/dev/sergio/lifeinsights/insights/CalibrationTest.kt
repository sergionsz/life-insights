package dev.sergio.lifeinsights.insights

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.roundToInt

/**
 * Guards the two properties that decide whether this app is trustworthy: it should almost never
 * invent a pattern, and it should still find a real one from a realistic amount of data.
 *
 * Both are measured against simulated data whose true structure is known, which is the only way to
 * check them before months of real logging exist.
 */
class CalibrationTest {

    private val start: LocalDate = LocalDate.of(2026, 1, 5)
    private val engine = InsightsEngine(replicates = 400)

    @Test
    fun `pure noise rarely produces any finding and never a confident one`() {
        var runsWithFindings = 0
        var confident = 0
        val runs = 20
        for (seed in 1..runs) {
            val rng = Random(seed.toLong() * 977)
            val days = 100
            val report = engine.analyse(
                InsightsInput(
                    outcomes = listOf(
                        series("mood", "Mood", scale(DoubleArray(days) { rng.nextGaussian() })),
                        series("energy", "Energy", scale(DoubleArray(days) { rng.nextGaussian() })),
                    ),
                    predictors = listOf(
                        series("sleep_minutes", "Sleep", DoubleArray(days) { 420 + rng.nextGaussian() * 50 }),
                        series("screen_minutes", "Screen time", DoubleArray(days) { 300 + rng.nextGaussian() * 90 }),
                        series("steps", "Steps", DoubleArray(days) { 7000 + rng.nextGaussian() * 2500 }),
                    ),
                ),
            )
            if (report.insights.isNotEmpty()) runsWithFindings++
            confident += report.insights.count { it.confidence == Confidence.CLEAR }
        }
        // The false discovery rate is controlled at 10%; allow sampling slack, but a regression that
        // doubles it should fail here.
        assertTrue(
            "noise produced findings in $runsWithFindings of $runs runs",
            runsWithFindings <= 5,
        )
        // The top tier is Bonferroni-controlled at a 5% family-wise rate, so across 20 runs one
        // stray confident finding is the expected worst case; two would mean the control has
        // slipped.
        assertTrue("noise produced $confident confident findings across $runs runs", confident <= 1)
    }

    @Test
    fun `a real lagged effect is found from about three months of data`() {
        var correct = 0
        val runs = 20
        for (seed in 1..runs) {
            val rng = Random(seed.toLong() * 131)
            val days = 100
            val sleep = DoubleArray(days) { 420 + rng.nextGaussian() * 55 }
            val mood = DoubleArray(days) { t ->
                val driver = if (t >= 2) (sleep[t - 2] - 420) / 55.0 else 0.0
                0.7 * driver + 0.8 * rng.nextGaussian()
            }
            val report = engine.analyse(
                InsightsInput(
                    outcomes = listOf(series("mood", "Mood", scale(mood))),
                    predictors = listOf(
                        series("sleep_minutes", "Sleep", sleep),
                        series("screen_minutes", "Screen time", DoubleArray(days) { 300 + rng.nextGaussian() * 90 }),
                        series("steps", "Steps", DoubleArray(days) { 7000 + rng.nextGaussian() * 2500 }),
                    ),
                ),
            )
            val hit = report.insights.firstOrNull { it.id.contains("sleep_minutes") }
            if (hit != null && hit.id.endsWith(":lag2") && hit.effect > 0) correct++
        }
        assertTrue("found the real lag-2 effect in only $correct of $runs runs", correct >= 17)
    }

    private fun series(key: String, label: String, values: DoubleArray) =
        MetricSeries(key, label, values.mapIndexed { i, v -> start.plusDays(i.toLong()) to v }.toMap())

    private fun scale(raw: DoubleArray) =
        DoubleArray(raw.size) { raw[it].roundToInt().coerceIn(-3, 3).toDouble() }
}
