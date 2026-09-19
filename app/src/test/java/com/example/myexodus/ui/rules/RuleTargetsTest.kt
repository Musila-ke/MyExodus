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
    fun toggleDay_flipsOneDay_permanentlySevenChars() {
        assertEquals("0111111", toggleDay("1111111", 0))
        assertEquals("1111111", toggleDay("0111111", 0))
        assertEquals("1111110", toggleDay("1111111", 6))
        assertEquals("1000000", toggleDay("0000000", 0))
    }
}
