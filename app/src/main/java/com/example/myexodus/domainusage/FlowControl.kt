package com.example.myexodus.domainusage

/**
 * §4.2: the Baseline's per-flow enforcement handle. TCP: RST to the app; UDP: mark the flow
 * blocked so later datagrams are dropped. Implementations must be thread-safe — the Engine
 * calls [reset] from the tick thread while the relay thread may still be relaying.
 */
interface FlowControl {
    fun reset()
}
