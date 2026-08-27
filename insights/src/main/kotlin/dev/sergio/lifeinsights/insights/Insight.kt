package dev.sergio.lifeinsights.insights

import kotlin.math.abs

enum class InsightKind { SAME_DAY, LAGGED, SLEEP_DEBT, TAG, DAY_OF_WEEK }

/**
 * How much the data actually supports the claim.
 *
 * [CLEAR] means the interval still excludes zero after adjusting for how many relationships were
 * tested at once; [TENTATIVE] means it only survives at the unadjusted 95% level, which -- given
 * that the app tests dozens of pairs -- is roughly what pure chance produces a couple of times per
 * run. Nothing weaker than TENTATIVE is ever shown.
 */
enum class Confidence { CLEAR, TENTATIVE }

enum class Strength { WEAK, MODERATE, STRONG;
    fun label(): String = when (this) {
        WEAK -> "weak"
        MODERATE -> "moderate"
        STRONG -> "strong"
    }
}

data class Insight(
    val id: String,
    val kind: InsightKind,
    val headline: String,
    val detail: String,
    /** Spearman rho for correlations, Cohen's d for group comparisons. */
    val effect: Double,
    val ciLow: Double,
    val ciHigh: Double,
    val n: Int,
    val confidence: Confidence,
    val strength: Strength,
    val caveats: List<String> = emptyList(),
) {
    /** Ranking key: prefer well-supported findings, then larger effects. */
    val rankScore: Double
        get() = abs(effect) * (if (confidence == Confidence.CLEAR) 1.0 else 0.6)
}

/** An analysis that was deliberately not run because there is not yet enough data for it. */
data class PendingAnalysis(
    val id: String,
    val label: String,
    val daysAvailable: Int,
    val daysRequired: Int,
) {
    val daysRemaining: Int get() = (daysRequired - daysAvailable).coerceAtLeast(0)
}

data class InsightsReport(
    val insights: List<Insight>,
    val pending: List<PendingAnalysis>,
    /** Number of relationships tested, which is what the multiplicity adjustment corrects for. */
    val testsRun: Int,
) {
    val isEmpty: Boolean get() = insights.isEmpty()
}

internal object Bands {
    fun strengthOf(effect: Double): Strength? = when {
        abs(effect) < 0.20 -> null
        abs(effect) < 0.35 -> Strength.WEAK
        abs(effect) < 0.55 -> Strength.MODERATE
        else -> Strength.STRONG
    }

    fun direction(effect: Double): String = if (effect >= 0) "higher" else "lower"
}
