package com.example.myexodus.blocking.recovery

import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.dao.RecoveryStateDao
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoveryState
import javax.inject.Inject

/**
 * Room-backed recovery session store (blocking-core plan §6 "Recovery starts": both
 * deadlines + rule written in one transaction; §8.3 persistence). Friction duration comes
 * from the seeded [com.example.myexodus.data.db.entity.RecoveryPolicy]. One row per rule
 * (owner acceptance test 2026-11-02): starting a second rule's session never touches the
 * first, and each session's override/cooldown is its own.
 */
class RecoveryStateStore @Inject constructor(private val storeFactory: AppRulesStoreFactory) {
    private fun dao(): RecoveryStateDao =
        storeFactory.open()?.recoveryStateDao()
            ?: throw IllegalStateException("Rules store unavailable")

    /** Every persisted session, one per rule; the UI filters the ones that are still live. */
    suspend fun sessions(): List<RecoveryState> =
        storeFactory.open()?.recoveryStateDao()?.all().orEmpty()

    /** The user's current friction length (Settings → Friction timer), in seconds. */
    suspend fun frictionSeconds(): Long =
        storeFactory.open()?.recoveryPolicyDao()?.current()?.defaultFrictionSeconds
            ?: DEFAULT_FRICTION_SECONDS

    /** Applies to the next session; a countdown already running keeps the length it started with. */
    suspend fun setFrictionSeconds(seconds: Long) {
        val dao = storeFactory.open()?.recoveryPolicyDao()
            ?: throw IllegalStateException("Rules store unavailable")
        val cooldown = dao.current()?.overrideCooldownSeconds ?: DEFAULT_OVERRIDE_COOLDOWN_SECONDS
        dao.put(
            RecoveryPolicy(
                defaultFrictionSeconds = seconds.coerceIn(MIN_FRICTION_SECONDS, MAX_FRICTION_SECONDS),
                overrideCooldownSeconds = cooldown,
            ),
        )
    }

    suspend fun startRecovery(ruleId: String, clock: RecoveryClock = SystemRecoveryClock): RecoveryState {
        val frictionSeconds = frictionSeconds()
        val nowWall = clock.nowWallClockUtcMillis()
        val nowElapsed = clock.nowElapsedRealtimeMillis()
        val state = RecoveryState(
            ruleId = ruleId,
            deadlineWallClockUtcMillis = nowWall + frictionSeconds * 1000,
            deadlineElapsedRealtimeThisBoot = nowElapsed + frictionSeconds * 1000,
            bootSessionId = nowElapsed,
        )
        dao().upsert(state)
        return state
    }

    suspend fun recordOverride(ruleId: String, clock: RecoveryClock = SystemRecoveryClock) {
        val current = dao().forRule(ruleId) ?: return
        dao().upsert(current.copy(lastOverrideStartWallUtcMillis = clock.nowWallClockUtcMillis()))
    }

    /** Consumes one rule's session. Every unlock path must call this: a spent session row
     *  left in the DB makes the next blocker launch for the same rule auto-unlock it
     *  (device-caught 2026-09-13 — re-armed rule, blocker flashed, rule expired again with
     *  zero friction). Sibling rules' sessions are untouched. */
    suspend fun clear(ruleId: String) {
        dao().delete(ruleId)
    }

    suspend fun overrideEligible(ruleId: String, clock: RecoveryClock = SystemRecoveryClock): Boolean {
        val current = dao().forRule(ruleId) ?: return true
        val cooldown = storeFactory.open()?.recoveryPolicyDao()?.current()?.overrideCooldownSeconds
            ?: DEFAULT_OVERRIDE_COOLDOWN_SECONDS
        return RecoveryTimer.overrideEligible(
            current.lastOverrideStartWallUtcMillis,
            cooldown,
            clock.nowWallClockUtcMillis(),
        )
    }

    companion object {
        const val DEFAULT_FRICTION_SECONDS = 60L
        const val DEFAULT_OVERRIDE_COOLDOWN_SECONDS = 600L
        /** Owner spec 2026-11-01: "you can say one minute... you can say 24 hours". */
        const val MIN_FRICTION_SECONDS = 60L
        const val MAX_FRICTION_SECONDS = 24L * 60 * 60
    }
}
