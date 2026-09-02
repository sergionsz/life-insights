package dev.sergio.lifeinsights.sync

import dev.sergio.lifeinsights.data.db.AppDatabase
import dev.sergio.lifeinsights.data.db.SyncStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface SyncResult {
    /** No server configured, or sync switched off. Not an error. */
    data object NotConfigured : SyncResult
    data class Success(val pushed: Int, val pulled: Int) : SyncResult
    data class Failure(val message: String) : SyncResult
}

/**
 * Reconciles this phone with the server.
 *
 * The shape is push, then pull, repeated until neither has anything left. Pushing first means the
 * server has seen everything local before this phone asks what it missed, so a single round settles
 * the common case of one device that has been offline.
 *
 * Conflicts are not resolved here. Every disagreement goes through `Merge` in the shared module,
 * which the server runs too, so both ends reach the same answer without another round trip.
 */
class SyncEngine(
    private val database: AppDatabase,
    private val api: SyncApi = SyncApi(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val checkIns = database.checkInDao()
    private val dailyMetrics = database.dailyMetricDao()
    private val tags = database.tagDao()
    private val syncState = database.syncStateDao()

    // Sync runs from the app launch, a periodic worker and a button in Settings, and those can
    // overlap. Two passes at once would push the same rows twice and race on the cursor.
    private val running = Mutex()

    fun observeState(): Flow<SyncStateEntity?> = syncState.observe()

    fun observePendingCount(): Flow<Int> = checkIns.observeDirtyCount()

    suspend fun syncNow(target: SyncTarget): SyncResult {
        if (!target.isConfigured) return SyncResult.NotConfigured

        return running.withLock {
            val state = currentState()
            try {
                val cursor = reconcileCursor(target, state)
                val pushed = pushEverythingPending(target, state.deviceId)
                val pulled = pullEverythingNew(target, cursor)
                syncState.upsert(
                    state.copy(
                        lastSeenSeq = pulled.cursor,
                        lastSyncAtUtc = now(),
                        lastError = null,
                    ),
                )
                SyncResult.Success(pushed = pushed, pulled = pulled.applied)
            } catch (e: SyncFailure) {
                syncState.upsert(state.copy(lastError = e.message))
                SyncResult.Failure(e.message.orEmpty())
            }
        }
    }

    /** Clears the cursor and marks everything for re-upload, for a change of server. */
    suspend fun resetForNewServer() {
        val state = currentState()
        syncState.upsert(state.copy(lastSeenSeq = 0, lastSyncAtUtc = 0, lastError = null))
        markEverythingPending()
    }

    /** Forgets the server entirely. Used when local data is wiped, so nothing is pulled back. */
    suspend fun forget() {
        syncState.clear()
    }

    private suspend fun markEverythingPending() {
        checkIns.markAllDirty()
        dailyMetrics.markAllDirty()
        tags.markAllDirty()
    }

    private suspend fun currentState(): SyncStateEntity =
        syncState.get() ?: SyncStateEntity(deviceId = UUID.randomUUID().toString())
            .also { syncState.upsert(it) }

    /**
     * Notices that the server is not the one this phone was last talking to.
     *
     * A server rebuilt from scratch starts its counter again at zero. This phone would still be
     * holding a cursor from the old one, see nothing above it, and have no dirty rows to push, so
     * it would report a perfectly successful sync forever while transferring nothing. Comparing
     * against the server's own counter catches that, and the answer is to start again from the
     * beginning and re-offer everything.
     */
    private suspend fun reconcileCursor(target: SyncTarget, state: SyncStateEntity): Long {
        val serverSeq = api.status(target).serverSeq
        if (serverSeq >= state.lastSeenSeq) return state.lastSeenSeq

        markEverythingPending()
        syncState.upsert(state.copy(lastSeenSeq = 0))
        return 0
    }

    // ---- push -----------------------------------------------------------------------------------

    private suspend fun pushEverythingPending(target: SyncTarget, deviceId: String): Int {
        var total = 0
        var rounds = 0
        while (true) {
            val pendingCheckIns = checkIns.dirty(BATCH)
            val pendingMetrics = dailyMetrics.dirty(BATCH)
            val pendingTags = tags.dirty(BATCH)
            if (pendingCheckIns.isEmpty() && pendingMetrics.isEmpty() && pendingTags.isEmpty()) {
                return total
            }

            val changes = ChangeSet(
                checkIns = pendingCheckIns.map { it.toWire() },
                dailyMetrics = pendingMetrics.map { it.toWire() },
                tags = pendingTags.map { it.toWire() },
            )
            api.push(target, PushRequest(deviceId = deviceId, changes = changes))

            // Cleared whether the server took the row or kept its own. A row the server rejected as
            // stale is not pending any more: the pull that follows brings back the version that
            // won, and leaving the flag set would offer the same losing row on every future sync.
            for (row in changes.checkIns) checkIns.clearDirty(row.uid, row.updatedAtUtc)
            for (row in changes.dailyMetrics) dailyMetrics.clearDirty(row.localDate, row.updatedAtUtc)
            for (row in changes.tags) tags.clearDirty(row.name, row.updatedAtUtc)

            total += changes.size

            // The flags above are cleared only for rows that still match what was sent, so an edit
            // made mid-push correctly stays pending. That means this loop cannot rely on the queue
            // shrinking to termination, and needs a bound of its own.
            if (++rounds >= MAX_ROUNDS) return total
        }
    }

    // ---- pull -----------------------------------------------------------------------------------

    private data class PullOutcome(val cursor: Long, val applied: Int)

    private suspend fun pullEverythingNew(target: SyncTarget, from: Long): PullOutcome {
        var cursor = from
        var applied = 0
        var rounds = 0

        while (true) {
            val response = api.pull(target, since = cursor, limit = BATCH)
            for (row in response.changes.checkIns) applied += applyCheckIn(row)
            for (row in response.changes.dailyMetrics) applied += applyDailyMetric(row)
            for (row in response.changes.tags) applied += applyTag(row)

            cursor = response.nextSince
            if (!response.hasMore || ++rounds >= MAX_ROUNDS) return PullOutcome(cursor, applied)
        }
    }

    /**
     * Applies one incoming row, returning 1 if anything changed locally.
     *
     * The local version is merged against the incoming one rather than overwritten, because the
     * server may not have seen a change this phone made since its last push. When the merge produces
     * something the server has not got, the row is left pending so the next push carries it back.
     */
    private suspend fun applyCheckIn(incoming: SyncCheckIn): Int {
        val localWire = checkIns.findByUid(incoming.uid)?.toWire()
        val merged = Merge.checkIn(localWire, incoming)!!
        if (merged == localWire) return 0

        checkIns.applyRemote(merged.toEntity(dirty = merged != incoming), merged.tags)
        return 1
    }

    private suspend fun applyDailyMetric(incoming: SyncDailyMetric): Int {
        val localWire = dailyMetrics.find(incoming.localDate)?.toWire()
        val merged = Merge.dailyMetric(localWire, incoming)!!
        if (merged == localWire) return 0

        dailyMetrics.upsert(merged.toEntity(dirty = merged != incoming))
        return 1
    }

    private suspend fun applyTag(incoming: SyncTag): Int {
        val localWire = tags.find(incoming.name)?.toWire()
        val merged = Merge.tag(localWire, incoming)!!
        if (merged == localWire) return 0

        tags.upsert(merged.toEntity(dirty = merged != incoming))
        return 1
    }

    private companion object {
        const val BATCH = 200

        /**
         * A bound on how many round trips one sync will make.
         *
         * Both loops are meant to end on their own, but a bug on either side that kept a row
         * pending or a cursor from advancing would otherwise spin against the network forever on a
         * phone battery. Stopping early only defers the rest to the next sync.
         */
        const val MAX_ROUNDS = 50
    }
}
