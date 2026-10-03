package com.example.myexodus.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import com.example.myexodus.accountability.sync.PushInstallationRegistrar
import com.example.myexodus.blocking.usage.AccountingDiagnostics
import com.example.myexodus.blocking.usage.AppendOutcome
import com.example.myexodus.blocking.usage.LedgerEventDraft
import com.example.myexodus.blocking.usage.LedgerRetention
import com.example.myexodus.blocking.usage.PlatformUsageReader
import com.example.myexodus.blocking.usage.UsageLedgerRepository
import com.example.myexodus.blocking.usage.WarningState
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.DailyUsageTracker
import com.example.myexodus.data.db.entity.UsageLedgerKind
import com.example.myexodus.data.db.entity.UsageLedgerSource
import com.example.myexodus.data.db.entity.UserProfile
import com.google.android.gms.tasks.Tasks
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Focus-free §12 assertions (debug only): starting [DeviceVerificationActivity] to read state
 * hijacks the foreground — the read activity's own WSC closes the very usage session under
 * measurement (device-caught 2026-09-15 on the handoff scenario: the "read" ended the accrual
 * it was measuring). A broadcast runs in-process with no window, so `usage-read` here is safe
 * to call mid-measurement.
 *
 * Host: `am broadcast -n com.example.myexodus/.blocking.DeviceVerificationReceiver -a usage-read --es pkg <pkg>`
 *
 * The ledger suite (§8.8/§12) shares the host form: `-a ledger-read` (journal counts),
 * `-a ledger-seed-old` (one past-retention row), `-a ledger-run-maintenance` (the real
 * sweep), `-a ledger-simulate-jump` (plant a 29 h clock disparity, device clock untouched),
 * `-a ledger-reset` (maintenance state back to first-run), `-a ledger-bench` (§11's
 * performance run — bounded, self-cleaning), `-a ledger-outage` (D1's read model),
 * `-a ledger-day-audit --es pkg <p> [--es day yyyy-MM-dd]` (task #13: journal rows + day
 * totals + gap rows + platform totals/intervals, the tracker-vs-DW comparison),
 * `-a ledger-age-evidence --es hours <n>` (task #13: plant a liveness row n hours in the past
 * to force a measurable gap — chain it with a freeze in one shell command; **every** use must
 * end with the purge below, the action's log line prints its own recipe), and
 * `-a ledger-correct --es eventId <full> --es deltaMs <n>` (task #13: land a §8.12 signed
 * correction against one event and rebuild the projection — the device leg's reversal seam),
 * and `-a ledger-purge-window --es kind <KIND> --es fromMs <wall> --es toMs <wall>` (task #62:
 * retire journal rows a debug leg fabricated — evidence kinds only, refused for crediting kinds),
 * and `-a protection-reenable` (task #69: clear the user-stop flag `am force-stop` sets on the
 * emulator, which otherwise gates the FGS tick body — poll, oracle, and session feed), and
 * `-a mirror-read` (task #52: the v12 per-pair mirror-link rails — schema version, both link
 * tables' counts and per-pair rows, and the legacy `event_sequence` marker census), and
 * `-a push-read` (§18's FCM matrix leg: the FCM registration token + the AD-18a installation id,
 * so a host-side `messages:send` can target this install; the delivery proof is the app's own
 * `MyExodusPush: wake for <messageId>` line in whatever state the device is in).
 */
class DeviceVerificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pkg = intent.getStringExtra("pkg")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_USAGE_READ -> readRow(context, pkg)
                    ACTION_LEDGER_READ -> ledgerRead(context)
                    ACTION_LEDGER_SEED_OLD -> ledgerSeedOld(context)
                    ACTION_LEDGER_RUN_MAINTENANCE -> ledgerRunMaintenance(context)
                    ACTION_LEDGER_SIMULATE_JUMP -> ledgerSimulateJump(context)
                    ACTION_LEDGER_RESET -> ledgerReset(context)
                    ACTION_LEDGER_OUTAGE -> ledgerOutage(context)
                    ACTION_LEDGER_BENCH -> ledgerBench(context)
                    ACTION_LEDGER_DAY_AUDIT -> ledgerDayAudit(context, pkg, intent.getStringExtra("day"))
                    ACTION_LEDGER_AGE_EVIDENCE -> ledgerAgeEvidence(
                        context,
                        intent.getStringExtra("hours")?.toLongOrNull() ?: 8L,
                    )
                    ACTION_LEDGER_CORRECT -> ledgerCorrect(context, intent)
                    ACTION_LEDGER_PURGE_WINDOW -> ledgerPurgeWindow(context, intent)
                    ACTION_PROTECTION_REENABLE -> protectionReenable(context)
                    ACTION_MIRROR_READ -> mirrorRead(context)
                    ACTION_PUSH_READ -> pushRead(context)
                    else -> Log.w(TAG, "unknown action $action")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "$action failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private fun entryPoint(context: Context) = EntryPointAccessors.fromApplication(
        context.applicationContext, EnhancedModeTestEntryPoint::class.java,
    )

    /** §18 FCM matrix leg: the token a host-side `messages:send` needs to target this install,
     *  plus the FID the dispatcher multicasts to (SOS §8.12) and the legacy ANDROID_ID so the
     *  migration leg can confirm its document was deleted. `Tasks.await` blocks, but the
     *  receiver already runs on Dispatchers.IO. */
    private fun pushRead(context: Context) {
        val token = runCatching { Tasks.await(FirebaseMessaging.getInstance().token) }
            .onFailure { Log.w(TAG, "push fcm token unavailable: ${it.message}") }
            .getOrNull()
        val fid = runCatching { Tasks.await(FirebaseInstallations.getInstance().id) }
            .onFailure { Log.w(TAG, "push fid unavailable: ${it.message}") }
            .getOrNull()
        Log.i(
            TAG,
            "push fcm token=${token ?: "<unavailable>"} " +
                "installationId=${fid ?: "<unavailable>"} " +
                "legacyAndroidId=${PushInstallationRegistrar.androidIdOf(context)}",
        )
    }

    private suspend fun readRow(context: Context, pkg: String?) {
        if (pkg == null) {
            Log.e(TAG, "usage row: --es pkg required")
            return
        }
        val entryPoint = entryPoint(context)
        val store = entryPoint.appRulesStoreFactory().open() ?: error("store unavailable")
        val profile = store.userProfileDao().observeActive().first() ?: PROFILE
        val rule = store.restrictionRuleDao().getByProfileAndPackage(profile.userId, pkg)
        if (rule == null) Log.i(TAG, "usage rule pkg=$pkg ABSENT") else Log.i(
            TAG,
            "usage rule pkg=$pkg enabled=${rule.isEnabled} limit=${rule.dailyLimitMs} " +
                "days=${rule.activeDaysOfWeek} tz=${rule.timezone} alwaysOn=${rule.isAlwaysOn} " +
                "window=${rule.timeRangeStart}..${rule.timeRangeEnd} expiresAt=${rule.blockExpiresAt}",
        )
        val rows = store.dailyUsageTrackerDao().getForProfile(profile.userId)
            .filter { it.packageName == pkg }
        if (rows.isEmpty()) Log.i(TAG, "usage row pkg=$pkg ABSENT") else rows.forEach {
            Log.i(
                TAG,
                "usage row pkg=${it.packageName} used=${it.msUsedToday} anchor=${it.localDateAnchor}/${it.timezoneIdAtAnchor} " +
                    "w1=${it.warning1State} w2=${it.warning2State} blocked=${it.blockedToday}",
            )
        }
    }

    /** §8.8 evidence: how much is eligible for the sweep, whether liveness/outage rows are
     *  landing at all, and the carryover rows (the migration leg's proof: `migration_seed:` rows
     *  exist only because a real v10 database was upgraded and had a live tracker row). Total is
     *  the same count with the cutoff pushed past every row. */
    private suspend fun ledgerRead(context: Context) {
        val dao = entryPoint(context).appRulesStoreFactory().open()?.usageLedgerDao()
            ?: error("store unavailable")
        val now = System.currentTimeMillis()
        val day = 24 * 60 * 60 * 1000L
        Log.i(
            TAG,
            "ledger total=${dao.countOlderThan(now + 1)} " +
                "pruneEligible=${dao.countOlderThan(now - LedgerRetention.RETENTION_DAYS * day)} " +
                "heartbeats24h=${dao.rowsOfKindSince(UsageLedgerKind.HEARTBEAT, now - day).size} " +
                "gaps24h=${dao.rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, now - day).size} " +
                "carryover=${dao.rowsOfKindSince(UsageLedgerKind.MIGRATION_CARRYOVER, 0).size} " +
                "accounting24h=${dao.rowsOfKindSince(UsageLedgerKind.ACCOUNTING, now - day).size}",
        )
        listOf(UsageLedgerKind.HEARTBEAT, UsageLedgerKind.GAP_DETECTED).forEach { kind ->
            val rows = dao.rowsOfKindSince(kind, now - day).sortedByDescending { it.wallClockTsMs }
            // Gaps print in full: the outage surfaces the owner sees are computed from exactly
            // this set, so a purge/keep ruling needs all of them, not a tail.
            (if (kind == UsageLedgerKind.GAP_DETECTED) rows else rows.take(5))
                .forEach {
                    Log.i(
                        TAG,
                        "ledger tail ${UsageLedgerKind.nameOf(kind)} wall=${it.wallClockTsMs} " +
                            "elapsed=${it.elapsedStartMs}..${it.elapsedEndMs} src=${it.source}",
                    )
                }
        }
    }

    /** One journal row dated past the retention window, so the sweep has something to delete.
     *  The append outcome is printed: a repeat inside the same heartbeat bucket is a Duplicate. */
    private suspend fun ledgerSeedOld(context: Context) {
        val draft = AccountingDiagnostics.heartbeatDraft(
            source = SEED_SOURCE,
            bootCount = 1,
            wallClockMs = System.currentTimeMillis() - 100 * 24 * 60 * 60 * 1000L,
            elapsedNowMs = SystemClock.elapsedRealtime(),
        )
        Log.i(TAG, "ledger seed-old ${entryPoint(context).usageLedgerRepository().appendStandalone(draft)}")
    }

    private suspend fun ledgerRunMaintenance(context: Context) {
        entryPoint(context).dailyMaintenancePass().run()
        Log.i(TAG, "ledger maintenance run done")
    }

    /** §12's jump row: the frame reads 30 h of wall time but only 1 h of monotonic time — a 29 h
     *  disparity past the 6 h threshold, planted without moving the device clock. */
    private fun ledgerSimulateJump(context: Context) {
        entryPoint(context).ledgerMaintenanceState().seedFrame(
            wallClockMs = System.currentTimeMillis() - 30 * 60 * 60 * 1000L,
            elapsedRealtimeMs = SystemClock.elapsedRealtime() - 60 * 60 * 1000L,
        )
        Log.i(TAG, "ledger jump seeded: wall -30h, elapsed -1h")
    }

    private fun ledgerReset(context: Context) {
        entryPoint(context).ledgerMaintenanceState().clear()
        Log.i(TAG, "ledger maintenance state cleared")
    }

    /** D1's read model as the three surfaces see it: the outage report the Home banner and the
     *  notification render, and the route the Protection row's offer would take. */
    private suspend fun ledgerOutage(context: Context) {
        val outage = entryPoint(context).accountingOutageSource().current()
        Log.i(
            TAG,
            "ledger outage count=${outage?.summary?.count} " +
                "largestWindowMs=${outage?.summary?.largestWindowMs} " +
                "promptRoute=${outage?.promptRoute}",
        )
    }

    /**
     * §11's low-end-device measurement, one bounded self-cleaning run. All bench rows land 100
     * days in the past under a synthetic package, so the ordinary §8.8 prune (which this action
     * also times) is the cleanup path; the one bench tracker row is deleted on the way out. The
     * covering-index shape of each read is the production DAO shape — nothing here re-implements
     * the repository. Contention with the running enforcement service is left in deliberately:
     * this is the device as it actually runs.
     */
    private suspend fun ledgerBench(context: Context) {
        val ep = entryPoint(context)
        val store = ep.appRulesStoreFactory().open() ?: error("store unavailable")
        val repo = ep.usageLedgerRepository()
        val profile = store.userProfileDao().getActive() ?: PROFILE
        val trackerDao = store.dailyUsageTrackerDao()
        val ledgerDao = store.usageLedgerDao()
        val zone = ZoneId.systemDefault()
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val boot = systemBootCount(context)
        val dayMs = 24 * 60 * 60 * 1000L
        val cutoff = nowWall - LedgerRetention.RETENTION_DAYS * dayMs
        val dbPath = context.getDatabasePath(AppRulesStoreFactory.DB_NAME).absolutePath
        val walBytes = { File("$dbPath-wal").length() }
        val oldDayKey = LocalDate.now(zone).minusDays(100)
        val oldMidnightMs = oldDayKey.atStartOfDay(zone).toInstant().toEpochMilli()

        fun draft(
            source: String,
            creditedMs: Long,
            elapsedEndMs: Long,
            wallClockTsMs: Long,
        ) = LedgerEventDraft(
            kind = UsageLedgerKind.ACCOUNTING,
            packageName = BENCH_PKG,
            creditedMs = creditedMs,
            elapsedStartMs = elapsedEndMs - creditedMs,
            elapsedEndMs = elapsedEndMs,
            bootCount = boot,
            source = source,
            wallClockTsMs = wallClockTsMs,
        )

        fun memoryInfo() = Debug.MemoryInfo().let { Debug.getMemoryInfo(it); it }
        val pssBefore = memoryInfo()
        val preEligible = ledgerDao.countOlderThan(cutoff)
        // Checkpoint first so the write phase starts from a flushed DB: the main-file delta then
        // measures the bench rows' real retained bytes (rows + indexes), not pending frames the
        // session's own earlier writes left in the WAL.
        store.useWriterConnection { it.executeSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
        val dbMainBytes = { File(dbPath).length() }
        val walBefore = walBytes()
        // Real retained bytes: pages in use (page_count − freelist_count) × page_size. The main
        // file's own length will not move — SQLite reuses freed pages, so file size is a
        // high-water mark, not a measure of what these 800 rows added.
        suspend fun pragmaLong(sql: String): Long = store.useWriterConnection { tx ->
            tx.usePrepared(sql) { stmt -> if (stmt.step()) stmt.getLong(0) else -1L }
        }
        suspend fun inUseBytes(): Long =
            (pragmaLong("PRAGMA page_count") - pragmaLong("PRAGMA freelist_count")) *
                pragmaLong("PRAGMA page_size")
        val inUseBefore = inUseBytes()

        // 1. Single-event append, own transaction (appendStandalone is the diagnostics shape).
        val single = benchRun(200) { i ->
            repo.appendStandalone(
                draft("bench-single", 1_000L, nowElapsed - i * 1_000L, nowWall - 100 * dayMs - i * 1_000L),
            )
        }
        benchLog("append-single", single)

        // 2. Day-split append: the span crosses local midnight, so the mapper writes two rows.
        val split = benchRun(100) { i ->
            repo.appendStandalone(
                draft("bench-split", 60_000L, nowElapsed - 20_000_000L - i * 1_000L, oldMidnightMs + 1L),
            )
        }
        benchLog("append-day-split", split)

        // 3. The production transaction: tracker read + in-transaction append + projection update,
        //    one write transaction — §11's "transaction latency including the projection update".
        val txn = benchRun(200) { i ->
            store.withWriteTransaction {
                // Production's exact shape (UsageAccountingEngine.observeUsage): read the day's
                // tracker, append in-transaction, then insert-on-first-write / update-after with
                // the journal's own closing-day share.
                val existing = trackerDao.findByPackage(profile.userId, BENCH_PKG)
                val tracker = existing ?: DailyUsageTracker(
                    trackerId = UUID.randomUUID().toString(),
                    profileId = profile.userId,
                    packageName = BENCH_PKG,
                    localDateAnchor = LocalDate.now(zone).toString(),
                    timezoneIdAtAnchor = zone.id,
                    msUsedToday = 0L,
                    warning1State = WarningState.NOT_DUE,
                    warning2State = WarningState.NOT_DUE,
                    blockedToday = false,
                )
                val outcome = repo.append(
                    draft("bench-txn", 1_000L, nowElapsed - 40_000_000L - i * 1_000L, nowWall - 100 * dayMs - i * 1_000L),
                )
                val credited = (outcome as? AppendOutcome.Written)?.closingDayCreditedMs ?: 0L
                val updated = tracker.copy(msUsedToday = tracker.msUsedToday + credited)
                if (existing == null) trackerDao.insert(updated) else trackerDao.update(updated)
            }
        }
        benchLog("append-txn+projection", txn)

        // 4. Batched inserts: 20 appends inside one transaction, 10 rounds.
        val batches = LongArray(10)
        repeat(10) { b ->
            val t0 = System.nanoTime()
            store.withWriteTransaction {
                repeat(20) { i ->
                    repo.append(
                        draft(
                            "bench-batch", 1_000L,
                            nowElapsed - 60_000_000L - (b * 20 + i) * 1_000L,
                            nowWall - 100 * dayMs - (b * 20 + i) * 1_000L,
                        ),
                    )
                }
            }
            batches[b] = System.nanoTime() - t0
        }
        val batchSorted = batches.sortedArray()
        Log.i(
            TAG,
            "bench append-batch rounds=10 rows=200 perTx p50=${"%.2f".format(Locale.US, batchSorted[5] / 1e6)}ms " +
                "max=${"%.2f".format(Locale.US, batchSorted[9] / 1e6)}ms",
        )

        val written = ledgerDao.countOlderThan(cutoff) - preEligible
        val walAfterWrites = walBytes()
        val inUseAfterWrites = inUseBytes()
        store.useWriterConnection { it.executeSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
        val perRowWrites = written.coerceAtLeast(1)
        Log.i(
            TAG,
            "bench wal writes rows=$written (expected 800) wroteB=${walAfterWrites - walBefore} " +
                "perRowB=${(walAfterWrites - walBefore) / perRowWrites}",
        )
        Log.i(
            TAG,
            "bench storage pages rows=$written retainedB=${inUseAfterWrites - inUseBefore} " +
                "perRowB=${(inUseAfterWrites - inUseBefore) / perRowWrites} mainFileB=${dbMainBytes()}",
        )

        // 5. Reads (§11's four: projection read, single-package reconstruction, multi-package
        //    history, prune-candidate selection).
        benchLog("read-tracker-current", benchRun(500) { trackerDao.findByPackage(profile.userId, BENCH_PKG) })
        benchLog("read-dayTotal-pkg", benchRun(200) { repo.dayTotal(BENCH_PKG, oldDayKey.toString()) })
        benchLog("read-dayTotals-all", benchRun(50) { ledgerDao.dayTotals(UsageLedgerKind.CREDITING) })
        benchLog("read-prune-scan", benchRun(50) { ledgerDao.countOlderThan(cutoff) })

        // 6. Prune: the real sweep at the real batch size, timed, with its own WAL growth.
        val walBeforePrune = walBytes()
        val t0 = System.nanoTime()
        val deleted = repo.prune(nowWall)
        val pruneMs = (System.nanoTime() - t0) / 1e6
        val walAfterPrune = walBytes()
        Log.i(
            TAG,
            "bench prune deleted=$deleted ms=${"%.1f".format(Locale.US, pruneMs)} " +
                "walGrewB=${walAfterPrune - walBeforePrune} batchSize=${LedgerRetention.PRUNE_BATCH_SIZE}",
        )

        // Cleanup + verification: ledger rows gone via the normal sweep, bench tracker deleted.
        val remaining = ledgerDao.countOlderThan(cutoff)
        val benchTrackers = trackerDao.getForProfile(profile.userId).filter { it.packageName == BENCH_PKG }
        benchTrackers.forEach { trackerDao.delete(it) }
        Log.i(
            TAG,
            "bench cleanup pruneEligibleRemaining=$remaining benchTrackerRowsDeleted=${benchTrackers.size} " +
                "inUseAfterPruneB=${inUseBytes() - inUseBefore} walNowB=${walBytes()}",
        )

        val pssAfter = memoryInfo()
        Log.i(
            TAG,
            "bench pss_kB pre total=${pssBefore.totalPss} native=${pssBefore.nativePss} dalvik=${pssBefore.dalvikPss} | " +
                "post total=${pssAfter.totalPss} native=${pssAfter.nativePss} dalvik=${pssAfter.dalvikPss}",
        )

        // Capacity model (§11): confirmed cadence — heartbeats every 30 s (2,880/day, one series,
        // not per package); ACCOUNTING rows at the 30 s usage-tick cadence while a package is
        // foreground (120/foreground-hour). Example: 10 packages × 2 h/day + heartbeats. Storage
        // uses the *retained* per-row bytes (post-checkpoint main-file delta); the WAL figure
        // above is transient write traffic, not storage.
        val perRowB = (inUseAfterWrites - inUseBefore) / perRowWrites
        val rowsPerDay = 2_880L + 10L * 2L * 120L
        Log.i(
            TAG,
            "bench capacity cadence=30s heartbeatRowsPerDay=2880 exampleRowsPerDay=$rowsPerDay " +
                "(10pkg×2h) retained90d=${rowsPerDay * 90} atMeasuredRetainedBytesPerRow=$perRowB " +
                "≈${rowsPerDay * 90 * perRowB / (1024 * 1024)}MB",
        )
    }

    /**
     * #13's audit instrument: one package-day as the journal and the platform each record it —
     * every ledger row in write order, the crediting day totals per package, the day's gap rows
     * (dead-window evidence), and `queryEvents` foreground totals over the same window. The
     * tracker-vs-Digital-Wellbeing comparison the undercount investigation reads; it goes
     * through the repository/DAO seams only, never the tracker projection.
     *
     * `--es day yyyy-MM-dd` defaults to today; `--es pkg <p>` adds the per-row listing, the
     * platform intervals behind the platform total, and the tracker row for today.
     */
    private suspend fun ledgerDayAudit(context: Context, pkg: String?, day: String?) {
        val ep = entryPoint(context)
        val store = ep.appRulesStoreFactory().open() ?: error("store unavailable")
        val dao = store.usageLedgerDao()
        val zone = ZoneId.systemDefault()
        val dayKey = day ?: LocalDate.now(zone).toString()
        val dayStart = runCatching {
            LocalDate.parse(dayKey).atStartOfDay(zone).toInstant().toEpochMilli()
        }.getOrElse {
            Log.e(TAG, "audit bad day=$day")
            return
        }
        val nowWall = System.currentTimeMillis()
        val dayEnd = minOf(nowWall, dayStart + 24 * 60 * 60 * 1000L)
        val time = DateTimeFormatter.ofPattern("HH:mm:ss")
        fun hhmmss(ms: Long) = time.format(Instant.ofEpochMilli(ms).atZone(zone))

        // Journal side. dayTotals is the §8.13 aggregation, so the per-package numbers here are
        // the same reduction the projection rebuild reads — not a second implementation.
        val journalTotals = dao.dayTotals(UsageLedgerKind.CREDITING)
            .filter { it.dayKey == dayKey }
            .associate { it.packageName to it.totalMs }
        if (pkg != null) {
            val rows = dao.rowsForPackageDay(pkg, dayKey)
            Log.i(TAG, "audit journal pkg=$pkg day=$dayKey sum=${journalTotals[pkg] ?: 0L}ms rows=${rows.size}")
            rows.forEach { row ->
                Log.i(
                    TAG,
                    "audit row id=${row.id} ${UsageLedgerKind.nameOf(row.kind)} src=${row.source} " +
                        "credited=${row.creditedMs}ms wall=${hhmmss(row.wallClockTsMs)} " +
                        "elapsed=${row.elapsedStartMs}..${row.elapsedEndMs} " +
                        "span=${row.elapsedEndMs - row.elapsedStartMs}ms boot=${row.bootCount} " +
                        "ev=${row.eventId}",
                )
            }
        }
        // Dead-window evidence for the day, in wall-clock order — a refused (capped) recovery
        // leaves exactly this row and no credit, which is the pre-fix signature.
        dao.rowsOfKindSince(UsageLedgerKind.GAP_DETECTED, dayStart)
            .filter { it.wallClockTsMs < dayEnd }
            .sortedBy { it.wallClockTsMs }
            .forEach { row ->
                Log.i(
                    TAG,
                    "audit gap wall=${hhmmss(row.wallClockTsMs)} " +
                        "elapsed=${row.elapsedStartMs}..${row.elapsedEndMs} " +
                        "span=${row.elapsedEndMs - row.elapsedStartMs}ms src=${row.source}",
                )
            }

        // Platform side: Digital Wellbeing's own record over [dayStart, dayEnd).
        val reader = PlatformUsageReader(context)
        val platform = reader.foregroundTotalsByPackage(dayStart, dayEnd)
        if (platform == null) {
            Log.w(TAG, "audit platform read failed (binder timeout / revoked usage access)")
        } else {
            (journalTotals.keys + platform.keys).distinct().sorted().forEach { p ->
                val journal = journalTotals[p] ?: 0L
                val plat = platform[p] ?: 0L
                if (journal > 0L || plat > 0L) {
                    Log.i(TAG, "audit pair pkg=$p journal=${journal}ms platform=${plat}ms diff=${plat - journal}ms")
                }
            }
        }
        if (pkg != null && platform != null) {
            val journal = journalTotals[pkg] ?: 0L
            val plat = platform[pkg] ?: 0L
            Log.i(TAG, "audit DELTA pkg=$pkg day=$dayKey journal=${journal}ms platform=${plat}ms diff=${plat - journal}ms")
            reader.foregroundIntervals(pkg, dayStart, dayEnd)?.let { intervals ->
                intervals.take(INTERVAL_LOG_CAP).forEach { iv ->
                    Log.i(
                        TAG,
                        "audit platformInterval ${hhmmss(iv.first)}-${hhmmss(iv.last + 1)} " +
                            "span=${iv.last + 1 - iv.first}ms",
                    )
                }
                if (intervals.size > INTERVAL_LOG_CAP) {
                    Log.i(TAG, "audit platformInterval ... ${intervals.size - INTERVAL_LOG_CAP} more")
                }
            }
        }
        if (pkg != null && day == null) readRow(context, pkg)
    }

    /**
     * #13's long-window leg: plant a liveness row [hours] in the past so the next §8.7 pass
     * measures a gap of exactly that size — the device-side instrument for the repair cap. The
     * row must land to move the baseline: a bucket already holding a real heartbeat derives the
     * same event id and dedupes, so the walk steps 1 min further back per retry (bounded) and
     * the outcome is logged — a Duplicate is visible, never silent. Chain it with the freeze in
     * one shell command so a 30 s pass cannot consume the planted gap first.
     *
     * The leg is not over when the gap is measured: it fabricated two rows (this HEARTBEAT and
     * the pass's GAP_DETECTED) that would otherwise inflate the outage count and heartbeat
     * census forever. The log line prints the plant's own purge command — a ±5 s window on the
     * wall that went INTO the draft, captured before the append. (Two earlier versions of this
     * recipe were wrong on device: recomputing the wall after the append let the cold-process
     * store open shift it outside a ±60 s window, which then swept two adjacent *real*
     * heartbeats instead — census-harmless, the pass baseline is the latest row, but the recipe
     * must delete the fabrication and nothing else.) The GAP row is purged the same way with
     * the wall the day-audit prints for it (`audit gap wall=…`), ±60 s.
     */
    private suspend fun ledgerAgeEvidence(context: Context, hours: Long) {
        val repo = entryPoint(context).usageLedgerRepository()
        val boot = systemBootCount(context)
        var back = minOf(hours * 3_600_000L, SystemClock.elapsedRealtime() - 60_000L)
            .coerceAtLeast(0L)
        var outcome: AppendOutcome? = null
        var attempt = 0
        var plantWall = 0L
        while (attempt < AGE_EVIDENCE_MAX_ATTEMPTS) {
            plantWall = System.currentTimeMillis() - back
            outcome = repo.appendStandalone(
                AccountingDiagnostics.heartbeatDraft(
                    source = UsageLedgerSource.PATH_A,
                    bootCount = boot,
                    wallClockMs = plantWall,
                    elapsedNowMs = SystemClock.elapsedRealtime() - back,
                ),
            )
            if (outcome !is AppendOutcome.Duplicate) break
            back += 60_000L
            attempt++
        }
        Log.i(
            TAG,
            "ledger age-evidence hours=$hours backMs=$back attempts=$attempt outcome=$outcome " +
                "boot=$boot plantWall=$plantWall elapsedNow=${SystemClock.elapsedRealtime()} " +
                "wallNow=${System.currentTimeMillis()} " +
                "cleanupPlant: -a ledger-purge-window --es kind HEARTBEAT " +
                "--es fromMs ${plantWall - 5_000L} --es toMs ${plantWall + 5_000L}",
        )
    }

    /**
     * #13's reversal seam: land a signed §8.12 CORRECTION against one event and rebuild the
     * projection from the journal. The device leg uses it to reverse a planted reconciliation
     * credit — the referenced row is never touched, and the rebuild re-derives the tracker
     * (and `blockedToday`) from `dayTotals(CREDITING)`, so the correction is visible in both.
     */
    private suspend fun ledgerCorrect(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra("eventId")
        val deltaMs = intent.getStringExtra("deltaMs")?.toLongOrNull()
        if (eventId == null || deltaMs == null) {
            Log.e(TAG, "ledger-correct needs --es eventId <full> --es deltaMs <n>")
            return
        }
        val repo = entryPoint(context).usageLedgerRepository()
        val outcome = repo.correct(
            targetEventId = eventId,
            deltaMs = deltaMs,
            nowWallClockMs = System.currentTimeMillis(),
            bootCount = systemBootCount(context),
            elapsedNowMs = SystemClock.elapsedRealtime(),
            source = UsageLedgerSource.MAINTENANCE,
        )
        val rewritten = repo.rebuildProjection()
        Log.i(
            TAG,
            "ledger-correct target=${eventId.take(8)} deltaMs=$deltaMs outcome=$outcome " +
                "projectionRewritten=$rewritten",
        )
    }

    /**
     * #62's cleanup seam: delete journal rows a debug leg fabricated, by kind and wall-clock
     * window [fromMs, toMs). The aged-baseline legs leave exactly two artifacts — the planted
     * HEARTBEAT (hours before the leg) and the GAP_DETECTED the forced silence produces — and
     * without this they sit in the outage count and heartbeat census forever. Debug-only on
     * purpose: the production DAO's append-only contract carries no delete, and the retention
     * sweep stays the only deleter of real rows. Crediting kinds are refused — removing an
     * ACCOUNTING/RECONCILIATION row after its in-transaction projection update would desync the
     * tracker silently (that flow has `ledger-correct` instead). The deleted count is a
     * same-transaction `count(*)` under the identical predicate taken before the DELETE —
     * `changes()` after `usePrepared` reported 0 on the first real purge here (Room 3 pooled
     * connection), which is a lying instrument; a matched count can't be.
     */
    private suspend fun ledgerPurgeWindow(context: Context, intent: Intent) {
        val kindName = intent.getStringExtra("kind")?.uppercase(Locale.US)
        val kind = (UsageLedgerKind.ACCOUNTING..UsageLedgerKind.CORRECTION)
            .firstOrNull { UsageLedgerKind.nameOf(it) == kindName }
        val fromMs = intent.getStringExtra("fromMs")?.toLongOrNull()
        val toMs = intent.getStringExtra("toMs")?.toLongOrNull()
        if (kind == null || fromMs == null || toMs == null) {
            Log.e(TAG, "ledger-purge-window needs --es kind <KIND> --es fromMs <wall> --es toMs <wall>")
            return
        }
        if (kind in UsageLedgerKind.CREDITING) {
            Log.e(
                TAG,
                "ledger-purge-window refuses crediting kind ${UsageLedgerKind.nameOf(kind)} " +
                    "(the projection would desync — reverse with ledger-correct instead)",
            )
            return
        }
        val store = entryPoint(context).appRulesStoreFactory().open() ?: error("store unavailable")
        val deleted = store.useWriterConnection { tx ->
            val matched = tx.usePrepared(
                "SELECT count(*) FROM usage_ledger WHERE kind = ? AND wall_clock_ts_ms >= ? AND wall_clock_ts_ms < ?",
            ) { stmt ->
                stmt.bindLong(1, kind.toLong())
                stmt.bindLong(2, fromMs)
                stmt.bindLong(3, toMs)
                if (stmt.step()) stmt.getLong(0) else -1L
            }
            tx.usePrepared(
                "DELETE FROM usage_ledger WHERE kind = ? AND wall_clock_ts_ms >= ? AND wall_clock_ts_ms < ?",
            ) { stmt ->
                stmt.bindLong(1, kind.toLong())
                stmt.bindLong(2, fromMs)
                stmt.bindLong(3, toMs)
                stmt.step()
            }
            matched
        }
        Log.i(
            TAG,
            "ledger purge kind=${UsageLedgerKind.nameOf(kind)} from=$fromMs to=$toMs deleted=$deleted",
        )
    }

    /**
     * Emulator repro seam: `am force-stop` (and the Task-Manager Stop it simulates) sets
     * `requiresExplicitReEnable`, which gates the FGS tick body — poll, oracle stamps, and
     * session feed all go silent until the user taps the banner. Clearing it here lets an
     * emulator leg flip the state without the manual tap; the FGS's own 30 s refresh
     * re-derives the visible degradation afterward.
     */
    private fun protectionReenable(context: Context) {
        val store = entryPoint(context).protectionStatus()
        store.reenable()
        Log.i(
            TAG,
            "protection reenable flag=${store.requiresExplicitReEnable} degradation=${store.degradation}",
        )
    }

    /**
     * #52's device evidence: the store open here runs the real v11→v12 migration first (the
     * factory's onOpen path), so `user_version` reads the newest version only if the migration
     * chain executed on this device's SQLCipher database; a failed migration throws and the
     * receiver's catch logs it instead — both outcomes are visible. The per-pair group-by is the
     * fan-out census: after the v12 migration both link tables are empty by design (no backfill;
     * pre-v12 deliveries stay excluded via `event_sequence`, whose census prints alongside), and
     * after v13 the activity journal/link/projection tables are new and likewise unbackfilled.
     */
    private suspend fun mirrorRead(context: Context) {
        val store = entryPoint(context).appRulesStoreFactory().open() ?: error("store unavailable")
        store.useWriterConnection { tx ->
            suspend fun long(sql: String): Long =
                tx.usePrepared(sql) { if (it.step()) it.getLong(0) else -1L }
            Log.i(TAG, "mirror schema user_version=${long("PRAGMA user_version")} (expect 13 — ADR #111 v12, ADR #115 v13)")
            listOf(
                "attempt_mirror_link",
                "recovery_mirror_link",
                "partner_activity_link",
            ).forEach { table ->
                Log.i(TAG, "mirror table $table rows=${long("SELECT count(*) FROM $table")}")
                tx.usePrepared("SELECT pair_id, count(*) FROM $table GROUP BY pair_id ORDER BY pair_id") { st ->
                    while (st.step()) Log.i(TAG, "mirror links $table pair=${st.getText(0)} n=${st.getLong(1)}")
                }
            }
            // ADR #115 census: the journal is written unconditionally by the domain transactions and
            // only drained for enrolled pairs, so a non-zero journal with zero links is the expected
            // shape on a device whose pairs are all gone — it is not an error.
            Log.i(
                TAG,
                "activity journal rows=${long("SELECT count(*) FROM partner_activity_outbox")} " +
                    "unlinked=${long("SELECT count(*) FROM partner_activity_outbox o LEFT JOIN partner_activity_link l ON o.event_id = l.event_id WHERE l.event_id IS NULL")} " +
                    "projections=${long("SELECT count(*) FROM partner_activity_projection")} " +
                    "pinned=${long("SELECT count(*) FROM partner_activity_projection WHERE pinned_message_text IS NOT NULL")}",
            )
            Log.i(
                TAG,
                "mirror sources attempts=${long("SELECT count(*) FROM attempt_log")} " +
                    "attemptsLegacyLinked=${long("SELECT count(*) FROM attempt_log WHERE event_sequence IS NOT NULL")} " +
                    "sessions=${long("SELECT count(*) FROM recovery_session")} " +
                    "sessionsLegacyLinked=${long("SELECT count(*) FROM recovery_session WHERE event_sequence IS NOT NULL")}",
            )
            // A zero census here means every rule/attempt/streak row is gone too — the census is
            // what separates "the owner has no attempts yet" from "this database is not the one
            // the app has been writing to" (or was reset).
            Log.i(
                TAG,
                "mirror store rules=${long("SELECT count(*) FROM restriction_rules")} " +
                    "streaks=${long("SELECT count(*) FROM habit_progress")} " +
                    "milestones=${long("SELECT count(*) FROM milestone_events")} " +
                    "coaching=${long("SELECT count(*) FROM coaching_step")} " +
                    "ledger=${long("SELECT count(*) FROM usage_ledger")}",
            )
        }
    }

    private suspend fun benchRun(n: Int, block: suspend (Int) -> Unit): LongArray {
        val out = LongArray(n)
        for (i in 0 until n) {
            val t0 = System.nanoTime()
            block(i)
            out[i] = System.nanoTime() - t0
        }
        return out
    }

    private fun benchLog(name: String, samples: LongArray) {
        val sorted = samples.sortedArray()
        fun pct(p: Int) = sorted[(sorted.size * p / 100).coerceAtMost(sorted.size - 1)] / 1e6
        Log.i(
            TAG,
            "bench $name n=${sorted.size} p50=${"%.2f".format(Locale.US, pct(50))}ms " +
                "p90=${"%.2f".format(Locale.US, pct(90))}ms max=${"%.2f".format(Locale.US, pct(100))}ms",
        )
    }

    private companion object {
        const val TAG = "MyExodusVerification"
        const val BENCH_PKG = "com.myexodus.bench"
        const val ACTION_USAGE_READ = "usage-read"
        const val ACTION_LEDGER_READ = "ledger-read"
        const val ACTION_LEDGER_SEED_OLD = "ledger-seed-old"
        const val ACTION_LEDGER_RUN_MAINTENANCE = "ledger-run-maintenance"
        const val ACTION_LEDGER_SIMULATE_JUMP = "ledger-simulate-jump"
        const val ACTION_LEDGER_RESET = "ledger-reset"
        const val ACTION_LEDGER_OUTAGE = "ledger-outage"
        const val ACTION_LEDGER_BENCH = "ledger-bench"
        const val ACTION_LEDGER_DAY_AUDIT = "ledger-day-audit"
        const val ACTION_LEDGER_AGE_EVIDENCE = "ledger-age-evidence"
        const val ACTION_LEDGER_CORRECT = "ledger-correct"
        const val ACTION_LEDGER_PURGE_WINDOW = "ledger-purge-window"
        const val ACTION_PROTECTION_REENABLE = "protection-reenable"
        const val ACTION_MIRROR_READ = "mirror-read"
        const val ACTION_PUSH_READ = "push-read"
        const val SEED_SOURCE = "device-verification-seed"
        const val INTERVAL_LOG_CAP = 120
        const val AGE_EVIDENCE_MAX_ATTEMPTS = 10
        val PROFILE = UserProfile(
            userId = "device-test-profile", name = "Device Test", email = "devicetest@example.com",
            phoneNumber = null, dateOfBirth = 0L, hasAccessibilityPermission = false,
            hasUsageStatsPermission = false, hasVpnPermission = false, hasNotificationPermission = false,
            hasDeviceAdminPermission = false, devicePublicKey = null, recoveryEscrowKey = null, isActive = true,
        )
    }
}
