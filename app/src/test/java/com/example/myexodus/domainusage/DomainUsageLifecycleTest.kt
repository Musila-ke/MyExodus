package com.example.myexodus.domainusage

import java.time.ZoneId
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.8/§6.9, §8 S7: start-up, the status composition, and the single-execution shutdown.
 *
 * Every test runs the Engine on real single-thread executors: `stop()`'s bounded `runBlocking`
 * flush needs real threads, and a virtual-time tick loop left running would make `runTest`'s
 * final scheduler drain spin (the ids/awaits below are spin-count-bounded — §1.4 confines
 * wall-clock reads to `SystemWallClock`).
 */
class DomainUsageLifecycleTest {

    private val counters = EngineCounters()
    private val mono = FakeMonotonicClock()
    private val wall = FakeWallClock()
    private val rule = DomainRule("r1", 0L, "example.com", 3_600_000L, true)

    /** The anchored zone is the test machine's, so "today" must be derived the same way. */
    private val today: String get() = wall.now(ZoneId.systemDefault()).toLocalDate().toString()

    private fun engine(mode: EngineMode = EngineMode.ENFORCE, debugBuild: Boolean = false) =
        DomainUsageEngine(mode, mono, counters, wall, debugBuild)

    @Test
    fun modeOffNeverStarts_andEveryHookStaysPassThrough() = runTest {
        val host = FakeDomainUsageHost()
        val engine = engine(EngineMode.OFF)

        withRealDispatchers { tick, db ->
            engine.start(
                rules = MutableStateFlow(listOf(rule)),
                seedRevisions = {},
                dao = { FakeRuleUsageDao() },
                host = host,
                tickDispatcher = tick,
                dbDispatcher = db,
            )

            assertEquals(EngineStatus.Dormant, engine.status.value)
            val query = DnsFixtures.query("example.com", 1)
            assertEquals(DnsQueryResult.Forward, engine.onDnsQuery(query, query.size))
            assertNull("no ledger is ever attached in OFF", engine.ledger)
            assertEquals("OFF never registers the Private-DNS monitor", 0, host.monitorRegistrations.get())
        }
        assertFalse("stop() on a never-started engine is a no-op", engine.stop())
    }

    @Test
    fun theDebugOverrideLowersTheModeBeforeAnythingLoads() = runTest {
        val engine = engine(EngineMode.ENFORCE, debugBuild = true)
        val host = FakeDomainUsageHost(override = EngineMode.ACCOUNT)

        withRealDispatchers { tick, db ->
            engine.start(
                rules = MutableStateFlow(listOf(rule)),
                seedRevisions = {},
                dao = { FakeRuleUsageDao() },
                host = host,
                tickDispatcher = tick,
                dbDispatcher = db,
            )

            assertEquals(EngineMode.ACCOUNT, engine.mode)
            assertEquals(EngineStatus.Active, engine.status.value)
            assertTrue(engine.stop())
        }
    }

    @Test
    fun startLoadsTodaysTotals_seedsD32Revisions_andGoesActive() = runTest {
        val dao = FakeRuleUsageDao()
        dao.seed("r1", 0L, today, 5_000L)
        dao.seed("r1", 3L, "2020-01-01", 9_000L)
        var seeded: Map<String, Long>? = null
        val host = FakeDomainUsageHost(persistedDayKey = "2020-01-01")

        val engine = engine()
        withRealDispatchers { tick, db ->
            engine.start(
                rules = MutableStateFlow(listOf(rule)),
                seedRevisions = { seeded = it },
                dao = { dao },
                host = host,
                tickDispatcher = tick,
                dbDispatcher = db,
            )

            assertEquals(EngineStatus.Active, engine.status.value)
            assertEquals(mapOf("r1" to 3L), seeded)
            assertEquals(5_000L, engine.ledger!!.usageTodayMs("r1"))
            assertEquals(today, engine.ledger!!.dayKey)
            assertEquals(today, host.lastDayKeyWritten)
            assertEquals(1, host.monitorRegistrations.get())
            assertTrue(engine.stop())
        }
    }

    @Test
    fun usageToday_msIsNullUntilTheLedgerLoads_thenReadsTheCurrentRevision() = runTest {
        val dao = FakeRuleUsageDao()
        dao.seed("r1", 0L, today, 5_000L)

        val engine = engine()
        assertNull(
            "S9.3: before the startup load the Rules row must hide its usage line, not claim 0",
            engine.usageTodayMs("r1"),
        )

        withRealDispatchers { tick, db ->
            engine.start(
                rules = MutableStateFlow(listOf(rule)),
                seedRevisions = {},
                dao = { dao },
                host = FakeDomainUsageHost(),
                tickDispatcher = tick,
                dbDispatcher = db,
            )

            assertEquals(5_000L, engine.usageTodayMs("r1"))
            assertEquals("an unknown rule reads as zero once the ledger is live", 0L, engine.usageTodayMs("nope"))
            assertTrue(engine.stop())
        }
    }

    @Test
    fun aPersistedDayKeyAheadOfTodayIsAdopted_neverMovedBackward() = runTest {
        val host = FakeDomainUsageHost(persistedDayKey = "2030-01-01")
        val dao = FakeRuleUsageDao()
        dao.seed("r1", 0L, "2030-01-01", 2_000L)

        val engine = engine()
        withRealDispatchers { tick, db ->
            engine.start(
                rules = MutableStateFlow(listOf(rule)),
                seedRevisions = {},
                dao = { dao },
                host = host,
                tickDispatcher = tick,
                dbDispatcher = db,
            )

            assertEquals("INV-3: max(today, persisted)", "2030-01-01", engine.ledger!!.dayKey)
            assertEquals(2_000L, engine.ledger!!.usageTodayMs("r1"))
            assertNull("the persisted key was already effective", host.lastDayKeyWritten)
            assertTrue(engine.stop())
        }
    }

    @Test
    fun zeroRulesKeepsDormant_butARuleAddedLaterMetersImmediately() = runTest {
        val rules = MutableStateFlow(emptyList<DomainRule>())
        val engine = engine()

        withRealDispatchers { tick, db ->
            engine.start(
                rules = rules,
                seedRevisions = {},
                dao = { FakeRuleUsageDao() },
                host = FakeDomainUsageHost(),
                tickDispatcher = tick,
                dbDispatcher = db,
            )
            assertEquals(EngineStatus.Dormant, engine.status.value)

            rules.value = listOf(rule)
            await("FR-12's Dormant test is the live snapshot") { engine.status.value == EngineStatus.Active }

            engine.ledger!!.counterFor("r1", 0L)!!.touch(0L)
            mono.now = 4_000L
            awaitUsage(engine, 4_000L)
            assertTrue(engine.stop())
        }
    }

    @Test
    fun aFailedLoadDegrades_thenGoesActiveWhenTheRetrySucceeds() = runTest {
        val dao = FakeRuleUsageDao()
        dao.seed("r1", 0L, today, 5_000L)
        dao.failLoad = true
        val engine = engine()

        withRealDispatchers { tick, db ->
            // start() returns only once the load succeeds — the retry loop is inside it, so the
            // degraded state must be observed while start() is still parked on its 10 s retry.
            // The launch runs on its own real dispatcher: a launch on the test dispatcher would
            // resume on this (blocked, spinning) thread and never attach the ledger.
            val startup = CoroutineScope(Dispatchers.Default).launch {
                engine.start(
                    rules = MutableStateFlow(listOf(rule)),
                    seedRevisions = {},
                    dao = { dao },
                    host = FakeDomainUsageHost(),
                    tickDispatcher = tick,
                    dbDispatcher = db,
                )
            }
            await("the failed load was counted") { counters.get(Counter.db_flush_fail) >= 1 }
            assertEquals(
                "§9: a failed load degrades immediately",
                EngineStatus.Degraded(EngineStatus.Reason.DB_ERROR),
                engine.status.value,
            )

            // The retry on the db thread then succeeds and start() attaches everything.
            dao.failLoad = false
            await("the retry erased the degradation") { engine.status.value == EngineStatus.Active }
            startup.join()

            assertEquals(5_000L, engine.ledger!!.usageTodayMs("r1"))
            assertTrue(engine.stop())
        }
    }

    @Test
    fun strictPrivateDnsDegrades_theBanner_andCountsTamperOnTheRisingEdgeOnly() = runTest {
        val host = FakeDomainUsageHost()
        val engine = engine()
        withRealDispatchers { tick, db ->
            engine.start(
                rules = MutableStateFlow(listOf(rule)),
                seedRevisions = {},
                dao = { FakeRuleUsageDao() },
                host = host,
                tickDispatcher = tick,
                dbDispatcher = db,
            )

            host.strictCallback!!(true)
            assertEquals(
                EngineStatus.Degraded(EngineStatus.Reason.PRIVATE_DNS),
                engine.status.value,
            )
            assertEquals(1L, counters.get(Counter.tamper_private_dns))

            host.strictCallback!!(true)
            assertEquals("still strict: not a second tamper event", 1L, counters.get(Counter.tamper_private_dns))

            host.strictCallback!!(false)
            assertEquals(EngineStatus.Active, engine.status.value)
            assertTrue(engine.stop())
        }
    }

    @Test
    fun aTrippedBreakerOutranksEveryOtherReason_andOutlivesTheSession() = runTest {
        val host = FakeDomainUsageHost()
        val engine = engine()
        withRealDispatchers { tick, db ->
            engine.start(
                rules = MutableStateFlow(listOf(rule)),
                seedRevisions = {},
                dao = { FakeRuleUsageDao() },
                host = host,
                tickDispatcher = tick,
                dbDispatcher = db,
            )
            host.strictCallback!!(true)
            assertEquals(
                EngineStatus.Degraded(EngineStatus.Reason.PRIVATE_DNS),
                engine.status.value,
            )

            repeat(MeteringConfig.BREAKER_MAX_EXCEPTIONS) {
                engine.hook(Unit) { throw IllegalStateException("boom") }
            }

            assertEquals(
                "U-12: the unstoppable fault outranks the blind spot",
                EngineStatus.Degraded(EngineStatus.Reason.INTERNAL_ERROR),
                engine.status.value,
            )
            assertTrue(engine.stop())
            assertEquals(
                "the trip survives the session (dormant until process restart)",
                EngineStatus.Degraded(EngineStatus.Reason.INTERNAL_ERROR),
                engine.status.value,
            )
        }
    }

    @Test
    fun stopFlushesAcksAndNotifiesExactlyOnce() = runTest {
        val dao = FakeRuleUsageDao()
        val host = FakeDomainUsageHost()
        val engine = engine()
        withRealDispatchers { tick, db ->
            engine.start(
                rules = MutableStateFlow(listOf(rule)),
                seedRevisions = {},
                dao = { dao },
                host = host,
                tickDispatcher = tick,
                dbDispatcher = db,
            )

            val counter = engine.ledger!!.counterFor("r1", 0L)!!
            counter.touch(0L)
            mono.now = 5_000L
            awaitUsage(engine, 5_000L)
            val day = engine.ledger!!.dayKey

            assertTrue(engine.stop())
            assertFalse("§6.9: only the first caller runs the shutdown", engine.stop())

            assertEquals("the final flush wrote the dirty row", 5_000L, dao.stored("r1", 0L, day))
            assertFalse("the committed generation cleared the dirty flag", counter.changedSincePersist)
            assertEquals(1, host.notifications.get())
            assertEquals(1, host.monitorUnregistrations.get())
            assertEquals(EngineStatus.Dormant, engine.status.value)
        }
    }

    @Test
    fun stopWithoutRulesPostsNoNotification() = runTest {
        val host = FakeDomainUsageHost()
        val engine = engine()
        withRealDispatchers { tick, db ->
            engine.start(
                rules = MutableStateFlow(emptyList()),
                seedRevisions = {},
                dao = { FakeRuleUsageDao() },
                host = host,
                tickDispatcher = tick,
                dbDispatcher = db,
            )

            assertTrue(engine.stop())
            assertEquals("S7.4: no rules means nothing was being enforced", 0, host.notifications.get())
            assertEquals(1, host.monitorUnregistrations.get())
        }
    }

    @Test
    fun aSecondSessionStartsTheSameEngineAgain_andReloadsThePersistedTotal() = runTest {
        val dao = FakeRuleUsageDao()
        val engine = engine()
        withRealDispatchers { tick, db ->
            engine.start(
                rules = MutableStateFlow(listOf(rule)),
                seedRevisions = {},
                dao = { dao },
                host = FakeDomainUsageHost(),
                tickDispatcher = tick,
                dbDispatcher = db,
            )
            val firstLedger = engine.ledger!!
            firstLedger.counterFor("r1", 0L)!!.touch(0L)
            mono.now = 4_000L
            awaitUsage(engine, 4_000L)
            assertTrue(engine.stop())

            // Session 2 runs on the same singleton Engine (a new service instance in the same
            // process): the startup load must seed the new ledger from the persisted row.
            mono.now = 10_000L
            engine.start(
                rules = MutableStateFlow(listOf(rule)),
                seedRevisions = {},
                dao = { dao },
                host = FakeDomainUsageHost(),
                tickDispatcher = tick,
                dbDispatcher = db,
            )
            assertEquals(EngineStatus.Active, engine.status.value)
            assertTrue("the second session gets a fresh ledger", engine.ledger !== firstLedger)
            assertEquals("§6.8: the restart reloads the persisted total", 4_000L, engine.ledger!!.usageTodayMs("r1"))

            engine.ledger!!.counterFor("r1", 0L)!!.touch(10_000L)
            mono.now = 13_000L
            awaitUsage(engine, 7_000L)
            assertTrue(engine.stop())
        }
    }

    @Test
    fun aFailedRolloverFinalIsRetried_untilTheOutgoingDaysRowIsWritten() = runTest {
        val dao = FakeRuleUsageDao()
        val host = FakeDomainUsageHost()
        val engine = engine()
        withRealDispatchers { tick, db ->
            engine.start(
                rules = MutableStateFlow(listOf(rule)),
                seedRevisions = {},
                dao = { dao },
                host = host,
                tickDispatcher = tick,
                dbDispatcher = db,
            )
            // Refuse every write from before the first persist cycle, so the outgoing day's
            // row can only ever appear through the rollover final itself.
            dao.failUpsert = true

            val counter = engine.ledger!!.counterFor("r1", 0L)!!
            counter.touch(0L)
            mono.now = 5_000L
            awaitUsage(engine, 5_000L)
            val oldDay = engine.ledger!!.dayKey
            val failuresBefore = counters.get(Counter.db_flush_fail)

            wall.instant = wall.instant.plusSeconds(86_400L)
            await("the ledger crossed midnight") { engine.ledger!!.dayKey != oldDay }
            // The rollover's write has failed by now (the dbScope is single-threaded and FIFO,
            // so this failure cannot trail a later launch); §6.7 must retry it, not drop it.
            await("the rollover's write failed") { counters.get(Counter.db_flush_fail) > failuresBefore }
            assertNull("a mid-day flush can never write the outgoing day", dao.stored("r1", 0L, oldDay))
            assertEquals(
                "the day key does not advance past an unwritten final",
                oldDay,
                host.lastDayKeyWritten,
            )

            dao.failUpsert = false
            await("the rollover final was retried") { dao.stored("r1", 0L, oldDay) == 5_000L }
            await("the new day key lands after the final") { host.lastDayKeyWritten == engine.ledger!!.dayKey }
            assertTrue(engine.stop())
        }
    }

    /** Real single-thread executors: `stop()`'s bounded `runBlocking` needs real threads. */
    private suspend fun withRealDispatchers(block: suspend (CoroutineDispatcher, CoroutineDispatcher) -> Unit) {
        val tickExec = Executors.newSingleThreadExecutor { Thread(it, "test-tick") }
        val dbExec = Executors.newSingleThreadExecutor { Thread(it, "test-db") }
        try {
            block(tickExec.asCoroutineDispatcher(), dbExec.asCoroutineDispatcher())
        } finally {
            tickExec.shutdown()
            dbExec.shutdown()
        }
    }

    /**
     * Waits for the real tick loop (1 s cadence) to book the interval the test just opened. Bounded
     * by spin count, not by a clock read — §1.4 confines wall-clock calls to `SystemWallClock`.
     */
    private fun awaitUsage(engine: DomainUsageEngine, expected: Long) =
        await("usage never reached $expected ms") { engine.ledger?.usageTodayMs("r1") == expected }

    /** Spin-bounded wait for an engine-side effect that lands on its own real threads. */
    private fun await(message: String, condition: () -> Boolean) {
        repeat(600) { // 600 × 25 ms ≈ 15 s, past the 1 s tick cadence and the 10 s retry
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(message, condition())
    }

    @Test
    fun theRevokePathCountsTheTamper() {
        engine(EngineMode.OFF).onVpnRevoked()
        assertEquals(1L, counters.get(Counter.tamper_vpn_revoked))
    }
}
