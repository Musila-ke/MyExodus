package com.example.myexodus.recovery

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stress sweep requested by the owner (2026-11-07): the engine and the episode machine must
 * never offer something the user ruled out, never crash on an odd combination of answers, and
 * never let a repeated check-in break the round bounds. Deterministic seeds, so a failure
 * reproduces exactly.
 */
class UrgeSelectionStressTest {

    private fun eligibleCount(input: UrgeSelectionInput): Int = UrgeCatalog.all.count { action ->
        (action.behaviors.isEmpty() || (input.category != null && input.category in action.behaviors)) &&
            action.id !in input.dislikedActions &&
            action.id !in input.excludedActions &&
            (input.canMove != false || !action.requiresMovement) &&
            (input.hasPartner || !action.requiresPartner) &&
            (!input.quietPreferred || !action.requiresAudio)
    }

    private fun assertInvariants(input: UrgeSelectionInput) {
        val result = UrgeSelectionEngine.select(input)
        val offered = listOf(result.primary) + result.alternatives

        if (input.immediateDanger) {
            assertEquals(UrgeCatalog.SAFETY_CHECK, result.primary.id)
            assertTrue("a safety report stands alone", result.alternatives.isEmpty())
            return
        }

        val fallback = result.primary.id == UrgeCatalog.LOG_ONLY && eligibleCount(input) == 0
        assertEquals(
            "no action may be offered twice",
            offered.size,
            offered.map { it.id }.distinct().size,
        )
        if (!fallback) {
            assertTrue(
                "a disliked or already-tried action was offered: ${offered.map { it.id }}",
                offered.none { it.id in input.dislikedActions || it.id in input.excludedActions },
            )
        }
        if (input.canMove == false) {
            assertTrue("a movement action survived an explicit no", offered.none { it.requiresMovement })
        }
        if (!input.hasPartner) {
            assertTrue("a partner action was offered with no partner", offered.none { it.requiresPartner })
        }
        if (input.quietPreferred) {
            assertTrue("an audio action was offered in quiet mode", offered.none { it.requiresAudio })
        }
        if (input.category == null) {
            assertTrue(
                "a category-only action appeared with no category named",
                offered.all { it.behaviors.isEmpty() },
            )
        } else {
            assertTrue(
                "an action for another behavior was offered",
                offered.all { it.behaviors.isEmpty() || input.category in it.behaviors },
            )
        }
    }

    @Test
    fun `every combination of answers keeps the hard constraints`() {
        val ids = UrgeCatalog.all.map { it.id }
        val random = Random(20261107)
        val preferenceSets = listOf(
            emptySet(),
            ids.shuffled(random).take(3).toSet(),
            ids.shuffled(random).take(3).toSet(),
            ids.shuffled(random).take(3).toSet(),
        )

        (UrgeCategory.entries + listOf<UrgeCategory?>(null)).forEach { category ->
            listOf(false, true).forEach { lateNight ->
                listOf(false, true).forEach { inTransaction ->
                    listOf(null, true, false).forEach { canMove ->
                        listOf(false, true).forEach { hasPartner ->
                            listOf(false, true).forEach { inRiskWindow ->
                                listOf(false, true).forEach { quiet ->
                                    preferenceSets.forEach { preferences ->
                                        assertInvariants(
                                            UrgeSelectionInput(
                                                category = category,
                                                lateNight = lateNight,
                                                inTransaction = inTransaction,
                                                canMove = canMove,
                                                hasPartner = hasPartner,
                                                inRiskWindow = inRiskWindow,
                                                quietPreferred = quiet,
                                                dislikedActions = preferences,
                                                excludedActions = preferences,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `random episode walks never break a round bound or offer a tried action`() {
        val random = Random(20261107)
        val ids = UrgeCatalog.all.map { it.id }

        repeat(40) {
            var episode = UrgeEpisode()
            repeat(30) {
                val actionId = ids.random(random)
                val modality = UrgeModality.entries.random(random)
                val result = UrgeCheckIn.entries.random(random)
                val before = episode
                val transition = episode.checkIn(result, actionId, modality)
                episode = transition.episode

                assertTrue("excluded actions never reappear", episode.excludedActions.containsAll(before.excludedActions))
                assertTrue("same rounds stay bounded", episode.sameRounds <= UrgeEpisode.MAX_SAME_ROUNDS)
                when (result) {
                    UrgeCheckIn.BETTER -> {
                        if (before.betterUsed) {
                            assertEquals(CheckInOutcome.FINISH, transition.outcome)
                            assertEquals(before, episode)
                        } else {
                            assertEquals(CheckInOutcome.EXTEND, transition.outcome)
                            assertTrue(episode.betterUsed)
                        }
                    }
                    UrgeCheckIn.SAME -> {
                        assertTrue(episode.excludedActions.contains(actionId))
                        assertEquals(
                            if (episode.sameRounds >= UrgeEpisode.MAX_SAME_ROUNDS) {
                                CheckInOutcome.ESCALATE
                            } else {
                                CheckInOutcome.NEW_ACTION
                            },
                            transition.outcome,
                        )
                    }
                    UrgeCheckIn.WORSE -> {
                        assertTrue(episode.excludedActions.contains(actionId))
                        assertEquals(CheckInOutcome.ESCALATE, transition.outcome)
                    }
                    UrgeCheckIn.SKIPPED -> {
                        assertEquals(CheckInOutcome.FINISH, transition.outcome)
                        assertEquals(before, episode)
                    }
                }

                val offered = UrgeSelectionEngine.select(
                    UrgeSelectionInput(category = UrgeCategory.PORN, excludedActions = episode.excludedActions),
                )
                val nowOffered = listOf(offered.primary) + offered.alternatives
                assertFalse(
                    "an action excluded by the episode was offered again",
                    nowOffered.any { it.id in episode.excludedActions && it.id != UrgeCatalog.LOG_ONLY },
                )
            }
        }
    }
}
