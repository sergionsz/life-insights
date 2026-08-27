package dev.sergio.lifeinsights.insights

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Low-level statistics used by the insights engine.
 *
 * Design notes that matter for correctness:
 *
 *  - Correlations always run on RAW daily values. Never feed a rolling average in here: smoothing
 *    two independent series inflates their correlation dramatically (Slutsky-Yule). Rolling
 *    averages exist for charts only.
 *  - Daily self-tracking series are strongly autocorrelated, so the effective sample size is far
 *    below n. Textbook correlation p-values are anticonservative here. We therefore never report a
 *    p-value; we report a moving-block bootstrap confidence interval, which preserves short-range
 *    serial dependence.
 */
object Stats {

    fun mean(xs: DoubleArray): Double {
        require(xs.isNotEmpty()) { "mean of empty array" }
        return xs.sum() / xs.size
    }

    fun median(xs: DoubleArray): Double {
        require(xs.isNotEmpty()) { "median of empty array" }
        val s = xs.sortedArray()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    fun sampleStdDev(xs: DoubleArray): Double {
        if (xs.size < 2) return 0.0
        val m = mean(xs)
        var acc = 0.0
        for (x in xs) acc += (x - m) * (x - m)
        return sqrt(acc / (xs.size - 1))
    }

    /** Pearson product-moment correlation. Returns null if either series has zero variance. */
    fun pearson(x: DoubleArray, y: DoubleArray): Double? {
        require(x.size == y.size) { "pearson: length mismatch ${x.size} vs ${y.size}" }
        if (x.size < 3) return null
        val mx = mean(x)
        val my = mean(y)
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in x.indices) {
            val dx = x[i] - mx
            val dy = y[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        val denom = sqrt(sxx * syy)
        if (denom <= 1e-12) return null
        return (sxy / denom).coerceIn(-1.0, 1.0)
    }

    /**
     * Spearman rank correlation with average ranks for ties. This is the default throughout the
     * app: mood and energy are ordinal 7-point scales with heavy ties, and daily metrics like
     * screen time have long right tails, both of which break Pearson's assumptions.
     */
    fun spearman(x: DoubleArray, y: DoubleArray): Double? {
        require(x.size == y.size) { "spearman: length mismatch ${x.size} vs ${y.size}" }
        if (x.size < 3) return null
        return pearson(rank(x), rank(y))
    }

    /** Ranks 1..n, ties receive the average of the ranks they span. */
    fun rank(xs: DoubleArray): DoubleArray {
        val n = xs.size
        val order = (0 until n).sortedBy { xs[it] }
        val ranks = DoubleArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && xs[order[j + 1]] == xs[order[i]]) j++
            val avg = (i + j + 2) / 2.0 // ranks are 1-based: (i+1 .. j+1) averaged
            for (k in i..j) ranks[order[k]] = avg
            i = j + 1
        }
        return ranks
    }

    /**
     * Ordinary least squares residuals of [y] on [covariates] (an intercept is added automatically).
     * Used for partial correlation: residualize both series on the same confounders, then correlate
     * the residuals.
     *
     * Returns [y] unchanged if the system is singular, which is the safe degenerate behaviour: no
     * adjustment rather than a garbage adjustment.
     */
    fun residuals(y: DoubleArray, covariates: List<DoubleArray>): DoubleArray {
        val n = y.size
        val cols = buildList {
            add(DoubleArray(n) { 1.0 })
            covariates.forEach { c ->
                require(c.size == n) { "residuals: covariate length ${c.size} != $n" }
                // Drop constant covariates; they are collinear with the intercept.
                if (sampleStdDev(c) > 1e-12) add(c)
            }
        }
        val p = cols.size
        if (n <= p) return y.copyOf()

        // Normal equations: (X'X) b = X'y
        val xtx = Array(p) { i -> DoubleArray(p) { j -> dot(cols[i], cols[j]) } }
        val xty = DoubleArray(p) { i -> dot(cols[i], y) }
        val beta = solve(xtx, xty) ?: return y.copyOf()

        return DoubleArray(n) { row ->
            var fitted = 0.0
            for (j in 0 until p) fitted += beta[j] * cols[j][row]
            y[row] - fitted
        }
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var acc = 0.0
        for (i in a.indices) acc += a[i] * b[i]
        return acc
    }

    /** Gaussian elimination with partial pivoting. Returns null when the matrix is singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-10) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col] / m[col][col]
                if (f == 0.0) continue
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    /**
     * Moving-block bootstrap confidence interval for a paired statistic.
     *
     * [pairs] must be in chronological order. Resampling contiguous blocks (rather than individual
     * points) preserves the short-range autocorrelation of daily data, so the interval widens
     * honestly instead of pretending each day is independent evidence.
     */
    fun blockBootstrapCi(
        x: DoubleArray,
        y: DoubleArray,
        alpha: Double,
        replicates: Int = 2000,
        seed: Long = 20260827L,
        statistic: (DoubleArray, DoubleArray) -> Double?,
    ): Pair<Double, Double>? {
        val dist = blockBootstrapDistribution(x, y, replicates, seed, statistic) ?: return null
        return ciFrom(dist, alpha)
    }

    /** Percentile interval at level 1 - [alpha] from a sorted bootstrap distribution. */
    fun ciFrom(sortedDistribution: List<Double>, alpha: Double): Pair<Double, Double> =
        Pair(quantile(sortedDistribution, alpha / 2), quantile(sortedDistribution, 1 - alpha / 2))

    /**
     * Interval built from the bootstrap standard error rather than from bootstrap percentiles.
     *
     * This exists because the multiplicity adjustment drives alpha very low (0.05 divided by the
     * number of relationships tested), and a percentile interval at alpha = 0.001 would be reading
     * the 0.05th percentile off a thousand replicates -- effectively the minimum of the sample,
     * which understates the tail badly and lets noise through as a confident finding. The standard
     * error is estimated from the whole distribution and stays stable at any alpha.
     *
     * [transform] should symmetrise the statistic first: Fisher's z for correlations, identity for
     * mean differences.
     */
    fun seInterval(
        effect: Double,
        distribution: List<Double>,
        alpha: Double,
        transform: (Double) -> Double = { it },
        inverse: (Double) -> Double = { it },
    ): Pair<Double, Double>? {
        val transformed = distribution.map(transform).filter { it.isFinite() }
        if (transformed.size < 2) return null
        val se = sampleStdDev(transformed.toDoubleArray())
        if (se <= 1e-12) return null
        val z = normalQuantile(1 - alpha / 2)
        val centre = transform(effect)
        if (!centre.isFinite()) return null
        return Pair(inverse(centre - z * se), inverse(centre + z * se))
    }

    /** Standard normal CDF, via the Abramowitz-Stegun erf approximation (~1e-7 absolute). */
    fun normalCdf(z: Double): Double {
        val sign = if (z < 0) -1.0 else 1.0
        val x = abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t +
            0.254829592) * t * kotlin.math.exp(-x * x)
        return 0.5 * (1.0 + sign * y)
    }

    /**
     * Two-sided p-value for a bootstrap statistic, on the [transform]ed scale where the sampling
     * distribution is roughly normal.
     */
    fun bootstrapPValue(
        effect: Double,
        distribution: List<Double>,
        transform: (Double) -> Double = { it },
    ): Double? {
        val transformed = distribution.map(transform).filter { it.isFinite() }
        if (transformed.size < 2) return null
        val se = sampleStdDev(transformed.toDoubleArray())
        if (se <= 1e-12) return null
        val centre = transform(effect)
        if (!centre.isFinite()) return null
        return (2.0 * (1.0 - normalCdf(abs(centre) / se))).coerceIn(0.0, 1.0)
    }

    /**
     * Benjamini-Hochberg step-up threshold controlling the false discovery rate at [q] across
     * [total] tests. Returns the largest p-value that counts as a discovery, or null when nothing
     * survives.
     *
     * This is the middle ground the app needs. Testing every metric at an unadjusted 5% produces
     * roughly two spurious findings per run at this many comparisons; Bonferroni is strict enough
     * to hide real moderate effects in a few weeks of data. BH bounds the expected share of
     * reported findings that are noise, and when nothing real is present it reports something at
     * all only about q of the time.
     */
    fun benjaminiHochbergThreshold(pValues: List<Double>, total: Int, q: Double): Double? {
        if (pValues.isEmpty() || total <= 0) return null
        val sorted = pValues.sorted()
        var threshold: Double? = null
        for ((index, p) in sorted.withIndex()) {
            if (p <= (index + 1).toDouble() / total * q) threshold = p
        }
        return threshold
    }

    /**
     * Standard deviation of a statistic under a null model, on the transformed scale.
     *
     * [draw] produces one statistic from a null resampling of the data. Callers choose the null
     * that fits their test: a circular shift of one series for correlations, a block permutation
     * for group comparisons.
     */
    fun nullSd(
        seed: Long,
        samples: Int = 300,
        transform: (Double) -> Double = ::fisherZ,
        draw: (Random) -> Double?,
    ): Double? {
        val rng = Random(seed xor 0x5DEECE66DL)
        val values = ArrayList<Double>(samples)
        repeat(samples) {
            draw(rng)?.let { v ->
                val t = transform(v)
                if (t.isFinite()) values.add(t)
            }
        }
        if (values.size < samples / 2) return null
        val sd = sampleStdDev(values.toDoubleArray())
        return if (sd > 1e-12) sd else null
    }

    /**
     * A rotation offset that actually moves the series: offsets near 0 or n leave most of the
     * alignment intact and would drag the null towards the observed value.
     */
    fun randomShiftOffset(n: Int, rng: Random): Int? {
        val minOffset = maxOf(3, n / 20)
        if (n - 2 * minOffset < 8) return null
        return minOffset + rng.nextInt(n - 2 * minOffset)
    }

    /**
     * Upper-tail probability of Student's t with [df] degrees of freedom.
     *
     * The null statistic is not quite normal: with a 7-point ordinal outcome, Spearman's rho carries
     * heavy tie mass and its null distribution has fatter tails than the Gaussian. Measured against
     * simulated ordinal data the null's quantiles sit about 5% further out than normal at every
     * level (2.05 vs 1.96 at 95%, 2.69 vs 2.58 at 99%, 3.49 vs 3.29 at 99.9%), which t with roughly
     * 30 degrees of freedom reproduces closely while staying slightly conservative in the far tail.
     * Using the normal here made the strictest confidence tier about twice as generous as it
     * claimed to be.
     */
    fun studentTSurvival(t: Double, df: Int): Double {
        require(df > 0) { "studentTSurvival: df must be positive" }
        val x = df / (df + t * t)
        val half = 0.5 * regularizedIncompleteBeta(x, df / 2.0, 0.5)
        return if (t >= 0) half else 1.0 - half
    }

    /** Regularized incomplete beta function I_x(a, b), via Lentz's continued fraction. */
    fun regularizedIncompleteBeta(x: Double, a: Double, b: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= 1.0) return 1.0
        val lbeta = lnGamma(a + b) - lnGamma(a) - lnGamma(b) +
            a * kotlin.math.ln(x) + b * kotlin.math.ln(1.0 - x)
        val front = kotlin.math.exp(lbeta)
        return if (x < (a + 1.0) / (a + b + 2.0)) {
            front * betaContinuedFraction(x, a, b) / a
        } else {
            1.0 - front * betaContinuedFraction(1.0 - x, b, a) / b
        }
    }

    private fun betaContinuedFraction(x: Double, a: Double, b: Double): Double {
        val tiny = 1e-30
        var c = 1.0
        var d = 1.0 - (a + b) * x / (a + 1.0)
        if (abs(d) < tiny) d = tiny
        d = 1.0 / d
        var result = d
        for (m in 1..300) {
            val m2 = 2 * m
            var numerator = m * (b - m) * x / ((a + m2 - 1) * (a + m2))
            d = 1.0 + numerator * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + numerator / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            result *= c * d

            numerator = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1))
            d = 1.0 + numerator * d
            if (abs(d) < tiny) d = tiny
            c = 1.0 + numerator / c
            if (abs(c) < tiny) c = tiny
            d = 1.0 / d
            val delta = c * d
            result *= delta
            if (abs(delta - 1.0) < 1e-12) break
        }
        return result
    }

    /** Lanczos approximation to ln(gamma(z)). */
    fun lnGamma(z: Double): Double {
        val g = doubleArrayOf(
            676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7,
        )
        if (z < 0.5) {
            return kotlin.math.ln(Math.PI / kotlin.math.sin(Math.PI * z)) - lnGamma(1.0 - z)
        }
        val x = z - 1.0
        var acc = 0.99999999999980993
        for (i in g.indices) acc += g[i] / (x + i + 1.0)
        val t = x + g.size - 0.5
        return 0.5 * kotlin.math.ln(2 * Math.PI) + (x + 0.5) * kotlin.math.ln(t) - t +
            kotlin.math.ln(acc)
    }

    /** Two-sided p-value for [effect] against a null with standard deviation [nullSd]. */
    fun pValueAgainstNull(
        effect: Double,
        nullSd: Double,
        transform: (Double) -> Double = ::fisherZ,
        referenceDf: Int = 30,
    ): Double {
        val centre = transform(effect)
        if (!centre.isFinite() || nullSd <= 1e-12) return 1.0
        return (2.0 * studentTSurvival(abs(centre) / nullSd, referenceDf)).coerceIn(0.0, 1.0)
    }

    /** Fisher's z. Correlations are bounded and skewed near +/-1; z is not. */
    fun fisherZ(r: Double): Double = kotlin.math.atanh(r.coerceIn(-0.999999, 0.999999))

    fun inverseFisherZ(z: Double): Double = kotlin.math.tanh(z)

    /**
     * Inverse standard normal CDF (Acklam's rational approximation, ~1e-9 relative accuracy).
     * Needed because the adjusted alpha can sit far out in the tail where a lookup table would not
     * reach.
     */
    fun normalQuantile(p: Double): Double {
        require(p > 0.0 && p < 1.0) { "normalQuantile: p must be in (0, 1), was $p" }
        val a = doubleArrayOf(
            -3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00,
        )
        val b = doubleArrayOf(
            -5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01,
        )
        val c = doubleArrayOf(
            -7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00,
        )
        val d = doubleArrayOf(
            7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
            3.754408661907416e+00,
        )
        val pLow = 0.02425
        val pHigh = 1 - pLow
        return when {
            p < pLow -> {
                val q = sqrt(-2 * kotlin.math.ln(p))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
            p <= pHigh -> {
                val q = p - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
            }
            else -> {
                val q = sqrt(-2 * kotlin.math.ln(1 - p))
                -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
        }
    }

    /**
     * The sorted moving-block bootstrap distribution of [statistic]. Returned rather than a single
     * interval so the caller can read several confidence levels off one resampling run -- the
     * insights engine needs both a plain 95% interval and a multiplicity-adjusted one.
     */
    fun blockBootstrapDistribution(
        x: DoubleArray,
        y: DoubleArray,
        replicates: Int = 2000,
        seed: Long = 20260827L,
        statistic: (DoubleArray, DoubleArray) -> Double?,
    ): List<Double>? {
        require(x.size == y.size)
        val n = x.size
        if (n < 6) return null
        val blockLen = blockLength(n)
        val numBlocks = ceilDiv(n, blockLen)
        val rng = Random(seed)
        val stats = ArrayList<Double>(replicates)
        val bx = DoubleArray(numBlocks * blockLen)
        val by = DoubleArray(numBlocks * blockLen)

        repeat(replicates) {
            var pos = 0
            repeat(numBlocks) {
                val start = rng.nextInt(n - blockLen + 1)
                for (k in 0 until blockLen) {
                    bx[pos] = x[start + k]
                    by[pos] = y[start + k]
                    pos++
                }
            }
            val sx = if (bx.size == n) bx else bx.copyOf(n)
            val sy = if (by.size == n) by else by.copyOf(n)
            statistic(sx, sy)?.let { if (!it.isNaN()) stats.add(it) }
        }
        if (stats.size < replicates / 2) return null
        stats.sort()
        return stats
    }

    /** Optimal-order block length for a moving-block bootstrap: O(n^(1/3)). */
    fun blockLength(n: Int): Int = cbrt(n.toDouble()).roundToInt().coerceIn(2, maxOf(2, n / 2))

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    /** Linearly interpolated quantile of a pre-sorted list. */
    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val pos = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }
}
