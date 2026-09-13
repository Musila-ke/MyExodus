package com.example.myexodus.blocking.recovery

import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.dao.RecoveryStateDao
import com.example.myexodus.data.db.entity.RecoveryState
import javax.inject.Inject

/**
 * Room-backed recovery session store (blocking-core plan §6 "Recovery starts": both
 * deadlines + rule written in one transaction; §8.3 persistence). Friction duration comes
 * from the seeded [com.example.myexodus.data.db.entity.RecoveryPolicy].
 */
class RecoveryStateStore @Inject constructor(private val storeFactory: AppRulesStoreFactory) {
    private fun dao(): RecoveryStateDao =
        storeFactory.open()?.recoveryStateDao()
            ?: throw IllegalStateException("Rules store unavailable")

    suspend fun current(): RecoveryState? = storeFactory.open()?.recoveryStateDao()?.current()

    suspend fun startRecovery(ruleId: String, clock: RecoveryClock = SystemRecoveryClock): RecoveryState {
        val frictionSeconds = storeFactory.open()
            ?.recoveryPolicyDao()?.current()?.defaultFrictionSeconds ?: DEFAULT_FRICTION_SECONDS
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

    suspend fun recordOverride(clock: RecoveryClock = SystemRecoveryClock) {
        val current = dao().current() ?: return
        dao().upsert(current.copy(lastOverrideStartWallUtcMillis = clock.nowWallClockUtcMillis()))
    }

    /** Consumes the session. Every unlock path must call this: a spent session row left in
     *  the DB makes the next blocker launch for the same rule auto-unlock it (device-caught
     *  2026-09-13 — re-armed rule, blocker flashed, rule expired again with zero friction). */
    suspend fun clear() {
        dao().clear()
    }

    suspend fun overrideEligible(clock: RecoveryClock = SystemRecoveryClock): Boolean {
        val current = dao().current() ?: return true
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
    }
}
