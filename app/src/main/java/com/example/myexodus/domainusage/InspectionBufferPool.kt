package com.example.myexodus.domainusage

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S4.4 / INV-13: [MeteringConfig.INSPECT_POOL_SIZE] pre-allocated inspection buffers in a
 * lock-free deque. A flow takes one at `tcpFlowOpened`; the slot goes back into the deque only
 * inside [OwnedBytes.release], which the Baseline calls after it has finished with the bytes —
 * never while the Engine still holds them. [acquire] returns null when the pool is empty (the
 * flow is then DNS-fallback-attributed and passed through immediately, counter
 * `inspect_pool_exhausted`), so the pool can never block a relay thread.
 *
 * Pool sizing is a deliberate, bounded trade-off: at most [MeteringConfig.INSPECT_POOL_SIZE]
 * flows can be under inspection at once; everything beyond that is accounted by DNS instead of
 * being held.
 */
class InspectionBufferPool(
    size: Int = MeteringConfig.INSPECT_POOL_SIZE,
    bufferBytes: Int = MeteringConfig.INSPECT_MAX_BYTES,
    private val counters: EngineCounters,
) {

    private val free = ConcurrentLinkedDeque<ByteArray>().apply {
        repeat(size) { add(ByteArray(bufferBytes)) }
    }

    /** A free buffer, or null if every buffer is in use. */
    fun acquire(): ByteArray? {
        val buffer = free.pollFirst()
        if (buffer == null) counters.inc(Counter.inspect_pool_exhausted)
        return buffer
    }

    /** Wraps an [acquire]d buffer so the Baseline can return its slot with [OwnedBytes.release]. */
    fun wrap(buffer: ByteArray, length: Int): OwnedBytes = PooledBytes(buffer, length)

    internal fun available(): Int = free.size

    private inner class PooledBytes(private val buffer: ByteArray, override val length: Int) : OwnedBytes {

        private val released = AtomicBoolean(false)

        override val bytes: ByteArray get() = buffer

        /** Idempotent: a second release would otherwise let two flows own the same buffer. */
        override fun release() {
            if (released.compareAndSet(false, true)) free.addFirst(buffer)
        }
    }
}
