package com.example.myexodus.blocking

/** Outcome of one poll scan (blocking-core plan §8.1): whether to launch BlockerActivity. */
sealed interface AppBlockDecision {
    data class Launch(val packageName: String, val ruleId: String, val severityLevel: String) : AppBlockDecision
    data object Nothing : AppBlockDecision
}

/**
 * Pure scan decision for the foreground-service poll path (blocking-core plan §8.1). The
 * service hands the latest ACTIVITY_RESUMED package to this; all inputs are passed in, so
 * the policy is unit-testable without Android. Fail-open on emergency-protected packages
 * (dialer, emergency surfaces) — the same exclusion the accessibility path uses.
 */
class AppBlockScanner(
    private val engine: BlockDecisionEngine,
    private val emergency: EmergencyExclusionManager,
) {
    fun evaluate(
        latestResumedPackage: String?,
        ourPackageName: String,
        snapshot: RulesSnapshot,
        nowEpochMillis: Long,
    ): AppBlockDecision {
        if (latestResumedPackage.isNullOrEmpty() || latestResumedPackage == ourPackageName) {
            return AppBlockDecision.Nothing
        }
        if (emergency.isProtected(latestResumedPackage, null)) {
            return AppBlockDecision.Nothing
        }
        val decision = engine.decideApp(latestResumedPackage, nowEpochMillis)
        if (decision is BlockDecision.Block) {
            val rule = snapshot.appRules[latestResumedPackage]
                ?: return AppBlockDecision.Nothing
            return AppBlockDecision.Launch(
                packageName = latestResumedPackage,
                ruleId = rule.ruleId,
                severityLevel = rule.severityLevel,
            )
        }
        return AppBlockDecision.Nothing
    }
}
