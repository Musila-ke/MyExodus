package com.example.myexodus.domainusage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.myexodus.blocking.RulesRepository
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.ui.rules.SiteLimitCheck
import com.example.myexodus.ui.rules.clampStoredLimitMs
import com.example.myexodus.ui.rules.siteLimitPatternsOf
import com.example.myexodus.ui.rules.siteLimitTarget
import com.example.myexodus.vpn.DnsSinkholeVpnService
import com.example.myexodus.vpn.VpnPacket
import dagger.hilt.android.EntryPointAccessors
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §12.2 harness seam (debug only, exported — the host shell cannot reach a non-exported
 * receiver; same rationale as [com.example.myexodus.blocking.DeviceVerificationReceiver]).
 * A receiver, not an activity: it never takes the foreground, so reading state cannot disturb
 * the accrual it is measuring (device-learned 2026-09-15, see the sibling receiver).
 *
 * INV-7/S-5 hold here too: every line logs rule ids, revisions and counters — never a
 * hostname, IP or payload. Failures log the exception's class name only, since a `Throwable`
 * message could carry a host (a `UnknownHostException`, for one).
 *
 * Commands (all `-n com.example.myexodus/.domainusage.DomainUsageTestReceiver`):
 *   -a du-mode        --es mode OFF|ACCOUNT|ENFORCE|clear
 *   -a du-addrule     --es pattern <p> --el limitSec <n> [--el expiresInSec <n>]
 *   -a du-setlimit    --es id <ruleId> --el limitSec <n>
 *   -a du-editpattern --es id <ruleId> --es pattern <p>
 *   -a du-delrule     --es id <ruleId>
 *   -a du-delall
 *   -a du-rules | du-usage | du-rows [--es day <key>] | du-counters | du-status
 *   -a du-apphttp     --es host <h> --ei port <p> [--es path </p>]
 *   -a du-bench       --ei n <packets>
 *   -a du-flush       --ei n <rows> [--ei iters <n>] [--ez fresh <b>]
 *   -a du-dispatch    --ei n <calls>   (PF-7 control: round-trips with no writes)
 *   -a du-revoke
 */
class DomainUsageTestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(context, action, intent)
            } catch (t: Throwable) {
                Log.e(TAG, "DUPROBE $action failed err=${t.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, action: String, intent: Intent) {
        val ep = EntryPointAccessors.fromApplication(
            context.applicationContext, DomainUsageTestEntryPoint::class.java,
        )
        when (action) {
            ACTION_MODE -> {
                val raw = intent.getStringExtra("mode")
                val mode =
                    if (raw == null || raw.equals("clear", ignoreCase = true)) null
                    else EngineMode.parseOrNull(raw)
                DomainUsageSettings.setModeOverride(context, mode)
                Log.i(TAG, "DUPROBE mode override=${mode?.name ?: "cleared"} effective=${ep.engine().mode}")
            }
            ACTION_ADD_RULE ->
                addRule(ep.rulesRepository(), ep.publicSuffixList(), intent, engineNowMs(ep))
            ACTION_SET_LIMIT -> editRule(ep.rulesRepository(), intent, psl = null, pattern = null)
            ACTION_EDIT_PATTERN ->
                editRule(ep.rulesRepository(), intent, ep.publicSuffixList(), intent.getStringExtra("pattern"))
            ACTION_DEL_RULE -> deleteRule(ep.rulesRepository(), intent)
            ACTION_DEL_ALL -> deleteAll(ep.rulesRepository())
            ACTION_RULES -> listRules(ep.domainRuleProvider().snapshot())
            ACTION_USAGE -> listUsage(ep.engine(), ep.domainRuleProvider().snapshot())
            ACTION_ROWS -> listRows(ep, intent)
            ACTION_COUNTERS -> Log.i(TAG, "DUPROBE ${ep.engine().counterLogLine()}")
            ACTION_STATUS -> {
                val engine = ep.engine()
                // `vpn` is the tunnel's own lifecycle, distinct from the engine status: an empty
                // rule set reports a legitimate Dormant (FR-12), so the harness cannot read
                // "Dormant" as "the tunnel is down" (device-caught 2026-09-21, matrix round 3).
                Log.i(
                    TAG,
                    "DUPROBE status=${engine.status.value} mode=${engine.mode} " +
                        "vpn=${DnsSinkholeVpnService.state} day=${engine.effectiveDayKey}",
                )
            }
            ACTION_APP_HTTP -> appHttp(intent)
            ACTION_BENCH -> benchPackets(intent)
            ACTION_FLUSH -> flushBench(ep, intent)
            ACTION_DISPATCH -> dispatchBench(ep, intent)
            ACTION_REVOKE -> revoke(context, ep.engine())
            else -> Log.w(TAG, "DUPROBE unknown action=$action")
        }
    }

    /** §8 S9.1/S2.8 chain: a rule added here is normalized exactly as the editor would. */
    private suspend fun addRule(
        repo: RulesRepository,
        psl: PublicSuffixList,
        intent: Intent,
        nowMillis: Long,
    ) {
        val pattern = intent.getStringExtra("pattern") ?: return run { Log.e(TAG, "DUPROBE addrule missing pattern") }
        val limitSec = intent.getLongExtra("limitSec", 0L)
        // The editor's own picker is day-granular (end of the selected day); expiry-driven UI
        // transitions are otherwise only reachable at a real midnight. The extra exists so the
        // S9 UI legs can put an expiry seconds away — same seam class as du-setlimit.
        val expiresInSec = intent.getLongExtra("expiresInSec", 0L)
        val rules = repo.getActiveRules()
        val check = siteLimitTarget(pattern, psl, siteLimitPatternsOf(rules, psl, null))
        when (check) {
            is SiteLimitCheck.Ok -> {
                val rule = baseRule(repo, check.host).copy(
                    dailyLimitMs = clampStoredLimitMs(limitSec * 1000L),
                    blockExpiresAt = if (expiresInSec > 0L) nowMillis + expiresInSec * 1000L else 0L,
                )
                repo.insertRule(rule)
                Log.i(
                    TAG,
                    "DUPROBE addrule result=Ok id=${rule.ruleId} limitMs=${rule.dailyLimitMs} " +
                        "expiresAt=${rule.blockExpiresAt}",
                )
            }
            is SiteLimitCheck.HasPath -> Log.i(TAG, "DUPROBE addrule result=HasPath")
            SiteLimitCheck.Invalid -> Log.i(TAG, "DUPROBE addrule result=Invalid")
            SiteLimitCheck.IpLiteral -> Log.i(TAG, "DUPROBE addrule result=IpLiteral")
            SiteLimitCheck.TooBroad -> Log.i(TAG, "DUPROBE addrule result=TooBroad")
            SiteLimitCheck.NeverBlock -> Log.i(TAG, "DUPROBE addrule result=NeverBlock")
            SiteLimitCheck.EncryptedDnsResolver -> Log.i(TAG, "DUPROBE addrule result=EncryptedDnsResolver")
            SiteLimitCheck.DuplicatePattern -> Log.i(TAG, "DUPROBE addrule result=DuplicatePattern")
        }
    }

    /** `pattern == null` keeps the stored one (limit-only edit); otherwise it is re-normalized. */
    private suspend fun editRule(
        repo: RulesRepository,
        intent: Intent,
        psl: PublicSuffixList?,
        pattern: String?,
    ) {
        val id = intent.getStringExtra("id") ?: return run { Log.e(TAG, "DUPROBE edit missing id") }
        val rule = repo.getActiveRules().firstOrNull { it.ruleId == id }
            ?: return run { Log.i(TAG, "DUPROBE edit id=$id missing") }
        var updated = rule
        if (intent.hasExtra("limitSec")) {
            updated = updated.copy(dailyLimitMs = clampStoredLimitMs(intent.getLongExtra("limitSec", 0L) * 1000L))
        }
        if (pattern != null && psl != null) {
            val others = siteLimitPatternsOf(repo.getActiveRules(), psl, excludeRuleId = id)
            when (val check = siteLimitTarget(pattern, psl, others)) {
                is SiteLimitCheck.Ok -> updated = updated.copy(blockedPackage = check.host)
                is SiteLimitCheck.HasPath -> return run { Log.i(TAG, "DUPROBE edit result=HasPath") }
                else -> {
                    val label = when (check) {
                        SiteLimitCheck.Invalid -> "Invalid"
                        SiteLimitCheck.IpLiteral -> "IpLiteral"
                        SiteLimitCheck.TooBroad -> "TooBroad"
                        SiteLimitCheck.NeverBlock -> "NeverBlock"
                        SiteLimitCheck.EncryptedDnsResolver -> "EncryptedDnsResolver"
                        SiteLimitCheck.DuplicatePattern -> "DuplicatePattern"
                        is SiteLimitCheck.Ok, is SiteLimitCheck.HasPath -> "unreachable"
                    }
                    return run { Log.i(TAG, "DUPROBE edit result=$label") }
                }
            }
        }
        // I-22: a pattern change must reach the provider as a pattern change, so the mutation
        // below is the only path that may allocate a new revision.
        repo.updateRule(updated)
        Log.i(TAG, "DUPROBE edit result=Ok id=$id limitMs=${updated.dailyLimitMs}")
    }

    private suspend fun deleteRule(repo: RulesRepository, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return run { Log.e(TAG, "DUPROBE delrule missing id") }
        val rule = repo.getActiveRules().firstOrNull { it.ruleId == id }
            ?: return run { Log.i(TAG, "DUPROBE delrule id=$id missing") }
        repo.deleteRule(rule)
        Log.i(TAG, "DUPROBE delrule id=$id ok")
    }

    /** Removes only the metered kind (WEBSITE + daily limit) — app rules are not this harness's. */
    private suspend fun deleteAll(repo: RulesRepository) {
        val metered = repo.getActiveRules().filter { it.targetType == "WEBSITE" && it.dailyLimitMs > 0L }
        metered.forEach { repo.deleteRule(it) }
        Log.i(TAG, "DUPROBE delall count=${metered.size}")
    }

    private fun listRules(rules: List<DomainRule>) {
        rules.forEach { rule ->
            Log.i(
                TAG,
                "DUPROBE rule id=${rule.id} revision=${rule.revision} limitMs=${rule.dailyLimitMs} enforcedToday=${rule.enforcedToday}",
            )
        }
    }

    private fun listUsage(engine: DomainUsageEngine, rules: List<DomainRule>) {
        rules.forEach { rule ->
            Log.i(TAG, "DUPROBE usage id=${rule.id} revision=${rule.revision} ms=${engine.usageTodayMs(rule.id)}")
        }
    }

    private suspend fun listRows(ep: DomainUsageTestEntryPoint, intent: Intent) {
        val day = intent.getStringExtra("day") ?: todayKey(ep)
        val dao = ep.appRulesStoreFactory().open()?.ruleUsageDao()
            ?: return run { Log.i(TAG, "DUPROBE rows store-unavailable") }
        dao.loadDay(day).forEach { row ->
            Log.i(TAG, "DUPROBE row id=${row.ruleId} revision=${row.revision} day=${row.dayKey} ms=${row.activeMs}")
        }
    }

    /** I-17: the app's own request — INV-9 excludes this uid from its TUN, so nothing may meter. */
    private suspend fun appHttp(intent: Intent) {
        val host = intent.getStringExtra("host") ?: return run { Log.e(TAG, "DUPROBE apphttp missing host") }
        val port = intent.getIntExtra("port", 80)
        val path = intent.getStringExtra("path") ?: "/"
        val result = runCatching {
            val conn = URL("http://$host:$port$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val code = conn.responseCode
            val bytes = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText().length }
            conn.disconnect()
            "code=$code chars=$bytes"
        }.getOrElse { "err=${it.javaClass.simpleName}" }
        Log.i(TAG, "DUPROBE apphttp done $result")
    }

    /** PF-8: RST + ICMP construction, checksum included, over [n] packets each. */
    private suspend fun benchPackets(intent: Intent) {
        val n = intent.getIntExtra("n", 100)
        val src = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 1)
        val dst = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 0, 0, 2)
        // The quoted datagram is the real thing: a built UDP packet, parsed back — the same
        // shape the UDP relay hands to buildIcmpPortUnreachable (§6.10).
        val datagram = VpnPacket.buildUdpPacket(dst, src, 40000, 443, ByteArray(32))
        val ip = VpnPacket.parseIp(datagram)!!
        repeat(WARMUP) {
            VpnPacket.buildTcpSegment(src, dst, 443, 40000, 1, 2, rst = true, ackFlag = true)
            VpnPacket.buildIcmpPortUnreachable(ip, datagram)
        }
        val rst = LongArray(n) {
            val t0 = System.nanoTime()
            VpnPacket.buildTcpSegment(src, dst, 443, 40000, 1, 2, rst = true, ackFlag = true)
            System.nanoTime() - t0
        }
        val icmp = LongArray(n) {
            val t0 = System.nanoTime()
            VpnPacket.buildIcmpPortUnreachable(ip, datagram)
            System.nanoTime() - t0
        }
        Log.i(TAG, "DUPROBE bench rst ${percentiles(rst)} icmp ${percentiles(icmp)} n=$n")
    }

    /** PF-7: `upsertMax` of [n] rows per iteration, on a single db thread like the engine's. */
    private suspend fun flushBench(ep: DomainUsageTestEntryPoint, intent: Intent) {
        val n = intent.getIntExtra("n", 200)
        val iters = intent.getIntExtra("iters", 20)
        // `fresh` splits the per-row cost into its insert half and its update half: every
        // iteration gets its own day key, so nothing conflicts (the insert always stores a row)
        // and the update always matches zero rows. Expired day keys, so the retention sweep on
        // the next engine start deletes the bench's own rows.
        val fresh = intent.getBooleanExtra("fresh", false)
        val db = ep.appRulesStoreFactory().open() ?: return run { Log.i(TAG, "DUPROBE flush store-unavailable") }
        val dao = db.ruleUsageDao()
        val day = todayKey(ep)
        val rows = List(n) { i -> UsageSnapshot("bench-$i", 0L, day, iters * 1000L + i, 1L) }
        val batches = if (fresh) {
            List(iters) { it ->
                List(n) { i -> UsageSnapshot("bench-$i", 0L, "2000-01-01-bench$it", iters * 1000L + i, 1L) }
            }
        } else {
            List(iters) { rows }
        }
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "domainusage-flushbench") }
        withContext(executor.asCoroutineDispatcher()) {
            dao.upsertMax(rows) // warm-up: first write pays the SQLCipher page cost
            val times = LongArray(iters) { it ->
                val t0 = System.nanoTime()
                dao.upsertMax(batches[it])
                System.nanoTime() - t0
            }
            Log.i(TAG, "DUPROBE flush ${percentiles(times)} rows=$n iters=$iters fresh=$fresh")
        }
        executor.shutdown()
    }

    /**
     * PF-7's control: the same suspend DAO round-trip machinery with no writes at all, so the
     * per-call dispatch cost can be told apart from SQLCipher's page cost. A day key with no rows
     * makes `loadDay` do the full statement round-trip and return an empty list — if this alone
     * costs a large fraction of the flush, the flush's floor is set by dispatch; if it is cheap,
     * the floor is the encryption of written pages and no statement shape can reach the gate.
     */
    private suspend fun dispatchBench(ep: DomainUsageTestEntryPoint, intent: Intent) {
        val n = intent.getIntExtra("n", 400)
        val dao = ep.appRulesStoreFactory().open()?.ruleUsageDao()
            ?: return run { Log.i(TAG, "DUPROBE dispatch store-unavailable") }
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "domainusage-dispatchbench") }
        withContext(executor.asCoroutineDispatcher()) {
            dao.loadDay("1900-01-01") // warm-up: first call pays the statement prepare + page read
            val times = LongArray(n) { i ->
                val t0 = System.nanoTime()
                dao.loadDay("1900-01-0${i % 10}")
                System.nanoTime() - t0
            }
            Log.i(TAG, "DUPROBE dispatch ${percentiles(times)} calls=$n")
        }
        executor.shutdown()
    }

    /**
     * The engine's own wall clock — the S1.4 check forbids wall-clock calls outside
     * `SystemWallClock.kt`, and reading it through the engine also keeps the harness's
     * "today" identical to the day key the ledger is actually accruing under (matters on
     * I-9/I-10, where the device clock is moved).
     */
    /**
     * The day a row belongs to is the ledger's effective key (§4.8), not the wall clock's: after a
     * clock jump the two disagree by design, and the wall clock would query a day the product
     * never opened. The wall clock still answers while the engine is down (nothing is metering).
     */
    private fun todayKey(ep: DomainUsageTestEntryPoint): String =
        ep.engine().effectiveDayKey.ifEmpty {
            ep.engine().wallClock.now(ZoneId.systemDefault()).toLocalDate().toString()
        }

    /** Epoch millis from the engine's own wall clock — S1.4 keeps raw clock calls out of this package. */
    private fun engineNowMs(ep: DomainUsageTestEntryPoint): Long =
        ZoneId.systemDefault().let { zone ->
            ep.engine().wallClock.now(zone).atZone(zone).toInstant().toEpochMilli()
        }

    private fun revoke(context: Context, engine: DomainUsageEngine) {
        engine.onVpnRevoked()
        context.startService(
            Intent(context, DnsSinkholeVpnService::class.java).setAction(DnsSinkholeVpnService.ACTION_STOP),
        )
        Log.i(TAG, "DUPROBE revoke issued")
    }

    private fun percentiles(samplesNs: LongArray): String {
        val sorted = samplesNs.sorted()
        fun at(p: Double) = sorted[((sorted.size - 1) * p).toInt()] / 1_000_000.0
        return "p50=%.3fms p99=%.3fms max=%.3fms".format(at(0.5), at(0.99), at(1.0))
    }

    private suspend fun baseRule(repo: RulesRepository, host: String) = RestrictionRule(
        ruleId = UUID.randomUUID().toString(),
        profileId = repo.ensureActiveProfile().userId,
        blockedPackage = host,
        targetType = "WEBSITE",
        isAlwaysOn = false,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        timezone = ZoneId.systemDefault().id,
        blockExpiresAt = 0L,
        severityLevel = "HIGH",
    )

    private companion object {
        const val TAG = "MyExodusVpn"
        const val WARMUP = 200

        const val ACTION_MODE = "du-mode"
        const val ACTION_ADD_RULE = "du-addrule"
        const val ACTION_SET_LIMIT = "du-setlimit"
        const val ACTION_EDIT_PATTERN = "du-editpattern"
        const val ACTION_DEL_RULE = "du-delrule"
        const val ACTION_DEL_ALL = "du-delall"
        const val ACTION_RULES = "du-rules"
        const val ACTION_USAGE = "du-usage"
        const val ACTION_ROWS = "du-rows"
        const val ACTION_COUNTERS = "du-counters"
        const val ACTION_STATUS = "du-status"
        const val ACTION_APP_HTTP = "du-apphttp"
        const val ACTION_BENCH = "du-bench"
        const val ACTION_FLUSH = "du-flush"
        const val ACTION_DISPATCH = "du-dispatch"
        const val ACTION_REVOKE = "du-revoke"
    }
}
