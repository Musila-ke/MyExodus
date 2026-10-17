package com.example.myexodus.blocking

import android.view.accessibility.AccessibilityNodeInfo
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Debounce, generation gating, browser discovery, view-ID extraction, and URL decisions
 *  (plan §8 Steps 7–8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserUrlScannerTest {

    private lateinit var engine: BlockDecisionEngine
    private lateinit var stateMachine: ForegroundStateMachine
    private val results = mutableListOf<Pair<Long, BlockDecision>>()
    private val canonicals = mutableListOf<CanonicalUrl?>()
    private val extractedViewIds = mutableListOf<String>()

    /** Fake platform trees: each browser package exposes exactly one toolbar id. */
    private val browserBars = mapOf(
        "com.android.chrome" to "url_bar",
        "com.brave.browser" to "url_bar",
        "org.mozilla.firefox" to "url_bar_text",
        "com.opera.browser" to "url_bar",
    )

    @Before
    fun setUp() {
        engine = BlockDecisionEngine { snapshot }
        stateMachine = ForegroundStateMachine()
    }

    private val snapshot = RulesSnapshot(
        appRules = emptyMap(),
        urlRules = listOf(
            UrlPattern.from(
                com.example.myexodus.data.db.entity.RestrictionRule(
                    ruleId = "r1", profileId = "p-1",
                    blockedPackage = "https://betting.example.com",
                    targetType = "WEBSITE", isAlwaysOn = true,
                    dailyLimitMs = 0L, timeRangeStart = null, timeRangeEnd = null,
                    activeDaysOfWeek = "1111111", blockExpiresAt = 0L,
                    severityLevel = "CRITICAL",
                ),
            )!!,
            UrlPattern.from(
                com.example.myexodus.data.db.entity.RestrictionRule(
                    ruleId = "r2", profileId = "p-1",
                    blockedPackage = "https://limited.example.com",
                    targetType = "WEBSITE", isAlwaysOn = false,
                    dailyLimitMs = 60_000L, timeRangeStart = null, timeRangeEnd = null,
                    activeDaysOfWeek = "1111111", blockExpiresAt = 0L,
                    severityLevel = "HIGH",
                ),
            )!!,
        ),
        version = 1L,
    )

    /** Fake platform seams: [extractUrlField] records the view-ID asked for and returns [text]
     *  with the bar's focused state (null root → null); [hasView] reports a toolbar only when
     *  the qualified id matches the package's entry in [browserBars]. The real tree walks are
     *  exercised on-device (§12); Robolectric cannot drive them. */
    private fun scanner(
        text: String?,
        usageTodayMs: (String) -> Long? = { null },
        focused: () -> Boolean = { false },
    ): BrowserUrlScanner = BrowserUrlScanner(
        engine, stateMachine,
        onResult = { g, d, c, _ -> results.add(g to d); canonicals.add(c) },
        usageTodayMs = usageTodayMs,
        extractUrlField = { root, viewId ->
            extractedViewIds.add(viewId)
            if (root == null) null else text?.let { UrlField(it, focused()) }
        },
        hasView = { _, viewId ->
            browserBars[viewId.substringBefore(":id/")] == viewId.substringAfter(":id/")
        },
    )

    private fun chromeRoot(text: String): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        setViewIdResourceName("com.android.chrome:id/url_bar")
        this.text = text
    }

    private fun idle() = idleFor(Duration.ofMillis(500))

    // --- Browser discovery (no package allowlist: probe toolbar ids in the package's tree) ---

    @Test
    fun discovery_unlistedBrowser_findsItsAddressBar() {
        val scanner = scanner("https://betting.example.com")
        assertEquals("com.opera.browser:id/url_bar", scanner.viewIdFor("com.opera.browser") { chromeRoot("x") })
    }

    @Test
    fun discovery_firefox_usesUrlBarText() {
        val scanner = scanner("https://betting.example.com")
        assertEquals("org.mozilla.firefox:id/url_bar_text", scanner.viewIdFor("org.mozilla.firefox") { chromeRoot("x") })
    }

    @Test
    fun discovery_brave_usesUrlBar() {
        val scanner = scanner("https://betting.example.com")
        assertEquals("com.brave.browser:id/url_bar", scanner.viewIdFor("com.brave.browser") { chromeRoot("x") })
    }

    @Test
    fun discovery_success_probesOnce() {
        val scanner = scanner("https://betting.example.com")
        var reads = 0
        scanner.viewIdFor("com.opera.browser") { reads++; chromeRoot("x") }
        scanner.viewIdFor("com.opera.browser") { reads++; chromeRoot("x") }
        assertEquals(1, reads) // second call is a memo hit: no tree read
    }

    @Test
    fun discovery_failure_notReprobedWithinRetryWindow() {
        val scanner = scanner("https://betting.example.com")
        var reads = 0
        assertNull(scanner.viewIdFor("com.instagram.android") { reads++; chromeRoot("x") })
        assertNull(scanner.viewIdFor("com.instagram.android") { reads++; chromeRoot("x") })
        assertEquals(1, reads)
    }

    @Test
    fun discovery_failure_retriedAfterWindow() {
        val scanner = scanner("https://betting.example.com")
        var reads = 0
        assertNull(scanner.viewIdFor("com.instagram.android") { reads++; chromeRoot("x") })
        // A browser whose toolbar was missing at first probe (fullscreen video, first paint)
        // must not stay unscanned for the process lifetime — that would silently bypass
        // every URL rule.
        idleFor(Duration.ofSeconds(11))
        assertNull(scanner.viewIdFor("com.instagram.android") { reads++; chromeRoot("x") })
        assertEquals(2, reads)
    }

    @Test
    fun discovery_foreignTree_isNotProbedOrCached() {
        val scanner = scanner("https://betting.example.com")
        val foreign = chromeRoot("x").apply { packageName = "com.other.app" }
        assertNull(scanner.viewIdFor("com.opera.browser") { foreign })
        // The package's own tree still discovers it: the foreign probe cached nothing.
        assertEquals("com.opera.browser:id/url_bar", scanner.viewIdFor("com.opera.browser") { chromeRoot("x") })
    }

    @Test
    fun discovery_unknownPackageTreeRoot_isProbed() {
        // Robolectric roots carry no package name; a null tree package must not block the probe.
        val scanner = scanner("https://betting.example.com")
        assertEquals("com.brave.browser:id/url_bar", scanner.viewIdFor("com.brave.browser") { chromeRoot("x") })
    }

    // --- Scanning ---

    @Test
    fun matchingUrl_blocksAfterDebounce() {
        val scanner = scanner("https://betting.example.com/sports")
        val viewId = scanner.viewIdFor("com.android.chrome") { chromeRoot("x") }!!
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        scanner.onContentChanged("com.android.chrome", viewId, { chromeRoot("x") }, gen)
        assertTrue(results.isEmpty()) // debounce: nothing before the delay elapses
        idle()
        assertEquals(1, results.size)
        assertEquals(BlockDecision.Block("CRITICAL", true), results[0].second)
        assertEquals(gen, results[0].first)
        assertEquals(listOf("com.android.chrome:id/url_bar"), extractedViewIds)
    }

    @Test
    fun newerGeneration_discardsResult() {
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        val scanner = scanner("https://betting.example.com")
        scanner.onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("x") }, gen)
        stateMachine.advance("com.instagram.android", 2, detectionTimeNanos = 2L)
        idle()
        assertTrue(results.isEmpty())
    }

    @Test
    fun rapidEvents_debounceIntoOneScan() {
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        val scanner = scanner("https://betting.example.com")
        scanner.onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("a") }, gen)
        scanner.onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("b") }, gen)
        scanner.onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("c") }, gen)
        idle()
        assertEquals(1, results.size) // three events collapse into one scan
    }

    @Test
    fun noAddressBarText_isSilentNoOp() {
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        scanner(null).onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("x") }, gen)
        idle()
        assertTrue(results.isEmpty())
    }

    @Test
    fun nullRoot_isSilentNoOp() {
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        scanner("https://betting.example.com").onContentChanged("com.android.chrome", CHROME_BAR, { null }, gen)
        idle()
        assertTrue(results.isEmpty())
    }

    @Test
    fun omniboxHintText_isNoOp() {
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        scanner("Search or type URL").onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("x") }, gen)
        idle()
        // Dotless host, matches no pattern: deciding it would Allow and dismiss a live card
        // over a still-blocked page (device-caught 2026-09-13).
        assertTrue(results.isEmpty())
    }

    @Test
    fun partialHostWhileTyping_isNoOp() {
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        scanner("ex").onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("x") }, gen)
        idle()
        assertTrue(results.isEmpty())
    }

    @Test
    fun schemeNavigation_allowsWithNoCanonical() {
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        scanner("about:blank").onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("x") }, gen)
        idle()
        assertEquals(1, results.size)
        assertEquals(BlockDecision.Allow, results[0].second)
        assertNull(canonicals[0])
    }

    @Test
    fun unblockedSite_allows() {
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        scanner("https://example.com").onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("x") }, gen)
        idle()
        assertEquals(1, results.size)
        assertEquals(BlockDecision.Allow, results[0].second)
    }

    // --- Daily-limit quota through the URL path (device-caught 2026-10-17) ---

    private fun scanLimitedSite(usageTodayMs: (String) -> Long?) {
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        scanner("https://limited.example.com", usageTodayMs)
            .onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("x") }, gen)
        idle()
    }

    @Test
    fun exhaustedDailyLimit_blocksTheUrlScan() {
        scanLimitedSite { if (it == "r2") 60_000L else null }
        assertEquals(1, results.size)
        assertEquals(BlockDecision.Block("HIGH", true), results[0].second)
    }

    @Test
    fun exhaustedLimit_blocksTheSameSiteInEveryDiscoveredBrowser() {
        // Usage is metered per domain, not per browser: whichever browser the user is in, the
        // same rule and the same exhausted counter decide. Discovery finds each browser's own
        // toolbar ID (Chrome/Brave url_bar, Firefox url_bar_text).
        val scanner = scanner("https://limited.example.com", usageTodayMs = { if (it == "r2") 60_000L else null })
        for (pkg in listOf("com.android.chrome", "com.brave.browser", "org.mozilla.firefox")) {
            val gen = stateMachine.advance(pkg, 1, detectionTimeNanos = 1L)
            val viewId = scanner.viewIdFor(pkg) { chromeRoot("x") }!!
            scanner.onContentChanged(pkg, viewId, { chromeRoot("x") }, gen)
            idle()
        }
        assertEquals(3, results.size)
        assertTrue(results.all { it.second == BlockDecision.Block("HIGH", true) })
    }

    @Test
    fun underDailyLimit_allowsTheUrlScan() {
        scanLimitedSite { if (it == "r2") 59_999L else null }
        assertEquals(listOf(BlockDecision.Allow), results.map { it.second })
    }

    @Test
    fun usageUnavailable_failsOpen() {
        scanLimitedSite { null }
        assertEquals(listOf(BlockDecision.Allow), results.map { it.second })
    }

    // --- Address-bar edit session (device-caught 2026-10-17) ---

    @Test
    fun editing_autocompletePreview_defersTheScan() {
        // Chrome writes "instagram.com" into the bar while the user types "insta"; the page
        // never loads. A decision here blocked a site the user never opened.
        val scanner = scanner("https://betting.example.com", focused = { true })
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        scanner.onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("x") }, gen)
        idle()
        assertTrue(results.isEmpty())
    }

    @Test
    fun editing_commitAfterEditing_blocksNormally() {
        var focused = true
        val scanner = scanner("https://betting.example.com", focused = { focused })
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        scanner.onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("x") }, gen)
        idle()
        assertTrue(results.isEmpty())
        focused = false // the user committed; focus left the bar and the page is loading
        scanner.onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("x") }, gen)
        idle()
        assertEquals(1, results.size)
        assertEquals(BlockDecision.Block("CRITICAL", true), results[0].second)
    }

    @Test
    fun editing_evaluatedAtFireTime_notEventTime() {
        // A commit inside the debounce window must decide: the state is read at fire time.
        var focused = true
        val scanner = scanner("https://betting.example.com", focused = { focused })
        val gen = stateMachine.advance("com.android.chrome", 1, detectionTimeNanos = 1L)
        scanner.onContentChanged("com.android.chrome", CHROME_BAR, { chromeRoot("x") }, gen)
        focused = false
        idle()
        assertEquals(1, results.size)
        assertEquals(BlockDecision.Block("CRITICAL", true), results[0].second)
    }

    private fun idleFor(duration: Duration) =
        shadowOf(android.os.Looper.getMainLooper()).idleFor(duration)

    private companion object {
        const val CHROME_BAR = "com.android.chrome:id/url_bar"
    }
}
