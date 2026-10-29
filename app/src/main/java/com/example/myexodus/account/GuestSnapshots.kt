package com.example.myexodus.account

import android.util.Log
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.entity.AttemptLog
import com.example.myexodus.data.db.entity.CoachingStep
import com.example.myexodus.data.db.entity.HabitProgress
import com.example.myexodus.data.db.entity.MilestoneEvent
import com.example.myexodus.data.db.entity.RecoveryPolicy
import com.example.myexodus.data.db.entity.RecoverySession
import com.example.myexodus.data.db.entity.RecoveryStreak
import com.example.myexodus.data.db.entity.RiskWindow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the import planner needs to know about the target database. Only rows the current
 * profile owns are included; attempts, sessions and risk windows are device-global tables.
 */
data class TargetSnapshot(
    val profileId: String,
    val habits: List<HabitProgress>,
    val streaks: List<RecoveryStreak>,
    val milestones: List<MilestoneEvent>,
    val attempts: List<AttemptLog>,
    val sessions: List<RecoverySession>,
    val coaching: List<CoachingStep>,
    val riskWindows: List<RiskWindow>,
    val policy: RecoveryPolicy?,
)

/**
 * Account plan §6.3.1: reads the source without mutating it. Called once, while the guest
 * session still owns the database (the switch relaunches the process, after which the guest
 * file is no longer the active scope).
 */
suspend fun readGuestSnapshot(db: AppRulesStore): GuestSnapshot {
    val profileId = db.userProfileDao().getActive()?.userId
    val habits = if (profileId != null) db.habitProgressDao().getForProfile(profileId) else emptyList()
    val habitIds = habits.map { it.progressId }.toSet()
    val streaks = db.recoveryStreakDao().getAll().filter { it.habitId in habitIds }
    val streakIds = streaks.map { it.streakId }.toSet()
    return GuestSnapshot(
        habits = habits,
        streaks = streaks,
        milestones = db.milestoneEventDao().getAll().filter { it.streakId in streakIds },
        attempts = db.attemptLogDao().getAll(),
        sessions = db.recoverySessionDao().getAll(),
        coaching = if (profileId != null) db.coachingStepDao().getForProfile(profileId) else emptyList(),
        riskWindows = db.riskWindowDao().all(),
        policy = db.recoveryPolicyDao().current(),
    )
}

suspend fun readTargetSnapshot(db: AppRulesStore, profileId: String): TargetSnapshot {
    val habits = db.habitProgressDao().getForProfile(profileId)
    val habitIds = habits.map { it.progressId }.toSet()
    val streaks = db.recoveryStreakDao().getAll().filter { it.habitId in habitIds }
    val streakIds = streaks.map { it.streakId }.toSet()
    return TargetSnapshot(
        profileId = profileId,
        habits = habits,
        streaks = streaks,
        milestones = db.milestoneEventDao().getAll().filter { it.streakId in streakIds },
        attempts = db.attemptLogDao().getAll(),
        sessions = db.recoverySessionDao().getAll(),
        coaching = db.coachingStepDao().getForProfile(profileId),
        riskWindows = db.riskWindowDao().all(),
        policy = db.recoveryPolicyDao().current(),
    )
}

/**
 * Account plan §6.2.3: an encrypted local copy exists before the user signs in to the target,
 * for both intents — discard included. Returning false refuses the switch; the coordinator
 * never prepares a transition whose guest data could not be secured.
 */
@Singleton
class GuestQuarantineStager @Inject constructor(
    private val storeFactory: AppRulesStoreFactory,
    private val quarantine: GuestQuarantine,
    private val clock: () -> Long = System::currentTimeMillis,
) : GuestDataStager {

    override suspend fun stage(
        guestUid: String,
        transitionId: String,
        intent: TransitionIntent,
    ): Boolean {
        val db = storeFactory.open() ?: return false
        return try {
            val snapshot = readGuestSnapshot(db)
            val prior = (quarantine.load(guestUid) as? QuarantineLoad.Present)?.record
                ?.takeIf { it.transitionId == transitionId }
            quarantine.save(
                GuestQuarantineRecord(
                    transitionId = transitionId,
                    guestUid = guestUid,
                    importOperationId = prior?.importOperationId ?: UUID.randomUUID().toString(),
                    intent = intent,
                    stagedAtMs = prior?.stagedAtMs ?: clock(),
                    snapshot = snapshot,
                ),
            )
        } catch (e: Exception) {
            // Never log data or keys — the class name is the whole diagnostic.
            Log.w(TAG, "guest quarantine staging failed (${e.javaClass.simpleName})")
            false
        }
    }

    private companion object {
        const val TAG = "GuestQuarantineStager"
    }
}
