package com.example.myexodus.domainusage

import java.util.concurrent.atomic.AtomicBoolean

/**
 * §6.3 / D-13: the TCP/443 tap. It holds the client's first bytes until the TLS parser is terminal
 * (or [MeteringConfig.MAX_INSPECTION_HOLD_MS] elapses, whichever comes first) so the ClientHello can
 * be attributed to a rule before it reaches the server; after the decision it is a plain activity
 * tap (the client's held bytes are forwarded in one piece).
 *
 * Buffer ownership (INV-13): the pooled buffer returns to [InspectionBufferPool] only when the
 * Baseline releases a `Forward` it has written, or from [onClosed] when no `Forward` was ever
 * handed out — a flow this tap resets releases its slot through `control.reset()` → close.
 *
 * Threading: [onClientData] runs on the Baseline's packet thread; [onServerData]/[onClosed] run on
 * the relay, sweep or tick threads. [holdLock] covers the hold phase only — the post-decision fast
 * path reads one volatile and takes no lock.
 */
internal class InspectingTcpTap(
    private val engine: DomainUsageEngine,
    private val control: FlowControl,
    private val dstIp: ByteArray,
    private val buffer: ByteArray,
) : FlowTap {

    private val holdLock = Any()
    private val deadlineElapsed = engine.clock.elapsedMs() + MeteringConfig.MAX_INSPECTION_HOLD_MS
    private val handedOff = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var terminal = false

    @Volatile
    private var counter: RuleCounter? = null

    private var held = 0

    override fun onClientData(data: ByteArray, off: Int, len: Int): ClientDataResult {
        // A close can race the packet thread (upstream EOF, sweep, device RST): never touch the
        // buffer or the counters after that, and never hold bytes that nothing will forward.
        if (closed.get()) return ClientDataResult.PassThrough
        try {
            if (engine.dormantUntilRestart) return failOpen()
            if (terminal) {
                counter?.touchChunk(len, engine.clock)
                return ClientDataResult.PassThrough
            }
            return hold(data, off, len)
        } catch (e: VirtualMachineError) {
            engine.counters.inc(Counter.fatal_vm_error)
            throw e
        } catch (e: Exception) {
            engine.onHookException()
            return failOpen()
        }
    }

    override fun onServerData(len: Int) {
        try {
            counter?.touchChunk(len, engine.clock)
        } catch (e: VirtualMachineError) {
            engine.counters.inc(Counter.fatal_vm_error)
            throw e
        } catch (e: Exception) {
            engine.onHookException()
        }
    }

    override fun onClosed() {
        // Exactly once (FlowTap contract): GuardedTap's fault path closes the inner tap and the
        // Baseline's teardown closes it again, and each pool.wrap() is a fresh OwnedBytes whose
        // own release guard cannot see the earlier one — a second release would put the same
        // buffer into the free list twice, handing it to two flows (review sweep 2026-10-17).
        if (!closed.compareAndSet(false, true)) return
        try {
            synchronized(holdLock) {
                terminal = true
                // Only if no Forward was handed out: once it was, the Baseline owns the buffer and
                // releases it — releasing here as well would hand the slot to another flow while
                // this flow's bytes are still being written out (INV-13).
                if (!handedOff.get()) engine.pool.wrap(buffer, 0).release()
            }
        } catch (e: VirtualMachineError) {
            engine.counters.inc(Counter.fatal_vm_error)
            throw e
        } catch (e: Exception) {
            engine.onHookException()
        }
    }

    // ---- hold phase (holdLock) ----

    private fun hold(data: ByteArray, off: Int, len: Int): ClientDataResult = synchronized(holdLock) {
        if (closed.get()) return ClientDataResult.PassThrough
        if (terminal) {
            counter?.touchChunk(len, engine.clock)
            return ClientDataResult.PassThrough
        }
        // A chunk is copied as far as it fits, then the parser re-runs over the whole accumulation
        // (S4.1). Whatever does not fit cannot be preserved — [ClientDataResult] forwards one byte
        // range per chunk — so a full buffer ends the hold; in practice this needs a ClientHello
        // fragmented across ~77 records, since the parser rejects oversize messages within its
        // first few bytes (TooLarge).
        val room = buffer.size - held
        val copy = if (len < room) len else room
        data.copyInto(buffer, held, off, off + copy)
        held += copy
        val parsed = TlsClientHelloParser.feed(buffer, 0, held)
        val decided: TlsClientHelloParser.Result? = if (parsed == TlsClientHelloParser.Result.NeedMore) {
            when {
                // Checked on every arriving chunk, so a retransmit of a stalled handshake drives it.
                engine.clock.elapsedMs() >= deadlineElapsed -> {
                    engine.counters.inc(Counter.inspect_timeout)
                    null // §9: a timeout is attributed exactly like NoSni
                }
                held == buffer.size -> {
                    engine.counters.inc(Counter.sni_toolarge)
                    null
                }
                else -> return ClientDataResult.Hold
            }
        } else {
            TlsClientHelloParser.countInto(parsed, engine.counters)
            parsed
        }
        return decide(decided)
    }

    /** Terminal: bind the flow to a `(rule, revision)` identity, or leave it unmetered (INV-8). */
    private fun decide(decided: TlsClientHelloParser.Result?): ClientDataResult {
        terminal = true
        val nowMillis = engine.wallClock.nowMillis()
        val rule = if (decided is TlsClientHelloParser.Result.Sni) {
            engine.resolver.bySni(decided.host, engine.ruleIndex, nowMillis)
        } else {
            (engine.resolver.byDns(dstIp, engine.ruleIndex, nowMillis) as? AttributionResolver.Dns.Hit)?.rule
        }
        val bound = rule?.let { engine.counterFor(it) }
        counter = bound
        if (bound == null) {
            engine.counters.inc(Counter.flows_unattributed)
        } else {
            engine.counters.inc(Counter.flows_attributed_total)
            if (engine.mode == EngineMode.ENFORCE && bound.exhausted) {
                engine.counters.inc(Counter.flow_resets_new)
                control.reset() // tears the flow down; onClosed releases the buffer
                return ClientDataResult.Reset
            }
            engine.registry.register(bound.key, control)
            bound.touchChunk(held, engine.clock) // the held bytes are relayed now
        }
        handedOff.set(true)
        return ClientDataResult.Forward(engine.pool.wrap(buffer, held))
    }

    /** NFR-6: on a fault (or dormancy) forward whatever was held, unmetered, and stop holding. */
    private fun failOpen(): ClientDataResult = synchronized(holdLock) {
        terminal = true
        if (handedOff.get() || held == 0) return ClientDataResult.PassThrough
        handedOff.set(true)
        return ClientDataResult.Forward(engine.pool.wrap(buffer, held))
    }
}
