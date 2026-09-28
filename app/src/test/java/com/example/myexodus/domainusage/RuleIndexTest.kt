package com.example.myexodus.domainusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** U-3: exact, subdomain, longest-wins, never-block exemption, duplicate-pattern dedup. */
class RuleIndexTest {

    private val counters = EngineCounters()

    private fun rule(id: String, pattern: String) =
        DomainRule(id = id, revision = 0L, pattern = pattern, dailyLimitMs = 60_000L, enforcedToday = true)

    private fun index(vararg rules: DomainRule) = RuleIndex.build(rules.toList(), counters)

    @Test
    fun exactAndSubdomainMatch() {
        val index = index(rule("r1", "example.com"))
        assertEquals("r1", index.match("example.com")!!.id)
        assertEquals("r1", index.match("www.example.com")!!.id)
        assertNull(index.match("example.com.evil.com"))
        assertNull(index.match("other.com"))
    }

    @Test
    fun longestPatternWins() {
        val index = index(rule("r-base", "example.com"), rule("r-sub", "sub.example.com"))
        assertEquals("r-sub", index.match("a.sub.example.com")!!.id)
        assertEquals("r-base", index.match("other.example.com")!!.id)
    }

    @Test
    fun neverBlockHostUnderARuleReturnsNull() {
        val index = index(rule("r-gstatic", "gstatic.com"))
        assertNull(index.match("connectivitycheck.gstatic.com"))
        assertEquals("r-gstatic", index.match("other.gstatic.com")!!.id)
    }

    @Test
    fun resolverHostsNeverMatch() {
        val index = index(rule("r-nextdns", "nextdns.io"))
        assertNull(index.match("dns.nextdns.io"))
        assertNull(index.match("dns.google"))
    }

    @Test
    fun walkStopsAtTwoLabels() {
        val index = index(rule("r1", "b.c"))
        assertEquals("r1", index.match("a.b.c")!!.id)
        assertEquals("r1", index.match("b.c")!!.id)
        assertNull(index.match("c"))
    }

    @Test
    fun caseAndTrailingDotInsensitive() {
        val index = index(rule("r1", "example.com"))
        assertEquals("r1", index.match("WWW.Example.COM.")!!.id)
    }

    @Test
    fun duplicatePatternKeepsSmallestIdAndCounts() {
        val index = index(rule("r-zz", "example.com"), rule("r-aa", "example.com"))
        assertEquals("r-aa", index.match("example.com")!!.id)
        assertEquals(1L, counters.get(Counter.rules_duplicate_pattern))
    }

    @Test
    fun revisionSurvivesTheIndex() {
        val index = index(
            DomainRule(id = "r1", revision = 7L, pattern = "example.com", dailyLimitMs = 60_000L, enforcedToday = true),
        )
        assertEquals(7L, index.match("www.example.com")!!.revision)
    }

}
