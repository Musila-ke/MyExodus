package com.example.myexodus.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The assist engine is the one place that decides what a person is offered in the moment.
 * These tests pin the promises the design makes: a behavior category changes the answer, an
 * explicit constraint is honored, an unknown constraint is not invented, a preference moves
 * the order, and escalation is offered rather than assumed.
 */
class UrgeSelectionEngineTest {

    private fun select(input: UrgeSelectionInput = UrgeSelectionInput()) =
        UrgeSelectionEngine.select(input)

    @Test
    fun `no category starts with the smallest physical step`() {
        val result = select()

        assertEquals(UrgeCatalog.MOVE_DEVICE, result.primary.id)
        assertEquals(UrgeReason.GENERIC, result.reason)
        assertTrue("alternates exist so the suggestion is never a lock-in", result.alternatives.isNotEmpty())
    }

    @Test
    fun `a porn urge late at night leads with the physical pair and adds device separation`() {
        val result = select(UrgeSelectionInput(category = UrgeCategory.PORN, lateNight = true))

        assertEquals(UrgeCatalog.MOVE_DEVICE, result.primary.id)
        assertEquals(
            listOf(UrgeCatalog.LEAVE_PRIVATE_SPACE, UrgeCatalog.LATE_NIGHT_DEVICE),
            result.alternatives.take(2).map { it.id },
        )
        assertEquals(UrgeReason.LATE_NIGHT, result.reason)
    }

    @Test
    fun `cannot move drops every movement action`() {
        val result = select(
            UrgeSelectionInput(category = UrgeCategory.PORN, lateNight = true, canMove = false),
        )

        val offered = listOf(result.primary) + result.alternatives
        assertTrue("at least one action survives the filter", offered.isNotEmpty())
        assertTrue(
            "a stated constraint is honored",
            offered.none { it.requiresMovement },
        )
        assertEquals(UrgeCatalog.MOVE_DEVICE, result.primary.id)
    }

    @Test
    fun `an unanswered mobility question does not remove movement actions`() {
        val result = select(UrgeSelectionInput(category = UrgeCategory.PORN, lateNight = true, canMove = null))

        assertTrue(
            "unknown is not 'cannot'",
            result.alternatives.any { it.requiresMovement },
        )
    }

    @Test
    fun `gambling in a transaction interrupts before anything reflective`() {
        val result = select(
            UrgeSelectionInput(category = UrgeCategory.GAMBLING, inTransaction = true),
        )

        assertEquals(UrgeCatalog.GAMBLING_STEP_AWAY, result.primary.id)
        assertEquals(UrgeCatalog.GAMBLING_SELF_EXCLUSION, result.alternatives.first().id)
        assertEquals(UrgeReason.GAMBLING_TRANSACTION, result.reason)
    }

    @Test
    fun `gambling outside a transaction leads with self-exclusion`() {
        val result = select(UrgeSelectionInput(category = UrgeCategory.GAMBLING))

        assertEquals(UrgeCatalog.GAMBLING_SELF_EXCLUSION, result.primary.id)
        assertEquals(UrgeReason.CATEGORY, result.reason)
    }

    @Test
    fun `scrolling returns to the intended activity rather than abstinence`() {
        val result = select(UrgeSelectionInput(category = UrgeCategory.SCROLLING))

        assertEquals(UrgeCatalog.RETURN_TO_TASK, result.primary.id)
    }

    @Test
    fun `shopping interrupts the checkout`() {
        val result = select(UrgeSelectionInput(category = UrgeCategory.SHOPPING))

        assertEquals(UrgeCatalog.SHOPPING_LEAVE_CHECKOUT, result.primary.id)
    }

    @Test
    fun `gaming stops at a point the user chooses`() {
        val result = select(UrgeSelectionInput(category = UrgeCategory.GAMING))

        assertEquals(UrgeCatalog.GAMING_STOP_POINT, result.primary.id)
    }

    @Test
    fun `gaming late at night adds the device-separation step`() {
        val result = select(UrgeSelectionInput(category = UrgeCategory.GAMING, lateNight = true))

        assertEquals(
            listOf(UrgeCatalog.MOVE_DEVICE, UrgeCatalog.LATE_NIGHT_DEVICE),
            result.alternatives.take(2).map { it.id },
        )
    }

    @Test
    fun `category-only actions never appear when no category was chosen`() {
        val result = select()
        val offered = listOf(result.primary) + result.alternatives

        assertTrue(
            "no gambling, shopping, or gaming action without the user naming the behavior",
            offered.none { it.behaviors.isNotEmpty() },
        )
    }

    @Test
    fun `no partner means no social action is offered`() {
        val result = select(UrgeSelectionInput(hasPartner = false))
        val offered = listOf(result.primary) + result.alternatives

        assertTrue(offered.none { it.requiresPartner })
    }

    @Test
    fun `a social action is offered only when a partner is paired`() {
        val top = setOf(
            UrgeCatalog.MOVE_DEVICE,
            UrgeCatalog.LEAVE_PRIVATE_SPACE,
            UrgeCatalog.LEAVE_ROOM,
            UrgeCatalog.CLOSE_CUE,
        )
        val paired = select(
            UrgeSelectionInput(category = UrgeCategory.PORN, hasPartner = true, dislikedActions = top),
        )
        val alone = select(
            UrgeSelectionInput(category = UrgeCategory.PORN, hasPartner = false, dislikedActions = top),
        )

        assertTrue((listOf(paired.primary) + paired.alternatives).any { it.requiresPartner })
        assertTrue((listOf(alone.primary) + alone.alternatives).none { it.requiresPartner })
    }

    @Test
    fun `no partner and no category offers the ranked offline next steps`() {
        val result = select(UrgeSelectionInput(hasPartner = false))

        assertEquals(UrgeCatalog.MOVE_DEVICE, result.primary.id)
        assertEquals(
            listOf(
                UrgeCatalog.GROUNDING,
                UrgeCatalog.BREATHING,
                UrgeCatalog.DEFUSION,
                UrgeCatalog.OPEN_PLAN,
                UrgeCatalog.GET_HELP,
            ),
            result.alternatives.take(5).map { it.id },
        )
    }

    @Test
    fun `the whole eligible catalogue is offered with no silent cap`() {
        val result = select(UrgeSelectionInput(category = UrgeCategory.PORN, hasPartner = true))
        val eligible = UrgeCatalog.all.count {
            it.behaviors.isEmpty() || UrgeCategory.PORN in it.behaviors
        }

        assertEquals(
            "every eligible action follows the primary; the UI decides how much to show",
            eligible - 1,
            result.alternatives.size,
        )
    }

    @Test
    fun `an action marked not for me is not offered`() {
        val result = select(UrgeSelectionInput(dislikedActions = setOf(UrgeCatalog.MOVE_DEVICE)))
        val offered = listOf(result.primary) + result.alternatives

        assertFalse(offered.any { it.id == UrgeCatalog.MOVE_DEVICE })
    }

    @Test
    fun `an action marked helpful is promoted`() {
        val result = select(UrgeSelectionInput(helpfulActions = setOf(UrgeCatalog.BREATHING)))

        assertEquals(UrgeCatalog.BREATHING, result.primary.id)
        assertEquals(UrgeReason.HELPFUL, result.reason)
    }

    @Test
    fun `a helpful preference never displaces the named category's lead`() {
        val scrolling = select(
            UrgeSelectionInput(category = UrgeCategory.SCROLLING, helpfulActions = setOf(UrgeCatalog.BREATHING)),
        )
        val porn = select(
            UrgeSelectionInput(
                category = UrgeCategory.PORN,
                lateNight = true,
                helpfulActions = setOf(UrgeCatalog.BREATHING),
            ),
        )

        assertEquals(
            "the behavior the user named outranks a stored preference",
            UrgeCatalog.RETURN_TO_TASK,
            scrolling.primary.id,
        )
        assertEquals(UrgeCatalog.BREATHING, scrolling.alternatives.first().id)
        assertEquals(UrgeCatalog.MOVE_DEVICE, porn.primary.id)
        assertEquals(UrgeCatalog.BREATHING, porn.alternatives.first().id)
    }

    @Test
    fun `an action already tried this episode is not offered again`() {
        val result = select(UrgeSelectionInput(excludedActions = setOf(UrgeCatalog.MOVE_DEVICE)))
        val offered = listOf(result.primary) + result.alternatives

        assertFalse(offered.any { it.id == UrgeCatalog.MOVE_DEVICE })
    }

    @Test
    fun `a repeated modality is demoted so the next round differs`() {
        val first = select()
        val second = select(UrgeSelectionInput(lastModality = first.primary.modality))

        assertNotEquals(
            "a second round must be a materially different move",
            first.primary.modality,
            second.primary.modality,
        )
    }

    @Test
    fun `immediate danger offers only the safety path`() {
        val result = select(
            UrgeSelectionInput(
                category = UrgeCategory.PORN,
                immediateDanger = true,
                excludedActions = setOf(UrgeCatalog.SAFETY_CHECK),
            ),
        )

        assertEquals(UrgeCatalog.SAFETY_CHECK, result.primary.id)
        assertTrue("no technique competes with a safety report", result.alternatives.isEmpty())
        assertEquals(UrgeReason.SAFETY, result.reason)
    }

    @Test
    fun `a fully filtered catalog still returns an honest minimum`() {
        val everything = UrgeCatalog.all.map { it.id }.toSet()
        val result = select(UrgeSelectionInput(dislikedActions = everything))

        assertEquals(UrgeCatalog.LOG_ONLY, result.primary.id)
    }
}
