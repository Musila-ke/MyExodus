package com.example.myexodus.recovery

import com.example.myexodus.R

/**
 * The Urge Assist action catalog (plan §F1). About twenty code-defined options; the four
 * practice techniques are reused by id rather than duplicated. One runner per action: the
 * app's `urge_surfing` technique is the paced-breathing runner, so [BREATHING] points at it;
 * `sober` is the walkthrough, `values_card` is the values reader.
 */
object UrgeCatalog {

    const val MOVE_DEVICE = "move_device"
    const val LEAVE_ROOM = "leave_room"
    const val CLOSE_CUE = "close_cue"
    const val LEAVE_PRIVATE_SPACE = "leave_private_space"
    const val OPEN_PLAN = "open_plan"
    const val READ_VALUES = "read_values"
    const val BREATHING = "breathing"
    const val DELAY_TIMER = "delay_timer"
    const val WALKTHROUGH = "walkthrough"
    const val PARTNER_MESSAGE = "partner_message"
    const val SUPPORT_MESSAGE = "support_message"
    const val GET_HELP = "get_help"
    const val SMALL_TASK = "small_task"
    const val LOG_ONLY = "log_only"
    const val GAMBLING_STEP_AWAY = "gambling_step_away"
    const val GAMBLING_SELF_EXCLUSION = "gambling_self_exclusion"
    const val SHOPPING_LEAVE_CHECKOUT = "shopping_leave_checkout"
    const val GAMING_STOP_POINT = "gaming_stop_point"
    const val RETURN_TO_TASK = "return_to_task"
    const val LATE_NIGHT_DEVICE = "late_night_device"
    const val SAFETY_CHECK = "safety_check"
    const val DISCONNECT = "disconnect"
    const val GROUNDING = "grounding"
    const val DEFUSION = "defusion"
    const val GAMBLING_REALITY_CHECK = "gambling_reality_check"
    const val SHOPPING_DEFER_LIST = "shopping_defer_list"
    const val POWER_DOWN = "power_down"

    val all: List<UrgeAction> = listOf(
        UrgeAction(
            id = MOVE_DEVICE,
            labelRes = R.string.urge_action_move_device,
            descriptionRes = R.string.urge_action_move_device_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.PHYSICAL,
            runner = UrgeRunner.STEP,
        ),
        UrgeAction(
            id = LEAVE_ROOM,
            labelRes = R.string.urge_action_leave_room,
            descriptionRes = R.string.urge_action_leave_room_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.ENVIRONMENT,
            runner = UrgeRunner.STEP,
            requiresMovement = true,
        ),
        UrgeAction(
            id = CLOSE_CUE,
            labelRes = R.string.urge_action_close_cue,
            descriptionRes = R.string.urge_action_close_cue_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.ENVIRONMENT,
            runner = UrgeRunner.STEP,
            behaviors = setOf(UrgeCategory.PORN, UrgeCategory.SCROLLING, UrgeCategory.SHOPPING, UrgeCategory.GAMBLING),
        ),
        UrgeAction(
            id = LEAVE_PRIVATE_SPACE,
            labelRes = R.string.urge_action_leave_private_space,
            descriptionRes = R.string.urge_action_leave_private_space_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.ENVIRONMENT,
            runner = UrgeRunner.STEP,
            behaviors = setOf(UrgeCategory.PORN),
            requiresMovement = true,
        ),
        UrgeAction(
            id = OPEN_PLAN,
            labelRes = R.string.urge_action_open_plan,
            descriptionRes = R.string.urge_action_open_plan_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.COGNITIVE,
            runner = UrgeRunner.STEP,
            showsPlanItems = true,
        ),
        UrgeAction(
            id = READ_VALUES,
            labelRes = R.string.urge_action_read_values,
            descriptionRes = R.string.urge_action_read_values_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.COGNITIVE,
            runner = UrgeRunner.TECHNIQUE,
            techniqueId = RecoveryResourceCatalog.VALUES_CARD,
        ),
        UrgeAction(
            id = BREATHING,
            labelRes = R.string.urge_action_breathing,
            descriptionRes = R.string.urge_action_breathing_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.BREATHING,
            runner = UrgeRunner.TECHNIQUE,
            techniqueId = RecoveryResourceCatalog.URGE_SURFING,
        ),
        UrgeAction(
            id = DELAY_TIMER,
            labelRes = R.string.urge_action_delay_timer,
            descriptionRes = R.string.urge_action_delay_timer_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.TIMER,
            runner = UrgeRunner.TECHNIQUE,
            techniqueId = RecoveryResourceCatalog.DELAY_TIMER,
        ),
        UrgeAction(
            id = WALKTHROUGH,
            labelRes = R.string.urge_action_walkthrough,
            descriptionRes = R.string.urge_action_walkthrough_desc,
            effort = EffortTier.MEDIUM,
            modality = UrgeModality.COGNITIVE,
            runner = UrgeRunner.TECHNIQUE,
            techniqueId = RecoveryResourceCatalog.SOBER,
        ),
        UrgeAction(
            id = PARTNER_MESSAGE,
            labelRes = R.string.urge_action_partner_message,
            descriptionRes = R.string.urge_action_partner_message_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.SOCIAL,
            runner = UrgeRunner.MESSAGE,
            requiresPartner = true,
        ),
        UrgeAction(
            id = SUPPORT_MESSAGE,
            labelRes = R.string.urge_action_support_message,
            descriptionRes = R.string.urge_action_support_message_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.SOCIAL,
            runner = UrgeRunner.MESSAGE,
            requiresPartner = true,
        ),
        UrgeAction(
            id = GET_HELP,
            labelRes = R.string.urge_action_get_help,
            descriptionRes = R.string.urge_action_get_help_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.ESCALATION,
            runner = UrgeRunner.LINK,
        ),
        UrgeAction(
            id = SMALL_TASK,
            labelRes = R.string.urge_action_small_task,
            descriptionRes = R.string.urge_action_small_task_desc,
            effort = EffortTier.MEDIUM,
            modality = UrgeModality.COGNITIVE,
            runner = UrgeRunner.STEP,
            behaviors = setOf(UrgeCategory.SCROLLING, UrgeCategory.GAMING, UrgeCategory.OTHER),
        ),
        UrgeAction(
            id = LOG_ONLY,
            labelRes = R.string.urge_action_log_only,
            descriptionRes = R.string.urge_action_log_only_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.COGNITIVE,
            runner = UrgeRunner.LOG_ONLY,
        ),
        UrgeAction(
            id = GAMBLING_STEP_AWAY,
            labelRes = R.string.urge_action_gambling_step_away,
            descriptionRes = R.string.urge_action_gambling_step_away_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.ENVIRONMENT,
            runner = UrgeRunner.STEP,
            behaviors = setOf(UrgeCategory.GAMBLING),
        ),
        UrgeAction(
            id = GAMBLING_SELF_EXCLUSION,
            labelRes = R.string.urge_action_gambling_self_exclusion,
            descriptionRes = R.string.urge_action_gambling_self_exclusion_desc,
            effort = EffortTier.MEDIUM,
            modality = UrgeModality.ACCESS,
            runner = UrgeRunner.LINK,
            behaviors = setOf(UrgeCategory.GAMBLING),
            directoryFilter = "gambling",
        ),
        UrgeAction(
            id = SHOPPING_LEAVE_CHECKOUT,
            labelRes = R.string.urge_action_shopping_leave_checkout,
            descriptionRes = R.string.urge_action_shopping_leave_checkout_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.ENVIRONMENT,
            runner = UrgeRunner.STEP,
            behaviors = setOf(UrgeCategory.SHOPPING),
        ),
        UrgeAction(
            id = GAMING_STOP_POINT,
            labelRes = R.string.urge_action_gaming_stop_point,
            descriptionRes = R.string.urge_action_gaming_stop_point_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.ENVIRONMENT,
            runner = UrgeRunner.STEP,
            behaviors = setOf(UrgeCategory.GAMING),
        ),
        UrgeAction(
            id = RETURN_TO_TASK,
            labelRes = R.string.urge_action_return_to_task,
            descriptionRes = R.string.urge_action_return_to_task_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.COGNITIVE,
            runner = UrgeRunner.STEP,
            behaviors = setOf(UrgeCategory.SCROLLING),
        ),
        UrgeAction(
            id = LATE_NIGHT_DEVICE,
            labelRes = R.string.urge_action_late_night_device,
            descriptionRes = R.string.urge_action_late_night_device_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.ENVIRONMENT,
            runner = UrgeRunner.STEP,
            behaviors = setOf(UrgeCategory.PORN, UrgeCategory.SCROLLING, UrgeCategory.GAMING),
            requiresMovement = true,
        ),
        UrgeAction(
            id = SAFETY_CHECK,
            labelRes = R.string.urge_action_safety_check,
            descriptionRes = R.string.urge_action_safety_check_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.ESCALATION,
            runner = UrgeRunner.LINK,
            directoryFilter = "urgent",
        ),
        // The remaining immediate interventions from the researched catalogue (report §6,
        // I05/I10/I12/I19/I21/I28) that the first pass had folded into other actions.
        UrgeAction(
            id = DISCONNECT,
            labelRes = R.string.urge_action_disconnect,
            descriptionRes = R.string.urge_action_disconnect_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.ENVIRONMENT,
            runner = UrgeRunner.STEP,
            behaviors = setOf(UrgeCategory.PORN, UrgeCategory.SCROLLING, UrgeCategory.GAMING),
        ),
        UrgeAction(
            id = GROUNDING,
            labelRes = R.string.urge_action_grounding,
            descriptionRes = R.string.urge_action_grounding_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.SENSORY,
            runner = UrgeRunner.TECHNIQUE,
            techniqueId = RecoveryResourceCatalog.GROUNDING,
        ),
        UrgeAction(
            id = DEFUSION,
            labelRes = R.string.urge_action_defusion,
            descriptionRes = R.string.urge_action_defusion_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.COGNITIVE,
            runner = UrgeRunner.TECHNIQUE,
            techniqueId = RecoveryResourceCatalog.DEFUSION,
        ),
        UrgeAction(
            id = GAMBLING_REALITY_CHECK,
            labelRes = R.string.urge_action_gambling_reality_check,
            descriptionRes = R.string.urge_action_gambling_reality_check_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.COGNITIVE,
            runner = UrgeRunner.STEP,
            behaviors = setOf(UrgeCategory.GAMBLING),
        ),
        UrgeAction(
            id = SHOPPING_DEFER_LIST,
            labelRes = R.string.urge_action_shopping_defer_list,
            descriptionRes = R.string.urge_action_shopping_defer_list_desc,
            effort = EffortTier.LOW,
            modality = UrgeModality.COGNITIVE,
            runner = UrgeRunner.STEP,
            behaviors = setOf(UrgeCategory.SHOPPING),
        ),
        UrgeAction(
            id = POWER_DOWN,
            labelRes = R.string.urge_action_power_down,
            descriptionRes = R.string.urge_action_power_down_desc,
            effort = EffortTier.MEDIUM,
            modality = UrgeModality.PHYSICAL,
            runner = UrgeRunner.STEP,
        ),
    )

    fun byId(id: String): UrgeAction? = all.firstOrNull { it.id == id }

    /** The action that hosts a practice technique, so a run from the Recovery hub is logged the
     *  same way an assist round is. Null for a technique the catalog has no action for. */
    fun actionForTechnique(techniqueId: String): UrgeAction? = all.firstOrNull {
        it.runner == UrgeRunner.TECHNIQUE && it.techniqueId == techniqueId
    }

    /** Per-behavior order (plan §F4). Ids missing from a list fall through to general order. */
    fun priorityFor(
        category: UrgeCategory?,
        lateNight: Boolean,
        inTransaction: Boolean,
    ): List<String> = when (category) {
        UrgeCategory.PORN -> if (lateNight) {
            listOf(
                MOVE_DEVICE, LEAVE_PRIVATE_SPACE, LATE_NIGHT_DEVICE, LEAVE_ROOM, CLOSE_CUE,
                DISCONNECT, GROUNDING, OPEN_PLAN, PARTNER_MESSAGE, SUPPORT_MESSAGE, BREATHING,
                DEFUSION, DELAY_TIMER, WALKTHROUGH, READ_VALUES, LOG_ONLY, GET_HELP,
            )
        } else {
            listOf(
                MOVE_DEVICE, LEAVE_PRIVATE_SPACE, LEAVE_ROOM, CLOSE_CUE, DISCONNECT, GROUNDING,
                OPEN_PLAN, PARTNER_MESSAGE, SUPPORT_MESSAGE, BREATHING, DEFUSION, DELAY_TIMER,
                WALKTHROUGH, READ_VALUES, LOG_ONLY, GET_HELP,
            )
        }
        UrgeCategory.GAMBLING -> if (inTransaction) {
            listOf(
                GAMBLING_STEP_AWAY, GAMBLING_SELF_EXCLUSION, GET_HELP, GAMBLING_REALITY_CHECK,
                LEAVE_ROOM, PARTNER_MESSAGE, OPEN_PLAN, MOVE_DEVICE, BREATHING, GROUNDING,
                DEFUSION, DELAY_TIMER, WALKTHROUGH, READ_VALUES, LOG_ONLY,
            )
        } else {
            listOf(
                GAMBLING_SELF_EXCLUSION, GAMBLING_REALITY_CHECK, LEAVE_ROOM, PARTNER_MESSAGE,
                OPEN_PLAN, MOVE_DEVICE, BREATHING, GROUNDING, DEFUSION, DELAY_TIMER,
                WALKTHROUGH, READ_VALUES, LOG_ONLY, GET_HELP,
            )
        }
        UrgeCategory.SCROLLING -> if (lateNight) {
            listOf(
                RETURN_TO_TASK, MOVE_DEVICE, LATE_NIGHT_DEVICE, DISCONNECT, SMALL_TASK,
                OPEN_PLAN, CLOSE_CUE, GROUNDING, BREATHING, DEFUSION, DELAY_TIMER,
                WALKTHROUGH, PARTNER_MESSAGE, LOG_ONLY, GET_HELP,
            )
        } else {
            listOf(
                RETURN_TO_TASK, MOVE_DEVICE, DISCONNECT, SMALL_TASK, OPEN_PLAN, CLOSE_CUE,
                GROUNDING, BREATHING, DEFUSION, DELAY_TIMER, WALKTHROUGH, PARTNER_MESSAGE,
                LOG_ONLY, GET_HELP,
            )
        }
        UrgeCategory.SHOPPING -> listOf(
            SHOPPING_LEAVE_CHECKOUT, SHOPPING_DEFER_LIST, LEAVE_ROOM, OPEN_PLAN,
            PARTNER_MESSAGE, CLOSE_CUE, MOVE_DEVICE, GROUNDING, BREATHING, DEFUSION,
            DELAY_TIMER, WALKTHROUGH, READ_VALUES, LOG_ONLY, GET_HELP,
        )
        UrgeCategory.GAMING -> if (lateNight) {
            listOf(
                GAMING_STOP_POINT, MOVE_DEVICE, LATE_NIGHT_DEVICE, DISCONNECT, SMALL_TASK,
                OPEN_PLAN, GROUNDING, BREATHING, DEFUSION, DELAY_TIMER, WALKTHROUGH,
                PARTNER_MESSAGE, LOG_ONLY, GET_HELP,
            )
        } else {
            listOf(
                GAMING_STOP_POINT, MOVE_DEVICE, DISCONNECT, SMALL_TASK, OPEN_PLAN, GROUNDING,
                BREATHING, DEFUSION, DELAY_TIMER, WALKTHROUGH, PARTNER_MESSAGE, LOG_ONLY,
                GET_HELP,
            )
        }
        UrgeCategory.OTHER, null -> listOf(
            MOVE_DEVICE, GROUNDING, BREATHING, DEFUSION, OPEN_PLAN, SMALL_TASK, PARTNER_MESSAGE,
            SUPPORT_MESSAGE, GET_HELP, DELAY_TIMER, WALKTHROUGH, READ_VALUES, POWER_DOWN,
            LOG_ONLY,
        )
    }
}
