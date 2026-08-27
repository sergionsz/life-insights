package dev.sergio.lifeinsights.insights

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * A single named daily metric. Missing days are simply absent from [values] rather than zero-filled
 * -- "no data" and "zero minutes" are different facts and conflating them silently corrupts every
 * correlation downstream.
 */
data class MetricSeries(
    val key: String,
    val label: String,
    val values: Map<LocalDate, Double>,
) {
    val size: Int get() = values.size
    val dates: List<LocalDate> get() = values.keys.sorted()

    operator fun get(date: LocalDate): Double? = values[date]

    /** Span in days between first and last observation, regardless of gaps. */
    fun spanDays(): Int {
        if (values.isEmpty()) return 0
        val d = dates
        return ChronoUnit.DAYS.between(d.first(), d.last()).toInt() + 1
    }

    companion object {
        fun of(key: String, label: String, points: Iterable<Pair<LocalDate, Double>>): MetricSeries =
            MetricSeries(key, label, points.toMap())
    }
}

/**
 * Two series aligned onto the dates where both have data, in chronological order, with [lagDays]
 * applied to the predictor.
 *
 * `outcome[d]` is paired with `predictor[d - lagDays]`, so lag 2 answers "does the thing that
 * happened two days ago track today's mood".
 */
data class AlignedPairs(
    val dates: List<LocalDate>,
    val outcome: DoubleArray,
    val predictor: DoubleArray,
) {
    val n: Int get() = dates.size

    /** Day-of-week of the OUTCOME day, as a 0/1 weekend indicator. */
    fun weekendDummy(): DoubleArray = DoubleArray(n) { i ->
        val dow = dates[i].dayOfWeek.value // 1 = Monday .. 7 = Sunday
        if (dow >= 6) 1.0 else 0.0
    }

    override fun equals(other: Any?): Boolean =
        other is AlignedPairs && dates == other.dates &&
            outcome.contentEquals(other.outcome) && predictor.contentEquals(other.predictor)

    override fun hashCode(): Int =
        31 * (31 * dates.hashCode() + outcome.contentHashCode()) + predictor.contentHashCode()
}

object SeriesOps {

    fun align(outcome: MetricSeries, predictor: MetricSeries, lagDays: Int = 0): AlignedPairs {
        val dates = ArrayList<LocalDate>()
        val ys = ArrayList<Double>()
        val xs = ArrayList<Double>()
        for (d in outcome.dates) {
            val y = outcome[d] ?: continue
            val x = predictor[d.minusDays(lagDays.toLong())] ?: continue
            dates.add(d); ys.add(y); xs.add(x)
        }
        return AlignedPairs(dates, ys.toDoubleArray(), xs.toDoubleArray())
    }

    /**
     * Centred rolling mean, used ONLY as a detrending covariate and for chart smoothing. Never
     * correlate two smoothed series directly.
     */
    fun centredRollingMean(dates: List<LocalDate>, values: DoubleArray, windowDays: Int): DoubleArray {
        require(dates.size == values.size) { "centredRollingMean: length mismatch" }
        val n = values.size
        if (n == 0) return DoubleArray(0)
        val half = windowDays / 2L

        // Two-pointer sliding window over the (ascending) dates. This is O(n) rather than O(n^2),
        // which matters because the null model re-detrends the series hundreds of times per test.
        val epochDay = LongArray(n) { dates[it].toEpochDay() }
        val result = DoubleArray(n)
        var lo = 0
        var hi = 0
        var sum = 0.0
        for (i in 0 until n) {
            while (hi < n && epochDay[hi] - epochDay[i] <= half) { sum += values[hi]; hi++ }
            while (lo < hi && epochDay[i] - epochDay[lo] > half) { sum -= values[lo]; lo++ }
            val count = hi - lo
            result[i] = if (count == 0) values[i] else sum / count
        }
        return result
    }

    /**
     * Rotates [values] by [offset] positions, wrapping around.
     *
     * This is how the null model is built: rotating one series against the other destroys any
     * relationship between them while leaving each series' own autocorrelation, marginal
     * distribution and ties completely intact. Shuffling would destroy the autocorrelation too and
     * produce a null that is far too narrow for daily self-tracking data.
     */
    fun circularShift(values: DoubleArray, offset: Int): DoubleArray {
        val n = values.size
        if (n == 0) return values
        val k = ((offset % n) + n) % n
        return DoubleArray(n) { values[(it + k) % n] }
    }

    /**
     * Trailing rolling mean over a calendar window, for display. Emits a value for a date only when
     * at least [minObservations] days inside the window have data, so a chart line does not imply
     * confidence it does not have.
     */
    fun trailingRollingMean(
        series: MetricSeries,
        windowDays: Int,
        minObservations: Int = windowDays / 2,
    ): Map<LocalDate, Double> {
        val result = LinkedHashMap<LocalDate, Double>()
        val sorted = series.dates
        for (d in sorted) {
            var acc = 0.0
            var count = 0
            var offset = 0L
            while (offset < windowDays) {
                series[d.minusDays(offset)]?.let { acc += it; count++ }
                offset++
            }
            if (count >= minObservations) result[d] = acc / count
        }
        return result
    }

    /**
     * Reassembles [values] from contiguous blocks taken in random order, with block lengths drawn
     * from a geometric distribution of mean [meanBlockLen].
     *
     * This is the null model for group comparisons (tags, day of week). A circular shift is wrong
     * there: rotating a weekday indicator by a multiple of seven reproduces the original alignment
     * exactly, so one draw in seven would return the observed effect and the null would collapse.
     * The block lengths are random rather than fixed for the same reason -- a fixed length that
     * happens to match the indicator's period preserves its phase and reintroduces the same
     * degeneracy.
     */
    fun blockPermute(values: DoubleArray, meanBlockLen: Int, rng: Random): DoubleArray {
        val n = values.size
        if (n <= 1) return values.copyOf()
        val mean = meanBlockLen.coerceIn(1, maxOf(1, n / 2))
        val p = 1.0 / mean

        val blocks = ArrayList<IntRange>()
        var start = 0
        while (start < n) {
            var len = 1
            while (len < n && rng.nextDouble() > p) len++
            val end = minOf(start + len, n)
            blocks.add(start until end)
            start = end
        }
        blocks.shuffle(rng)

        val out = DoubleArray(n)
        var pos = 0
        for (block in blocks) {
            for (k in block) {
                if (pos >= n) break
                out[pos++] = values[k]
            }
        }
        return out
    }

    /**
     * Personal sleep baseline: the median of the trailing [windowDays] of sleep. Using the user's
     * own median rather than a public "8 hours" figure is the point -- sleep debt is only meaningful
     * relative to what this person normally gets.
     */
    fun sleepBaseline(
        sleepMinutes: MetricSeries,
        windowDays: Int = 28,
        minObservations: Int = 7,
    ): Map<LocalDate, Double> {
        val result = LinkedHashMap<LocalDate, Double>()
        for (d in sleepMinutes.dates) {
            val window = ArrayList<Double>()
            var offset = 0L
            while (offset < windowDays) {
                sleepMinutes[d.minusDays(offset)]?.let { window.add(it) }
                offset++
            }
            if (window.size >= minObservations) {
                result[d] = Stats.median(window.toDoubleArray())
            }
        }
        return result
    }

    /**
     * Cumulative sleep debt over the trailing [days] nights: the summed shortfall against the
     * personal baseline, clamped at zero so that extra sleep on one night does not silently cancel
     * a bad night (recovery is not symmetric).
     *
     * Requires every night in the window to be present; a gap yields no value for that date rather
     * than an understated debt.
     */
    fun sleepDebt(
        sleepMinutes: MetricSeries,
        days: Int = 3,
        baselineWindowDays: Int = 28,
    ): MetricSeries {
        val baseline = sleepBaseline(sleepMinutes, baselineWindowDays)
        val result = LinkedHashMap<LocalDate, Double>()
        for (d in sleepMinutes.dates) {
            val base = baseline[d] ?: continue
            var debt = 0.0
            var complete = true
            for (offset in 0 until days) {
                val night = sleepMinutes[d.minusDays(offset.toLong())]
                if (night == null) { complete = false; break }
                debt += (base - night).coerceAtLeast(0.0)
            }
            if (complete) result[d] = debt
        }
        return MetricSeries("sleep_debt_${days}d", "${days}-day sleep debt", result)
    }
}
