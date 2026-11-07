package com.example.myexodus.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section and filter assignment decide what a person sees after tapping a filter chip, so each
 * branch is pinned here rather than discovered on a device. Constructed entries keep the test
 * independent of the asset's current contents.
 */
class CrisisResourcesTest {

    private fun resource(
        id: String = "entry",
        region: String = "KE",
        behaviors: List<String> = listOf(CrisisResources.BEHAVIOR_MENTAL_HEALTH),
        kind: String = CrisisResources.KIND_HELPLINE,
    ) = CrisisResource(
        id = id,
        region = region,
        name = "Name",
        description = "",
        phone = "",
        sms = "",
        whatsapp = "",
        url = "",
        source = "source",
        behaviors = behaviors,
        kind = kind,
        hours = "",
        cost = "",
        eligibility = "",
        limitations = "",
        lastVerifiedAt = "2026-11-07",
    )

    @Test
    fun `each behavior lands in its own section`() {
        assertEquals(
            CrisisResources.SECTION_GAMBLING,
            CrisisResources.sectionOf(resource(behaviors = listOf(CrisisResources.BEHAVIOR_GAMBLING))),
        )
        assertEquals(
            CrisisResources.SECTION_PORN,
            CrisisResources.sectionOf(resource(behaviors = listOf(CrisisResources.BEHAVIOR_PORN))),
        )
        assertEquals(
            CrisisResources.SECTION_GAMING,
            CrisisResources.sectionOf(resource(behaviors = listOf(CrisisResources.BEHAVIOR_GAMING))),
        )
        assertEquals(
            CrisisResources.SECTION_SUBSTANCE,
            CrisisResources.sectionOf(resource(behaviors = listOf(CrisisResources.BEHAVIOR_SUBSTANCE))),
        )
    }

    @Test
    fun `a professional body reads as professional help even though it is a directory`() {
        assertEquals(
            CrisisResources.SECTION_PROFESSIONAL,
            CrisisResources.sectionOf(
                resource(
                    behaviors = listOf(CrisisResources.BEHAVIOR_PROFESSIONAL),
                    kind = CrisisResources.KIND_DIRECTORY,
                ),
            ),
        )
    }

    @Test
    fun `the generic directory reads as more`() {
        assertEquals(
            CrisisResources.SECTION_MORE,
            CrisisResources.sectionOf(resource(kind = CrisisResources.KIND_DIRECTORY)),
        )
    }

    @Test
    fun `emergency wins over every other label`() {
        val entry = resource(
            behaviors = listOf(CrisisResources.BEHAVIOR_EMERGENCY, CrisisResources.BEHAVIOR_GAMBLING),
            kind = CrisisResources.KIND_EMERGENCY,
        )

        assertEquals(CrisisResources.SECTION_URGENT, CrisisResources.sectionOf(entry))
        assertTrue(CrisisResources.matches(entry, CrisisResources.FILTER_URGENT))
    }

    @Test
    fun `an ordinary helpline is matched only by its own filter and All`() {
        val gambling = resource(behaviors = listOf(CrisisResources.BEHAVIOR_GAMBLING))

        assertTrue(CrisisResources.matches(gambling, CrisisResources.FILTER_ALL))
        assertTrue(CrisisResources.matches(gambling, CrisisResources.FILTER_GAMBLING))
        assertFalse(CrisisResources.matches(gambling, CrisisResources.FILTER_PORN))
        assertFalse(CrisisResources.matches(gambling, CrisisResources.FILTER_MENTAL_HEALTH))
    }

    @Test
    fun `the assist category preselects only the matching filter`() {
        assertEquals(
            CrisisResources.FILTER_PORN,
            CrisisResources.filterForCategory(UrgeCategory.PORN),
        )
        assertEquals(
            CrisisResources.FILTER_GAMBLING,
            CrisisResources.filterForCategory(UrgeCategory.GAMBLING),
        )
        assertEquals(
            CrisisResources.FILTER_GAMING,
            CrisisResources.filterForCategory(UrgeCategory.GAMING),
        )
        assertEquals(
            CrisisResources.FILTER_ALL,
            CrisisResources.filterForCategory(UrgeCategory.SCROLLING),
        )
        assertEquals(CrisisResources.FILTER_ALL, CrisisResources.filterForCategory(null))
    }
}
