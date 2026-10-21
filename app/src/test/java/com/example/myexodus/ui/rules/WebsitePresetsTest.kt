package com.example.myexodus.ui.rules

import com.example.myexodus.data.db.entity.RestrictionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the category catalog's contract (owner request 2026-10-21): every entry must be a
 * bare, valid, globally unique website target, and dedupe must respect the table's unique
 * (profile_id, blocked_package) index — paused and expired rules still occupy a host.
 */
class WebsitePresetsTest {

    private fun rule(
        host: String,
        targetType: String = "WEBSITE",
        isEnabled: Boolean = true,
        expiresAt: Long = 0L,
    ) = RestrictionRule(
        ruleId = "rule-$host",
        profileId = "profile",
        blockedPackage = host,
        targetType = targetType,
        isAlwaysOn = true,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        timezone = "UTC",
        blockExpiresAt = expiresAt,
        severityLevel = "HIGH",
        isEnabled = isEnabled,
    )

    @Test
    fun catalogCategoryIdsAreUniqueAndAddressable() {
        val ids = WebsitePresets.categories.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertEquals(id, WebsitePresets.category(id)?.id)
        }
    }

    @Test
    fun everyCatalogHostIsABareValidAndGloballyUniqueWebsiteTarget() {
        val allHosts = WebsitePresets.categories.flatMap { it.sites }
        assertEquals(allHosts.size, allHosts.toSet().size)
        allHosts.forEach { host ->
            assertEquals(host, host.lowercase())
            assertFalse(host, host.startsWith("www."))
            assertFalse(host, host.contains("://"))
            assertFalse(host, host.any { it.isWhitespace() || it == '/' })
            assertTrue(host, isValidWebsiteTarget(host))
            assertEquals(host, targetKey(host, "WEBSITE"))
        }
    }

    @Test
    fun unblockedSites_skipsEveryStoredWebsiteRule_regardlessOfItsState() {
        val social = WebsitePresets.category("social")!!
        val existing = listOf(
            rule("facebook.com"),
            rule("instagram.com", isEnabled = false),
            rule("x.com", expiresAt = 1L),
            rule("com.example.app", targetType = "APP"),
        )

        val remaining = unblockedSites(social, existing)

        assertFalse(remaining.contains("facebook.com"))
        assertFalse(remaining.contains("instagram.com"))
        assertFalse(remaining.contains("x.com"))
        assertTrue(remaining.contains("tiktok.com"))
        assertEquals(social.sites.size - 3, remaining.size)
    }

    @Test
    fun unblockedSites_withNothingStored_returnsTheWholeCuratedList() {
        WebsitePresets.categories.forEach { category ->
            assertEquals(category.sites, unblockedSites(category, emptyList()))
        }
    }

    @Test
    fun presetRule_isAnAlwaysOnBlockOnlyWebsiteRule() {
        val preset = websitePresetRule("profile", "tiktok.com", "Africa/Nairobi")

        assertEquals("WEBSITE", preset.targetType)
        assertEquals("tiktok.com", preset.blockedPackage)
        assertTrue(preset.isAlwaysOn)
        assertEquals(0L, preset.dailyLimitMs)
        assertEquals(null, preset.timeRangeStart)
        assertEquals(null, preset.timeRangeEnd)
        assertEquals("1111111", preset.activeDaysOfWeek)
        assertEquals("Africa/Nairobi", preset.timezone)
        assertEquals(0L, preset.blockExpiresAt)
        assertTrue(preset.isEnabled)
        assertNotEquals(
            preset.ruleId,
            websitePresetRule("profile", "tiktok.com", "Africa/Nairobi").ruleId,
        )
    }
}
