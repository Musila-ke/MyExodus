package com.example.myexodus.accountability.sync

/**
 * Plan Step 9 (§8.12): the complete client-side contract for a push payload. A wake is a
 * generic synchronization trigger — never content — so the only decisions are "run the sync
 * pass, optionally handling this message first", "force a heartbeat", and "nothing to do".
 * The server's partner DMS notice uses the same `type` vocabulary and is rendered by the OS
 * from its `notification` block; the liveness probe is data-only and never shown.
 *
 * A missing `messageId` is a valid generic wake (§8.12's own example has none): the pass then
 * reconciles the whole mailbox. A present id must look like the Firestore document id the
 * relay/outbox paths generate — bounded `[A-Za-z0-9_-]` — before it rides into work input
 * data; anything else is ignored, never repaired.
 */
internal sealed interface PartnerPushWake {
    /** [messageId] is null for a whole-mailbox reconcile. */
    data class Sync(val messageId: String?) : PartnerPushWake

    /** Silent server wake for a missed heartbeat: force one check-in, nothing to show. */
    object LivenessProbe : PartnerPushWake

    /** Not ours, an unknown schema version, or a malformed id. */
    object Ignored : PartnerPushWake
}

internal object PartnerPushParser {

    const val KEY_VERSION = "v"
    const val KEY_TYPE = "type"
    const val KEY_MESSAGE_ID = "messageId"

    const val VERSION = "1"
    const val TYPE_SYNC_AVAILABLE = "SYNC_AVAILABLE"
    const val TYPE_LEGACY_DOMAIN_EVENT = "domain-event"
    const val TYPE_LIVENESS_PROBE = "LIVENESS_PROBE"

    /** §8.6 Phase 5: the partner notifier's data-only wake (its `eventId` key is content-free
     *  routing data; the mailbox pull reconciles by receipt watermark, so there is no
     *  expedited-id path to take here). */
    const val TYPE_PARTNER_EVENT = "PARTNER_EVENT"

    private val MESSAGE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

    fun parse(data: Map<String, String>): PartnerPushWake {
        // Absent is the pre-versioning relay payload; a different version is a schema this
        // build cannot interpret — ignoring it is safer than guessing.
        val version = data[KEY_VERSION]
        if (version != null && version != VERSION) return PartnerPushWake.Ignored

        return when (data[KEY_TYPE]) {
            TYPE_SYNC_AVAILABLE -> {
                val id = data[KEY_MESSAGE_ID]
                when {
                    id.isNullOrEmpty() -> PartnerPushWake.Sync(null)
                    MESSAGE_ID.matches(id) -> PartnerPushWake.Sync(id)
                    else -> PartnerPushWake.Ignored
                }
            }
            // Legacy relay type — still accepted so an old backend rollout never goes silent.
            TYPE_LEGACY_DOMAIN_EVENT -> {
                val id = data[KEY_MESSAGE_ID]
                if (id != null && MESSAGE_ID.matches(id)) PartnerPushWake.Sync(id) else PartnerPushWake.Ignored
            }
            TYPE_LIVENESS_PROBE -> PartnerPushWake.LivenessProbe
            TYPE_PARTNER_EVENT -> PartnerPushWake.Sync(null)
            else -> PartnerPushWake.Ignored
        }
    }
}
