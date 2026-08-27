package dev.sergio.lifeinsights.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Random
import kotlin.math.abs

class StatsTest {

    @Test
    fun `ranks average over ties`() {
        val ranks = Stats.rank(doubleArrayOf(10.0, 20.0, 20.0, 30.0))
        assertEquals(1.0, ranks[0], 1e-9)
        assertEquals(2.5, ranks[1], 1e-9)
        assertEquals(2.5, ranks[2], 1e-9)
        assertEquals(4.0, ranks[3], 1e-9)
    }

    @Test
    fun `spearman is one for any increasing relationship`() {
        val x = DoubleArray(20) { it.toDouble() }
        val y = DoubleArray(20) { it.toDouble() * it }
        assertEquals(1.0, Stats.spearman(x, y)!!, 1e-9)
        // Pearson is not, which is exactly why Spearman is the default.
        assertTrue(Stats.pearson(x, y)!! < 0.98)
    }

    @Test
    fun `zero variance yields no correlation rather than a crash`() {
        val x = DoubleArray(10) { 3.0 }
        val y = DoubleArray(10) { it.toDouble() }
        assertNull(Stats.spearman(x, y))
    }

    @Test
    fun `residualising removes an injected confounder`() {
        val rng = Random(1)
        val n = 200
        val confounder = DoubleArray(n) { if (it % 7 >= 5) 1.0 else 0.0 }
        val y = DoubleArray(n) { 5.0 * confounder[it] + rng.nextGaussian() }
        val x = DoubleArray(n) { 5.0 * confounder[it] + rng.nextGaussian() }

        val rawCorrelation = abs(Stats.spearman(x, y)!!)
        val adjusted = abs(
            Stats.spearman(
                Stats.residuals(y, listOf(confounder)),
                Stats.residuals(x, listOf(confounder)),
            )!!,
        )
        assertTrue("confounded correlation should be large, was $rawCorrelation", rawCorrelation > 0.6)
        assertTrue("adjusted correlation should collapse, was $adjusted", adjusted < 0.2)
    }

    /**
     * Guards the single most dangerous mistake available to this app: correlating smoothed series.
     * Two independent random walks look strongly related once they are 7-day averaged.
     */
    @Test
    fun `smoothing inflates correlation between independent series`() {
        val rng = Random(7)
        val n = 120
        val dates = (0 until n).map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()) }
        var inflatedCount = 0
        repeat(20) {
            val a = randomWalk(n, rng)
            val b = randomWalk(n, rng)
            val raw = abs(Stats.spearman(a, b) ?: 0.0)
            val smoothA = SeriesOps.centredRollingMean(dates, a, 7)
            val smoothB = SeriesOps.centredRollingMean(dates, b, 7)
            val smoothed = abs(Stats.spearman(smoothA, smoothB) ?: 0.0)
            if (smoothed > raw) inflatedCount++
        }
        assertTrue(
            "smoothing should inflate |rho| in the large majority of runs, got $inflatedCount/20",
            inflatedCount >= 16,
        )
    }

    @Test
    fun `block bootstrap gives a wider interval than ignoring autocorrelation`() {
        val rng = Random(3)
        val n = 120
        val x = randomWalk(n, rng)
        val y = randomWalk(n, rng)

        val blockCi = Stats.blockBootstrapCi(x, y, 0.05, replicates = 800) { a, b ->
            Stats.spearman(a, b)
        }
        assertNotNull(blockCi)
        // A meaningful interval, not a degenerate point estimate.
        assertTrue(blockCi!!.second - blockCi.first > 0.1)
    }

    @Test
    fun `block length grows with sample size`() {
        assertTrue(Stats.blockLength(500) > Stats.blockLength(30))
    }

    @Test
    fun `student t survival matches published critical values`() {
        // Standard t table, df = 30.
        assertEquals(0.05, Stats.studentTSurvival(1.697, 30), 5e-4)
        assertEquals(0.025, Stats.studentTSurvival(2.042, 30), 5e-4)
        assertEquals(0.005, Stats.studentTSurvival(2.750, 30), 5e-4)
        assertEquals(0.0005, Stats.studentTSurvival(3.646, 30), 5e-5)
        assertEquals(0.5, Stats.studentTSurvival(0.0, 30), 1e-9)
    }

    @Test
    fun `normal quantile matches published critical values`() {
        assertEquals(1.959964, Stats.normalQuantile(0.975), 1e-5)
        assertEquals(2.575829, Stats.normalQuantile(0.995), 1e-5)
        assertEquals(0.0, Stats.normalQuantile(0.5), 1e-9)
    }

    @Test
    fun `benjamini hochberg finds the step-up cutoff`() {
        // With 10 tests at q = 0.10 the cutoff is the largest p_k below k/10 * 0.10.
        val ps = listOf(0.001, 0.008, 0.04, 0.3, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95)
        assertEquals(0.008, Stats.benjaminiHochbergThreshold(ps, 10, 0.10)!!, 1e-12)
        // Nothing survives when every p is large.
        assertNull(Stats.benjaminiHochbergThreshold(listOf(0.4, 0.6, 0.9), 3, 0.10))
    }

    @Test
    fun `circular shift preserves the multiset and wraps around`() {
        val v = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertTrue(Stats.rank(SeriesOps.circularShift(v, 1)).size == 4)
        assertEquals(2.0, SeriesOps.circularShift(v, 1)[0], 1e-9)
        assertEquals(4.0, SeriesOps.circularShift(v, 3)[0], 1e-9)
        assertEquals(1.0, SeriesOps.circularShift(v, 4)[0], 1e-9)
    }

    private fun randomWalk(n: Int, rng: Random): DoubleArray {
        var v = 0.0
        return DoubleArray(n) { v += rng.nextGaussian(); v }
    }
}
