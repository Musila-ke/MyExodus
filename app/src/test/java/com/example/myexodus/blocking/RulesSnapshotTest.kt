package com.example.myexodus.blocking

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.data.db.entity.RestrictionRule
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Snapshot JSON cache round-trip (Robolectric provides org.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RulesSnapshotTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val rule = RestrictionRule(
        ruleId = "r-1",
        profileId = "p-1",
        blockedPackage = "com.instagram.android",
        targetType = "APP",
        isAlwaysOn = true,
        dailyLimitMs = 90_000L,
        timeRangeStart = 21 * 60,
        timeRangeEnd = 7 * 60,
        activeDaysOfWeek = "1111111",
        blockExpiresAt = 1_800_000_000_000L,
        severityLevel = "HIGH",
    )

    /** Fixed build time — before [rule]'s expiry, so it stays enforceable by default. */
    private val now = 1_700_000_000_000L

    private val urlRule = RestrictionRule(
        ruleId = "r-2",
        profileId = "p-1",
        blockedPackage = "https://betting.example.com:8443/forum",
        targetType = "WEBSITE",
        isAlwaysOn = false,
        dailyLimitMs = 0L,
        timeRangeStart = null,
        timeRangeEnd = null,
        activeDaysOfWeek = "1010101",
        blockExpiresAt = 0L,
        severityLevel = "CRITICAL",
    )

    @Test
    fun build_mapsAppsAndCanonicalizesUrls_skipsUnparseable() {
        val bad = urlRule.copy(ruleId = "r-3", blockedPackage = "blob:https://x/y")
        val snapshot = RulesSnapshot.build(listOf(rule, urlRule, bad), 7L, now)

        assertEquals(1, snapshot.appRules.size)
        val app = snapshot.appRules["com.instagram.android"]!!
        assertEquals("r-1", app.ruleId)
        assertEquals("HIGH", app.severityLevel)
        assertEquals(90_000L, app.dailyLimitMs)
        assertEquals(21 * 60L, app.timeRangeStart)

        assertEquals(1, snapshot.urlRules.size)
        val url = snapshot.urlRules.single()
        assertEquals("betting.example.com", url.hostUnicode)
        assertEquals(8443, url.port)
        assertEquals("/forum", url.pathPrefix)
        assertEquals("https", url.scheme)
        assertEquals(7L, snapshot.version)
    }

    @Test
    fun build_ordersUrlRulesBySpecificity_longestHostThenLongestPath() {
        val broad = urlRule.copy(ruleId = "broad", blockedPackage = "example.com")
        val narrowHost = urlRule.copy(ruleId = "narrow-host", blockedPackage = "news.example.com")
        val narrowPath = urlRule.copy(ruleId = "narrow-path", blockedPackage = "example.com/news")
        // Deliberately inserted broad-first: the old first-match-wins order picked it.
        val snapshot = RulesSnapshot.build(listOf(broad, narrowHost, narrowPath), 1L, now)

        assertEquals(listOf("narrow-host", "narrow-path", "broad"), snapshot.urlRules.map { it.ruleId })

        val engine = BlockDecisionEngine { snapshot }
        val canonical = UrlPattern.canonicalizeCandidate("https://news.example.com/news/today")!!
        assertEquals("narrow-host", engine.matchingUrlRule(canonical)?.ruleId)
    }

    @Test
    fun build_sameHost_longerPathWins() {
        val site = urlRule.copy(ruleId = "site", blockedPackage = "example.com")
        val section = urlRule.copy(ruleId = "section", blockedPackage = "example.com/news")
        val snapshot = RulesSnapshot.build(listOf(site, section), 1L, now)

        val engine = BlockDecisionEngine { snapshot }
        val canonical = UrlPattern.canonicalizeCandidate("https://example.com/news/today")!!
        assertEquals("section", engine.matchingUrlRule(canonical)?.ruleId)
    }

    @Test
    fun build_excludesPausedRules_fromBothEnforcementLists() {
        val pausedApp = rule.copy(ruleId = "r-paused", isEnabled = false)
        val pausedUrl = urlRule.copy(ruleId = "r-url-paused", isEnabled = false)
        val snapshot = RulesSnapshot.build(listOf(pausedApp, pausedUrl, rule, urlRule), 9L, now)

        assertEquals(listOf("r-1"), snapshot.appRules.values.map { it.ruleId })
        assertEquals(listOf("r-2"), snapshot.urlRules.map { it.ruleId })
    }

    @Test
    fun build_excludesExpiredRules_fromBothEnforcementLists() {
        val expiredApp = rule.copy(ruleId = "r-expired", blockExpiresAt = now - 1)
        val expiredUrl = urlRule.copy(ruleId = "r-url-expired", blockExpiresAt = now - 1)
        val snapshot = RulesSnapshot.build(listOf(expiredApp, expiredUrl, rule, urlRule), 11L, now)

        assertEquals(listOf("r-1"), snapshot.appRules.values.map { it.ruleId })
        assertEquals(listOf("r-2"), snapshot.urlRules.map { it.ruleId })
    }

    /** Shared by both accounting paths before a session may open. */
    @Test
    fun isLimitTracked_requiresARuleWithAPositiveLimit_onACoveredDay() {
        val noLimit = rule.copy(ruleId = "r-none", blockedPackage = "com.example.none", dailyLimitMs = 0L)
        val snapshot = RulesSnapshot.build(listOf(rule, noLimit), 1L, now)

        assertTrue(snapshot.isLimitTracked("com.instagram.android"))
        assertFalse("zero limit never accrues", snapshot.isLimitTracked("com.example.none"))
        assertFalse("no rule at all", snapshot.isLimitTracked("com.example.unknown"))

        // Rule timezone defaults to UTC ("1111111" is a superset, never exercised here).
        val todayIndex = LocalDate.now(ZoneId.of("UTC")).dayOfWeek.value - 1
        val everyDayButToday = String(CharArray(7) { if (it == todayIndex) '0' else '1' })
        val offDay = RulesSnapshot.build(listOf(rule.copy(activeDaysOfWeek = everyDayButToday)), 2L, now)
        assertFalse("a rule not active today must not open a session", offDay.isLimitTracked("com.instagram.android"))
    }

    @Test
    fun jsonRoundTrip_preservesAppsUrlsAndNulls() {
        val snapshot = RulesSnapshot.build(listOf(rule, urlRule), 3L, now)
        val file = File(tmp.root, "rules_snapshot.json")

        snapshot.writeTo(file)
        val read = RulesSnapshot.readFrom(file)!!

        assertEquals(3L, read.version)
        assertEquals(snapshot.appRules, read.appRules)
        assertEquals(snapshot.urlRules, read.urlRules)
        assertEquals(null, read.urlRules.single().timeRangeStart)
    }

    @Test
    fun readFrom_missingFile_isNull() {
        assertNull(RulesSnapshot.readFrom(File(tmp.root, "nope.json")))
    }

    @Test
    fun readFrom_corruptFile_isNullAndDeleted() {
        val file = File(tmp.root, "rules_snapshot.json")
        file.writeText("{not json")
        assertNull(RulesSnapshot.readFrom(file))
        assertFalse("Corrupt cache must be discarded", file.exists())
    }
}
