package com.example.myexodus.vpn

import java.util.ArrayDeque

/**
 * Send-side flow control for the relay's synthetic device-facing TCP endpoint: RFC 793 §3.3/§3.4
 * window accounting, §3.7 retransmission, RFC 1122 §4.2.2.17 zero-window probing.
 *
 * Device-proven defect (2026-10-18): without this, the relay wrote 3,991,319 bytes past a
 * zero advertised window during a 10,485,760-byte download. The device kernel discarded them
 * and answered with duplicate ACKs the relay ignored, so the transfer wedged permanently at
 * the 6,495,000 bytes the kernel had accepted (ADR #175). No window scaling is negotiated in
 * the SYN-ACK, so the device's 16-bit window field is the window.
 *
 * [onAck] runs on the TUN pump thread; reserve/probe/retransmit run on the flow's relay thread.
 * The lock covers bookkeeping only — packet building and TUN writes happen outside it.
 */
internal class TcpSendWindow(isn: Int, initialWindow: Int) {

    class Segment(val seq: Int, val payload: ByteArray, val length: Int)

    private val lock = Object()
    private val unacked = ArrayDeque<Segment>()

    // RFC 793 §3.4: a window update is adopted only from a newer segment (SND.WL1/SND.WL2).
    private var wl1 = 0
    private var wl2 = 0
    private var rtoMs = RTO_INITIAL_MS
    private var retransmitAtMs = 0L

    /** First sequence number the device has not acknowledged. */
    var sndUna: Int = isn + 1
        private set

    /** Next sequence number for new data; the SYN consumed [isn]. */
    var nextSeq: Int = isn + 1
        private set

    /** Bytes the device is currently willing to receive past [sndUna]. */
    var sndWnd: Int = initialWindow.coerceIn(0, 0xFFFF)
        private set

    fun hasUnacked(): Boolean = synchronized(lock) { unacked.isNotEmpty() }

    /**
     * Blocks up to [waitMs] for room inside the device's window, then reserves the next
     * at-most-[maxLen] bytes of [buf] as an unacknowledged segment. Null = window still full.
     */
    fun reserveSend(buf: ByteArray, off: Int, maxLen: Int, waitMs: Long, nowMs: Long): Segment? =
        synchronized(lock) {
            if (availableLocked() <= 0 && waitMs > 0) {
                try {
                    lock.wait(waitMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            val room = availableLocked()
            if (room <= 0) return null
            val len = minOf(room, maxLen, VpnPacket.MAX_TCP_SEGMENT)
            val seg = Segment(nextSeq, buf.copyOfRange(off, off + len), len)
            if (unacked.isEmpty()) retransmitAtMs = nowMs + rtoMs
            unacked.addLast(seg)
            nextSeq += len
            seg
        }

    /**
     * One byte past a zero window, tracked like normal data: if the device accepted it after
     * all, the ACK advances SND.UNA; otherwise the device re-advertises its current window.
     * Only when nothing is already in flight (a hole is repaired by [dueRetransmit] instead).
     */
    fun reserveProbe(buf: ByteArray, off: Int, nowMs: Long): Segment? = synchronized(lock) {
        if (sndWnd != 0 || unacked.isNotEmpty() || nowMs < retransmitAtMs) return null
        val seg = Segment(nextSeq, byteArrayOf(buf[off]), 1)
        unacked.addLast(seg)
        nextSeq += 1
        advanceRepairTimerLocked(nowMs)
        seg
    }

    /** The oldest unacknowledged segment once the repair timer expires; null otherwise. */
    fun dueRetransmit(nowMs: Long): Segment? = synchronized(lock) {
        if (unacked.isEmpty() || nowMs < retransmitAtMs) return null
        advanceRepairTimerLocked(nowMs)
        unacked.first()
    }

    /**
     * Cumulative ACK from the device: advances SND.UNA, drops acknowledged segments, resets
     * the repair timer, and adopts the advertised window when the carrying segment is newer.
     */
    fun onAck(seq: Int, ack: Int, window: Int, nowMs: Long) {
        synchronized(lock) {
            if (Integer.compareUnsigned(seq, wl1) > 0 ||
                (seq == wl1 && Integer.compareUnsigned(ack, wl2) >= 0)
            ) {
                sndWnd = window.coerceIn(0, 0xFFFF)
                wl1 = seq
                wl2 = ack
            }
            if (Integer.compareUnsigned(ack, sndUna) <= 0) return
            if (Integer.compareUnsigned(ack, nextSeq) > 0) return // never acknowledge unsent data
            sndUna = ack
            while (unacked.isNotEmpty() &&
                Integer.compareUnsigned(unacked.first().seq + unacked.first().length, ack) <= 0
            ) {
                unacked.removeFirst()
            }
            rtoMs = RTO_INITIAL_MS
            retransmitAtMs = if (unacked.isEmpty()) 0L else nowMs + rtoMs
            lock.notifyAll()
        }
    }

    private fun availableLocked(): Int {
        val inFlight = nextSeq - sndUna
        return if (sndWnd > inFlight) sndWnd - inFlight else 0
    }

    private fun advanceRepairTimerLocked(nowMs: Long) {
        retransmitAtMs = nowMs + rtoMs
        rtoMs = minOf(rtoMs * 2, RTO_MAX_MS)
    }

    private companion object {
        const val RTO_INITIAL_MS = 1_000L
        const val RTO_MAX_MS = 8_000L
    }
}
