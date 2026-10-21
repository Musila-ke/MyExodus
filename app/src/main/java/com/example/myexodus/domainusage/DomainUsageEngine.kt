package com.example.myexodus.domainusage

import android.util.Log
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MyExodusVpn"

/**
 * The Engine facade (S1: mode, status, counters, circuit breaker; S6: the hook surface and the
 * collaborators the taps read; S7: lifecycle, startup load, status). Everything here is called
 * from the Baseline's relay/packet threads, so every hook is exception-safe (INV-6) and
 * allocation-free on the per-chunk path (INV-12).
 */
class DomainUsageEngine(
    mode: EngineMode,
    internal val clock: MonotonicClock,
    @PublishedApi internal val counters: EngineCounters = EngineCounters(),
    val wallClock: WallClock = SystemWallClock(),
    private val debugBuild: Boolean = false,
) {

    /**
     * §4.5/S1.3: resolved from `BuildConfig.DOMAIN_USAGE_MODE` at construction; `start()` (S7) may
     * lower it to the debug DataStore override — the value cannot be read synchronously at DI time.
     */
    @Volatile
    var mode: EngineMode = mode
        internal set

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Dormant)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    /**
     * Set when the breaker trips; the Engine stays dormant until service restart (S1.5) — the
     * flag is deliberately not cleared anywhere, so a new Engine instance is the only reset.
     */
    @PublishedApi
    @Volatile
    internal var dormantUntilRestart = false
        private set

    /**
     * NFR-2/INV-6: ordinary exceptions never escape a hook — they are counted and the fallback is
     * returned. A `VirtualMachineError` is counted and rethrown instead (D-35); continuing after
     * one is unsafe, so "exception-safe" means *ordinary* exceptions, not all `Throwable`s.
     * Inline so hook bodies on the relay path add no lambda allocation (INV-12).
     */
    inline fun <T> hook(fallback: T, body: () -> T): T {
        if (dormantUntilRestart) return fallback
        return try {
            body()
        } catch (e: VirtualMachineError) {
            counters.inc(Counter.fatal_vm_error)
            throw e
        } catch (e: Exception) {
            onHookException()
            fallback
        }
    }

    @PublishedApi
    internal fun onHookException() {
        counters.inc(Counter.hook_exceptions)
        if (dormantUntilRestart) return
        var tripped = false
        synchronized(breakerLock) {
            if (dormantUntilRestart) return@synchronized
            val now = clock.elapsedMs()
            exceptionTimes[exceptionWriteIndex] = now
            exceptionWriteIndex = (exceptionWriteIndex + 1) % MeteringConfig.BREAKER_MAX_EXCEPTIONS
            if (exceptionCount < MeteringConfig.BREAKER_MAX_EXCEPTIONS) exceptionCount++
            // Once the ring is full, the write index holds the oldest sample after the write.
            if (exceptionCount == MeteringConfig.BREAKER_MAX_EXCEPTIONS &&
                now - exceptionTimes[exceptionWriteIndex] <= MeteringConfig.BREAKER_WINDOW_MS
            ) {
                tripped = true
            }
        }
        if (tripped) {
            dormantUntilRestart = true
            counters.inc(Counter.breaker_tripped)
            refreshStatus()
        }
    }

    // ---- S6: attribution, taps and the DNS decision (plan §8 S6) ----

    /** §4.5: the immutable matcher, swapped atomically on every rule snapshot. */
    @Volatile
    internal var ruleIndex: RuleIndex = RuleIndex.build(emptyList(), counters)
        private set

    /** Prebuilt so §6.8's "no ledger yet ⇒ nothing shapes" costs no allocation per query. */
    private val noRuleIndex: RuleIndex = RuleIndex.build(emptyList(), counters)

    /**
     * §4.9/S7: attached by `start()` once the persisted day's totals have loaded. Until then the
     * Engine has no accounting identities, so every flow is allowed and nothing is metered
     * (fail-open) — a rule can never be enforced against a total that was never loaded.
     */
    @Volatile
    internal var ledger: UsageLedger? = null

    /**
     * §12.2 I-9/I-10 harness read (§4.8): the effective day key. A clock jump is exactly the case
     * where the ledger and the wall clock disagree — the key is monotonic within a run — so the
     * harness must read it here, never derive it from the device date.
     */
    internal val effectiveDayKey: String get() = ledger?.dayKey.orEmpty()

    internal val observations = DnsObservationStore(clock)
    internal val registry = FlowRegistry(counters)
    internal val pool = InspectionBufferPool(counters = counters)

    private val shaper = DnsShaper(counters, observations)
    internal val resolver = AttributionResolver(observations, counters)

    /** §6.1's per-rule exhausted predicate — one instance, so the DNS path allocates nothing. */
    private val exhausted: (DomainRule) -> Boolean = { counterFor(it)?.exhausted == true }

    /** The accounting identity bound to [rule]'s current revision, or null before `start()`. */
    internal fun counterFor(rule: DomainRule): RuleCounter? = ledger?.counterFor(rule.id, rule.revision)

    /**
     * S9.3: the UI's "Used today" figure for a rule's current revision. Null until the startup
     * load attaches the ledger — the row hides the line rather than claiming a false zero.
     * Reads are tick-thread writes of a volatile total, so any thread may call this.
     */
    fun usageTodayMs(ruleId: String): Long? = ledger?.usageTodayMs(ruleId)

    /** §14 debug surface: exact per-rule totals (see [UsageLedger.debugTotals]). */
    fun debugTotals(): List<Pair<String, Long>> = ledger?.debugTotals().orEmpty()

    /** §4.5: adopt a rule snapshot (S7's publisher collector calls this, on the tick thread). */
    internal fun updateRules(rules: List<DomainRule>) {
        ruleIndex = RuleIndex.build(rules, counters)
        hasRules = rules.isNotEmpty()
        ledger?.applyRules(rules)
        refreshStatus()
    }

    /** §6.1: parse, then shape only in `ENFORCE` and only for a name that matches a rule. */
    fun onDnsQuery(query: ByteArray, len: Int): DnsQueryResult {
        if (mode == EngineMode.OFF) return DnsQueryResult.Forward
        return hook<DnsQueryResult>(DnsQueryResult.Forward) {
            val bytes = if (len == query.size) query else query.copyOf(len)
            // §6.8: until the startup load succeeds there is no accounting identity, so every
            // hook stays pass-through — including DNS shaping, which would otherwise suppress
            // AAAA/SVCB for a matching rule before its totals are known.
            val index = if (ledger == null) noRuleIndex else ruleIndex
            when (val decision = shaper.decide(bytes, mode, index, wallClock.nowMillis(), exhausted)) {
                is DnsShaper.Decision.Respond -> DnsQueryResult.Respond(decision.bytes, decision.bytes.size)
                DnsShaper.Decision.Forward -> DnsQueryResult.Forward
            }
        }
    }

    /** §6.2: the alias-chain walk that feeds [DnsObservationStore]; the bytes are never modified. */
    fun onDnsResponse(response: ByteArray, len: Int) {
        if (mode == EngineMode.OFF) return
        hook(Unit) {
            shaper.onResponse(if (len == response.size) response else response.copyOf(len))
        }
    }

    /**
     * §6.3/§6.4: the per-flow admission. Port 443 gets the inspecting tap (when a buffer is free);
     * everything else is DNS-fallback-attributed right here. ADR #189: only flows owned by a
     * browser are admitted to metering — another app's traffic (the site's own app, background
     * sync) and an unresolved owner pass through untouched, so a website rule neither counts nor
     * resets them.
     */
    fun tcpFlowOpened(dstIp: ByteArray, dstPort: Int, control: FlowControl, owner: FlowOwner): FlowTap {
        if (mode == EngineMode.OFF) return PassThroughTap
        return hook<FlowTap>(PassThroughTap) {
            counters.inc(Counter.flows_tcp_total)
            countOwner(owner)
            if (owner != FlowOwner.BROWSER) return@hook PassThroughTap
            if (dstPort != 443) return@hook attributeAtOpen(dstIp, control)
            counters.inc(Counter.tls443_flows)
            // §6.3 step 2: no buffer free ⇒ pass through immediately, attributed by DNS.
            val buffer = pool.acquire() ?: return@hook attributeAtOpen(dstIp, control)
            GuardedTap(this, InspectingTcpTap(this, control, dstIp, buffer))
        }
    }

    /** §6.4: the UDP 5-tuple's admission, decided once at open. Browser-gated like TCP. */
    fun udpFlowOpened(dstIp: ByteArray, dstPort: Int, control: FlowControl, owner: FlowOwner): UdpAdmission {
        if (mode == EngineMode.OFF) return allowPassThrough
        return hook<UdpAdmission>(allowPassThrough) {
            countOwner(owner)
            if (owner != FlowOwner.BROWSER) return@hook allowPassThrough
            val rule = (resolver.byDns(dstIp, ruleIndex, wallClock.nowMillis()) as? AttributionResolver.Dns.Hit)?.rule
            val counter = rule?.let { counterFor(it) } ?: return@hook allowPassThrough
            if (mode == EngineMode.ENFORCE && counter.exhausted) {
                // §6.4/§6.10: dropped, not reset — the ICMP Port-Unreachable nudge is S8's.
                counters.inc(Counter.udp_dropped)
                return@hook UdpAdmission.Drop
            }
            registry.register(counter.key, control)
            UdpAdmission.Allow(GuardedTap(this, UdpActivityTap(this, counter, control)))
        }
    }

    private val allowPassThrough = UdpAdmission.Allow(PassThroughTap)

    private fun countOwner(owner: FlowOwner) {
        when (owner) {
            FlowOwner.BROWSER -> counters.inc(Counter.flows_owner_browser)
            FlowOwner.NON_BROWSER -> counters.inc(Counter.flows_owner_non_browser)
            FlowOwner.UNKNOWN -> counters.inc(Counter.flows_owner_unknown)
        }
    }

    /** §4.6 for non-443 flows: DNS fallback at open; an exhausted rule is cut at attribution. */
    private fun attributeAtOpen(dstIp: ByteArray, control: FlowControl): FlowTap {
        val rule = (resolver.byDns(dstIp, ruleIndex, wallClock.nowMillis()) as? AttributionResolver.Dns.Hit)?.rule
        val counter = rule?.let { counterFor(it) }
        if (counter == null) {
            counters.inc(Counter.flows_unattributed)
            return PassThroughTap
        }
        counters.inc(Counter.flows_attributed_total)
        if (mode == EngineMode.ENFORCE && counter.exhausted) {
            counters.inc(Counter.flow_resets_new)
            control.reset()
            return PassThroughTap
        }
        registry.register(counter.key, control)
        return GuardedTap(this, ActivityTap(this, counter, control))
    }

    /** Guarded by [breakerLock]; a plain ring because hook exceptions are rare by definition. */
    private val breakerLock = Any()
    private val exceptionTimes = LongArray(MeteringConfig.BREAKER_MAX_EXCEPTIONS)
    private var exceptionWriteIndex = 0
    private var exceptionCount = 0

    // ---- S7: startup, shutdown and status (plan §6.8/§6.9, §8 S7.3–S7.6) ----

    /**
     * §6.9's state guard: [start] claims the engine, [stop] shuts it down exactly once, and any
     * later or concurrent call is a no-op — so the final flush and the VPN-stopped notification
     * can never run twice. [STOPPING] is only ever observable inside `stop()` (which holds
     * [startStopLock]); a completed shutdown rests back at `NOT_STARTED` so the next VPN session
     * can start the same singleton Engine again.
     */
    private val lifecycle = AtomicInteger(NOT_STARTED)

    /** FR-12's Dormant test: the current snapshot holds at least one usable rule. */
    @Volatile
    private var hasRules = false

    @Volatile
    private var dbDegraded = false

    @Volatile
    private var privateDnsStrict = false

    private var host: DomainUsageHost? = null
    private var persistence: UsagePersistence? = null
    private var tickScope: CoroutineScope? = null
    private var dbScope: CoroutineScope? = null
    private var tickDispatcher: CoroutineDispatcher? = null
    private var dbDispatcher: CoroutineDispatcher? = null
    private var unregisterPrivateDns: () -> Unit = {}

    /**
     * Held across [start]'s attachment tail and all of [stop]: both sections are
     * suspension-free, so the two can never interleave — a concurrent stop either happens
     * entirely before the tail (which then sees STOPPING and bails) or entirely after it (and
     * tears down a fully attached engine).
     */
    private val startStopLock = Any()

    /**
     * §6.8, plan S7.3: called once per service session, after `establish()` succeeded. Resolves
     * the mode (S1.3), anchors the zone, loads the persisted day, seeds the D-32 revisions, then
     * attaches the ledger, starts the tick, collects rule snapshots and starts the Private-DNS
     * monitor. A load failure leaves the ledger detached — every hook stays pass-through (fail
     * open) and the status is `Degraded(DB_ERROR)` until the retry inside [UsagePersistence]
     * succeeds, exactly like zero-rule start-up (Dormant, but still metering-capable).
     */
    suspend fun start(
        rules: Flow<List<DomainRule>>,
        seedRevisions: (Map<String, Long>) -> Unit,
        dao: () -> RuleUsageDao?,
        host: DomainUsageHost,
        tickDispatcher: CoroutineDispatcher,
        dbDispatcher: CoroutineDispatcher,
    ) {
        // S1.3: the debug-only DataStore override wins over the build-configured mode.
        if (debugBuild) host.modeOverride()?.let { mode = it }
        if (mode == EngineMode.OFF) return
        if (!lifecycle.compareAndSet(NOT_STARTED, RUNNING)) return

        val zone = ZoneId.systemDefault()
        val today = wallClock.now(zone).toLocalDate().toString()
        val persisted = host.lastDayKey()
        val effectiveDayKey = maxOf(today, persisted ?: "")
        val persistence = UsagePersistence(dao, counters, ::setDbDegraded)

        val load = withContext(dbDispatcher) { persistence.loadStartupState(effectiveDayKey) }
        if (persisted != effectiveDayKey) host.setLastDayKey(effectiveDayKey)
        // D-32: the provider's in-memory revisions must adopt the persisted maximum before the
        // first snapshot is adapted, or a restarted process would re-mint revision 0 and hand a
        // new rule the old ones' usage.
        seedRevisions(load.maxRevisionByRuleId)

        val snapshot = rules.first()
        val ledger = UsageLedger(clock, wallClock, counters, registry, mode, zone, effectiveDayKey)
        withContext(tickDispatcher) { ledger.applyRules(snapshot, load.totalsByKey) }

        synchronized(startStopLock) {
            if (lifecycle.get() != RUNNING) return
            this.host = host
            this.persistence = persistence
            this.tickDispatcher = tickDispatcher
            this.dbDispatcher = dbDispatcher
            this.hasRules = snapshot.isNotEmpty()
            this.ledger = ledger

            val dbScope = CoroutineScope(SupervisorJob() + dbDispatcher).also { this.dbScope = it }
            val tickScope = CoroutineScope(SupervisorJob() + tickDispatcher).also { this.tickScope = it }
            UsageTickLoop(
                ledger = ledger,
                counters = counters,
                onRollover = { rollover ->
                    dbScope.launch { persistRollover(rollover) }
                    // §6.5 step 5: re-read the rule snapshot on the new day so `enforcedToday`
                    // is re-evaluated. The collector's last emission carries the day scope of
                    // the day it was adapted on; without this re-read a rule that activates or
                    // deactivates at midnight keeps the previous day's mask until the next
                    // snapshot publish (apps get this for free — isLimitTracked evaluates
                    // dayScopeCoversToday on every call).
                    updateRules(rules.first())
                },
                onTick = {
                    for (warning in ledger.takeLimitWarnings()) {
                        host.postWebsiteLimitWarning(warning.level, warning.remainingMs, warning.pattern)
                    }
                },
                onPersistDue = {
                    // §6.6: the day key is stamped on the tick thread; the write itself is handed
                    // to the db thread so a slow commit cannot delay the next tick.
                    val rows = ledger.dirtySnapshot()
                    if (rows.isNotEmpty()) dbScope.launch { persistence.flush(ledger, rows) }
                },
            ).launch(tickScope)
            // Rule snapshots are applied on the tick thread (§4.3) — the ledger's single-writer rule.
            tickScope.launch { rules.collect { updateRules(it) } }
            tickScope.launch {
                while (isActive) {
                    delay(MeteringConfig.COUNTER_LOG_INTERVAL_MS)
                    if (counterLogEnabled()) Log.i(TAG, counterLogLine())
                }
            }

            unregisterPrivateDns = host.registerPrivateDnsMonitor(::onPrivateDnsStrictChanged)
        }
        refreshStatus()
    }

    /**
     * §6.9: called from `onDestroy` and `onRevoke`; only the first caller runs the side effects.
     * The bounded synchronous flush is the one place the engine is allowed to block — both
     * callers already tolerate a brief stop.
     *
     * The guard rests back at `NOT_STARTED` once the side effects have run: this Engine is a
     * process-wide singleton, and a new VPN session must be able to start it again. `stop()` is
     * still once-per-session — a second call finds `NOT_STARTED` and bails — and the breaker's
     * [dormantUntilRestart] flag deliberately outlives the session.
     *
     * @return true for the caller that ran the shutdown (the service uses it for its own logging).
     */
    fun stop(): Boolean {
        synchronized(startStopLock) {
            if (!lifecycle.compareAndSet(RUNNING, STOPPING)) return false
            try {
                tickScope?.cancel()
                flushOnStop()
                unregisterPrivateDns()
                unregisterPrivateDns = {}
                observations.clear()
                registry.clear()
                if (hasRules && mode != EngineMode.OFF) host?.postVpnStoppedNotification()
            } finally {
                ledger = null
                persistence = null
                host = null
                tickScope = null
                tickDispatcher = null
                dbScope?.cancel()
                dbScope = null
                dbDispatcher = null
                hasRules = false
                dbDegraded = false
                privateDnsStrict = false
                lifecycle.set(NOT_STARTED)
                refreshStatus()
            }
        }
        return true
    }

    /** §6.9: one flush attempt of every dirty counter, bounded by the shutdown timeout. */
    private fun flushOnStop() {
        val ledger = ledger ?: return
        val persistence = persistence ?: return
        val tickDispatcher = tickDispatcher ?: return
        val dbDispatcher = dbDispatcher ?: return
        runBlocking {
            withTimeoutOrNull(MeteringConfig.SHUTDOWN_FLUSH_TIMEOUT_MS) {
                val rows = withContext(tickDispatcher) { ledger.dirtySnapshot() }
                if (rows.isNotEmpty()) withContext(dbDispatcher) { persistence.flush(ledger, rows) }
            }
        }
    }

    /**
     * §6.7: the rollover's writes — the outgoing day's finals, retention, and the new day key.
     * The finals are retried until written: the split has already moved the counters into the
     * new day, so a single failed attempt would drop the outgoing day's row permanently
     * (§6.6's "next cycle writes the newer cumulative value" cannot reach it).
     *
     * Outside [hook] on purpose: this is not a relay-path hook, so breaker dormancy must not
     * skip a data write, and cancelling the db scope must end the retry via `delay`.
     */
    private suspend fun persistRollover(rollover: DayRollover) {
        val ledger = ledger ?: return
        val persistence = persistence ?: return
        while (!persistence.flush(ledger, rollover.finals)) {
            delay(UsagePersistence.RETRY_MS)
        }
        persistence.deleteOlderThan(UsagePersistence.retentionCutoff(rollover.newDayKey))
        hook(Unit) { host?.setLastDayKey(rollover.newDayKey) }
    }

    /** §6.8/S7.5: the monitor reports every change of the recomputed strictness (D-34). */
    internal fun onPrivateDnsStrictChanged(strict: Boolean) {
        if (strict && !privateDnsStrict) counters.inc(Counter.tamper_private_dns)
        privateDnsStrict = strict
        refreshStatus()
    }

    /** §14: `tamper_vpn_revoked` — the revoke path both stops the engine and counts the tamper. */
    fun onVpnRevoked() {
        counters.inc(Counter.tamper_vpn_revoked)
    }

    /**
     * §6.10: the packet layer's report of one refusal attempt — `injected` is false when the
     * builder refused to emit (INV-14) or the TUN write failed, which is the silent drop.
     */
    internal fun onIcmpNudge(injected: Boolean) {
        counters.inc(if (injected) Counter.icmp_injected else Counter.icmp_build_fail)
    }

    /** §6.3's RST counterpart: every RST the relay writes into the TUN, however it was decided. */
    internal fun onRstNudge(injected: Boolean) {
        counters.inc(if (injected) Counter.rst_injected else Counter.rst_build_fail)
    }

    private fun setDbDegraded(degraded: Boolean) {
        dbDegraded = degraded
        refreshStatus()
    }

    /**
     * §14: debug builds always log the counter line; a release build logs only during the beta
     * `ACCOUNT` stage — by the time rollout reaches `ENFORCE`/100% (§13) release logs nothing.
     */
    internal fun counterLogEnabled(): Boolean = debugBuild || mode == EngineMode.ACCOUNT

    /** §14 line: `DomainUsage k=v k=v …` — counters only, never a name (INV-7). */
    internal fun counterLogLine(): String =
        counters.snapshot().entries.joinToString(" ", prefix = "DomainUsage ") { "${it.key}=${it.value}" }

    /**
     * §8 S7.6: the composition, highest severity first. The tripped breaker outranks even the
     * not-running state — U-12 requires the trip to be visible for the rest of the process
     * (it survives `stop()` by design) — and Dormant is the honest answer for a session that
     * has not started, has stopped, or has nothing to enforce.
     */
    internal fun refreshStatus() {
        _status.value = when {
            dormantUntilRestart -> EngineStatus.Degraded(EngineStatus.Reason.INTERNAL_ERROR)
            lifecycle.get() != RUNNING -> EngineStatus.Dormant
            privateDnsStrict -> EngineStatus.Degraded(EngineStatus.Reason.PRIVATE_DNS)
            dbDegraded -> EngineStatus.Degraded(EngineStatus.Reason.DB_ERROR)
            ledger == null || !hasRules -> EngineStatus.Dormant
            else -> EngineStatus.Active
        }
    }

    private companion object {
        const val NOT_STARTED = 0
        const val RUNNING = 1
        const val STOPPING = 2
    }
}
