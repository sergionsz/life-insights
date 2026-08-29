package dev.sergio.lifeinsights.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sergio.lifeinsights.data.db.AppDatabase
import dev.sergio.lifeinsights.data.db.CheckInEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The client half of sync, against the real server logic.
 *
 * The transport is mocked, but nothing else is: these are real Room databases, and the thing on the
 * other end of the wire is the same [SyncStore] the deployed server runs, over the same
 * [InMemoryRowStore] its own tests use. So a disagreement between the two ends shows up here rather
 * than in production, which is the entire reason that class sits in the shared module.
 *
 * Two databases stand in for two phones. Convergence between them is the property that matters and
 * it cannot be observed with only one.
 */
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val target = SyncTarget("https://server.example", "token")

    private lateinit var server: FakeServer
    private lateinit var phoneA: AppDatabase
    private lateinit var phoneB: AppDatabase
    private lateinit var engineA: SyncEngine
    private lateinit var engineB: SyncEngine

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        server = FakeServer()
        phoneA = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        phoneB = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        engineA = SyncEngine(phoneA, SyncApi(server.client()))
        engineB = SyncEngine(phoneB, SyncApi(server.client()))
    }

    @After
    fun tearDown() {
        phoneA.close()
        phoneB.close()
    }

    private suspend fun AppDatabase.addCheckIn(
        uid: String = UUID.randomUUID().toString(),
        mood: Int = 1,
        note: String? = null,
        updatedAtUtc: Long = 1_000,
    ): String {
        checkInDao().save(
            CheckInEntity(
                uid = uid,
                timestampUtc = 1_700_000_000_000,
                zoneId = "Europe/Madrid",
                localDate = 20_000,
                mood = mood,
                energy = 0,
                note = note,
                updatedAtUtc = updatedAtUtc,
            ),
            listOf("caffeine"),
        )
        return uid
    }

    @Test
    fun aLocalCheckInReachesTheServerAndStopsBeingPending() = runBlocking {
        val uid = phoneA.addCheckIn(mood = 2)
        assertEquals(1, phoneA.checkInDao().dirty(10).size)

        val result = engineA.syncNow(target)

        assertTrue("sync succeeded: $result", result is SyncResult.Success)
        assertEquals(2, server.store.pull(0, 10).changes.checkIns.single().mood)
        assertEquals("nothing left waiting", 0, phoneA.checkInDao().dirty(10).size)
        assertEquals(uid, phoneA.checkInDao().all().single().checkIn.uid)
    }

    @Test
    fun anEntryMadeOnOnePhoneAppearsOnTheOther() = runBlocking {
        phoneA.addCheckIn(mood = -2, note = "rough day")
        engineA.syncNow(target)

        engineB.syncNow(target)

        val arrived = phoneB.checkInDao().all().single()
        assertEquals(-2, arrived.checkIn.mood)
        assertEquals("rough day", arrived.checkIn.note)
        assertEquals(listOf("caffeine"), arrived.tagNames)
        assertEquals("an arrived row is not pending", 0, phoneB.checkInDao().dirty(10).size)
    }

    /**
     * The case a hard delete cannot express. Without a tombstone the second phone has no way to
     * tell "this was deleted" from "you have not seen this yet", and would push it back.
     */
    @Test
    fun aDeletionOnOnePhoneRemovesTheEntryFromTheOther() = runBlocking {
        phoneA.addCheckIn()
        engineA.syncNow(target)
        engineB.syncNow(target)
        assertEquals(1, phoneB.checkInDao().all().size)

        val localId = phoneA.checkInDao().all().single().checkIn.id
        phoneA.checkInDao().softDelete(localId, now = 2_000)
        engineA.syncNow(target)
        engineB.syncNow(target)

        assertTrue("the entry is gone from the second phone", phoneB.checkInDao().all().isEmpty())

        // And it stays gone. A resurrection would show up on the round after this one.
        engineB.syncNow(target)
        engineA.syncNow(target)
        assertTrue(phoneB.checkInDao().all().isEmpty())
        assertTrue(phoneA.checkInDao().all().isEmpty())
    }

    @Test
    fun theNewerOfTwoConflictingEditsWins() = runBlocking {
        val uid = phoneA.addCheckIn(mood = 1, updatedAtUtc = 1_000)
        engineA.syncNow(target)
        engineB.syncNow(target)

        // Both phones edit the same entry while apart. B's edit is the later one.
        phoneA.checkInDao().updateExisting(
            phoneA.checkInDao().all().single().checkIn.id, 2, 0, "from A", updatedAtUtc = 2_000,
        )
        phoneB.checkInDao().updateExisting(
            phoneB.checkInDao().all().single().checkIn.id, -3, 0, "from B", updatedAtUtc = 3_000,
        )

        engineA.syncNow(target)
        engineB.syncNow(target)
        engineA.syncNow(target)

        assertEquals("from B", phoneA.checkInDao().all().single().checkIn.note)
        assertEquals("from B", phoneB.checkInDao().all().single().checkIn.note)
        assertEquals(uid, phoneA.checkInDao().all().single().checkIn.uid)
    }

    @Test
    fun aSecondSyncWithNothingNewTransfersNothing() = runBlocking {
        phoneA.addCheckIn()
        engineA.syncNow(target)
        val seqAfterFirst = server.store.status().serverSeq

        val second = engineA.syncNow(target)

        assertEquals(SyncResult.Success(pushed = 0, pulled = 0), second)
        assertEquals("no sequence burned by an idle sync", seqAfterFirst, server.store.status().serverSeq)
    }

    @Test
    fun theCursorIsRememberedBetweenSyncs() = runBlocking {
        phoneA.addCheckIn()
        engineA.syncNow(target)

        val state = phoneA.syncStateDao().get()
        assertNotNull(state)
        assertTrue("the cursor moved past the pushed row", state!!.lastSeenSeq > 0)
        assertNull(state.lastError)
        assertTrue(state.lastSyncAtUtc > 0)
    }

    /**
     * A rebuilt server starts its counter again from zero. A phone still holding a cursor from the
     * old one would see nothing above it and have nothing flagged as pending, and would go on
     * reporting successful syncs forever while transferring nothing at all.
     */
    @Test
    fun aRebuiltServerIsNoticedAndEverythingIsOfferedAgain() = runBlocking {
        phoneA.addCheckIn(mood = 3)
        engineA.syncNow(target)
        assertEquals(1, server.store.pull(0, 10).changes.checkIns.size)

        server.wipe()
        assertEquals(0L, server.store.status().serverSeq)

        val result = engineA.syncNow(target)

        assertTrue(result is SyncResult.Success)
        assertEquals(
            "the phone's history was uploaded again",
            3,
            server.store.pull(0, 10).changes.checkIns.single().mood,
        )
    }

    @Test
    fun aFailureIsRecordedAndLeavesTheRowPending() = runBlocking {
        phoneA.addCheckIn()
        val broken = SyncEngine(phoneA, SyncApi(server.client(failing = true)))

        val result = broken.syncNow(target)

        assertTrue("expected a failure, got $result", result is SyncResult.Failure)
        assertEquals("the row is still waiting", 1, phoneA.checkInDao().dirty(10).size)
        assertEquals(0L, phoneA.syncStateDao().get()!!.lastSeenSeq)
        assertNotNull(phoneA.syncStateDao().get()!!.lastError)
    }

    @Test
    fun anUnconfiguredTargetIsNotAnError() = runBlocking {
        assertEquals(SyncResult.NotConfigured, engineA.syncNow(SyncTarget("", "")))
        assertEquals(SyncResult.NotConfigured, engineA.syncNow(SyncTarget("https://h", "")))
    }

    @Test
    fun aBatchLargerThanOnePageStillTransfersEverything() = runBlocking {
        repeat(450) { i -> phoneA.addCheckIn(mood = i % 4 - 1, updatedAtUtc = 1_000L + i) }
        engineA.syncNow(target)
        engineB.syncNow(target)

        assertEquals(450, phoneB.checkInDao().all().size)
        assertFalse(phoneB.checkInDao().dirty(10).isNotEmpty())
    }

    /** The server the phone talks to, driven by the same logic the deployed one runs. */
    private inner class FakeServer {
        var store = SyncStore(InMemoryRowStore())
            private set

        fun wipe() {
            store = SyncStore(InMemoryRowStore())
        }

        fun client(failing: Boolean = false) = HttpClient(
            MockEngine { request ->
                if (failing) {
                    return@MockEngine respond(
                        """{"error":"internal error"}""",
                        HttpStatusCode.InternalServerError,
                        jsonHeaders,
                    )
                }
                val path = request.url.encodedPath
                when {
                    path.endsWith("/status") ->
                        respond(json.encodeToString(store.status()), HttpStatusCode.OK, jsonHeaders)

                    path.endsWith("/pull") -> {
                        val since = request.url.parameters["since"]!!.toLong()
                        val limit = request.url.parameters["limit"]!!.toInt()
                        respond(
                            json.encodeToString(store.pull(since, limit)),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }

                    path.endsWith("/push") -> {
                        val body = json.decodeFromString<PushRequest>(request.bodyText())
                        respond(
                            json.encodeToString(store.push(body.changes)),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }

                    else -> respond("", HttpStatusCode.NotFound, jsonHeaders)
                }
            },
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(json) }
        }
    }

    private suspend fun HttpRequestData.bodyText(): String {
        val content = body as io.ktor.http.content.OutgoingContent
        return when (content) {
            is io.ktor.http.content.TextContent -> content.text
            is io.ktor.http.content.ByteArrayContent -> String(content.bytes())
            else -> error("unexpected request body type: ${content::class}")
        }
    }

    private val jsonHeaders =
        headersOf("Content-Type", ContentType.Application.Json.toString())
}
