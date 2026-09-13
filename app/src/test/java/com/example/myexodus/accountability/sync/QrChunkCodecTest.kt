package com.example.myexodus.accountability.sync

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QrChunkCodecTest {

    private fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }

    @Test
    fun roundTrip_preservesPayload() {
        val payload = randomBytes(2_000)
        val frames = QrChunkCodec.encode(payload)
        assertTrue(frames.size >= 3) // 2000 bytes -> 4 chunks of 592
        val reassembled = QrReassembler().apply {
            frames.forEach { accept(QrChunkCodec.decode(it)) }
        }
        assertTrue(reassembled.isComplete)
        assertArrayEquals(payload, reassembled.assembledPayload())
    }

    @Test
    fun everyFrame_isExactly600BytesOrSmallerAndSelfDescribing() {
        val frames = QrChunkCodec.encode(randomBytes(1_000), sessionId = byteArrayOf(1, 2, 3, 4))
        for (frame in frames) {
            assertTrue(frame.size <= QrChunkCodec.FRAME_SIZE)
            val chunk = QrChunkCodec.decode(frame)
            assertEquals(1, chunk.sessionId[0].toInt())
            assertEquals(2, chunk.sessionId[1].toInt())
            assertEquals(3, chunk.sessionId[2].toInt())
            assertEquals(4, chunk.sessionId[3].toInt())
            assertEquals(frames.size, chunk.chunkCount)
        }
    }

    @Test
    fun singleChunkPayload_roundTrips() {
        val payload = randomBytes(100)
        val reassembled = QrReassembler().apply {
            accept(QrChunkCodec.decode(QrChunkCodec.encode(payload)[0]))
        }
        assertTrue(reassembled.isComplete)
        assertArrayEquals(payload, reassembled.assembledPayload())
    }

    @Test
    fun payloadEndingInZeroBytes_isPreservedExactly() {
        val payload = ByteArray(600) { 0xAB.toByte() } // 2 chunks, both full 592/8
        val reassembled = QrReassembler().apply {
            QrChunkCodec.encode(payload).forEach { accept(QrChunkCodec.decode(it)) }
        }
        assertArrayEquals(payload, reassembled.assembledPayload())
    }

    @Test
    fun malformedFrames_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { QrChunkCodec.decode(ByteArray(7)) } // short header
        assertThrows(IllegalArgumentException::class.java) { QrChunkCodec.decode(ByteArray(601)) } // over budget
        // Header bytes crafted directly (bypassing Chunk's own invariants): count=2, index=5.
        val badIndex = byteArrayOf(0, 0, 0, 1, 0, 2, 0, 5) + ByteArray(10)
        assertThrows(IllegalArgumentException::class.java) { QrChunkCodec.decode(badIndex) }
        val badCount = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + ByteArray(10) // count=0
        assertThrows(IllegalArgumentException::class.java) { QrChunkCodec.decode(badCount) }
    }

    @Test
    fun oversizedPayload_isRejected() {
        val tooBig = ByteArray(QrChunkCodec.MAX_CHUNKS * QrChunkCodec.CHUNK_PAYLOAD_SIZE + 1)
        assertThrows(IllegalArgumentException::class.java) { QrChunkCodec.encode(tooBig) }
    }
}
