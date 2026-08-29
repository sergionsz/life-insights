package dev.sergio.lifeinsights.sync

/**
 * How two versions of the same row are reconciled.
 *
 * Both ends run this identical code: the phone runs it when applying a pulled row over its local
 * one, and the server runs it when applying a pushed row over its stored one. That is the point of
 * putting it in a shared module. If the two sides resolved conflicts differently they would each
 * keep their own version, push it, and never converge.
 *
 * Three properties every rule here has to hold, or the two ends can disagree forever:
 *
 *  - **Total.** Any two versions have a winner. There is no "ask the user" case, because sync runs
 *    in the background with nobody to ask.
 *  - **Commutative.** `merge(a, b) == merge(b, a)`. Which side happened to be holding which version
 *    must not change the answer, since the phone and the server see the pair in opposite orders.
 *  - **Deterministic on ties.** Equal timestamps are not rare: two edits inside the same
 *    millisecond are unlikely, but a clock set backwards makes exact ties ordinary. "Prefer the
 *    incoming row" looks reasonable and is the trap, because it is not commutative: the server
 *    would keep the phone's version while the phone keeps the server's. Ties fall back to comparing
 *    the rows' content, which is arbitrary but identical on both ends.
 */
object Merge {

    fun checkIn(local: SyncCheckIn?, incoming: SyncCheckIn?): SyncCheckIn? {
        if (local == null) return incoming
        if (incoming == null) return local
        require(local.uid == incoming.uid) {
            "Merge.checkIn: different rows (${local.uid} vs ${incoming.uid})"
        }
        // A check-in is small and self-consistent: mood, energy, note and tags were chosen together
        // in one sitting. Merging them field by field could produce a row nobody ever entered, so
        // the whole row moves as a unit.
        return if (wins(incoming, local, { it.updatedAtUtc }, ::canonical)) incoming else local
    }

    fun tag(local: SyncTag?, incoming: SyncTag?): SyncTag? {
        if (local == null) return incoming
        if (incoming == null) return local
        require(local.name == incoming.name) {
            "Merge.tag: different rows (${local.name} vs ${incoming.name})"
        }
        return if (wins(incoming, local, { it.updatedAtUtc }, ::canonical)) incoming else local
    }

    /**
     * Daily metrics merge one source group at a time, unlike check-ins.
     *
     * Taking the newer row whole would lose data, because null here means "this device has no
     * reading" rather than "the value is zero", and the sources are independent. A phone without
     * usage access granted still reports steps; its row, pushed later, would erase the screen time
     * a second device had recorded for the same day.
     *
     * Merging each of the twelve fields on its own does not work either, and the reason is worth
     * recording because it looks fine until it is tested. With one timestamp for the row, a merged
     * row claims `max(a, b)` for values that came from whichever side was older. That freshly
     * stamped stale value then beats a genuinely newer one on the next merge, so the answer depends
     * on the order the rows happened to arrive in. A randomised order-independence test found it
     * immediately: three rows, two orders, two different answers.
     *
     * So the unit is the source group, each carrying its own timestamp: usage stats, the sleep
     * proxy, Health Connect. Within a group the fields are written together by one computation and
     * move together. Across groups they never interfere. Every group timestamp is preserved rather
     * than collapsed, which is what makes the result independent of arrival order.
     */
    fun dailyMetric(local: SyncDailyMetric?, incoming: SyncDailyMetric?): SyncDailyMetric? {
        if (local == null) return incoming
        if (incoming == null) return local
        require(local.localDate == incoming.localDate) {
            "Merge.dailyMetric: different days (${local.localDate} vs ${incoming.localDate})"
        }

        val usage = groupWinner(local, incoming, { it.usageUpdatedAtUtc }, ::canonicalUsage)
        val health = groupWinner(local, incoming, { it.healthUpdatedAtUtc }, ::canonicalHealth)
        val sleep = sleepWinner(local, incoming)

        return SyncDailyMetric(
            localDate = local.localDate,
            screenMinutes = usage.screenMinutes,
            socialMediaMinutes = usage.socialMediaMinutes,
            lateNightScreenMinutes = usage.lateNightScreenMinutes,
            unlockCount = usage.unlockCount,
            usageUpdatedAtUtc = usage.usageUpdatedAtUtc,
            sleepMinutes = sleep.sleepMinutes,
            sleepSource = sleep.sleepSource,
            sleepStartUtc = sleep.sleepStartUtc,
            sleepEndUtc = sleep.sleepEndUtc,
            sleepUpdatedAtUtc = sleep.sleepUpdatedAtUtc,
            steps = health.steps,
            exerciseMinutes = health.exerciseMinutes,
            restingHeartRate = health.restingHeartRate,
            hrv = health.hrv,
            healthUpdatedAtUtc = health.healthUpdatedAtUtc,
            updatedAtUtc = maxOf(local.updatedAtUtc, incoming.updatedAtUtc),
        )
    }

    /** Last writer wins, but per group and on that group's own timestamp. */
    private fun groupWinner(
        a: SyncDailyMetric,
        b: SyncDailyMetric,
        timestamp: (SyncDailyMetric) -> Long,
        canonical: (SyncDailyMetric) -> String,
    ): SyncDailyMetric = if (wins(a, b, timestamp, canonical)) a else b

    /**
     * Sleep is ranked by source before it is ranked by time.
     *
     * A later write is not a better one here. Re-aggregation runs on every app open, so a proxy
     * estimate is almost always the most recent thing to have touched the row, and plain
     * last-writer-wins would stomp a correction the user made by hand days earlier. That is the
     * same rule `UsageRepository.writeDay` already applies on one device, extended across devices.
     *
     * MANUAL outranks WEARABLE because someone who went in and fixed a night did so knowing what
     * the device had recorded.
     */
    private fun sleepWinner(a: SyncDailyMetric, b: SyncDailyMetric): SyncDailyMetric {
        val rankA = sleepRank(a)
        val rankB = sleepRank(b)
        if (rankA != rankB) return if (rankA > rankB) a else b
        return if (wins(a, b, { it.sleepUpdatedAtUtc }, ::canonicalSleep)) a else b
    }

    private fun sleepRank(row: SyncDailyMetric): Int {
        if (row.sleepMinutes == null) return -1
        return when (row.sleepSource) {
            "MANUAL" -> 3
            "WEARABLE" -> 2
            "PROXY" -> 1
            // A reading with no source recorded is still a reading, and still beats no reading.
            else -> 0
        }
    }

    /** True when [candidate] should replace [current]. Strictly greater, so equal rows never flap. */
    private fun <T> wins(
        candidate: T,
        current: T,
        updatedAt: (T) -> Long,
        canonical: (T) -> String,
    ): Boolean {
        val byTime = updatedAt(candidate).compareTo(updatedAt(current))
        if (byTime != 0) return byTime > 0
        return canonical(candidate) > canonical(current)
    }

    // Content comparison is only ever a tiebreak, so what these strings say does not matter. What
    // matters is that every field appears, that the separator cannot occur inside a field (hence the
    // length prefix on free text), and that both ends build them identically.
    private fun canonical(row: SyncCheckIn): String = listOf(
        row.timestampUtc,
        row.zoneId,
        row.localDate,
        row.mood,
        row.energy,
        row.deleted,
        sized(row.note),
        row.tags.sorted().joinToString(",") { sized(it) },
    ).joinToString(" ")

    private fun canonical(row: SyncTag): String =
        listOf(row.sortOrder, row.enabled, row.deleted).joinToString(" ")

    private fun canonicalUsage(row: SyncDailyMetric): String = listOf(
        row.screenMinutes,
        row.socialMediaMinutes,
        row.lateNightScreenMinutes,
        row.unlockCount,
    ).joinToString(" ") { it?.toString() ?: "" }

    private fun canonicalSleep(row: SyncDailyMetric): String = listOf(
        row.sleepMinutes,
        row.sleepSource,
        row.sleepStartUtc,
        row.sleepEndUtc,
    ).joinToString(" ") { it?.toString() ?: "" }

    private fun canonicalHealth(row: SyncDailyMetric): String = listOf(
        row.steps,
        row.exerciseMinutes,
        row.restingHeartRate,
        row.hrv,
    ).joinToString(" ") { it?.toString() ?: "" }

    private fun sized(value: String?): String = if (value == null) "-" else "${value.length}:$value"
}
