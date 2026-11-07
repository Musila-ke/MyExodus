package com.example.myexodus.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One episode's check-in rules (plan §F5). These are the promises the escalation copy makes, so
 * each test names a sentence the screen would otherwise have to assert on its own: better is
 * allowed once, "same" changes the move before it escalates, "worse" ends the offers, and a
 * skipped check-in is not a failure.
 */
class UrgeEpisodeTest {

    private val action = UrgeCatalog.MOVE_DEVICE
    private val modality = UrgeModality.PHYSICAL

    @Test
    fun `a better answer extends once and then finishes`() {
        val first = UrgeEpisode().checkIn(UrgeCheckIn.BETTER, action, modality)

        assertEquals(CheckInOutcome.EXTEND, first.outcome)
        assertTrue(first.episode.betterUsed)
        assertEquals("repeating the action is what 'keep going' means", emptySet<String>(), first.episode.excludedActions)

        val second = first.episode.checkIn(UrgeCheckIn.BETTER, action, modality)

        assertEquals(CheckInOutcome.FINISH, second.outcome)
    }

    @Test
    fun `one same round asks for a materially different action and excludes the tried one`() {
        val result = UrgeEpisode().checkIn(UrgeCheckIn.SAME, action, modality)

        assertEquals(CheckInOutcome.NEW_ACTION, result.outcome)
        assertEquals(1, result.episode.sameRounds)
        assertTrue(result.episode.excludedActions.contains(action))
        assertEquals(modality, result.episode.lastModality)
    }

    @Test
    fun `two same rounds escalate`() {
        val first = UrgeEpisode().checkIn(UrgeCheckIn.SAME, action, modality)
        val second = first.episode.checkIn(UrgeCheckIn.SAME, UrgeCatalog.BREATHING, UrgeModality.BREATHING)

        assertEquals(CheckInOutcome.ESCALATE, second.outcome)
        assertEquals(UrgeEpisode.MAX_SAME_ROUNDS, second.episode.sameRounds)
        assertEquals(
            setOf(UrgeCatalog.MOVE_DEVICE, UrgeCatalog.BREATHING),
            second.episode.excludedActions,
        )
    }

    @Test
    fun `worse ends the offers immediately and never repeats the action`() {
        val result = UrgeEpisode().checkIn(UrgeCheckIn.WORSE, action, modality)

        assertEquals(CheckInOutcome.ESCALATE, result.outcome)
        assertTrue(result.episode.excludedActions.contains(action))
        assertEquals(modality, result.episode.lastModality)
        assertFalse("worse does not spend the better extension", result.episode.betterUsed)
    }

    @Test
    fun `skipping finishes without excluding or counting a round`() {
        val result = UrgeEpisode().checkIn(UrgeCheckIn.SKIPPED, action, modality)

        assertEquals(CheckInOutcome.FINISH, result.outcome)
        assertEquals(emptySet<String>(), result.episode.excludedActions)
        assertEquals(0, result.episode.sameRounds)
    }

    @Test
    fun `i cannot do that removes the action from this episode only`() {
        val episode = UrgeEpisode().cantDo(action)

        assertTrue(episode.excludedActions.contains(action))
        assertEquals(0, episode.sameRounds)
    }

    @Test
    fun `late night runs from 22 to 5`() {
        assertFalse(isLateNight(21))
        assertTrue(isLateNight(22))
        assertTrue(isLateNight(0))
        assertTrue(isLateNight(5))
        assertFalse(isLateNight(6))
    }
}
