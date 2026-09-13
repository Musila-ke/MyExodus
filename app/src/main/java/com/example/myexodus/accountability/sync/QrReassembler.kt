package com.example.myexodus.accountability.sync

import java.util.Arrays

/**
 * §6.3 receiver side: reassembles chunk payloads in a memory buffer until every unique
 * index of the session is present. Duplicate frames are ignored (a 10 FPS link re-sends
 * each frame many times); a session change resets the buffer, so pointing the camera at
 * a different phone mid-transfer is safe. Tolerates arbitrary frame loss — missing chunks
 * simply keep the session incomplete (§11: 30% frame loss must not require a restart).
 */
class QrReassembler {

    private var sessionId: ByteArray? = null
    private var expectedCount = 0
    private var buffer: ByteArray = ByteArray(0)
    private var receivedFlags = BooleanArray(0)
    private var received = 0
    private var lastChunkSize = 0

    val isComplete: Boolean get() = sessionId != null && received == expectedCount

    /** 0..1 progress of the current session, 0 when no session has been seen. */
    val progress: Float get() = if (expectedCount == 0) 0f else received.toFloat() / expectedCount

    fun reset() {
        sessionId = null
        expectedCount = 0
        buffer = ByteArray(0)
        receivedFlags = BooleanArray(0)
        received = 0
        lastChunkSize = 0
    }

    /**
     * Ingests one decoded frame. Returns true only on the transition to complete (duplicates
     * of a finished session return false). A frame for a different session — or an empty
     * [payload] — resets the buffer, so pointing the camera at another phone mid-transfer
     * starts a fresh session.
     */
    fun accept(chunk: QrChunkCodec.Chunk): Boolean {
        if (chunk.payload.isEmpty()) return isComplete
        val current = sessionId
        if (current == null || !Arrays.equals(current, chunk.sessionId)) {
            sessionId = chunk.sessionId
            expectedCount = chunk.chunkCount
            buffer = ByteArray(chunk.chunkCount * QrChunkCodec.CHUNK_PAYLOAD_SIZE)
            receivedFlags = BooleanArray(chunk.chunkCount)
            received = 0
            lastChunkSize = 0
        } else if (isComplete) {
            return false
        }
        if (receivedFlags[chunk.chunkIndex]) return isComplete
        chunk.payload.copyInto(buffer, chunk.chunkIndex * QrChunkCodec.CHUNK_PAYLOAD_SIZE)
        receivedFlags[chunk.chunkIndex] = true
        received++
        if (chunk.chunkIndex == chunk.chunkCount - 1) lastChunkSize = chunk.payload.size
        return isComplete
    }

    /** The reassembled payload; call only when [isComplete]. */
    fun assembledPayload(): ByteArray {
        check(isComplete) { "reassembly incomplete: $received/$expectedCount chunks" }
        // Non-final chunks are full 592 bytes; the final chunk is unpadded, so its size is the
        // exact payload tail — no zero-byte guessing.
        val total = (expectedCount - 1) * QrChunkCodec.CHUNK_PAYLOAD_SIZE + lastChunkSize
        return buffer.copyOfRange(0, total)
    }
}
