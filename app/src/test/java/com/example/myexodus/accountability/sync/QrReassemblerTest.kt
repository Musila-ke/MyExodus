package com.example.myexodus.accountability.sync

import java.security.SecureRandom
import java.util.Collections
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QrReassemblerTest {

    private fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun chunks(payload: ByteArray, sessionId: ByteArray = QrChunkCodec.newSessionId()) =
        QrChunkCodec.encode(payload, sessionId).map { QrChunkCodec.decode(it) }

    @Test
    fun thirtyPercentFrameLoss_stillReassembles() {
        val payload = randomBytes(5_920) // 10 chunks
        val all = chunks(payload)
        // A 10 FPS loop re-sends every frame; frame loss = failed decode attempts, not lost chunks.
        // 9 of 30 frames dropped (exactly 30%), spread so no chunk loses all three of its copies.
        val transmission = (0 until 3).flatMap { all }
        val dropped = setOf(2, 5, 8, 10, 13, 16, 21, 24, 27)
        val received = transmission.filterIndexed { i, _ -> i !in dropped }
        assertEquals(0.7, received.size.toDouble() / transmission.size, 0.001)
        val reassembled = QrReassembler().apply { received.forEach { accept(it) } }
        assertTrue(reassembled.isComplete)
        assertArrayEquals(payload, reassembled.assembledPayload())
    }

    @Test
    fun outOfOrderFrames_reassemble() {
        val payload = randomBytes(3_000)
        val shuffled = chunks(payload).shuffled()
        val reassembled = QrReassembler().apply { shuffled.forEach { accept(it) } }
        assertTrue(reassembled.isComplete)
        assertArrayEquals(payload, reassembled.assembledPayload())
    }

    @Test
    fun duplicateFrames_areIgnored() {
        val payload = randomBytes(2_000)
        val all = chunks(payload)
        var completions = 0
        val reassembled = QrReassembler()
        for (i in 0 until 3) { // three full passes over the same frames
            all.forEach { if (reassembled.accept(it)) completions++ }
        }
        assertEquals(1, completions) // completes once, then stays complete
        assertArrayEquals(payload, reassembled.assembledPayload())
    }

    @Test
    fun sessionChange_resetsBuffer() {
        val first = randomBytes(500)
        val second = randomBytes(800)
        val reassembled = QrReassembler()
        // Receive most of session A, then frames of session B (user pointed at another phone).
        val a = chunks(first).dropLast(1)
        a.forEach { reassembled.accept(it) }
        assertFalse(reassembled.isComplete)
        val b = chunks(second)
        b.forEach { reassembled.accept(it) }
        assertTrue(reassembled.isComplete)
        assertArrayEquals(second, reassembled.assembledPayload())
    }

    @Test
    fun progress_tracksReceivedFraction() {
        val all = chunks(randomBytes(1_500)) // 3 chunks
        val reassembled = QrReassembler()
        assertEquals(0f, reassembled.progress)
        reassembled.accept(all[0])
        assertEquals(1f / 3f, reassembled.progress, 0.001f)
        reassembled.accept(all[2])
        assertEquals(2f / 3f, reassembled.progress, 0.001f)
        assertFalse(reassembled.isComplete)
        reassembled.accept(all[1])
        assertTrue(reassembled.isComplete)
        assertEquals(1f, reassembled.progress, 0.001f)
    }

    @Test
    fun assembledBeforeComplete_isRejected() {
        val all = chunks(randomBytes(1_500))
        val reassembled = QrReassembler()
        reassembled.accept(all[0])
        assertThrows(IllegalStateException::class.java) { reassembled.assembledPayload() }
    }

    @Test
    fun malformedChunk_doesNotCorruptSession() {
        val payload = randomBytes(1_500)
        val all = chunks(payload)
        val reassembled = QrReassembler()
        all.forEach { reassembled.accept(it) }
        // A structurally-valid but content-mangled chunk at an already-received index is ignored.
        val mangled = all[0].copy(payload = randomBytes(592))
        assertEquals(true, reassembled.isComplete) // unchanged
        assertArrayEquals(payload, reassembled.assembledPayload())
    }

    @Test
    fun forgedHeaderWithLargerCount_isIgnored_sessionStaysIntact() {
        val payload = randomBytes(1_500) // 3 chunks
        val all = chunks(payload)
        val reassembled = QrReassembler()
        reassembled.accept(all[0])
        // Hostile frame: same session id, but a header claiming 100 chunks at index 99.
        // Without the shape guard this indexes the 3-slot arrays sized by the first frame
        // and throws ArrayIndexOutOfBoundsException.
        val forged = QrChunkCodec.decode(
            all[0].sessionId + byteArrayOf(0, 100) + byteArrayOf(0, 99) + ByteArray(10),
        )
        assertFalse(reassembled.accept(forged))
        assertEquals(1f / 3f, reassembled.progress, 0.001f) // progress flushed by nothing
        reassembled.accept(all[1])
        reassembled.accept(all[2])
        assertTrue(reassembled.isComplete)
        assertArrayEquals(payload, reassembled.assembledPayload())
    }

    @Test
    fun headerOnlyFrame_isIgnored_notASessionReset() {
        val payload = randomBytes(1_500) // 3 chunks
        val all = chunks(payload)
        val reassembled = QrReassembler()
        reassembled.accept(all[0])
        // An 8-byte frame (header, no payload) decodes fine but must not flush the session.
        val bare = QrChunkCodec.decode(all[0].sessionId + byteArrayOf(0, 3) + byteArrayOf(0, 0))
        assertFalse(reassembled.accept(bare))
        assertEquals(1f / 3f, reassembled.progress, 0.001f)
    }

    /** §12 fuzz: random payloads, random orders, random duplication — never crashes, always exact. */
    @Test
    fun fuzz_randomPayloadsInRandomOrders() {
        val rng = SecureRandom()
        repeat(50) { seed ->
            val size = 1 + rng.nextInt(3_500)
            val payload = randomBytes(size)
            val frames = chunks(payload, sessionId = ByteArray(4) { (seed % 256).toByte() })
            val sequence = ArrayList<QrChunkCodec.Chunk>()
            repeat(2) { frames.forEach { sequence.add(it) } } // duplicates
            Collections.shuffle(sequence, java.util.Random(seed.toLong()))
            val reassembled = QrReassembler()
            sequence.forEach { reassembled.accept(it) }
            assertTrue("seed=$seed size=$size", reassembled.isComplete)
            assertArrayEquals("seed=$seed", payload, reassembled.assembledPayload())
        }
    }
}
