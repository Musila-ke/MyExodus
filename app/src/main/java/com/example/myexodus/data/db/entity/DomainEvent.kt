package com.example.myexodus.data.db.entity

/**
 * Row model for the encrypted activity log (was a Room entity pre-split; the log moved to
 * the raw SQLCipher [com.example.myexodus.data.db.ActivityLogStore], spec §8 Step 1).
 * event_id + installation_id form the sync idempotency key (V1 Req 27).
 */
data class DomainEvent(
    val eventId: String,
    val installationId: String,
    val eventType: String,
    val payload: ByteArray,
    val syncStatus: String,
    val createdAt: Long,
)
