package com.example.myexodus.domainusage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * §8 S5.4 / §6.5: the tick loop. Runs on the caller's `domainusage-tick` dispatcher (§4.3) one
 * ledger pass per `TICK_MS`, and hands the persistence-facing outcomes to the db thread through
 * the two callbacks — the loop itself never touches Room (INV-1).
 *
 * The loop's correctness does not depend on the cadence: [UsageLedger.tick] books exact
 * intervals for any gap, so a delayed or skipped invocation still totals correctly (INV-16).
 */
class UsageTickLoop(
    private val ledger: UsageLedger,
    private val counters: EngineCounters,
    private val onRollover: suspend (DayRollover) -> Unit = {},
    /** After every ledger pass (post-rollover): the engine surfaces threshold warnings here. */
    private val onTick: () -> Unit = {},
    private val onPersistDue: suspend () -> Unit = {},
) {

    fun launch(scope: CoroutineScope): Job = scope.launch {
        var ticksSincePersist = 0
        while (isActive) {
            delay(MeteringConfig.TICK_MS)
            try {
                ledger.tick()?.let { onRollover(it) }
                onTick()
                if (++ticksSincePersist >= PERSIST_EVERY_TICKS) {
                    ticksSincePersist = 0
                    onPersistDue()
                }
            } catch (e: VirtualMachineError) {
                counters.inc(Counter.fatal_vm_error)
                throw e
            } catch (e: Exception) {
                // A dead tick loop silently stops all metering and enforcement, so an ordinary
                // fault is counted and the loop continues. The §14 set has no tick counter —
                // hook_exceptions is reused (see the implementation log).
                counters.inc(Counter.hook_exceptions)
            }
        }
    }

    companion object {
        val PERSIST_EVERY_TICKS: Int =
            (MeteringConfig.PERSIST_INTERVAL_MS / MeteringConfig.TICK_MS).toInt()
    }
}
