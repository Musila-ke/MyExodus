package com.example.myexodus.accountability.delivery

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * §8.6 Phase 5 recipient-side delivery receipt: the `(recipientInstallationId, mailboxEventId)`
 * pair the plan names as the deduplication key. A receipt is written only after the document
 * reached a terminal state (delivered, undecryptable, or structurally skipped), so a crash
 * mid-page re-processes the unrecorded document and nothing is delivered twice.
 *
 * Only ids and a status are stored — never plaintext (I07: this is the plain-FBE database).
 */
@Entity(
    tableName = "partner_mailbox_delivery",
    primaryKeys = ["recipient_installation_id", "mailbox_event_id"],
)
data class PartnerMailboxDelivery(
    @ColumnInfo(name = "recipient_installation_id") val recipientInstallationId: String,
    @ColumnInfo(name = "mailbox_event_id") val mailboxEventId: String,
    @ColumnInfo(name = "status") val status: String,
) {
    companion object {
        const val STATUS_DELIVERED = "DELIVERED"

        /** The envelope cannot be opened (missing enrollment/key, bad tag, bad payload). */
        const val STATUS_UNDECRYPTABLE = "UNDECRYPTABLE"

        /** Structurally unusable on this build: unknown event kind or unsupported encoding. */
        const val STATUS_SKIPPED = "SKIPPED"
    }
}

/**
 * §8.6 mailbox watermark per recipient installation. The query is `createdAt >= watermark`
 * (ties re-read and dedupe through [PartnerMailboxDelivery]), so advancing only past documents
 * that reached a terminal state can never strand one behind the watermark.
 */
@Entity(tableName = "partner_mailbox_state")
data class PartnerMailboxState(
    @PrimaryKey @ColumnInfo(name = "installation_id") val installationId: String,
    @ColumnInfo(name = "watermark_created_at_ms") val watermarkCreatedAtMs: Long,
)
