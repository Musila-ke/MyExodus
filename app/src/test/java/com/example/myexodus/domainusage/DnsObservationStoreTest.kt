package com.example.myexodus.domainusage

import org.junit.Assert.assertEquals
import org.junit.Test

/** U-7: bounded on both axes, monotonic expiry from the record-time clock. */
class DnsObservationStoreTest {

    private val clock = FakeMonotonicClock()
    private val store = DnsObservationStore(clock)

    private val ip = DnsFixtures.ip(1, 2, 3, 4)

    @Test
    fun recordsAndReturnsTheNameUntilTheTtlExpires() {
        store.record(ip, "example.com", 60L)
        assertEquals(listOf("example.com"), store.candidates(ip))
        clock.now = 59_999L
        assertEquals(listOf("example.com"), store.candidates(ip))
        clock.now = 60_000L
        assertEquals(emptyList<String>(), store.candidates(ip))
    }

    @Test
    fun zeroTtlExpiresImmediatelyAndNegativeTtlIsClampedUp() {
        store.record(ip, "zero.example", 0L)
        assertEquals(emptyList<String>(), store.candidates(ip))
        store.record(ip, "negative.example", -10L)
        assertEquals(emptyList<String>(), store.candidates(ip))
    }

    @Test
    fun ttlIsCappedAtTheConfiguredCeiling() {
        store.record(ip, "long.example", MeteringConfig.DNS_TTL_CAP_S.toLong() + 100_000L)
        clock.now = MeteringConfig.DNS_TTL_CAP_S * 1_000L - 1
        assertEquals(listOf("long.example"), store.candidates(ip))
        clock.now = MeteringConfig.DNS_TTL_CAP_S * 1_000L
        assertEquals(emptyList<String>(), store.candidates(ip))
    }

    @Test
    fun namesWithinOneIpEvictTheOldestAtThePerIpCap() {
        for (i in 0 until MeteringConfig.DNS_OBS_MAX_NAMES_PER_IP) {
            store.record(ip, "host$i.example", 600L)
        }
        store.record(ip, "newest.example", 600L)
        val names = store.candidates(ip)
        assertEquals(MeteringConfig.DNS_OBS_MAX_NAMES_PER_IP, names.size)
        assertEquals("host1.example", names.first())
        assertEquals("newest.example", names.last())
    }

    @Test
    fun reRecordingANameRefreshesItsExpiryAndMovesItToTheNewestSlot() {
        store.record(ip, "a.example", 10L)
        clock.now = 5_000L
        store.record(ip, "b.example", 600L)
        clock.now = 9_000L
        store.record(ip, "a.example", 600L)
        assertEquals(listOf("b.example", "a.example"), store.candidates(ip))

        clock.now = 10_000L
        assertEquals(listOf("b.example", "a.example"), store.candidates(ip))
    }

    @Test
    fun theIpLruEvictsTheLeastRecentlyAccessedIpOnly() {
        val small = DnsObservationStore(clock, maxIps = 2)
        val ipA = DnsFixtures.ip(1, 1, 1, 1)
        val ipB = DnsFixtures.ip(2, 2, 2, 2)
        val ipC = DnsFixtures.ip(3, 3, 3, 3)
        small.record(ipA, "a.example", 600L)
        small.record(ipB, "b.example", 600L)
        small.candidates(ipA)
        small.record(ipC, "c.example", 600L)
        assertEquals(emptyList<String>(), small.candidates(ipB))
        assertEquals(listOf("a.example"), small.candidates(ipA))
        assertEquals(listOf("c.example"), small.candidates(ipC))
    }

    @Test
    fun clearDropsEverything() {
        store.record(ip, "example.com", 600L)
        store.clear()
        assertEquals(emptyList<String>(), store.candidates(ip))
    }

    @Test
    fun theFourByteAndIpv4MappedFormsAddressTheSameEntry() {
        val mapped = ByteArray(16).also {
            it[10] = -1; it[11] = -1
            ip.copyInto(it, 12)
        }
        store.record(ip, "example.com", 600L)
        assertEquals(listOf("example.com"), store.candidates(mapped))

        store.record(mapped, "other.example", 600L)
        assertEquals(listOf("example.com", "other.example"), store.candidates(ip))
    }

    @Test
    fun unsupportedAddressLengthsAreIgnoredNotThrown() {
        store.record(ByteArray(5), "example.com", 600L)
        assertEquals(emptyList<String>(), store.candidates(ByteArray(5)))
    }
}
