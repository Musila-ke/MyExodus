package com.example.myexodus.domainusage

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stress slice A1 (backlog `project_stress_test_backlog`): the immutable matcher under volume.
 * `RuleIndexTest` pins the semantics at tiny scale; this file pushes the same policy through
 * 1,000–2,000 patterns and hundreds of thousands of probes — full recall, longest-wins under
 * collisions, dedup at volume, and a deliberately generous wall-time ceiling that only trips
 * on a complexity regression (e.g. an accidental O(rules) linear scan per probe), not on CI
 * jitter.
 */
class RuleIndexVolumeStressTest {

    private val counters = EngineCounters()

    private fun rule(id: String, pattern: String, revision: Long = 0L) =
        DomainRule(id = id, revision = revision, pattern = pattern, dailyLimitMs = 60_000L, enforcedToday = true)

    /** s{n}.d{n}.test — pairwise disjoint: no generated pattern is a suffix of another. */
    private fun basePattern(n: Int) = "s$n.d$n.test"

    private fun baseId(n: Int) = "base-$n"

    private fun builtIndex(n: Int) =
        RuleIndex.build((1..n).map { rule(baseId(it), basePattern(it)) }, counters)

    @Test
    fun thousandPatterns_fullRecallOnSelfAndDeepSubdomains() {
        val index = builtIndex(1_000)
        for (n in 1..1_000) {
            assertEquals("exact probe $n", baseId(n), index.match(basePattern(n))!!.id)
            assertEquals("subdomain probe $n", baseId(n), index.match("a.b.${basePattern(n)}")!!.id)
            assertEquals("mixed case probe $n", baseId(n), index.match("A.B.${basePattern(n).uppercase()}")!!.id)
            // A host whose walk passes through the pattern's labels but never lands on it.
            assertNull("foreign suffix probe $n", index.match("x.${basePattern(n)}.evil.test"))
        }
    }

    @Test
    fun longestWins_underTwoThousandPatterns() {
        val rules = (1..1_000).flatMap {
            listOf(rule(baseId(it), basePattern(it)), rule("deep-$it", "deep.${basePattern(it)}", revision = 7L))
        }
        val index = RuleIndex.build(rules, counters)
        for (n in 1..1_000) {
            assertEquals("deep host $n", "deep-$n", index.match("a.b.deep.${basePattern(n)}")!!.id)
            assertEquals("shallow host $n", baseId(n), index.match("a.b.${basePattern(n)}")!!.id)
        }
    }

    @Test
    fun neverBlockExemption_survivesTheVolumeSet() {
        // The exemption is per-HOST: a rule may own the parent pattern while the never-block
        // child host below it still walks through to null (same shape as RuleIndexTest).
        val rules = (1..1_000).map { rule(baseId(it), basePattern(it)) } +
            rule("r-gstatic", "gstatic.com")
        val index = RuleIndex.build(rules, counters)
        assertNull(index.match("connectivitycheck.gstatic.com"))
        // A listed host with no rule at all is exempt regardless.
        assertNull(index.match("play.googleapis.com"))
        // The parent pattern itself still matches — the exemption is not a blanket lift.
        assertEquals("r-gstatic", index.match("www.gstatic.com")!!.id)
    }

    @Test
    fun fiveHundredDuplicatePatterns_countedSmallestIdKept() {
        val duplicates = (1..500).map { rule("dup-${it.toString().padStart(4, '0')}", "dup.test", revision = it.toLong()) }
        val index = RuleIndex.build(duplicates, counters)
        assertEquals("dup-0001", index.match("x.dup.test")!!.id)
        assertEquals(499L, counters.get(Counter.rules_duplicate_pattern))
    }

    @Test
    fun twoHundredThousandProbes_stayUnderTheComplexityCeiling() {
        val index = builtIndex(1_000)
        val probes = (1..200_000).map { "h$it.${basePattern(it % 1_000 + 1)}" }.toTypedArray()
        // Warm-up: one pass so JIT/lazy init is not billed to the measured run.
        for (p in probes.take(10_000)) index.match(p)
        val startNanos = System.nanoTime()
        var hits = 0
        for (p in probes) if (index.match(p) != null) hits++
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        assertEquals("every probe must land on its pattern", 200_000, hits)
        // ~1–2 µs/probe measured; 5 s = 25 µs/probe ceiling catches only a real regression.
        System.err.println("A1 volume: 200k probes in ${elapsedMs}ms")
        assertTrue("200k probes took ${elapsedMs}ms — a linear-scan regression?", elapsedMs < 5_000)
    }

    @Test
    fun concurrentProbes_areConsistentAgainstTheSequentialTruth() {
        val index = builtIndex(1_000)
        val probes = (1..250).map { "a.b.${basePattern(it)}" to baseId(it) }
        val mismatches = AtomicInteger()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 4).map {
                executor.submit {
                    repeat(40) {
                        for ((host, expectedId) in probes) {
                            if (index.match(host)?.id != expectedId) mismatches.incrementAndGet()
                        }
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
        }
        assertEquals(0, mismatches.get())
    }
}
