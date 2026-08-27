package dev.sergio.lifeinsights.insights

/**
 * Minimum data before each analysis is allowed to produce a claim.
 *
 * These are deliberately per-analysis rather than one global gate. Fourteen days is a defensible
 * floor for a same-day correlation but gives only two observations per weekday, which is nowhere
 * near enough to say anything about Mondays.
 */
object Thresholds {
    const val MIN_DAYS_SAME_DAY = 14
    const val MIN_DAYS_LAGGED = 21
    const val MIN_DAYS_SLEEP_DEBT = 21
    const val MIN_DAYS_DAY_OF_WEEK = 42
    const val MIN_DAYS_TAG = 14

    /** A tag needs enough days both with and without it, or the comparison is meaningless. */
    const val MIN_TAG_OCCURRENCES = 4

    const val MAX_LAG_DAYS = 3

    /** Family-wise level for the top confidence tier, split across every relationship tested. */
    const val FAMILYWISE_ALPHA = 0.05

    /** False discovery rate allowed among reported findings. */
    const val FDR_Q = 0.10

    /** Level used for the interval that is actually displayed next to a finding. */
    const val DISPLAY_ALPHA = 0.05

    const val BOOTSTRAP_REPLICATES = 1000

    /** Window for the slow-drift covariate; roughly a month of context around each day. */
    const val DETREND_WINDOW_DAYS = 29
}
