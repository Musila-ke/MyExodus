package com.example.myexodus.accountability.delivery

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/** Phase 5 recipient mailbox receipts and watermark (see [PartnerMailboxDelivery]). */
@Dao
interface PartnerMailboxDao {

    @Query(
        "SELECT status FROM partner_mailbox_delivery " +
            "WHERE recipient_installation_id = :installationId AND mailbox_event_id = :messageId",
    )
    suspend fun receipt(installationId: String, messageId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDelivery(row: PartnerMailboxDelivery): Long

    @Query("SELECT watermark_created_at_ms FROM partner_mailbox_state WHERE installation_id = :installationId")
    suspend fun watermark(installationId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(row: PartnerMailboxState)
}
