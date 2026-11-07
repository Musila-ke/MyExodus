package com.example.myexodus.recovery

/**
 * One in-the-moment option the Urge Assist flow can offer (plan §F1). Content is code-defined
 * and offline: no network, no database, no clock. User-visible copy lives in
 * `strings_recovery.xml`, keyed by [id].
 *
 * [behaviors] empty means the action applies regardless of the selected category. A non-empty
 * set makes it category-only; with no category chosen ("Not sure") those actions are not offered.
 */
data class UrgeAction(
    val id: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val effort: EffortTier,
    val modality: UrgeModality,
    val runner: UrgeRunner,
    val behaviors: Set<UrgeCategory> = emptySet(),
    val techniqueId: String? = null,
    val requiresMovement: Boolean = false,
    val requiresPartner: Boolean = false,
    val requiresAudio: Boolean = false,
    /** The step runner shows the user's if-then plan items inline. */
    val showsPlanItems: Boolean = false,
    /** Directory filter the link runner should open with. */
    val directoryFilter: String? = null,
)

enum class UrgeModality { PHYSICAL, ENVIRONMENT, SOCIAL, COGNITIVE, BREATHING, TIMER, ACCESS, ESCALATION, SENSORY }

enum class UrgeRunner { TECHNIQUE, STEP, MESSAGE, LINK, LOG_ONLY }

/**
 * The optional category chips (plan §E2.2). "Not sure" is the absence of a category, not an
 * entry: nothing is inferred, and a null category never filters anything out.
 */
enum class UrgeCategory(val id: String) {
    PORN("porn"),
    GAMBLING("gambling"),
    SCROLLING("scrolling"),
    SHOPPING("shopping"),
    GAMING("gaming"),
    OTHER("other"),
    ;

    companion object {
        fun fromId(id: String?): UrgeCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Everything the engine may consider. All fields are either explicit user choices, stored
 * preferences, or facts about the install (pairing state). Time never enters as an emotional
 * signal: [lateNight] only changes ordering and can add device-separation actions.
 */
data class UrgeSelectionInput(
    val category: UrgeCategory? = null,
    val lateNight: Boolean = false,
    val inRiskWindow: Boolean = false,
    /** null = not answered; unknown is never treated as "cannot move". */
    val canMove: Boolean? = null,
    val quietPreferred: Boolean = false,
    val hasPartner: Boolean = false,
    /** Only meaningful with [category] = GAMBLING; asked once, never inferred. */
    val inTransaction: Boolean = false,
    val immediateDanger: Boolean = false,
    val dislikedActions: Set<String> = emptySet(),
    /** Actions already tried this episode. A "worse" result adds one here. */
    val excludedActions: Set<String> = emptySet(),
    val helpfulActions: Set<String> = emptySet(),
    val lastModality: UrgeModality? = null,
)

/** Resolves to one short, data-free line of copy (plan §F3 step 7). */
enum class UrgeReason { SAFETY, GAMBLING_TRANSACTION, HELPFUL, LATE_NIGHT, RISK_WINDOW, CATEGORY, GENERIC }

data class UrgeRecommendation(
    val primary: UrgeAction,
    val alternatives: List<UrgeAction>,
    val reason: UrgeReason,
)
