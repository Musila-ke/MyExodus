package com.example.myexodus.accountability.sync

import java.security.SecureRandom

/**
 * §6.3 animated-QR framing: 592-byte chunk payloads each carrying an 8-byte header
 * `[sessionId: 4][chunkCount: 2][chunkIndex: 2]` (big-endian), so every frame is self-
 * describing: the receiver knows the total, its position, and which transfer run it
 * belongs to without any out-of-band metadata. Frame budget is 600 bytes (§5/§11).
 */
object QrChunkCodec {

    const val HEADER_SIZE = 8
    const val CHUNK_PAYLOAD_SIZE = 592
    const val FRAME_SIZE = HEADER_SIZE + CHUNK_PAYLOAD_SIZE

    /** §8.4 mailbox rules cap the envelope at `envelope.size() < 1048576` (strict), so the
     *  largest envelope any transfer can legally carry is 1,048,575 bytes. */
    const val MAX_ENVELOPE_BYTES = 1_048_576

    /** ceil((MAX_ENVELOPE_BYTES − 1) / 592) = 1772 — the largest count a legal envelope
     *  produces, and the bound for header validation: a header claiming more is malformed. */
    const val MAX_CHUNKS = 1_772

    data class Chunk(
        val sessionId: ByteArray,
        val chunkCount: Int,
        val chunkIndex: Int,
        val payload: ByteArray,
    ) {
        fun frame(): ByteArray {
            require(chunkIndex in 0 until chunkCount) { "chunk index out of range" }
            return sessionId +
                int16(chunkCount) +
                int16(chunkIndex) +
                payload
        }
    }

    fun newSessionId(): ByteArray = ByteArray(4).also { SecureRandom().nextBytes(it) }

    /** Splits [payload] into §6.3 frames; only non-final chunks are padded to 592 bytes. */
    fun encode(payload: ByteArray, sessionId: ByteArray = newSessionId()): List<ByteArray> {
        require(payload.isNotEmpty()) { "empty payload" }
        require(payload.size < MAX_ENVELOPE_BYTES) { "payload exceeds the 1 MiB envelope cap" }
        require(sessionId.size == 4) { "session id must be 4 bytes" }
        // chunkCount for the largest legal envelope (1,048,575 bytes) is exactly MAX_CHUNKS.
        val chunkCount = (payload.size + CHUNK_PAYLOAD_SIZE - 1) / CHUNK_PAYLOAD_SIZE
        return (0 until chunkCount).map { i ->
            val start = i * CHUNK_PAYLOAD_SIZE
            val end = minOf(start + CHUNK_PAYLOAD_SIZE, payload.size)
            // Non-final chunks are full 592 bytes; the final partial chunk stays unpadded so
            // the receiver never has to guess the true payload length (no trailing-zero trimming).
            Chunk(sessionId, chunkCount, i, payload.copyOfRange(start, end)).frame()
        }
    }

    /** Parses a frame; rejects malformed framing (short frame, impossible index/count). */
    fun decode(frame: ByteArray): Chunk {
        require(frame.size >= HEADER_SIZE) { "frame shorter than header" }
        require(frame.size <= FRAME_SIZE) { "frame exceeds 600-byte budget" }
        val sessionId = frame.copyOfRange(0, 4)
        val chunkCount = int16(frame, 4)
        val chunkIndex = int16(frame, 6)
        require(chunkCount in 1..MAX_CHUNKS) { "chunk count out of range" }
        require(chunkIndex in 0 until chunkCount) { "chunk index out of range" }
        return Chunk(sessionId, chunkCount, chunkIndex, frame.copyOfRange(HEADER_SIZE, frame.size))
    }

    private fun int16(value: Int): ByteArray = byteArrayOf(
        ((value shr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    private fun int16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
