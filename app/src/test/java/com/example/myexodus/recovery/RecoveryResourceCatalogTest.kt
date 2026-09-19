package com.example.myexodus.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Req 19 ordering + v2 M1.3 suggestion matrix, plus the FR-4 pacing numbers the pacer reads. */
class RecoveryResourceCatalogTest {

    @Test
    fun inRiskWindow_lowEffortGroundingLeads() {
        val ordered = RecoveryResourceCatalog.ordered(inRiskWindow = true)
        assertEquals(EffortTier.LOW, ordered.first().effort)
        assertEquals(
            "low-effort tier order is the catalog's own",
            listOf(RecoveryResourceCatalog.URGE_SURFING, RecoveryResourceCatalog.DELAY_TIMER),
            ordered.take(2).map { it.id },
        )
        assertTrue(
            "effort must be non-decreasing inside a risk window",
            ordered.map { it.effort.ordinal }.zipWithNext().all { (a, b) -> a <= b },
        )
    }

    @Test
    fun outsideRiskWindow_catalogOrderStands() {
        assertEquals(
            RecoveryResourceCatalog.all,
            RecoveryResourceCatalog.ordered(inRiskWindow = false),
        )
    }

    @Test
    fun recommended_matrix() {
        assertEquals(
            RecoveryResourceCatalog.URGE_SURFING,
            RecoveryResourceCatalog.recommended(rating = 7, inRiskWindow = false).id,
        )
        assertEquals(
            "6 is the boundary below the strong-urge rule",
            RecoveryResourceCatalog.SOBER,
            RecoveryResourceCatalog.recommended(rating = 6, inRiskWindow = false).id,
        )
        assertEquals(
            RecoveryResourceCatalog.URGE_SURFING,
            RecoveryResourceCatalog.recommended(rating = null, inRiskWindow = true).id,
        )
        assertEquals(
            RecoveryResourceCatalog.SOBER,
            RecoveryResourceCatalog.recommended(rating = null, inRiskWindow = false).id,
        )
    }

    @Test
    fun urgeSurfing_pacingIsFourInSixOut_defaultFiveMinutes() {
        val surfing = RecoveryResourceCatalog.byId(RecoveryResourceCatalog.URGE_SURFING)!!
        assertEquals(4, surfing.breathInSeconds)
        assertEquals(6, surfing.breathOutSeconds)
        assertEquals(30, surfing.promptRotationSeconds)
        assertEquals(5, surfing.defaultDurationMinutes)
        assertTrue(surfing.durationChoicesMinutes.containsAll(listOf(3, 5, 10)))
    }

    @Test
    fun sober_hasFiveOrderedPhases_andRespondCarriesIfThen() {
        val sober = RecoveryResourceCatalog.byId(RecoveryResourceCatalog.SOBER)!!
        assertEquals(
            listOf("stop", "observe", "breathe", "expand", "respond"),
            sober.phases,
        )
        assertTrue(sober.showsIfThen)
    }

    @Test
    fun delayTimer_isTenMinutes_andValuesCardCarriesValues() {
        assertEquals(
            10,
            RecoveryResourceCatalog.byId(RecoveryResourceCatalog.DELAY_TIMER)!!.defaultDurationMinutes,
        )
        assertTrue(RecoveryResourceCatalog.byId(RecoveryResourceCatalog.VALUES_CARD)!!.showsValues)
    }

    @Test
    fun everyTechniqueIdIsUnique() {
        assertEquals(
            RecoveryResourceCatalog.all.size,
            RecoveryResourceCatalog.all.map { it.id }.toSet().size,
        )
    }
}
