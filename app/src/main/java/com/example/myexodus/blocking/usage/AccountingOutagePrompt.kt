package com.example.myexodus.blocking.usage

/**
 * R8/§8.7's two routes for the exemption fix. Both carry the same offer — explain a detected
 * tracking interruption, then offer the battery-optimization remedy — and differ only in which
 * platform affordance the fix can use.
 */
enum class BatteryPromptRoute {
    /** `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — permitted for this distribution channel. */
    DIRECT_REQUEST,

    /** The exemption list only: the user still makes the change there (the standing §5 rule). */
    SETTINGS_GUIDANCE,
}

/**
 * D1's honest outage report as the three surfaces (notification, Home, Settings) show it: how
 * many corroborated interruptions were measured inside the freshness window, the largest one,
 * and [promptRoute] — non-null only while the exemption offer may accompany the report.
 *
 * #13 adds [trackingResumed]: once the fix flow was fired and the exemption is on — with no
 * evidence newer than the fix — the report is replaced by a one-time confirmation the user can
 * acknowledge, instead of the alert silently outliving the problem. Never both states at once.
 */
data class AccountingOutage(
    val summary: AccountingDiagnostics.OutageSummary,
    val promptRoute: BatteryPromptRoute?,
    /** True = the "tracking can run again" confirmation, not an interruption report. */
    val trackingResumed: Boolean = false,
)

/**
 * #13: which of the three outage surfaces' states the evidence calls for, as one pure decision
 * over the persisted consequence keys. Precedence:
 *
 *  1. [Stage.CONFIRMED] — the fix flow was fired, the exemption stands, and nothing newer than
 *     the fix has been measured: the interruption is addressed and is acknowledged once (also
 *     dismissible — that is what the dismissal wall clock compares against).
 *  2. [Stage.REPORT] — measured evidence the user has not dismissed (newest measured
 *     interruption after the dismissal). Evidence newer than the fix lands here too: the fix
 *     did not hold, so the report returns rather than a false confirmation.
 *  3. [Stage.NONE] — nothing fresh, or everything fresh has been dismissed.
 */
object OutageStage {
    enum class Stage { NONE, REPORT, CONFIRMED }

    fun stage(
        newestMeasuredOutageAtMs: Long,
        batteryExempt: Boolean,
        lastFixFiredAtMs: Long,
        dismissedAtMs: Long,
        nowWallClockMs: Long,
        freshnessMs: Long = AccountingDiagnosticsState.ELIGIBILITY_FRESHNESS_MS,
    ): Stage = when {
        batteryExempt && lastFixFiredAtMs != 0L &&
            nowWallClockMs - lastFixFiredAtMs <= freshnessMs &&
            lastFixFiredAtMs > dismissedAtMs &&
            lastFixFiredAtMs >= newestMeasuredOutageAtMs -> Stage.CONFIRMED
        newestMeasuredOutageAtMs > dismissedAtMs -> Stage.REPORT
        else -> Stage.NONE
    }
}

/**
 * §8.7's battery-prompt chain as one pure decision: not exempt already → prompt-eligible gap
 * present (which itself already required the escalation threshold, an unchanged boot count, no
 * platform-recoverable activity, and clear prompt/decline cooldowns) → the fix's route.
 * Null = do not offer, which is also the first-launch answer: nothing is eligible until a
 * corroborated interruption has actually been recorded. AD-13: nothing here names a cause.
 *
 * [DIRECT_REQUEST_PERMITTED] answers the plan's own open question (§8.7/R8: "is the direct
 * exemption intent intended for every distribution channel this app ships on?"). This app
 * ships as a direct APK with no Play listing, and Play's acceptable-use table is the only
 * policy gating the API — so the intent is permitted for every channel this app has, and an
 * ineligible channel would need a new product decision, not a silent default. The runtime
 * check stays the real gate: the call site falls back to [BatteryPromptRoute.SETTINGS_GUIDANCE]
 * when the intent does not resolve on a given build.
 */
object BatteryPromptGate {
    const val DIRECT_REQUEST_PERMITTED = true

    fun route(
        alreadyExempt: Boolean,
        promptEligible: Boolean,
        directRequestPermitted: Boolean = DIRECT_REQUEST_PERMITTED,
    ): BatteryPromptRoute? = when {
        alreadyExempt || !promptEligible -> null
        directRequestPermitted -> BatteryPromptRoute.DIRECT_REQUEST
        else -> BatteryPromptRoute.SETTINGS_GUIDANCE
    }
}
