package com.example.myexodus.accountability.enrollment

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * A completed enrollment: the partner's identity material needed for FR4 (signature
 * verification of their events) and CEK re-derivation after restart. All fields are public
 * data — the CEK itself is never stored (§5).
 */
@Entity(tableName = "enrollments")
data class EnrollmentRecord(
    @PrimaryKey @ColumnInfo(name = "pair_id") val pairId: String,
    /** Hex SHA-256 of this device's signing public key (matches [com.example.myexodus.accountability.serialization.UnsignedEnvelope.senderKeyId]). */
    @ColumnInfo(name = "my_sender_key_id") val mySenderKeyId: String,
    /** Hex SHA-256 of the partner's signing public key. */
    @ColumnInfo(name = "partner_signing_key_id") val partnerSigningKeyId: String,
    /** DER SubjectPublicKeyInfo — partner's attested signing key (FR4). */
    @ColumnInfo(name = "partner_signing_pubkey") val partnerSigningPubKey: ByteArray,
    /** DER SubjectPublicKeyInfo — partner's agreement key (CEK re-derivation). */
    @ColumnInfo(name = "partner_agreement_pubkey") val partnerAgreementPubKey: ByteArray,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    /**
     * Firestore server-timestamp watermark of the last mailbox pull (§7.5 quota fix): the
     * next pull starts after it instead of re-reading the whole mailbox. Null = never pulled.
     */
    @ColumnInfo(name = "mailbox_watermark_ts") val mailboxWatermarkTsEpochMillis: Long? = null,
    /**
     * AD-07: the partnership epoch this enrollment was created under (server-assigned by
     * registerPairing, read from the pair doc). Disable-approval responses are rejected when
     * their carried epoch differs — a response minted before a re-pair can never apply after it.
     * 0 = pre-v7 enrollment (treated as epoch 0, matching a server doc that never re-paired).
     */
    @ColumnInfo(name = "pairing_epoch") val pairingEpoch: Int = 0,
)
