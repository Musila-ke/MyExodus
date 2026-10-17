package com.example.myexodus.data.db

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.serialization.EventPayload
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.PairStreakSharing
import com.example.myexodus.data.db.entity.PartnerActivityOutbox
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RiskWindow
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.data.db.entity.decodeMilestoneDays
import com.example.myexodus.data.db.entity.encodeMilestoneDays
import com.example.myexodus.domainusage.UsageSnapshot
import com.example.myexodus.recovery.RecoveryResourceCatalog
import java.io.RandomAccessFile
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rules-store migration chain 2→16 (recovery_state, attempt_log, is_enabled, streak tables,
 * daily-usage tracker day-identity reshape, recovery-session + risk-window tables, the
 * domain-usage `rule_usage_daily` table, the usage ledger, the per-pair mirror links, the
 * partner-activity journal/link/projection tables, the journal's message-addressing column,
 * and the v16 per-pair consent matrix).
 * The v2 baseline is the exact DDL Room generates
 * for version 2 (verbatim copies of the SQL emitted by AppRulesStore_Impl), so the test proves
 * the real upgrade path end to end, including Room's post-migration schema validation (the
 * 2026-08-28 migration failure class) and that pre-existing rule rows survive the 4→5 column
 * add as enabled. The 5→6 specifics are covered on-device by
 * AppRulesStoreMigrationInstrumentedTest (MigrationTestHelper).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppRulesStoreMigrationTest {

    private lateinit var context: Context
    private lateinit var recovery: RecoveryManager
    private lateinit var healthStore: DatabaseHealthStore
    private lateinit var factory: AppRulesStoreFactory

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore)
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun migration2to14_addsEveryTableAndRoomAcceptsTheSchema() {
        val dbPath = context.getDatabasePath(AppRulesStoreFactory.DB_NAME).absolutePath

        AndroidSQLiteDriver().open(dbPath).use { conn ->
            V2_DDL.forEach { ddl ->
                conn.prepare(ddl).use { it.step() }
            }
            // Pre-existing v2 data: a v2-era rule must survive the whole chain as enabled.
            conn.prepare(
                "INSERT INTO user_profile (user_id, name, email, date_of_birth, has_accessibility_permission, " +
                    "has_usage_stats_permission, has_vpn_permission, has_notification_permission, " +
                    "has_device_admin_permission, is_active) VALUES " +
                    "('p-1', 'Test', 't@example.com', 0, 0, 0, 0, 0, 0, 1)",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO restriction_rules (rule_id, profile_id, blocked_package, target_type, is_always_on, " +
                    "daily_limit_ms, active_days_of_week, timezone, block_expires_at, severity_level) VALUES " +
                    "('r-1', 'p-1', 'com.instagram.android', 'APP', 1, 0, '1111111', 'UTC', 0, 'HIGH')",
            ).use { it.step() }
            // v2-era dormant tracker row (enforcement plan §12): must survive 7→8 losslessly
            // with the specified defaults.
            conn.prepare(
                "INSERT INTO daily_usage_tracker (tracker_id, profile_id, package_name, date_epoch, " +
                    "ms_used_today) VALUES ('t-legacy', 'p-1', 'com.instagram.android', 20820, 1000)",
            ).use { it.step() }
            // v2-era coaching step (the pre-8 shape, no kind column): must read back as STEP.
            conn.prepare(
                "INSERT INTO coaching_step (step_id, profile_id, step_order, action_text, " +
                    "associated_trigger_window) VALUES ('c-1', 'p-1', 1, 'Call my brother', '')",
            ).use { it.step() }
            conn.prepare("PRAGMA user_version = 2").use { it.step() }
        }

        val store = factory.open()
        assertNotNull("Store must open after the whole 2→9 chain", store)
        assertEquals(DatabaseHealth.Ready, healthStore.health.value)

        runBlocking {
            // Fresh recovery_state table: no rows yet, fully usable.
            assertNull(store!!.recoveryStateDao().current())
            // Sibling tables intact and queryable after the migration.
            assertEquals(60L, store.recoveryPolicyDao().current()!!.defaultFrictionSeconds)
            // The v2-era rule survived with the v5 column defaulted to enabled.
            val rule = store.restrictionRuleDao().getById("r-1")
            assertNotNull("Pre-existing rule must survive the chain", rule)
            assertEquals(true, rule!!.isEnabled)
            // attempt_log (v4) exists and is fully usable post-migration.
            val dao = store.attemptLogDao()
            assertEquals(0L, dao.count())
            val id = dao.insert(
                AttemptLog(
                    occurredAtEpochMillis = 1_700_000_000_000L,
                    ruleId = "r-1",
                    blockedTarget = "com.instagram.android",
                    outcome = "BLOCKED",
                ),
            )
            assertEquals(1L, dao.count())
            assertEquals(id, dao.recent(1).single().id)

            // Tracker 7→8: the dormant legacy row survives with the plan's stated defaults —
            // anchored to the migration's local date + device timezone, warnings NOT_DUE,
            // not blocked.
            val tracker = store.dailyUsageTrackerDao()
                .findByKey("p-1", "com.instagram.android", LocalDate.now().toString())
            assertNotNull("Legacy tracker row must survive migration 7→8", tracker)
            assertEquals(1_000L, tracker!!.msUsedToday)
            assertEquals("NOT_DUE", tracker.warning1State)
            assertEquals("NOT_DUE", tracker.warning2State)
            assertEquals(false, tracker.blockedToday)
            assertTrue(tracker.timezoneIdAtAnchor.isNotBlank())

            // Recovery resource 8→9: both new tables are created and fully usable, and the
            // pre-existing coaching_step table gains the kind column defaulted to STEP.
            val sessions = store.recoverySessionDao()
            assertEquals(0L, sessions.count())
            sessions.insert(
                RecoverySession(
                    id = "s-1",
                    technique = RecoveryResourceCatalog.URGE_SURFING,
                    startedAtEpochMs = 1_700_000_000_000L,
                    zoneId = "Africa/Nairobi",
                    entry = RecoverySession.ENTRY_TAB,
                ),
            )
            assertEquals(1, sessions.page(10, 0).size)
            assertEquals(
                "a STARTED row is not mirrorable",
                0,
                sessions.pendingMirror("pair-a", sinceMs = 0L, limit = 10).size,
            )

            val windows = store.riskWindowDao()
            assertEquals(0L, windows.count())
            windows.insert(RiskWindow.DEFAULT)
            assertEquals(1, windows.all().size)

            // The legacy coaching_step row (inserted above with the v2 DDL) reads back as STEP.
            val step = store.coachingStepDao().getForProfile("p-1").single()
            assertEquals(CoachingStep.KIND_STEP, step.kind)

            // Domain-usage metering 9→10: the usage table is created, empty, and fully usable.
            val usage = store.ruleUsageDao()
            assertEquals(0, usage.loadDay("2026-09-20").size)
            usage.upsertMax(listOf(UsageSnapshot("r-1", 0L, "2026-09-20", 5_000L, generation = 1L)))
            assertEquals(5_000L, usage.loadDay("2026-09-20").single().activeMs)
            assertEquals("r-1", usage.maxRevisions().single().ruleId)

            // Usage ledger 10→11 (§8.10): the legacy tracker row was anchored to *today* by
            // MIGRATION_7_8, so the day-aware seeding carries its 1000 ms forward as one
            // deterministic MIGRATION_CARRYOVER row — and the projection (the tracker row
            // itself) is untouched, so tracker == Σ ledger still holds after the migration.
            val seeded = store.usageLedgerDao().findByEventId("migration_seed:com.instagram.android")
            assertNotNull("the current-day legacy scalar must seed a carryover row", seeded)
            assertEquals(UsageLedgerKind.MIGRATION_CARRYOVER, seeded!!.kind)
            assertEquals(1_000L, seeded.creditedMs)
            assertEquals(LocalDate.now().toString(), seeded.dayKey)
            assertEquals(UsageLedgerSource.MAINTENANCE, seeded.source)
            assertEquals(0L, seeded.elapsedStartMs)
            assertEquals(0L, seeded.elapsedEndMs)
            assertEquals(
                1_000L,
                store.usageLedgerDao().sumCredited(
                    "com.instagram.android",
                    LocalDate.now().toString(),
                    UsageLedgerKind.CREDITING,
                ),
            )

            // Per-pair mirror links 11→12 (ADR #111): the attempt inserted above pends for
            // EVERY pair until each is linked; one pair's delivery never hides it from others.
            assertEquals(listOf(id), dao.pendingEventCreation("pair-a", sinceMs = 0L, limit = 10).map { it.id })
            assertEquals(listOf(id), dao.pendingEventCreation("pair-b", sinceMs = 0L, limit = 10).map { it.id })
            dao.linkEvent(id, "pair-a", sequence = 11L)
            assertEquals(11L, dao.linkSequence(id, "pair-a"))
            assertTrue(dao.pendingEventCreation("pair-a", sinceMs = 0L, limit = 10).isEmpty())
            assertEquals(
                "the second pair still sees the row pending",
                listOf(id),
                dao.pendingEventCreation("pair-b", sinceMs = 0L, limit = 10).map { it.id },
            )

            // Partner-activity layer 12→13: journal + per-pair links + received-state
            // projection, all usable after the chain (and empty on fresh install — the
            // pre-existing rows above must not leak into them). v16 (ADR #135): a habit-kind
            // row is pending only for a pair whose consent matrix covers this habit — seed
            // the habit under the surviving v2 profile and a share_streak row per pair.
            val activity = store.partnerActivityDao()
            assertEquals(0, activity.deleteJournalOlderThan(Long.MAX_VALUE, 10))
            store.habitProgressDao().insert(
                HabitProgress(
                    progressId = "h-1",
                    profileId = "p-1",
                    targetIdentifier = "Instagram",
                    currentCleanDaysStreak = 0,
                    totalRelapses = 0,
                    accessAttemptsCount = 0,
                    habitDurationYears = 1,
                    onboardingSeverityLevel = "MODERATE",
                    lastRelapseTimestamp = null,
                    assignedPartnershipId = null,
                    isSelected = true,
                ),
            )
            for (pair in listOf("pair-a", "pair-b")) {
                store.pairSharingDao().upsertStreak(
                    PairStreakSharing(
                        pairId = pair,
                        habitProgressId = "h-1",
                        shareStreak = true,
                    ),
                )
            }
            activity.insertJournal(
                PartnerActivityOutbox(
                    eventId = "evt-1",
                    kind = EventPayload.TYPE_STREAK_UPDATED,
                    habitProgressId = "h-1",
                    text = "",
                    occurredAtMs = 1_700_000_000_000L,
                    daysFree = 3,
                    relapseCount = 0,
                    milestonesReached = encodeMilestoneDays(emptyList()),
                    coachingProgress = 1,
                    protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
                    schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
                    createdAtMs = 1_700_000_000_000L,
                ),
            )
            assertEquals(listOf("evt-1"), activity.pendingForPair("pair-a", sinceMs = 0L, limit = 10).map { it.eventId })
            assertEquals(listOf("evt-1"), activity.pendingForPair("pair-b", sinceMs = 0L, limit = 10).map { it.eventId })
            activity.linkEvent("evt-1", "pair-a", sequence = 7L)
            assertEquals(7L, activity.linkSequence("evt-1", "pair-a"))
            assertTrue(activity.pendingForPair("pair-a", sinceMs = 0L, limit = 10).isEmpty())
            assertEquals(
                "the second pair still sees the journal row pending",
                listOf("evt-1"),
                activity.pendingForPair("pair-b", sinceMs = 0L, limit = 10).map { it.eventId },
            )

            // Projection: wholesale apply, then the monotonic guard rejects a late replay.
            activity.applyStateAfter(
                "pair-a",
                daysFree = 11,
                relapseCount = 2,
                milestonesReached = encodeMilestoneDays(listOf(7, 30)),
                coachingProgress = 3,
                occurredAtMs = 1L,
                sequence = 5L,
            )
            val projected = activity.projection("pair-a")!!
            assertEquals(11, projected.daysFree)
            assertEquals(listOf(7, 30), decodeMilestoneDays(projected.milestonesReached))
            activity.applyStateAfter(
                "pair-a",
                daysFree = 1,
                relapseCount = 0,
                milestonesReached = encodeMilestoneDays(emptyList()),
                coachingProgress = 0,
                occurredAtMs = 0L,
                sequence = 4L,
            )
            assertEquals(
                "an older replay must not move the projection backwards",
                11,
                activity.projection("pair-a")!!.daysFree,
            )
            assertEquals(5L, activity.projection("pair-a")!!.lastSequence)

            // A message-only arrival creates its row; newest pin first across pairs (#12 list).
            activity.pinMessage("pair-b", "You've got this", atMs = 1_700_000_100_000L)
            assertEquals(listOf("pair-b"), activity.observePinnedMessages().first().map { it.pairId })
            activity.pinMessage("pair-a", "Still rooting for you", atMs = 1_700_000_200_000L)
            assertEquals(listOf("pair-a", "pair-b"), activity.observePinnedMessages().first().map { it.pairId })

            // Retention: nothing older than the cutoff survives it (and the deleted journal
            // row stops pending for the pair that never got a link).
            assertEquals(0, activity.deleteJournalOlderThan(1_700_000_000_000L, 10))
            assertEquals(1, activity.deleteJournalOlderThan(1_700_000_000_001L, 10))
            assertTrue(activity.pendingForPair("pair-b", sinceMs = 0L, limit = 10).isEmpty())
            assertNull(
                "an expired journal row's per-pair link must die with it (finding #63)",
                activity.linkSequence("evt-1", "pair-a"),
            )

            // Message addressing 13→14 (#96): with the column in place, a row addressed to one
            // pair pends only for that pair, while an untargeted row keeps fanning out.
            activity.insertJournal(
                PartnerActivityOutbox(
                    eventId = "evt-msg",
                    kind = EventPayload.TYPE_MOTIVATIONAL_MESSAGE,
                    text = "For you only",
                    occurredAtMs = 1_700_000_000_100L,
                    daysFree = 0,
                    relapseCount = 0,
                    milestonesReached = encodeMilestoneDays(emptyList()),
                    coachingProgress = 0,
                    protocolVersion = EventPayload.ACTIVITY_PROTOCOL_VERSION,
                    schemaVersion = EventPayload.ACTIVITY_SCHEMA_VERSION,
                    createdAtMs = 1_700_000_000_100L,
                    targetPairId = "pair-b",
                ),
            )
            assertTrue(
                "a row addressed to one pair must not pend for another",
                activity.pendingForPair("pair-a", sinceMs = 0L, limit = 10).isEmpty(),
            )
            assertEquals(
                listOf("evt-msg"),
                activity.pendingForPair("pair-b", sinceMs = 0L, limit = 10).map { it.eventId },
            )
        }
    }

    /**
     * #96 message addressing, direct invocation (the MIGRATION_10_11 pattern, for the same
     * reason: the full chain would mask the intermediate state): 13→14 is one additive ALTER —
     * every pre-existing row reads back untargeted, which is exactly the pre-v14 fan-out
     * semantics — and 14→13 must physically drop the column (Room validates unknown columns on
     * open, so leaving it in place would fail a downgraded build's open) while recreating the
     * table with every journal row and its index intact.
     */
    @Test
    fun migration13to14AndBack_dropsTheTargetColumn_andKeepsTheJournalRows() {
        val scratchPath = context.getDatabasePath("msg-addressing-test.db").absolutePath
        AndroidSQLiteDriver().open(scratchPath).use { conn ->
            conn.prepare(V13_DDL_ACTIVITY_OUTBOX).use { it.step() }
            conn.prepare(V13_DDL_ACTIVITY_OUTBOX_INDEX).use { it.step() }
            conn.prepare(
                "INSERT INTO partner_activity_outbox (event_id, kind, text, occurred_at_ms, " +
                    "days_free, relapse_count, milestones_reached, coaching_progress, " +
                    "protocol_version, schema_version, created_at_ms) VALUES " +
                    "('evt-legacy', 'streak_updated', '', 100, 5, 0, '7', 1, 1, 1, 100)",
            ).use { it.step() }

            runBlocking { AppRulesStoreFactory.MIGRATION_13_14.migrate(conn) }

            assertTrue(
                "13→14 must add the addressing column",
                "target_pair_id" in columnNames(conn, "partner_activity_outbox"),
            )
            conn.prepare(
                "SELECT target_pair_id FROM partner_activity_outbox WHERE event_id = 'evt-legacy'",
            ).use { stmt ->
                assertTrue(stmt.step())
                assertTrue("a pre-v14 row must read back untargeted", stmt.isNull(0))
            }
            conn.prepare(
                "INSERT INTO partner_activity_outbox (event_id, kind, text, occurred_at_ms, " +
                    "days_free, relapse_count, milestones_reached, coaching_progress, " +
                    "protocol_version, schema_version, created_at_ms, target_pair_id) VALUES " +
                    "('evt-addressed', 'motivational_message', 'Keep going', 200, 5, 0, '7', 1, " +
                    "1, 1, 200, 'pair-b')",
            ).use { it.step() }

            runBlocking { AppRulesStoreFactory.MIGRATION_14_13.migrate(conn) }

            assertFalse(
                "14→13 must physically drop the addressing column",
                "target_pair_id" in columnNames(conn, "partner_activity_outbox"),
            )
            val rows = mutableMapOf<String, Pair<String, Long>>()
            conn.prepare(
                "SELECT event_id, kind, occurred_at_ms FROM partner_activity_outbox",
            ).use { stmt ->
                while (stmt.step()) {
                    rows[stmt.getText(0)] = stmt.getText(1) to stmt.getLong(2)
                }
            }
            assertEquals(
                "both journal rows — targeted and untargeted — survive the recreate",
                mapOf(
                    "evt-legacy" to ("streak_updated" to 100L),
                    "evt-addressed" to ("motivational_message" to 200L),
                ),
                rows,
            )
            assertTrue(
                "the index the recreate dropped must be back — Room validates indices on open",
                "index_partner_activity_outbox_created_at_ms" in
                    conn.prepare(
                        "SELECT name FROM sqlite_master WHERE type = 'index' " +
                            "AND tbl_name = 'partner_activity_outbox'",
                    ).use { stmt ->
                        val names = mutableListOf<String>()
                        while (stmt.step()) names.add(stmt.getText(0))
                        names
                    },
            )
        }
        context.deleteDatabase("msg-addressing-test.db")
    }

    /**
     * The v16 consent matrix (ADR #135), direct invocation: 15→16 drops the legacy consent
     * columns outright (recreate-table, no seed — "nothing until you choose" for every
     * install) and adds the per-(pair, item) tables plus the journal's consent key; 16→15
     * reconstructs each legacy flag as "any pair had it on" before dropping the pair tables,
     * so a downgraded build still opens with the user's choices preserved as closely as a
     * single column can hold them.
     */
    @Test
    fun migration15to16AndBack_dropsLegacyConsentColumns_andReconstructsPerPairConsentOnRollback() {
        val scratchPath = context.getDatabasePath("consent-matrix-test.db").absolutePath
        AndroidSQLiteDriver().open(scratchPath).use { conn ->
            conn.prepare(V15_DDL_HABIT_PROGRESS).use { it.step() }
            conn.prepare(V15_DDL_USER_PROFILE).use { it.step() }
            conn.prepare(V15_DDL_ACTIVITY_OUTBOX).use { it.step() }
            conn.prepare(
                "INSERT INTO user_profile (user_id, name, email, phone_number, date_of_birth, " +
                    "has_accessibility_permission, has_usage_stats_permission, has_vpn_permission, " +
                    "has_notification_permission, has_device_admin_permission, device_public_key, " +
                    "recovery_escrow_key, is_active, share_check_ins, share_recovery) VALUES " +
                    "('p-1', 'Test', 't@example.com', NULL, 0, 0, 0, 0, 0, 0, NULL, NULL, 1, 1, 1)",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO habit_progress (progress_id, profile_id, target_identifier, " +
                    "current_clean_days_streak, total_relapses, access_attempts_count, habit_duration_years, " +
                    "onboarding_severity_level, last_relapse_timestamp, share_access_attempts, " +
                    "share_relapses, share_milestones, share_streak, assigned_partnership_id, " +
                    "all_time_max_streak_elapsed_ms, is_selected) VALUES " +
                    "('h-1', 'p-1', 'Instagram', 12, 3, 0, 2, 'MODERATE', NULL, 0, 1, 1, 1, NULL, 0, 1)",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO partner_activity_outbox (event_id, kind, target_pair_id, text, " +
                    "occurred_at_ms, days_free, relapse_count, milestones_reached, habit_label, " +
                    "ended_days, milestone_day, coaching_progress, protocol_version, schema_version, " +
                    "created_at_ms) VALUES ('evt-legacy', 'streak_updated', NULL, '', 100, 12, 3, " +
                    "'7', 'Instagram', NULL, NULL, 1, 1, 2, 100)",
            ).use { it.step() }

            runBlocking { AppRulesStoreFactory.MIGRATION_15_16.migrate(conn) }

            assertFalse(
                "the legacy per-habit consent columns must be physically gone",
                columnNames(conn, "habit_progress").any { it in LEGACY_CONSENT_COLUMNS },
            )
            assertFalse(
                "the legacy per-profile consent columns must be physically gone",
                columnNames(conn, "user_profile").any { it == "share_check_ins" || it == "share_recovery" },
            )
            assertTrue(
                "the journal gains its consent key",
                "habit_progress_id" in columnNames(conn, "partner_activity_outbox"),
            )
            conn.prepare(
                "SELECT habit_progress_id FROM partner_activity_outbox WHERE event_id = 'evt-legacy'",
            ).use { stmt ->
                assertTrue(stmt.step())
                assertTrue("a pre-v16 row matches no pair's slice — null", stmt.isNull(0))
            }
            conn.prepare(
                "SELECT target_identifier, total_relapses, is_selected FROM habit_progress WHERE progress_id = 'h-1'",
            ).use { stmt ->
                assertTrue(stmt.step())
                assertEquals("the habit row survives the recreate intact", "Instagram", stmt.getText(0))
                assertEquals(3L, stmt.getLong(1))
                assertEquals(1L, stmt.getLong(2))
            }
            conn.prepare("SELECT name FROM user_profile WHERE user_id = 'p-1'").use { stmt ->
                assertTrue(stmt.step())
                assertEquals("Test", stmt.getText(0))
            }

            // v16-era choices: pair-1 shares nothing of the streak; pair-2 shares the streak
            // and relapses but not milestones. Profile: pair-1 only, recovery only.
            conn.prepare(
                "INSERT INTO pair_streak_sharing (pair_id, habit_progress_id, share_streak, " +
                    "share_relapses, share_milestones, streak_enabled_since_ms, " +
                    "relapse_enabled_since_ms, milestone_enabled_since_ms) VALUES " +
                    "('pair-1', 'h-1', 0, 0, 0, 0, 0, 0), " +
                    "('pair-2', 'h-1', 1, 1, 0, 150, 160, 0)",
            ).use { it.step() }
            conn.prepare(
                "INSERT INTO pair_profile_sharing (pair_id, share_checkins, share_recovery, " +
                    "checkins_enabled_since_ms, recovery_enabled_since_ms) VALUES ('pair-1', 0, 1, 0, 170)",
            ).use { it.step() }

            runBlocking { AppRulesStoreFactory.MIGRATION_16_15.migrate(conn) }

            conn.prepare(
                "SELECT share_access_attempts, share_relapses, share_milestones, share_streak, " +
                    "total_relapses FROM habit_progress WHERE progress_id = 'h-1'",
            ).use { stmt ->
                assertTrue(stmt.step())
                assertEquals("access attempts were never writable by any UI", 0L, stmt.getLong(0))
                assertEquals("pair-2 had relapses on", 1L, stmt.getLong(1))
                assertEquals("no pair had milestones on", 0L, stmt.getLong(2))
                assertEquals("pair-2 had the streak on", 1L, stmt.getLong(3))
                assertEquals("streak data survives the round trip", 3L, stmt.getLong(4))
            }
            conn.prepare(
                "SELECT share_check_ins, share_recovery FROM user_profile WHERE user_id = 'p-1'",
            ).use { stmt ->
                assertTrue(stmt.step())
                assertEquals("pair-1 had check-ins off", 0L, stmt.getLong(0))
                assertEquals("pair-1 had recovery on", 1L, stmt.getLong(1))
            }
            conn.prepare(
                "SELECT kind, days_free FROM partner_activity_outbox WHERE event_id = 'evt-legacy'",
            ).use { stmt ->
                assertTrue(stmt.step())
                assertEquals("streak_updated", stmt.getText(0))
                assertEquals("the journal row survives both recreates", 12L, stmt.getLong(1))
            }
            assertFalse(
                "habit_progress_id is v16-only",
                "habit_progress_id" in columnNames(conn, "partner_activity_outbox"),
            )
            assertTrue("pair tables dropped on rollback", columnNames(conn, "pair_streak_sharing").isEmpty())
            assertTrue("pair tables dropped on rollback", columnNames(conn, "pair_profile_sharing").isEmpty())
        }
        context.deleteDatabase("consent-matrix-test.db")
    }

    private fun columnNames(conn: androidx.sqlite.SQLiteConnection, table: String): List<String> {
        val names = mutableListOf<String>()
        conn.prepare("PRAGMA table_info($table)").use { stmt ->
            while (stmt.step()) names.add(stmt.getText(1))
        }
        return names
    }

    /**
     * §8.10's day-aware seeding, branch by branch, against a hand-built v10 database — the
     * three cases the plan's migration acceptance names (dated-and-current, dated-and-stale,
     * undated → the v10 schema always carries an anchor, so the stale case is represented by
     * both a previous-day anchor and a timezone mismatch), plus the zero-usage case and AD-11's
     * loud failure on unexpected pre-existing schema state.
     *
     * Direct migration invocation is deliberate: routing through `factory.open()` would let
     * MIGRATION_7_8 re-anchor every row to today, making a stale row impossible to construct
     * through the chain at all.
     */
    @Test
    fun migration10to11_seedsOnlyTheCurrentDayScalar_andFailsLoudlyOnPreExistingLedger() {
        val today = LocalDate.now().toString()
        val zone = ZoneId.systemDefault().id
        val scratchPath = context.getDatabasePath("ledger-seed-test.db").absolutePath

        AndroidSQLiteDriver().open(scratchPath).use { conn ->
            conn.prepare(V10_MINIMAL_DDL_TRACKER).use { it.step() }
            conn.prepare(V10_MINIMAL_DDL_PROFILE).use { it.step() }
            conn.prepare("INSERT INTO user_profile (user_id, is_active) VALUES ('p-1', 1)").use { it.step() }
            // (a) current day + current zone → carried.
            insertLegacyTracker(conn, "t-current", "com.today", today, zone, 5_000L, "p-1")
            // (b) previous day → dropped, not treated as today's usage.
            insertLegacyTracker(conn, "t-stale", "com.yesterday", "2020-01-01", zone, 90_000L, "p-1")
            // (c) same day, different zone → dropped (the anchor pair is the day identity).
            insertLegacyTracker(conn, "t-zone", "com.zonemoved", today, "Pacific/Kiritimati", 7_000L, "p-1")
            // (d) zero usage → nothing to carry.
            insertLegacyTracker(conn, "t-zero", "com.none", today, zone, 0L, "p-1")

            runBlocking { AppRulesStoreFactory.MIGRATION_10_11.migrate(conn) }

            val rows = mutableListOf<Triple<String, String, Long>>()
            conn.prepare(
                "SELECT event_id, day_key, credited_ms FROM usage_ledger ORDER BY event_id",
            ).use { stmt ->
                while (stmt.step()) {
                    rows.add(Triple(stmt.getText(0), stmt.getText(1), stmt.getLong(2)))
                }
            }
            assertEquals(
                "only the current-day, current-zone scalar seeds",
                listOf(Triple("migration_seed:com.today", today, 5_000L)),
                rows,
            )

            // AD-11: plain DDL, so a pre-existing usage_ledger fails the migration loudly
            // rather than being silently masked by IF NOT EXISTS.
            var threw = false
            try {
                runBlocking { AppRulesStoreFactory.MIGRATION_10_11.migrate(conn) }
            } catch (e: Exception) {
                threw = true
            }
            assertTrue("re-running must fail on the existing table (AD-11 plain DDL)", threw)
        }
        context.deleteDatabase("ledger-seed-test.db")
    }

    private fun insertLegacyTracker(
        conn: androidx.sqlite.SQLiteConnection,
        trackerId: String,
        packageName: String,
        anchor: String,
        zone: String,
        msUsed: Long,
        profileId: String,
    ) {
        conn.prepare(
            "INSERT INTO daily_usage_tracker (tracker_id, profile_id, package_name, " +
                "local_date_anchor, timezone_id_at_anchor, ms_used_today) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
        ).use { stmt ->
            stmt.bindText(1, trackerId)
            stmt.bindText(2, profileId)
            stmt.bindText(3, packageName)
            stmt.bindText(4, anchor)
            stmt.bindText(5, zone)
            stmt.bindLong(6, msUsed)
            stmt.step()
        }
    }

    /**
     * The ADR #58 guard, applied to this store: a hand-maintained migration list that misses a
     * step leaves installed devices unable to open their DB. Reads the version Room actually
     * creates for a fresh install (SQLite header offset 60) and requires [ALL_MIGRATIONS] to
     * cover every step to it.
     */
    @Test
    fun allMigrations_coverEveryVersionStepToTheDeclaredSchemaVersion() {
        val scratch = "rules-migration-coverage.db"
        context.deleteDatabase(scratch)
        val db = Room.databaseBuilder(context, AppRulesStore::class.java, scratch).build()
        runBlocking { db.restrictionRuleDao().getActiveRules() }
        db.close()
        val declared = RandomAccessFile(context.getDatabasePath(scratch), "r").use {
            it.seek(60) // SQLite header: user_version, 4 bytes big-endian
            it.readInt()
        }
        context.deleteDatabase(scratch)

        val covered = AppRulesStoreFactory.ALL_MIGRATIONS
            .map { it.startVersion to it.endVersion }
            .toSet()
        (1 until declared).forEach { from ->
            assertTrue(
                "no migration registered for v$from → v${from + 1} (declared schema v$declared)",
                (from to from + 1) in covered,
            )
        }
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    companion object {
        /** Only the columns MIGRATION_10_11 reads: the tracker's day identity + scalar, and
         *  the active-profile lookup. Kept deliberately minimal so the test fails if the
         *  migration ever starts depending on a column not listed here. */
        private const val V10_MINIMAL_DDL_TRACKER =
            "CREATE TABLE `daily_usage_tracker` (" +
                "`tracker_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, " +
                "`package_name` TEXT NOT NULL, `local_date_anchor` TEXT NOT NULL, " +
                "`timezone_id_at_anchor` TEXT NOT NULL, `ms_used_today` INTEGER NOT NULL, " +
                "PRIMARY KEY(`tracker_id`))"
        private const val V10_MINIMAL_DDL_PROFILE =
            "CREATE TABLE `user_profile` (`user_id` TEXT NOT NULL, `is_active` INTEGER NOT NULL, " +
                "PRIMARY KEY(`user_id`))"

        /** Verbatim v13 DDL for the journal table + its index (from 13.json), the only objects
         *  the 13↔14 pair touches. */
        private const val V13_DDL_ACTIVITY_OUTBOX =
            "CREATE TABLE IF NOT EXISTS `partner_activity_outbox` (`event_id` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `text` TEXT NOT NULL, `occurred_at_ms` INTEGER NOT NULL, " +
                "`days_free` INTEGER NOT NULL, `relapse_count` INTEGER NOT NULL, " +
                "`milestones_reached` TEXT NOT NULL, `coaching_progress` INTEGER NOT NULL, " +
                "`protocol_version` INTEGER NOT NULL, `schema_version` INTEGER NOT NULL, " +
                "`created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`event_id`))"
        private const val V13_DDL_ACTIVITY_OUTBOX_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_partner_activity_outbox_created_at_ms` " +
                "ON `partner_activity_outbox` (`created_at_ms`)"

        /** Verbatim v15 DDL for the three tables the 15↔16 pair touches (from 15.json). */
        private const val V15_DDL_HABIT_PROGRESS =
            "CREATE TABLE IF NOT EXISTS `habit_progress` (`progress_id` TEXT NOT NULL, " +
                "`profile_id` TEXT NOT NULL, `target_identifier` TEXT NOT NULL, " +
                "`current_clean_days_streak` INTEGER NOT NULL, `total_relapses` INTEGER NOT NULL, " +
                "`access_attempts_count` INTEGER NOT NULL, `habit_duration_years` INTEGER NOT NULL, " +
                "`onboarding_severity_level` TEXT NOT NULL, `last_relapse_timestamp` INTEGER, " +
                "`share_access_attempts` INTEGER NOT NULL, `share_relapses` INTEGER NOT NULL, " +
                "`share_milestones` INTEGER NOT NULL, `share_streak` INTEGER NOT NULL DEFAULT 1, " +
                "`assigned_partnership_id` TEXT, " +
                "`all_time_max_streak_elapsed_ms` INTEGER NOT NULL DEFAULT 0, " +
                "`is_selected` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`progress_id`), " +
                "FOREIGN KEY(`profile_id`) REFERENCES `user_profile`(`user_id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        private const val V15_DDL_USER_PROFILE =
            "CREATE TABLE IF NOT EXISTS `user_profile` (`user_id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `email` TEXT NOT NULL, `phone_number` TEXT, " +
                "`date_of_birth` INTEGER NOT NULL, `has_accessibility_permission` INTEGER NOT NULL, " +
                "`has_usage_stats_permission` INTEGER NOT NULL, `has_vpn_permission` INTEGER NOT NULL, " +
                "`has_notification_permission` INTEGER NOT NULL, " +
                "`has_device_admin_permission` INTEGER NOT NULL, `device_public_key` TEXT, " +
                "`recovery_escrow_key` TEXT, `is_active` INTEGER NOT NULL, " +
                "`share_check_ins` INTEGER NOT NULL DEFAULT 1, " +
                "`share_recovery` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`user_id`))"
        private const val V15_DDL_ACTIVITY_OUTBOX =
            "CREATE TABLE IF NOT EXISTS `partner_activity_outbox` (`event_id` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `target_pair_id` TEXT, `text` TEXT NOT NULL, " +
                "`occurred_at_ms` INTEGER NOT NULL, `days_free` INTEGER NOT NULL, " +
                "`relapse_count` INTEGER NOT NULL, `milestones_reached` TEXT NOT NULL, " +
                "`habit_label` TEXT, `ended_days` INTEGER, `milestone_day` INTEGER, " +
                "`coaching_progress` INTEGER NOT NULL, `protocol_version` INTEGER NOT NULL, " +
                "`schema_version` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`event_id`))"

        private val LEGACY_CONSENT_COLUMNS =
            listOf("share_access_attempts", "share_relapses", "share_milestones", "share_streak")

        /** Verbatim v2 schema DDL from Room's generated AppRulesStore_Impl (v3 minus recovery_state). */
        private val V2_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `user_profile` (`user_id` TEXT NOT NULL, `name` TEXT NOT NULL, `email` TEXT NOT NULL, `phone_number` TEXT, `date_of_birth` INTEGER NOT NULL, `has_accessibility_permission` INTEGER NOT NULL, `has_usage_stats_permission` INTEGER NOT NULL, `has_vpn_permission` INTEGER NOT NULL, `has_notification_permission` INTEGER NOT NULL, `has_device_admin_permission` INTEGER NOT NULL, `device_public_key` TEXT, `recovery_escrow_key` TEXT, `is_active` INTEGER NOT NULL, PRIMARY KEY(`user_id`))",
            "CREATE TABLE IF NOT EXISTS `restriction_rules` (`rule_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `blocked_package` TEXT NOT NULL, `target_type` TEXT NOT NULL, `is_always_on` INTEGER NOT NULL, `daily_limit_ms` INTEGER NOT NULL, `time_range_start` INTEGER, `time_range_end` INTEGER, `active_days_of_week` TEXT NOT NULL, `timezone` TEXT NOT NULL DEFAULT 'UTC', `block_expires_at` INTEGER NOT NULL, `severity_level` TEXT NOT NULL, PRIMARY KEY(`rule_id`), FOREIGN KEY(`profile_id`) REFERENCES `user_profile`(`user_id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_restriction_rules_profile_id_blocked_package` ON `restriction_rules` (`profile_id`, `blocked_package`)",
            "CREATE TABLE IF NOT EXISTS `daily_usage_tracker` (`tracker_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `package_name` TEXT NOT NULL, `date_epoch` INTEGER NOT NULL, `ms_used_today` INTEGER NOT NULL, PRIMARY KEY(`tracker_id`), FOREIGN KEY(`profile_id`) REFERENCES `user_profile`(`user_id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_daily_usage_tracker_profile_id_package_name_date_epoch` ON `daily_usage_tracker` (`profile_id`, `package_name`, `date_epoch`)",
            "CREATE TABLE IF NOT EXISTS `habit_progress` (`progress_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `target_identifier` TEXT NOT NULL, `current_clean_days_streak` INTEGER NOT NULL, `total_relapses` INTEGER NOT NULL, `access_attempts_count` INTEGER NOT NULL, `habit_duration_years` INTEGER NOT NULL, `onboarding_severity_level` TEXT NOT NULL, `last_relapse_timestamp` INTEGER, `share_access_attempts` INTEGER NOT NULL, `share_relapses` INTEGER NOT NULL, `share_milestones` INTEGER NOT NULL, `assigned_partnership_id` TEXT, PRIMARY KEY(`progress_id`), FOREIGN KEY(`profile_id`) REFERENCES `user_profile`(`user_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `coaching_step` (`step_id` TEXT NOT NULL, `profile_id` TEXT NOT NULL, `step_order` INTEGER NOT NULL, `action_text` TEXT NOT NULL, `associated_trigger_window` TEXT NOT NULL, PRIMARY KEY(`step_id`), FOREIGN KEY(`profile_id`) REFERENCES `user_profile`(`user_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE TABLE IF NOT EXISTS `policy_version` (`id` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `recovery_policy` (`id` INTEGER NOT NULL, `default_friction_seconds` INTEGER NOT NULL, `override_cooldown_seconds` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            // Real v2 databases carry the seeded singleton rows (onCreate / MIGRATION_1_2).
            "INSERT OR IGNORE INTO policy_version (id, version) VALUES (0, 0)",
            "INSERT OR IGNORE INTO recovery_policy (id, default_friction_seconds, override_cooldown_seconds) VALUES (0, 60, 600)",
        )
    }
}
