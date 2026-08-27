package dev.sergio.lifeinsights.insights

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

data class InsightsInput(
    /** Subjective outcomes: mood and energy. */
    val outcomes: List<MetricSeries>,
    /** Everything measured automatically, plus any derived series. */
    val predictors: List<MetricSeries>,
    /** Context tags to the set of days they were logged on. */
    val tagDays: Map<String, Set<LocalDate>> = emptyMap(),
)

/**
 * Turns daily series into ranked, honestly-hedged statements.
 *
 * The hard part here is not computing correlations, it is refusing to report the ones that are
 * artefacts. Four things are done deliberately:
 *
 *  1. Correlations run on raw daily values, never on rolling averages.
 *  2. Both series are residualised on a weekday/weekend indicator and on a ~month-long local trend,
 *     so that "weekends change everything at once" and slow life drift do not masquerade as a
 *     relationship between two specific metrics.
 *  3. Confidence intervals come from a moving-block bootstrap, which keeps the day-to-day
 *     autocorrelation of the data instead of pretending each day is independent evidence.
 *  4. Every relationship examined counts towards a multiplicity adjustment, including each lag
 *     tried. Picking the best of four lags and reporting it at face value is exactly how noise gets
 *     promoted to insight.
 */
class InsightsEngine(
    private val replicates: Int = Thresholds.BOOTSTRAP_REPLICATES,
    private val seed: Long = 20260827L,
) {

    fun analyse(input: InsightsInput): InsightsReport {
        val candidates = ArrayList<TestResult>()
        val pending = ArrayList<PendingAnalysis>()

        val outcomeDays = input.outcomes.maxOfOrNull { it.size } ?: 0

        for (outcome in input.outcomes) {
            for (predictor in input.predictors) {
                for (lag in 0..Thresholds.MAX_LAG_DAYS) {
                    val minDays =
                        if (lag == 0) Thresholds.MIN_DAYS_SAME_DAY else Thresholds.MIN_DAYS_LAGGED
                    correlationTest(outcome, predictor, lag, minDays)?.let(candidates::add)
                }
            }

            for ((tag, days) in input.tagDays) {
                groupTest(
                    id = "tag:${outcome.key}:$tag",
                    kind = InsightKind.TAG,
                    outcome = outcome,
                    groupLabel = tag,
                    inGroup = { it in days },
                    minDays = Thresholds.MIN_DAYS_TAG,
                    adjustForWeekend = true,
                )?.let(candidates::add)
            }

            if (outcome.size >= Thresholds.MIN_DAYS_DAY_OF_WEEK) {
                for (dow in DayOfWeek.entries) {
                    groupTest(
                        id = "dow:${outcome.key}:${dow.name}",
                        kind = InsightKind.DAY_OF_WEEK,
                        outcome = outcome,
                        groupLabel = dow.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                        inGroup = { it.dayOfWeek == dow },
                        minDays = Thresholds.MIN_DAYS_DAY_OF_WEEK,
                        // The weekend IS the effect being measured here, so do not adjust it away.
                        adjustForWeekend = false,
                    )?.let(candidates::add)
                }
            }
        }

        addPendingAnalyses(input, outcomeDays, pending)

        // The family is every relationship actually examined, including each lag tried. Trying four
        // lags and reporting only the best one at face value is precisely how noise becomes an
        // insight, so every attempt counts towards the correction.
        val family = candidates.size.coerceAtLeast(1)
        val bonferroniAlpha = Thresholds.FAMILYWISE_ALPHA / family

        val scored = candidates.map { candidate ->
            candidate to Stats.pValueAgainstNull(
                candidate.effect, candidate.nullSd, candidate.transform(),
            )
        }
        val fdrThreshold =
            Stats.benjaminiHochbergThreshold(scored.map { it.second }, family, Thresholds.FDR_Q)

        val surviving = scored.mapNotNull { (candidate, p) ->
            if (fdrThreshold == null || p > fdrThreshold) return@mapNotNull null
            candidate.toInsight(
                confidence = if (p <= bonferroniAlpha) Confidence.CLEAR else Confidence.TENTATIVE,
                input = input,
            )
        }
        val ranked = dedupeToStrongestPerPair(surviving).sortedByDescending { it.rankScore }

        return InsightsReport(insights = ranked, pending = pending, testsRun = candidates.size)
    }

    // --- tests -------------------------------------------------------------------------------

    private fun correlationTest(
        outcome: MetricSeries,
        predictor: MetricSeries,
        lag: Int,
        minDays: Int,
    ): TestResult? {
        val aligned = SeriesOps.align(outcome, predictor, lag)
        if (aligned.n < minDays) return null

        val weekend = aligned.weekendDummy()
        val yRes = detrendAndAdjust(aligned.dates, aligned.outcome, weekend)
        val xRes = detrendAndAdjust(aligned.dates, aligned.predictor, weekend)

        val rho = Stats.spearman(yRes, xRes) ?: return null
        val dist = Stats.blockBootstrapDistribution(yRes, xRes, replicates, seed) { a, b ->
            Stats.spearman(a, b)
        } ?: return null

        // Null model: rotate the predictor against the outcome, re-running the same detrending on
        // the rotated series so the null reflects the whole procedure and not just the final
        // correlation step.
        val nullSd = Stats.nullSd(seed) { rng ->
            val offset = Stats.randomShiftOffset(aligned.n, rng) ?: return@nullSd null
            val shifted = SeriesOps.circularShift(aligned.predictor, offset)
            Stats.spearman(yRes, detrendAndAdjust(aligned.dates, shifted, weekend))
        } ?: return null

        val kind = when {
            predictor.key.startsWith("sleep_debt") -> InsightKind.SLEEP_DEBT
            lag == 0 -> InsightKind.SAME_DAY
            else -> InsightKind.LAGGED
        }
        return TestResult(
            id = "corr:${outcome.key}:${predictor.key}:lag$lag",
            pairKey = "${outcome.key}|${predictor.key}",
            kind = kind,
            outcome = outcome,
            predictor = predictor,
            lag = lag,
            groupLabel = null,
            effect = rho,
            distribution = dist,
            nullSd = nullSd,
            n = aligned.n,
        )
    }

    private fun groupTest(
        id: String,
        kind: InsightKind,
        outcome: MetricSeries,
        groupLabel: String,
        inGroup: (LocalDate) -> Boolean,
        minDays: Int,
        adjustForWeekend: Boolean,
    ): TestResult? {
        val dates = outcome.dates
        if (dates.size < minDays) return null

        val values = DoubleArray(dates.size) { outcome[dates[it]]!! }
        val indicator = DoubleArray(dates.size) { if (inGroup(dates[it])) 1.0 else 0.0 }

        val inCount = indicator.count { it == 1.0 }
        val outCount = indicator.size - inCount
        if (inCount < Thresholds.MIN_TAG_OCCURRENCES || outCount < Thresholds.MIN_TAG_OCCURRENCES) {
            return null
        }

        val weekend = if (adjustForWeekend) {
            DoubleArray(dates.size) { if (dates[it].dayOfWeek.value >= 6) 1.0 else 0.0 }
        } else {
            null
        }
        val yRes = detrendAndAdjust(dates, values, weekend)

        val d = standardisedMeanDifference(yRes, indicator) ?: return null
        val dist = Stats.blockBootstrapDistribution(yRes, indicator, replicates, seed) { a, b ->
            standardisedMeanDifference(a, b)
        } ?: return null

        // Rotating the group indicator keeps its structure -- a weekday indicator stays a weekday
        // indicator, a tag keeps its clustering -- while breaking any link to the outcome.
        // Block-permute the outcome rather than shifting the indicator: a weekday indicator is
        // period-7, so rotating it would reproduce the observed alignment one draw in seven.
        val blockLen = Stats.blockLength(dates.size)
        val nullSd = Stats.nullSd(seed = seed, transform = { v -> v }) { rng ->
            standardisedMeanDifference(SeriesOps.blockPermute(yRes, blockLen, rng), indicator)
        } ?: return null

        return TestResult(
            id = id,
            pairKey = id,
            kind = kind,
            outcome = outcome,
            predictor = null,
            lag = 0,
            groupLabel = groupLabel,
            effect = d,
            distribution = dist,
            nullSd = nullSd,
            n = dates.size,
            rawMeanDifference = rawMeanDifference(values, indicator),
        )
    }

    /**
     * Residualise on the weekend indicator (when supplied) and on a centred ~month-long rolling
     * mean of the series itself, so what gets correlated is each day's deviation from its own local
     * normal rather than the shared shape of two slow trends.
     */
    private fun detrendAndAdjust(
        dates: List<LocalDate>,
        values: DoubleArray,
        weekend: DoubleArray?,
    ): DoubleArray {
        val trend = SeriesOps.centredRollingMean(dates, values, Thresholds.DETREND_WINDOW_DAYS)
        val covariates = buildList {
            weekend?.let { add(it) }
            add(trend)
        }
        return Stats.residuals(values, covariates)
    }

    private fun standardisedMeanDifference(values: DoubleArray, indicator: DoubleArray): Double? {
        val diff = rawMeanDifference(values, indicator) ?: return null
        val sd = Stats.sampleStdDev(values)
        if (sd <= 1e-9) return null
        return diff / sd
    }

    private fun rawMeanDifference(values: DoubleArray, indicator: DoubleArray): Double? {
        var inSum = 0.0; var inN = 0
        var outSum = 0.0; var outN = 0
        for (i in values.indices) {
            if (indicator[i] == 1.0) { inSum += values[i]; inN++ } else { outSum += values[i]; outN++ }
        }
        if (inN == 0 || outN == 0) return null
        return inSum / inN - outSum / outN
    }

    // --- reporting ---------------------------------------------------------------------------

    /** Correlations are symmetrised with Fisher's z; mean differences need no transform. */
    private fun TestResult.transform(): (Double) -> Double =
        if (isGroupComparison()) { v -> v } else Stats::fisherZ

    private fun TestResult.inverse(): (Double) -> Double =
        if (isGroupComparison()) { v -> v } else Stats::inverseFisherZ

    private fun TestResult.isGroupComparison(): Boolean =
        kind == InsightKind.TAG || kind == InsightKind.DAY_OF_WEEK

    private fun TestResult.toInsight(confidence: Confidence, input: InsightsInput): Insight? {
        // A relationship can be statistically detectable and still far too small to act on.
        val strength = Bands.strengthOf(effect) ?: return null

        val plainCi = Stats.seInterval(
            effect, distribution, Thresholds.DISPLAY_ALPHA, transform(), inverse(),
        ) ?: return null

        val caveats = ArrayList<String>()
        if (confidence == Confidence.TENTATIVE) {
            caveats += "Survives the false-discovery check but not the strictest one, so treat " +
                "it as a lead rather than a settled finding."
        }
        if (n < 30) caveats += "Based on a short history ($n days); expect this to move."

        if (kind == InsightKind.LAGGED || kind == InsightKind.SLEEP_DEBT) {
            reverseDirectionCaveat(input)?.let { caveats += it }
        }

        return Insight(
            id = id,
            kind = kind,
            headline = headline(strength),
            detail = detail(strength, plainCi),
            effect = effect,
            ciLow = plainCi.first,
            ciHigh = plainCi.second,
            n = n,
            confidence = confidence,
            strength = strength,
            caveats = caveats,
        )
    }

    /**
     * Low mood plausibly causes doomscrolling at least as readily as the reverse, so for any lagged
     * finding we also measure the relationship running the other way. If it is comparably strong,
     * say so rather than implying a direction the data cannot support.
     */
    private fun TestResult.reverseDirectionCaveat(input: InsightsInput): String? {
        val pred = predictor ?: return null
        if (lag == 0) return null
        val reverse = SeriesOps.align(pred, outcome, lag)
        if (reverse.n < Thresholds.MIN_DAYS_LAGGED) return null
        val weekend = reverse.weekendDummy()
        val a = detrendAndAdjust(reverse.dates, reverse.outcome, weekend)
        val b = detrendAndAdjust(reverse.dates, reverse.predictor, weekend)
        val reverseRho = Stats.spearman(a, b) ?: return null
        return if (abs(reverseRho) >= abs(effect) * 0.8) {
            "The reverse also holds about as strongly (${outcome.label} predicts later " +
                "${pred.label} at ${fmt(reverseRho)}), so which way this runs is unclear."
        } else {
            null
        }
    }

    private fun TestResult.headline(strength: Strength): String = when (kind) {
        InsightKind.SAME_DAY ->
            "Higher ${predictor!!.label.lowercase()} goes with " +
                "${Bands.direction(effect)} ${outcome.label.lowercase()} the same day"
        InsightKind.LAGGED ->
            "${predictor!!.label} ${lagPhrase(lag)} tracks " +
                "${Bands.direction(effect)} ${outcome.label.lowercase()}"
        InsightKind.SLEEP_DEBT ->
            "Accumulated sleep debt tracks ${Bands.direction(effect)} ${outcome.label.lowercase()}"
        InsightKind.TAG ->
            "${outcome.label} runs ${Bands.direction(effect)} on days tagged \"$groupLabel\""
        InsightKind.DAY_OF_WEEK ->
            "${outcome.label} tends to be ${Bands.direction(effect)} on ${groupLabel}s"
    }.replaceFirstChar { it.uppercase() }

    private fun TestResult.detail(strength: Strength, ci: Pair<Double, Double>): String {
        val stat = when (kind) {
            InsightKind.TAG, InsightKind.DAY_OF_WEEK -> {
                val raw = rawMeanDifference?.let {
                    ", about ${fmt(abs(it))} points on the scale"
                } ?: ""
                "${strength.label().replaceFirstChar { c -> c.uppercase() }} difference " +
                    "(d = ${fmt(effect)}$raw"
            }
            else ->
                "${strength.label().replaceFirstChar { c -> c.uppercase() }} association " +
                    "(rho = ${fmt(effect)}"
        }
        return "$stat, 95% CI ${fmt(ci.first)} to ${fmt(ci.second)}, over $n days). " +
            "Adjusted for weekday/weekend and slow drift. " +
            "This is an association in your own data, not evidence of cause."
    }

    /**
     * One statement per outcome/predictor pair. Trying four lags and reporting each surviving one
     * would present a single relationship as four pieces of evidence.
     */
    private fun dedupeToStrongestPerPair(insights: List<Insight>): List<Insight> =
        insights.groupBy { it.id.substringBeforeLast(":lag") }
            .map { (_, group) -> group.maxByOrNull { it.rankScore }!! }

    private fun addPendingAnalyses(
        input: InsightsInput,
        outcomeDays: Int,
        pending: MutableList<PendingAnalysis>,
    ) {
        if (outcomeDays < Thresholds.MIN_DAYS_SAME_DAY) {
            pending += PendingAnalysis(
                "same_day", "Same-day patterns", outcomeDays, Thresholds.MIN_DAYS_SAME_DAY,
            )
        }
        if (outcomeDays < Thresholds.MIN_DAYS_LAGGED) {
            pending += PendingAnalysis(
                "lagged", "Effects of previous days", outcomeDays, Thresholds.MIN_DAYS_LAGGED,
            )
        }
        if (outcomeDays < Thresholds.MIN_DAYS_DAY_OF_WEEK) {
            pending += PendingAnalysis(
                "day_of_week", "Day-of-week pattern", outcomeDays, Thresholds.MIN_DAYS_DAY_OF_WEEK,
            )
        }
        if (input.predictors.none { it.key.startsWith("sleep") }) {
            pending += PendingAnalysis("sleep", "Sleep effects", 0, Thresholds.MIN_DAYS_SLEEP_DEBT)
        }
    }

    private fun lagPhrase(lag: Int): String = when (lag) {
        1 -> "the day before"
        else -> "$lag days earlier"
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    private data class TestResult(
        val id: String,
        val pairKey: String,
        val kind: InsightKind,
        val outcome: MetricSeries,
        val predictor: MetricSeries?,
        val lag: Int,
        val groupLabel: String?,
        val effect: Double,
        val distribution: List<Double>,
        /** Standard deviation of the statistic under the circular-shift null, on the transformed scale. */
        val nullSd: Double,
        val n: Int,
        val rawMeanDifference: Double? = null,
    )
}
