package com.example.myexodus.ui.rules

import com.example.myexodus.ui.toggleDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editor-side target/day validation — the gate that keeps dead rules out of the DB. */
class RuleTargetsTest {

    @Test
    fun websiteTarget_acceptsBrowserForms_rejectsWhatCanNeverMatch() {
        assertTrue(isValidWebsiteTarget("example.com"))
        assertTrue(isValidWebsiteTarget("https://example.com/path?q=1"))
        assertTrue(isValidWebsiteTarget("betting.example.com:8443/forum"))

        assertFalse(isValidWebsiteTarget(""))
        assertFalse("A host with no dot matches no address", isValidWebsiteTarget("asdf"))
        assertFalse("Spaces never appear in an address", isValidWebsiteTarget("my site.com"))
        assertFalse(isValidWebsiteTarget("https://exa mple.com"))
        assertFalse(isValidWebsiteTarget("blob:https://x/y"))
    }

    @Test
    fun targetKey_normalizesFormAndCase_soDuplicatesAreCaught() {
        assertEquals(targetKey("Example.COM", "WEBSITE"), targetKey("https://example.com/", "WEBSITE"))
        assertEquals(
            targetKey("https://betting.example.com:8443/forum", "WEBSITE"),
            targetKey("betting.example.com:8443/forum", "WEBSITE"),
        )
        assertEquals(targetKey("com.Instagram.Android", "APP"), targetKey("com.instagram.android", "APP"))
    }

    @Test
    fun websiteTarget_emojiAndOverlongHosts_surviveCanonicalization() {
        // adb cannot type emoji (input text is ASCII-only), so this path is pinned in JVM:
        // canonicalization must never throw on a label the IDN transform rejects.
        assertTrue("Emoji hosts are valid IDN, and must not crash the editor", isValidWebsiteTarget("😀.com"))
        assertEquals(targetKey("😀.com", "WEBSITE"), targetKey("😀.com", "WEBSITE"))

        val long = "a".repeat(80) + ".com"
        assertTrue(isValidWebsiteTarget(long))
        assertEquals(long, targetKey(long, "WEBSITE"))

        assertTrue(isValidWebsiteTarget("${"b".repeat(70)}.com"))
    }

    @Test
    fun toggleDay_flipsOneDay_permanentlySevenChars() {
        assertEquals("0111111", toggleDay("1111111", 0))
        assertEquals("1111111", toggleDay("0111111", 0))
        assertEquals("1111110", toggleDay("1111111", 6))
        assertEquals("1000000", toggleDay("0000000", 0))
    }

    @Test
    fun toggleDay_neverClearsTheLastDay() {
        // "No days" would never match, so the rule could never fire — the guard lives in the
        // pure function (both editors inherit it), not in the chip's onClick.
        assertEquals("0000010", toggleDay("0000010", 5))
        assertEquals("1000000", toggleDay("1000000", 0))
        assertEquals("can still flip when two remain", "0000001", toggleDay("0000011", 5))
    }
}
