package com.example.myexodus.accountability.delivery

/**
 * Plan §7 Phase 1 fake-transport seam and §8.4 upload boundary. Implementations throw on
 * whole-request failures (network, auth, App Check); the drain maps those to RETRYABLE and
 * must keep the exact signed bytes, because a thrown call may have committed server-side
 * (§8.2 immutable-after-possible-acceptance). Never mutate an envelope outside the two
 * documented regeneration paths in [PartnerEventResultCode].
 */
interface PartnerDeliveryTransport {
    suspend fun submit(batch: PartnerEventBatchV1): PartnerEventBatchResultV1
}
