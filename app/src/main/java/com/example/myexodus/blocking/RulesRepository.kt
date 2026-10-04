package com.example.myexodus.blocking

import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.RestrictionRule
import com.example.myexodus.data.db.entity.UserProfile
import com.example.myexodus.data.db.entity.isExpiredAt
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
) {
    private fun store() = storeFactory.open() ?: throw IllegalStateException("Rules store unavailable")

    suspend fun insertRule(rule: RestrictionRule) {
        val db = store()
        val dao = db.restrictionRuleDao()
        val cap = when (rule.targetType) {
            "APP" -> RulesSnapshot.MAX_APP_RULES
            "WEBSITE" -> RulesSnapshot.MAX_URL_RULES
            else -> throw IllegalArgumentException("Unknown target_type ${rule.targetType}")
        }
        if (dao.countByProfileAndType(rule.profileId, rule.targetType) >= cap) {
            throw RulesetLimitException(
                "The ${targetLabel(rule.targetType)} rule limit of ${"%,d".format(cap)} rules has been reached. " +
                    "Delete an existing rule before adding a new one.",
            )
        }
        dao.insertAndBump(rule, db.policyVersionDao())
        publisher.loadFromDb()
    }

    suspend fun updateRule(rule: RestrictionRule) {
        val db = store()
        db.restrictionRuleDao().updateAndBump(rule, db.policyVersionDao())
        publisher.loadFromDb()
    }

    suspend fun deleteRule(rule: RestrictionRule) {
        val db = store()
        db.restrictionRuleDao().deleteAndBump(rule, db.policyVersionDao())
        publisher.loadFromDb()
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
        getActiveRules()
            .filter { it.isEnabled && it.isExpiredAt(nowMillis) }
            .forEach { updateRule(it.copy(isEnabled = false)) }
    }

    /** #18 cleanup: delete a whole batch behind one snapshot reload — per-rule [deleteRule]
     *  would reload the enforcement snapshot once per rule. Each delete still bumps the policy
     *  version in its own transaction; the version only feeds change detection, so N bumps
     *  before the single reload are equivalent to one. */
    suspend fun deleteRules(rules: List<RestrictionRule>) {
        if (rules.isEmpty()) return
        val db = store()
        val versionDao = db.policyVersionDao()
        rules.forEach { db.restrictionRuleDao().deleteAndBump(it, versionDao) }
        publisher.loadFromDb()
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
