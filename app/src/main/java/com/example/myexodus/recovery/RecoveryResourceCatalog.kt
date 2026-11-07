package com.example.myexodus.recovery

/** How much focus a technique asks for — the ordering key for Req 19 (low willpower → low effort). */
enum class EffortTier { LOW, MEDIUM, HIGH }

/**
 * One technique in the offline catalog (v2 FR-4). The catalog is the single source of truth for
 * its pacing numbers — the pacer's animation and its timer read the same fields, so they cannot
 * drift. All user-visible copy lives in `strings.xml`, keyed by [id] / phase id; no efficacy
 * claims anywhere (v2 M1.5).
 */
data class RecoveryTechnique(
    val id: String,
    val effort: EffortTier,
    val breathInSeconds: Int? = null,
    val breathOutSeconds: Int? = null,
    val promptRotationSeconds: Int = 30,
    val durationChoicesMinutes: List<Int> = emptyList(),
    val defaultDurationMinutes: Int? = null,
    /** Ordered phase ids (SOBER's five screens); copy lives in strings. */
    val phases: List<String> = emptyList(),
    /** Renders the user's If-Then plan items while the technique runs (FR-4). */
    val showsIfThen: Boolean = false,
    /** Renders the user's Value statements, rotating (FR-4). */
    val showsValues: Boolean = false,
)

/**
 * The four V1 techniques. Contents are the shape v2 §8 M1.5 settled; nothing here needs a
 * network, a DB, or a clock — Req 19's offline edge case is satisfied by construction.
 */
object RecoveryResourceCatalog {

    const val URGE_SURFING = "urge_surfing"
    const val SOBER = "sober"
    const val DELAY_TIMER = "delay_timer"
    const val VALUES_CARD = "values_card"
    const val GROUNDING = "grounding_5_4_3_2_1"
    const val DEFUSION = "defusion"

    const val SOBER_STOP = "stop"
    const val SOBER_OBSERVE = "observe"
    const val SOBER_BREATHE = "breathe"
    const val SOBER_EXPAND = "expand"
    const val SOBER_RESPOND = "respond"

    const val GROUNDING_SEE = "grounding_see"
    const val GROUNDING_HEAR = "grounding_hear"
    const val GROUNDING_FEEL = "grounding_feel"
    const val GROUNDING_SMELL = "grounding_smell"
    const val GROUNDING_TASTE = "grounding_taste"

    const val DEFUSION_NOTICE = "defusion_notice"
    const val DEFUSION_NAME = "defusion_name"
    const val DEFUSION_RELEASE = "defusion_release"

    val all: List<RecoveryTechnique> = listOf(
        RecoveryTechnique(
            id = URGE_SURFING,
            effort = EffortTier.LOW,
            breathInSeconds = 4,
            breathOutSeconds = 6,
            durationChoicesMinutes = listOf(3, 5, 10),
            defaultDurationMinutes = 5,
        ),
        RecoveryTechnique(
            id = DELAY_TIMER,
            effort = EffortTier.LOW,
            durationChoicesMinutes = listOf(10),
            defaultDurationMinutes = 10,
            showsIfThen = true,
        ),
        RecoveryTechnique(
            id = SOBER,
            effort = EffortTier.MEDIUM,
            phases = listOf(SOBER_STOP, SOBER_OBSERVE, SOBER_BREATHE, SOBER_EXPAND, SOBER_RESPOND),
            showsIfThen = true,
        ),
        RecoveryTechnique(
            id = VALUES_CARD,
            effort = EffortTier.HIGH,
            showsValues = true,
        ),
        RecoveryTechnique(
            id = GROUNDING,
            effort = EffortTier.LOW,
            phases = listOf(
                GROUNDING_SEE, GROUNDING_HEAR, GROUNDING_FEEL, GROUNDING_SMELL, GROUNDING_TASTE,
            ),
        ),
        RecoveryTechnique(
            id = DEFUSION,
            effort = EffortTier.LOW,
            phases = listOf(DEFUSION_NOTICE, DEFUSION_NAME, DEFUSION_RELEASE),
        ),
    )

    fun byId(id: String): RecoveryTechnique? = all.firstOrNull { it.id == id }

    /**
     * Req 19: inside a risk window the low-effort grounding options lead (3 AM → paced breathing
     * before reflective work); outside one the catalog's own order stands. Stable within a tier —
     * `sortedBy` is stable, so the catalog order is the tie-break.
     */
    fun ordered(inRiskWindow: Boolean): List<RecoveryTechnique> =
        if (inRiskWindow) all.sortedBy { it.effort.ordinal } else all

    /**
     * The one-tap suggestion (v2 M1.3): a rated-strong urge or a risk window goes to paced
     * breathing; otherwise the guided walkthrough is the default. "Choose another" always exists —
     * this only decides what leads.
     */
    fun recommended(rating: Int?, inRiskWindow: Boolean): RecoveryTechnique {
        val id = if ((rating != null && rating >= URGE_SURFING_RATING) || inRiskWindow) {
            URGE_SURFING
        } else {
            SOBER
        }
        return requireNotNull(byId(id))
    }

    const val URGE_SURFING_RATING = 7
}
