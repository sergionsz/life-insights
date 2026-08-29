package dev.sergio.lifeinsights.sync


/**
 * Row storage, with no opinion about what any of it means.
 *
 * Splitting this out from [SyncStore] keeps every decision the server makes (which version of a row
 * wins, when to advance a cursor, where to cut a page) in code that runs against a map as happily
 * as against Postgres. The SQL that implements it does nothing but read and write rows.
 *
 * It lives in the shared module rather than in the server for the same reason [Merge] does: the
 * phone's own tests can then drive the real server logic instead of an imitation of it that would
 * be free to disagree with the thing it stands in for.
 *
 * Implementations assign the sequence number on write. It must be drawn from one counter shared by
 * all three tables and must increase on every write, because a client holds a single cursor across
 * all of them.
 */
interface SyncRows {
    fun checkIn(uid: String): SyncCheckIn?
    fun dailyMetric(localDate: Long): SyncDailyMetric?
    fun tag(name: String): SyncTag?

    fun put(row: SyncCheckIn)
    fun put(row: SyncDailyMetric)
    fun put(row: SyncTag)

    fun checkInsSince(since: Long, limit: Int): List<Pair<Long, SyncCheckIn>>
    fun dailyMetricsSince(since: Long, limit: Int): List<Pair<Long, SyncDailyMetric>>
    fun tagsSince(since: Long, limit: Int): List<Pair<Long, SyncTag>>

    fun currentSeq(): Long
    fun counts(): Counts

    data class Counts(val checkIns: Int, val dailyMetrics: Int, val tags: Int)
}

/** Transactional access to [SyncRows]. Writers are serialised; see [PostgresRows] for why. */
interface SyncRowStore {
    fun <T> write(body: (SyncRows) -> T): T
    fun <T> read(body: (SyncRows) -> T): T
}

/**
 * Everything the server decides.
 *
 * Conflict resolution is not implemented here either: it lives in the shared `:sync-model` module
 * and runs identically on the phone, which is the only way the two ends are guaranteed to agree on
 * which version of a row won.
 */
class SyncStore(private val rows: SyncRowStore) {

    /**
     * Applies a batch of rows from a device.
     *
     * The whole batch is one transaction, so a client that dies mid-push leaves nothing partially
     * applied and can simply push again.
     */
    fun push(changes: ChangeSet): PushResponse = rows.write { store ->
        val superseded = mutableListOf<String>()
        var applied = 0

        fun <T> apply(incoming: T, stored: T?, merged: T, key: String, write: (T) -> Unit) {
            when {
                // Writing an unchanged row anyway would burn a sequence number, and the client
                // would pull the row straight back and find nothing new. Left alone, a device that
                // keeps pushing rows the server already has settles instead of looping.
                merged == stored -> if (incoming != stored) superseded += key
                else -> { write(merged); applied++ }
            }
        }

        for (incoming in changes.checkIns) {
            val stored = store.checkIn(incoming.uid)
            apply(incoming, stored, Merge.checkIn(stored, incoming)!!, incoming.uid, store::put)
        }
        for (incoming in changes.dailyMetrics) {
            val stored = store.dailyMetric(incoming.localDate)
            val merged = Merge.dailyMetric(stored, incoming)!!
            apply(incoming, stored, merged, "day:${incoming.localDate}", store::put)
        }
        for (incoming in changes.tags) {
            val stored = store.tag(incoming.name)
            apply(incoming, stored, Merge.tag(stored, incoming)!!, "tag:${incoming.name}", store::put)
        }

        PushResponse(
            serverSeq = store.currentSeq(),
            applied = applied,
            superseded = superseded,
        )
    }

    /**
     * Returns changes with a sequence above [since], oldest first.
     *
     * The three tables are read separately and interleaved by sequence, which works because they
     * share one counter. Paging then cuts the merged list at a row boundary; since no two rows hold
     * the same sequence, any cut point is safe and the next page resumes exactly there.
     */
    fun pull(since: Long, limit: Int): PullResponse = rows.read { store ->
        // Over-fetch by one per table so "is there more" needs no second query.
        val fetch = limit + 1
        val merged = buildList<Pair<Long, Any>> {
            addAll(store.checkInsSince(since, fetch))
            addAll(store.dailyMetricsSince(since, fetch))
            addAll(store.tagsSince(since, fetch))
        }.sortedBy { it.first }

        val page = merged.take(limit)

        PullResponse(
            changes = ChangeSet(
                checkIns = page.mapNotNull { it.second as? SyncCheckIn },
                dailyMetrics = page.mapNotNull { it.second as? SyncDailyMetric },
                tags = page.mapNotNull { it.second as? SyncTag },
            ),
            // Advance only over rows actually handed out. A cursor that jumped to the newest
            // sequence would silently skip everything this page had no room for.
            nextSince = page.lastOrNull()?.first ?: since,
            hasMore = merged.size > limit,
        )
    }

    fun status(): SyncStatus = rows.read { store ->
        val counts = store.counts()
        SyncStatus(
            serverSeq = store.currentSeq(),
            checkIns = counts.checkIns,
            dailyMetrics = counts.dailyMetrics,
            tags = counts.tags,
        )
    }
}
