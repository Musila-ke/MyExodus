package com.example.myexodus.domainusage

import java.util.concurrent.ConcurrentHashMap

/**
 * §8 S5.3: the live flows per `(rule_id, revision)`, so an exhausted rule can sweep what is
 * already connected (D-09) and a removed rule's counter can be dropped once nothing references
 * it (§6.5). Capped per rule at [MeteringConfig.MAX_TRACKED_FLOWS_PER_RULE]: a flow beyond the
 * cap stays metered and is still cut off by its own next lookup or activity check — it simply
 * does not get the immediate sweep (counter `flow_registry_capped`).
 *
 * Registration is per flow-open and deregistration per close, never per chunk — the relay hot
 * path only touches its [RuleCounter] (INV-12).
 */
class FlowRegistry(private val counters: EngineCounters) {

    private val byRule = ConcurrentHashMap<String, MutableSet<FlowControl>>()

    fun register(key: String, control: FlowControl) {
        val set = byRule.computeIfAbsent(key) { ConcurrentHashMap.newKeySet() }
        // Benign under concurrency: two racing registrations may exceed the cap by one; the cap
        // bounds sweep work, not correctness, so an off-by-a-few is not worth a lock.
        if (set.size >= MeteringConfig.MAX_TRACKED_FLOWS_PER_RULE) {
            counters.inc(Counter.flow_registry_capped)
            return
        }
        set.add(control)
    }

    fun unregister(key: String, control: FlowControl) {
        val set = byRule[key] ?: return
        set.remove(control)
        if (set.isEmpty()) byRule.remove(key, set)
    }

    /** §6.5 step 5: reset every flow currently attributed to an exhausted rule. */
    fun resetAll(key: String) {
        val set = byRule[key] ?: return
        for (control in set) {
            control.reset()
            counters.inc(Counter.flow_resets_sweep)
        }
    }

    fun isEmpty(key: String): Boolean = byRule[key]?.isEmpty() ?: true

    fun clear() = byRule.clear()
}
