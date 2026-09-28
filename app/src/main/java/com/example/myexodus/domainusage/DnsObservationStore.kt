package com.example.myexodus.domainusage

/**
 * S3.4 (§6.2): the IP → observed-names map the DNS-fallback attribution reads. Bounded on both
 * axes — a capped, access-ordered LRU over IPs, and at most [MeteringConfig.DNS_OBS_MAX_NAMES_PER_IP]
 * names per IP with the oldest evicted. Expiry is monotonic-only: `mono + clamp(ttl, 0, CAP)`,
 * with no floor, so a short authoritative TTL is honored exactly as given (§16 item 10).
 */
class DnsObservationStore(
    private val clock: MonotonicClock,
    private val maxIps: Int = MeteringConfig.DNS_OBS_MAX_IPS,
    private val maxNamesPerIp: Int = MeteringConfig.DNS_OBS_MAX_NAMES_PER_IP,
) {

    private class IpKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is IpKey && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private class NameEntry(val name: String, val expiresAtElapsed: Long)

    private val lock = Any()
    private val byIp = object : LinkedHashMap<IpKey, ArrayDeque<NameEntry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IpKey, ArrayDeque<NameEntry>>): Boolean =
            size > maxIps
    }

    /** Records [name] as an observed answer for [ip] (4- or 16-byte form). */
    fun record(ip: ByteArray, name: String, ttlSeconds: Long) {
        val key = keyOf(ip) ?: return
        val expires = clock.elapsedMs() +
            ttlSeconds.coerceIn(0L, MeteringConfig.DNS_TTL_CAP_S.toLong()) * 1_000L
        synchronized(lock) {
            val names = byIp.getOrPut(key) { ArrayDeque() }
            names.removeAll { it.name == name }
            names.addLast(NameEntry(name, expires))
            while (names.size > maxNamesPerIp) names.removeFirst()
        }
    }

    /** The unexpired names recorded for [ip], oldest observation first. */
    fun candidates(ip: ByteArray): List<String> {
        val key = keyOf(ip) ?: return emptyList()
        val now = clock.elapsedMs()
        synchronized(lock) {
            val names = byIp[key] ?: return emptyList()
            names.removeAll { it.expiresAtElapsed <= now }
            return names.map { it.name }
        }
    }

    fun clear() {
        synchronized(lock) { byIp.clear() }
    }

    private fun keyOf(ip: ByteArray): IpKey? = when (ip.size) {
        16 -> IpKey(ip.copyOf())
        4 -> IpKey(ByteArray(16).also { IPv4_MAPPED_PREFIX.copyInto(it); ip.copyInto(it, 12) })
        else -> null
    }

    private companion object {
        /** `::ffff:0:0/96` — the 16-byte key form of an IPv4 address. */
        val IPv4_MAPPED_PREFIX = ByteArray(16).also { it[10] = -1; it[11] = -1 }
    }
}
