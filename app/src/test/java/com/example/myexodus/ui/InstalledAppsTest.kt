package com.example.myexodus.ui

import com.example.myexodus.data.db.entity.RestrictionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner ruling 2026-11-01: an uninstalled app's block is hidden, not deleted — the stored rule
 * comes back unchanged with the app. These assert the one visibility predicate every surface
 * (Rules list, Home block list and its counts) shares.
 */
class InstalledAppsTest {

    @Test
    fun appRuleHiddenOnlyWhileItsPackageIsNotInstalled() {
        val rule = appRule("com.instagram.android")
        assertEquals(emptyList<RestrictionRule>(), visibleRules(listOf(rule), emptySet()))
        assertEquals(listOf(rule), visibleRules(listOf(rule), setOf("com.instagram.android")))
    }

    @Test
    fun websiteRulesNeverDependOnAnInstalledPackage() {
        val website = appRule("instagram.com").copy(targetType = "WEBSITE")
        assertEquals(listOf(website), visibleRules(listOf(website), emptySet()))
    }

    @Test
    fun hiddenRulesLeaveTheCountsButVisibleOnesKeepTheirOrder() {
        val installed = appRule("com.instagram.android")
        val missing = appRule("com.zhiliaoapp.musically")
        val visible = visibleRules(listOf(installed, missing), setOf("com.instagram.android"))
        assertEquals(1, visible.size)
        assertTrue(visible.single().blockedPackage == "com.instagram.android")
    }

    private fun appRule(packageName: String) = RestrictionRule(
        ruleId = "rule-$packageName",
        profileId = "p-1",
        blockedPackage = packageName,
        targetType = "APP",
        isAlwaysOn = false,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1111111",
        blockExpiresAt = 0L,
        severityLevel = "HIGH",
    )
}
