package com.example.myexodus.accountability.sync

/**
 * Plan Step 9 (§8.12): the complete client-side contract for a push payload. A wake is a
 * generic synchronization trigger — never content — so the only decisions are "run the sync
 * pass, optionally handling this message first" and "nothing to do". The server's own DMS
 * notice and check-in reminder use the same `type` vocabulary; a visible one is rendered by
 * the OS from the `notification` block, and its data half still triggers the in-app sync that
 * keeps status correct when the notification itself is muted or blocked.
 *
 * A missing `messageId` is a valid generic wake (§8.12's own example has none): the pass then
 * reconciles the whole mailbox. A present id must look like the Firestore document id the
 * relay/outbox paths generate — bounded `[A-Za-z0-9_-]` — before it rides into work input
 * data; anything else is ignored, never repaired.
 */
internal sealed interface PartnerPushWake {
    /** [messageId] is null for a whole-mailbox reconcile. */
    data class Sync(val messageId: String?) : PartnerPushWake

    /** The OS already showed the server's generic reminder; this device has nothing to pull. */
    object CheckinReminder : PartnerPushWake

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
    const val TYPE_CHECKIN_REMINDER = "CHECKIN_REMINDER"

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
            TYPE_CHECKIN_REMINDER -> PartnerPushWake.CheckinReminder
            else -> PartnerPushWake.Ignored
        }
    }
}
