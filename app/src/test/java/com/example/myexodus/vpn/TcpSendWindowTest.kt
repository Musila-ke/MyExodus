package com.example.myexodus.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The send-side flow-control contract that keeps the relay inside the device's advertised
 * window (RFC 793 §3.3/§3.4/§3.7). The device-proven failure this pins down: the relay sent
 * 3,991,319 bytes past a zero window and the flow never recovered (ADR #175).
 */
class TcpSendWindowTest {

    private fun window(isn: Int = 1_000, wnd: Int = 10_000) = TcpSendWindow(isn, wnd)

    @Test
    fun sendsOnlyInsideTheAdvertisedWindow() {
        val w = window(isn = 1_000, wnd = 2_000)
        val buf = ByteArray(4_000)

        val first = w.reserveSend(buf, 0, 2_000, waitMs = 0, nowMs = 0)!!
        assertEquals(1_001, first.seq)
        assertEquals(VpnPacket.MAX_TCP_SEGMENT, first.length) // capped per segment

        val second = w.reserveSend(buf, first.length, 2_000 - first.length, waitMs = 0, nowMs = 0)!!
        assertEquals(2_000 - VpnPacket.MAX_TCP_SEGMENT, second.length)

        assertNull("window is full", w.reserveSend(buf, 2_000, 1_000, waitMs = 0, nowMs = 0))
    }

    @Test
    fun ackAdvancesSndUnaAndReopensTheWindow() {
        val w = window(isn = 1_000, wnd = 2_000)
        val buf = ByteArray(8_000)
        val first = w.reserveSend(buf, 0, 2_000, 0, 0)!!
        val second = w.reserveSend(buf, first.length, 2_000 - first.length, 0, 0)!!

        // Acknowledge only the first segment: 1200 bytes of room are back.
        w.onAck(seq = 2_000, ack = first.seq + first.length, window = 2_000, nowMs = 10)
        assertEquals(first.seq + first.length, w.sndUna)
        val third = w.reserveSend(buf, 2_000, 1_000, 0, 10)!!
        assertEquals("the caller's maxLen caps it once room allows", 1_000, third.length)

        // Acknowledging the rest leaves exactly the third segment in flight.
        w.onAck(seq = 2_000, ack = second.seq + second.length, window = 2_000, nowMs = 20)
        assertEquals(1_000, w.sndWnd - (w.nextSeq - w.sndUna))
    }

    @Test
    fun ackBeyondSentDataAndDuplicateAcksDoNotCorruptBookkeeping() {
        val w = window(isn = 1_000, wnd = 2_000)
        val buf = ByteArray(8_000)
        val seg = w.reserveSend(buf, 0, 1_000, 0, 0)!!
        val sent = seg.seq + seg.length

        w.onAck(seq = 2_000, ack = sent + 5_000, window = 2_000, nowMs = 10) // impossible ACK
        assertEquals(1_001, w.sndUna)

        w.onAck(seq = 2_000, ack = sent, window = 2_000, nowMs = 20)
        w.onAck(seq = 2_000, ack = sent - 500, window = 2_000, nowMs = 30) // duplicate
        assertEquals(sent, w.sndUna)
    }

    @Test
    fun retransmitsTheOldestUnackedSegmentAfterTheRtoAndBacksOff() {
        val w = window(isn = 1_000, wnd = 64_000)
        val buf = ByteArray(8_000)
        val first = w.reserveSend(buf, 0, 2_000, 0, nowMs = 0)!!
        w.reserveSend(buf, first.length, 2_000, 0, nowMs = 0)

        assertNull("not due before the RTO", w.dueRetransmit(nowMs = 999))
        assertSame(first, w.dueRetransmit(nowMs = 1_000))
        assertNull("next attempt backs off by 2x", w.dueRetransmit(nowMs = 1_999))
        assertNotNull(w.dueRetransmit(nowMs = 2_000))

        // ACK progress resets the timer to the initial RTO.
        w.onAck(seq = 2_000, ack = first.seq + first.length, window = 64_000, nowMs = 2_100)
        assertNull(w.dueRetransmit(nowMs = 3_099))
        assertNotNull(w.dueRetransmit(nowMs = 3_100))
    }

    @Test
    fun zeroWindowProbeIsOneTrackedByte() {
        val w = window(isn = 1_000, wnd = 0)
        val buf = ByteArray(8_000) { 7 }
        assertNull(w.reserveSend(buf, 0, 1_000, waitMs = 0, nowMs = 0))

        val probe = w.reserveProbe(buf, 0, nowMs = 0)!!
        assertEquals(1, probe.length)
        assertEquals(7, probe.payload[0].toInt())
        assertEquals(1_001, probe.seq)
        assertEquals(1_002, w.nextSeq)
        assertTrue(w.hasUnacked())

        // The device answers with a fresh window; normal sending resumes.
        w.onAck(seq = 2_000, ack = 1_001, window = 4_000, nowMs = 10)
        assertEquals(1_001, w.sndUna)
        assertNotNull(w.reserveSend(buf, 1, 1_000, 0, nowMs = 10))
    }

    @Test
    fun staleSegmentDoesNotShrinkTheWindow() {
        val w = window(isn = 1_000, wnd = 4_000)
        // Newer segment: window opens.
        w.onAck(seq = 2_000, ack = 1_001, window = 65_535, nowMs = 0)
        assertEquals(65_535, w.sndWnd)
        // Older segment (out-of-order device retransmit) claiming a zero window: ignored.
        w.onAck(seq = 1_900, ack = 1_001, window = 0, nowMs = 0)
        assertEquals(65_535, w.sndWnd)
        // Same seq, newer ack: accepted.
        w.onAck(seq = 2_000, ack = 1_001, window = 3_000, nowMs = 0)
        assertEquals(3_000, w.sndWnd)
    }
}
