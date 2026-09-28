package com.example.myexodus.domainusage

/**
 * §4.2 / INV-13: explicit buffer-ownership contract. The Engine must not return a pooled buffer
 * to [InspectionBufferPool] before the consumer of `Forward` has finished reading it; [release]
 * performs that return and is called by the Baseline exactly once, after the bytes have been
 * fully written or queued to the outbound socket.
 */
interface OwnedBytes {
    val bytes: ByteArray
    val length: Int
    fun release()
}
