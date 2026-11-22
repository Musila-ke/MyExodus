package com.example.myexodus.accountability.delivery

import com.example.myexodus.data.db.VaultLockedException
import kotlinx.coroutines.CancellationException

/**
 * §8.6 Phase 5 recipient half: retrieve and open this installation's partner mailbox. Retrieval
 * is FCM-independent by construction — the same [pull] runs from the periodic pass, the push
 * wake's sync work, and the foreground refresh, so a device that never received a push (denied
 * notifications, no token, killed app) still converges on the next pass.
 *
 * Ordering is `createdAt` ascending with a `>=` watermark; documents at the watermark re-read
 * and are ignored through [PartnerMailboxDao.receipt]. A document reaches a terminal receipt
 * (delivered / undecryptable / skipped) before the watermark advances past it, so a crash can
 * only cause a re-process, never a skip. An exception from [onDelivered] propagates and stops
 * the page — that document has no receipt and is retried next pass.
 *
 * [VaultLockedException] is the one exception that does not stop the page: the projections
 * post their notification from background-accessible state BEFORE they need the vault (plan §4
 * line 87 — App Lock must not silence the tray), so a locked vault writes a PENDING receipt,
 * holds the watermark (a pending document must stay re-readable), and the next pass retries the
 * projection with `notify = false`.
 *
 * A document that cannot be opened is terminal rather than retried forever: a missing
 * enrollment, a shredded pair key, or a foreign envelope will not become readable on this
 * device, and retrying it would stall every later document behind the watermark (AC07 "mark
 * as not decryptable").
 */
class PartnerMailboxReceiver(
    private val dao: PartnerMailboxDao,
    private val ownerUid: () -> String?,
    /** The owner-bound service installation id — the id the mailbox is addressed by. */
    private val recipientInstallationId: suspend (uid: String) -> String?,
    private val queryMailbox: suspend (
        uid: String,
        installationId: String,
        createdAtFromMs: Long,
        limit: Int,
    ) -> List<PartnerMailboxDocument>,
    /** Null when the envelope cannot be opened on this device (see the class note). */
    private val decrypt: suspend (PartnerMailboxDocument) -> ByteArray?,
    /** Phase 7 consumer seam: projects a delivered event into local state. `notify = false`
     *  means the notification already posted on an earlier, vault-locked pass. */
    private val onDelivered: suspend (PartnerMailboxDocument, ByteArray, notify: Boolean) -> Unit =
        { _, _, _ -> },
    private val limit: Int = 200,
) {

    data class PullResult(
        val delivered: Int = 0,
        val duplicates: Int = 0,
        val undecryptable: Int = 0,
        val skipped: Int = 0,
        /** Vault-locked projections deferred to a later pass; their notifications already
         *  posted, so the user never waits on the App Lock unlock for the alert. */
        val deferred: Int = 0,
    )

    suspend fun pull(): PullResult {
        val uid = ownerUid() ?: return PullResult()
        val installationId = recipientInstallationId(uid) ?: return PullResult()
        var watermark = dao.watermark(installationId) ?: 0L
        val page = queryMailbox(uid, installationId, watermark, limit)
        var result = PullResult()
        var deferred = false
        for (document in page) {
            val previous = dao.receipt(installationId, document.messageId)
            if (previous != null && previous != PartnerMailboxDelivery.STATUS_PENDING) {
                result = result.copy(duplicates = result.duplicates + 1)
            } else {
                val status = try {
                    receiptStatus(document, notify = previous == null)
                } catch (e: VaultLockedException) {
                    if (previous == null) {
                        dao.insertDelivery(
                            PartnerMailboxDelivery(
                                installationId,
                                document.messageId,
                                PartnerMailboxDelivery.STATUS_PENDING,
                            ),
                        )
                    }
                    deferred = true
                    result = result.copy(deferred = result.deferred + 1)
                    continue
                }
                if (previous == null) {
                    dao.insertDelivery(
                        PartnerMailboxDelivery(installationId, document.messageId, status),
                    )
                } else {
                    dao.updateDeliveryStatus(installationId, document.messageId, status)
                }
                result = when (status) {
                    PartnerMailboxDelivery.STATUS_DELIVERED ->
                        result.copy(delivered = result.delivered + 1)
                    PartnerMailboxDelivery.STATUS_UNDECRYPTABLE ->
                        result.copy(undecryptable = result.undecryptable + 1)
                    else -> result.copy(skipped = result.skipped + 1)
                }
            }
            // A pending document keeps the watermark below its `createdAt` so the next pass
            // re-reads and retries it — advancing past it would strand it forever.
            if (!deferred && document.createdAtMs > watermark) {
                watermark = document.createdAtMs
                dao.upsertState(PartnerMailboxState(installationId, watermark))
            }
        }
        return result
    }

    /** Throws [VaultLockedException] when the projection deferred, or any other exception
     *  [onDelivered] does — a failed projection must keep its document retryable. */
    private suspend fun receiptStatus(document: PartnerMailboxDocument, notify: Boolean): String {
        val kind = PartnerEventKind.fromWire(document.kind ?: "")
        val usable = document.encoding == PartnerEventProtocol.ENCODING_EXISTING_E2EE &&
            kind != null &&
            document.eventId != null &&
            document.senderInstallationId != null &&
            document.senderOwnerUid != null &&
            document.recipientKeyId != null &&
            document.payload != null &&
            document.pairId != null
        if (!usable) return PartnerMailboxDelivery.STATUS_SKIPPED
        val plaintext = try {
            decrypt(document)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return PartnerMailboxDelivery.STATUS_UNDECRYPTABLE
        onDelivered(document, plaintext, notify)
        return PartnerMailboxDelivery.STATUS_DELIVERED
    }
}

/** One `partnerMailboxes/{uid}/messages` document; raw strings exactly as Firestore stores
 *  them. Every field except the id and ordering key is nullable so a malformed document is
 *  classified (skipped) instead of breaking the page. */
data class PartnerMailboxDocument(
    val messageId: String,
    val eventId: String?,
    val senderInstallationId: String?,
    val senderOwnerUid: String?,
    val kind: String?,
    val recipientKeyId: String?,
    val payload: String?,
    val encoding: String?,
    val pairId: String?,
    val createdAtMs: Long,
    /** The sender's staged outbox sequence (`partner-ingest.js` writes `sequence`). Null for
     *  a document from a backend that predates the field. */
    val senderSequence: Long? = null,
    /** The sender's device occurrence instant (`deviceOccurredAtMs`), already the wire's time
     *  for a deferred block. Null for a document from a backend that predates the field —
     *  callers fall back to [createdAtMs]. */
    val deviceOccurredAtMs: Long? = null,
)
