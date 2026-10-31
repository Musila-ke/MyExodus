package com.example.myexodus.blocking

import com.example.myexodus.accountability.serialization.AccountabilityEventWire
import com.example.myexodus.accountability.serialization.RuleChange
import com.example.myexodus.accountability.serialization.RuleIdentity
import com.example.myexodus.accountability.serialization.RuleTargetType
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.dao.RestrictionRuleDao
import com.example.myexodus.data.db.entity.PartnerEventJournal
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import com.example.myexodus.data.db.entity.isExpiredAt
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Rejects a rule write past the snapshot caps (plan §8 Step 2); the UI maps [message]. */
class RulesetLimitException(message: String) : Exception(message)

/**
 * The single write path for restriction rules (blocking-core plan §6: "Room transaction
 * commits new rule + increments PolicyVersion"). Enforces the 2,000-app / 2,000-URL caps with
 * an explanatory message, bumps [com.example.myexodus.data.db.entity.PolicyVersion] in the
 * same transaction as the rule write (a failed write never advances the version), and
 * republishes the snapshot after every successful transaction. Reads for the engine go
 * through [RulesSnapshotPublisher] — this repository is used by write sites and the UI only.
 *
 * Staleness window (plan §6): writes synchronously reload the snapshot via
 * [RulesSnapshotPublisher.loadFromDb] before returning, so an engine observes the new
 * version immediately after the write completes — no background propagation delay.
 */
class RulesRepository(
    private val storeFactory: AppRulesStoreFactory,
    private val publisher: RulesSnapshotPublisher,
    /** A write journaled at least one partner-routed row — kicks an immediate partner-sync
     *  pass. Without it the change waits for the 15-minute worker, which an owner watching
     *  the partner's phone reads as "no notification" (owner report 2026-10-31). */
    private val onJournaled: () -> Unit = {},
    /** Human name for a target as THIS device sees it (app label, website host). The partner
     *  must be told what changed (owner mandate 2026-10-31); DI wires this to the app-label
     *  resolver, the default keeps tests and any unwired call site honest. */
    private val labelOf: (targetType: String, target: String) -> String = { _, target -> target },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun store() = storeFactory.open() ?: throw IllegalStateException("Rules store unavailable")

    private fun journaled(rows: List<PartnerEventJournal>) {
        if (rows.isEmpty()) return
        runCatching(onJournaled)
    }

    suspend fun insertRule(rule: RestrictionRule) {
        val db = store()
        val dao = db.restrictionRuleDao()
        requireCapacity(dao, rule.profileId, rule.targetType)
        val journal = journalRows(null, rule)
        dao.insertAndBump(rule, db.policyVersionDao(), db.partnerEventJournalDao(), journal)
        publisher.loadFromDb()
        journaled(journal)
    }

    suspend fun updateRule(rule: RestrictionRule) {
        val db = store()
        val old = db.restrictionRuleDao().getById(rule.ruleId)
        val journal = if (old == null) emptyList() else journalRows(old, rule)
        db.restrictionRuleDao().updateAndBump(rule, db.policyVersionDao(), db.partnerEventJournalDao(), journal)
        publisher.loadFromDb()
        journaled(journal)
    }

    suspend fun deleteRule(rule: RestrictionRule) {
        val db = store()
        val journal = journalRows(rule, null)
        db.restrictionRuleDao().deleteAndBump(
            rule, db.policyVersionDao(), db.partnerEventJournalDao(), journal,
        )
        publisher.loadFromDb()
        journaled(journal)
    }

    /** Pause/resume (Rules screen): same transaction path as any rule write, so the
     *  enforcement snapshot republishes immediately and the engine stops/starts deciding. */
    suspend fun setEnabled(rule: RestrictionRule, enabled: Boolean) =
        updateRule(rule.copy(isEnabled = enabled))

    /**
     * #18 (owner ruling 2026-10-04): a lapsed duration flips the rule's own toggle off, so the
     * switch tells the same truth enforcement already acts on (engine Rule 3 ignores expired
     * rules). Idempotent and best-effort at its call sites: if a write fails the engine is
     * still correct, the next screen visit sweeps again.
     */
    suspend fun sweepExpired(nowMillis: Long) {
        val active = getActiveRules()
        val due = active.filter { it.isEnabled && it.isExpiredAt(nowMillis) }
        if (due.isEmpty()) return
        val db = store()
        val dao = db.restrictionRuleDao()
        val versionDao = db.policyVersionDao()
        val journalDao = db.partnerEventJournalDao()
        val allRows = mutableListOf<PartnerEventJournal>()
        // A batch whose every live member is expiring is told once ("Social media (12 items)
        // ended"), not twelve times; a partly expiring batch still reports per member.
        val wholeBatches = mutableListOf<Pair<List<RestrictionRule>, List<PartnerEventJournal>>>()
        due.mapNotNull { it.batchId }.toSet().forEach { batchId ->
            val members = active.filter { it.batchId == batchId }
            val live = members.filter { it.isEnabled }
            if (live.isNotEmpty() && live.all { it.isExpiredAt(nowMillis) }) {
                val rows = batchJournalRows(members, RuleChange.EXPIRED)
                if (rows.isNotEmpty()) wholeBatches += members to rows
            }
        }
        val handled = wholeBatches.flatMap { (members, _) -> members.map { it.ruleId } }.toSet()
        wholeBatches.forEach { (members, rows) ->
            allRows += rows
            dao.updateBatchAndBump(
                members.filter { it.isEnabled }.map { it.copy(isEnabled = false) }, versionDao, journalDao, rows,
            )
        }
        // EXPIRED, not DISABLED: a set duration running out is not the owner switching the
        // block off, and the partner's copy says so (owner mandate 2026-10-31).
        due.filter { it.ruleId !in handled }.forEach { rule ->
            val rows = journalRows(rule, rule.copy(isEnabled = false), forcedChange = RuleChange.EXPIRED)
            allRows += rows
            dao.updateAndBump(rule.copy(isEnabled = false), versionDao, journalDao, rows)
        }
        publisher.loadFromDb()
        journaled(allRows)
    }

    /** #18 cleanup: delete a whole batch behind one snapshot reload — per-rule [deleteRule]
     *  would reload the enforcement snapshot once per rule. Each delete still bumps the policy
     *  version in its own transaction; the version only feeds change detection, so N bumps
     *  before the single reload are equivalent to one. */
    suspend fun deleteRules(rules: List<RestrictionRule>) {
        if (rules.isEmpty()) return
        val db = store()
        val versionDao = db.policyVersionDao()
        val journalDao = db.partnerEventJournalDao()
        val allRows = mutableListOf<PartnerEventJournal>()
        rules.forEach {
            val rows = journalRows(it, null)
            allRows += rows
            db.restrictionRuleDao().deleteAndBump(it, versionDao, journalDao, rows)
        }
        publisher.loadFromDb()
        journaled(allRows)
    }

    /**
     * Batch creation (owner mandate 2026-10-31): members and the ONE batch-level journal row
     * commit in a single transaction, so the partner learns about the batch as a whole and can
     * never see half of it. Caps are checked per target type before anything is written.
     */
    suspend fun insertBatch(rules: List<RestrictionRule>) {
        require(rules.isNotEmpty()) { "A batch needs at least one rule" }
        val batchId = rules.first().batchId ?: throw IllegalArgumentException("Batch rules need a batch_id")
        require(rules.all { it.batchId == batchId }) { "Batch members must share one batch_id" }
        val db = store()
        val dao = db.restrictionRuleDao()
        rules.groupingBy { it.targetType }.eachCount().forEach { (type, count) ->
            requireCapacity(dao, rules.first().profileId, type, adding = count.toLong())
        }
        val journal = batchJournalRows(rules, RuleChange.CREATED)
        dao.insertBatchAndBump(rules, db.policyVersionDao(), db.partnerEventJournalDao(), journal)
        publisher.loadFromDb()
        journaled(journal)
    }

    /** Batch pause/resume (Rules screen): one batch-level row ("Social media (12 items)
     *  disabled"), never twelve member rows. The row describes the whole batch, so the
     *  identity is built from every member even when only some still needed switching. */
    suspend fun setBatchEnabled(batchId: String, enabled: Boolean) {
        val db = store()
        val dao = db.restrictionRuleDao()
        val members = dao.getByBatch(batchId)
        val nowMs = now()
        val changed = members.filter { it.isEnabled != enabled }.map { member ->
            // Resume drops a lapsed end date (same #18 ruling as a single rule's toggle);
            // keeping it would leave the batch on-but-expired, and the sweep would flip it off.
            member.copy(
                isEnabled = enabled,
                blockExpiresAt = if (enabled && member.isExpiredAt(nowMs)) 0L else member.blockExpiresAt,
            )
        }
        if (changed.isEmpty()) return
        val journal = batchJournalRows(members, if (enabled) RuleChange.UPDATED else RuleChange.DISABLED)
        dao.updateBatchAndBump(changed, db.policyVersionDao(), db.partnerEventJournalDao(), journal)
        publisher.loadFromDb()
        journaled(journal)
    }

    /** Removes the batch's assigned partner from every member in one write plus one
     *  batch-level UNASSIGNED row (owner mandate 2026-10-31: removing the partner from the
     *  block notifies them). Identity comes from the pre-write members — the route needs the
     *  pair id the rows are being cleared from. */
    suspend fun removeBatchPartner(batchId: String) {
        val db = store()
        val dao = db.restrictionRuleDao()
        val members = dao.getByBatch(batchId)
        val changed = members.filter { it.assignedPairId != null }.map { it.copy(assignedPairId = null) }
        if (changed.isEmpty()) return
        val journal = batchJournalRows(members, RuleChange.UNASSIGNED)
        dao.updateBatchAndBump(changed, db.policyVersionDao(), db.partnerEventJournalDao(), journal)
        publisher.loadFromDb()
        journaled(journal)
    }

    /** Deletes every member with one batch-level DELETED row, so the partner sees the batch
     *  disappear as one event instead of N deletions. */
    suspend fun deleteBatch(batchId: String) {
        val db = store()
        val dao = db.restrictionRuleDao()
        val members = dao.getByBatch(batchId)
        if (members.isEmpty()) return
        val journal = batchJournalRows(members, RuleChange.DELETED)
        dao.deleteBatchAndBump(members, db.policyVersionDao(), db.partnerEventJournalDao(), journal)
        publisher.loadFromDb()
        journaled(journal)
    }

    /**
     * Rule-lifecycle journal rows, in the rule's own transaction (plan §2.1 req 3). §8.4 routes
     * them to the rule's assigned partner only; an unassigned rule has no partner to tell.
     * Rule ids on this store are always minted as UUIDs, and the closed wire accepts nothing
     * else — an unparseable legacy id simply has no wire identity.
     */
    private fun journalRows(
        old: RestrictionRule?,
        new: RestrictionRule?,
        /** Natural expiry overrides the same-rule verb (owner mandate 2026-10-31). */
        forcedChange: RuleChange? = null,
    ): List<PartnerEventJournal> {
        val from = old?.assignedPairId
        val to = new?.assignedPairId
        val atMs = now()
        val minute = AccountabilityEventWire.minuteOf(atMs)
        val rows = mutableListOf<PartnerEventJournal>()

        fun record(pairId: String, ruleId: String, change: RuleChange, rule: RestrictionRule) {
            // No wire identity (legacy id, pre-1970 clock) means nothing to journal — the
            // contract row would be rejected, and a broken clock must never block the write.
            if (minute < 1) return
            val uuid = runCatching { UUID.fromString(ruleId) }.getOrNull() ?: return
            rows += PartnerEventJournal.forRuleChange(
                eventId = AccountabilityEventWire.newEventId(),
                ruleId = uuid,
                change = change,
                routePairId = pairId,
                occurredAtMinute = minute,
                createdAtMs = atMs,
                identity = identityOf(rule),
            )
        }

        when {
            new == null -> if (old != null && from != null) record(from, old.ruleId, RuleChange.DELETED, old)
            old == null -> if (to != null) record(to, new.ruleId, RuleChange.CREATED, new)
            from != to -> {
                if (from != null) record(from, old.ruleId, RuleChange.UNASSIGNED, old)
                if (to != null) record(to, new.ruleId, RuleChange.ASSIGNED, new)
            }
            to != null && old != new -> record(
                to,
                new.ruleId,
                forcedChange ?: if (old.isEnabled && !new.isEnabled) RuleChange.DISABLED else RuleChange.UPDATED,
                new,
            )
        }
        return rows
    }

    /** What the partner is told changed: the app label / host this device shows, plus the
     *  batch name when the rule belongs to one. */
    private fun identityOf(rule: RestrictionRule): RuleIdentity = RuleIdentity.of(
        targetType = rule.targetType,
        target = rule.blockedPackage,
        label = labelOf(rule.targetType, rule.blockedPackage),
        batchName = rule.batchName,
    )

    /** One batch-level journal row per assigned pair — the partner sees "Social media
     *  (12 items)", not twelve rows. */
    private fun batchJournalRows(rules: List<RestrictionRule>, change: RuleChange): List<PartnerEventJournal> {
        val name = rules.firstNotNullOfOrNull { it.batchName?.takeIf(String::isNotBlank) } ?: return emptyList()
        val batchId = rules.firstNotNullOfOrNull { it.batchId } ?: return emptyList()
        val atMs = now()
        val minute = AccountabilityEventWire.minuteOf(atMs)
        if (minute < 1) return emptyList()
        val uuid = runCatching { UUID.fromString(batchId) }.getOrNull() ?: return emptyList()
        val identity = RuleIdentity(
            label = AccountabilityEventWire.sanitizeRuleLabel(name),
            targetType = RuleTargetType.BATCH,
            itemCount = rules.size.toLong(),
        )
        return rules.groupBy { it.assignedPairId }.mapNotNull { (pairId, members) ->
            if (pairId == null) return@mapNotNull null
            PartnerEventJournal.forRuleChange(
                eventId = AccountabilityEventWire.newEventId(),
                ruleId = uuid,
                change = change,
                routePairId = pairId,
                occurredAtMinute = minute,
                createdAtMs = atMs,
                identity = identity.copy(itemCount = members.size.toLong()),
            )
        }
    }

    /**
     * Every rule FK-references a profile (`profile_id` is NOT NULL), but V1 ships no
     * onboarding yet — so the first launch creates the single local profile. Idempotent:
     * safe to call on app start and again before any write.
     */
    suspend fun ensureActiveProfile(): UserProfile {
        val dao = store().userProfileDao()
        return dao.getActive() ?: LOCAL_PROFILE.also { dao.insert(it) }
    }

    /** §19.5: the self-chosen name the pairing ceremony exchanges as this device's label.
     *  Callers pass an already-sanitized value ([com.example.myexodus.accountability.enrollment.PartnerLabel]). */
    suspend fun updateProfileName(name: String) {
        val dao = store().userProfileDao()
        dao.update(ensureActiveProfile().copy(name = name))
    }

    suspend fun getActiveRules(): List<RestrictionRule> = store().restrictionRuleDao().getActiveRules()

    fun observeRules(profileId: String): Flow<List<RestrictionRule>> =
        store().restrictionRuleDao().observeByProfile(profileId)

    private suspend fun requireCapacity(
        dao: RestrictionRuleDao,
        profileId: String,
        targetType: String,
        adding: Long = 1,
    ) {
        val cap = capFor(targetType)
        if (dao.countByProfileAndType(profileId, targetType) + adding > cap) {
            throw RulesetLimitException(
                "The ${targetLabel(targetType)} rule limit of ${"%,d".format(cap)} rules has been reached. " +
                    "Delete an existing rule before adding a new one.",
            )
        }
    }

    private fun capFor(targetType: String): Int = when (targetType) {
        "APP" -> RulesSnapshot.MAX_APP_RULES
        "WEBSITE" -> RulesSnapshot.MAX_URL_RULES
        else -> throw IllegalArgumentException("Unknown target_type $targetType")
    }

    private fun targetLabel(targetType: String): String =
        if (targetType == "APP") "app" else "website"

    companion object {
        /** The placeholder for a device whose owner never chose a name — never sent as a
         *  pairing label; the ceremony shows an empty field until the owner types one. */
        const val DEFAULT_PROFILE_NAME = "You"

        /** Fixed id: a racing double-insert fails on the PK instead of creating two profiles. */
        private const val LOCAL_PROFILE_ID = "local"

        private val LOCAL_PROFILE = UserProfile(
            userId = LOCAL_PROFILE_ID,
            name = DEFAULT_PROFILE_NAME,
            email = "",
            phoneNumber = null,
            dateOfBirth = 0L,
            hasAccessibilityPermission = false,
            hasUsageStatsPermission = false,
            hasVpnPermission = false,
            hasNotificationPermission = false,
            hasDeviceAdminPermission = false,
            devicePublicKey = null,
            recoveryEscrowKey = null,
            isActive = true,
        )
    }
}
