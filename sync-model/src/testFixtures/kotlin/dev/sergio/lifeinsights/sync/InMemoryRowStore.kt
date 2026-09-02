package dev.sergio.lifeinsights.sync


/**
 * The storage contract, backed by maps.
 *
 * This exists so that everything the server decides can be tested without a database: which version
 * of a row wins, when a write is skipped, how a cursor advances, where a page is cut. It keeps the
 * same invariant the SQL does, which is the only one [SyncStore] relies on: one counter shared by
 * all three tables, incremented on every write.
 */
class InMemoryRowStore : SyncRowStore, SyncRows {

    private val checkIns = linkedMapOf<String, Pair<Long, SyncCheckIn>>()
    private val dailyMetrics = linkedMapOf<Long, Pair<Long, SyncDailyMetric>>()
    private val tags = linkedMapOf<String, Pair<Long, SyncTag>>()
    private var seq = 0L

    /** Counts writes, so a test can assert that a redundant push touched nothing. */
    var writes = 0
        private set

    override fun <T> write(body: (SyncRows) -> T): T = body(this)
    override fun <T> read(body: (SyncRows) -> T): T = body(this)

    override fun checkIn(uid: String): SyncCheckIn? = checkIns[uid]?.second
    override fun dailyMetric(localDate: Long): SyncDailyMetric? = dailyMetrics[localDate]?.second
    override fun tag(name: String): SyncTag? = tags[name]?.second

    override fun put(row: SyncCheckIn) {
        checkIns[row.uid] = ++seq to row
        writes++
    }

    override fun put(row: SyncDailyMetric) {
        dailyMetrics[row.localDate] = ++seq to row
        writes++
    }

    override fun put(row: SyncTag) {
        tags[row.name] = ++seq to row
        writes++
    }

    override fun checkInsSince(since: Long, limit: Int) = checkIns.values.since(since, limit)
    override fun dailyMetricsSince(since: Long, limit: Int) = dailyMetrics.values.since(since, limit)
    override fun tagsSince(since: Long, limit: Int) = tags.values.since(since, limit)

    private fun <T> Collection<Pair<Long, T>>.since(since: Long, limit: Int) =
        filter { it.first > since }.sortedBy { it.first }.take(limit)

    override fun currentSeq(): Long = seq

    override fun counts() = SyncRows.Counts(
        checkIns = checkIns.values.count { !it.second.deleted },
        dailyMetrics = dailyMetrics.size,
        tags = tags.values.count { !it.second.deleted },
    )
}
