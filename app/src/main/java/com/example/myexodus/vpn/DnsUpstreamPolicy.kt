package com.example.myexodus.vpn

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Upstream DNS selection and bounded-retry policy (ADR #318). A hardcoded resolver list is a
 * single point of failure: networks that block or intercept 8.8.8.8 (public-Wi-Fi portals,
 * carrier filters) broke all resolution. Queries try the hardcoded primary first, then the
 * current network's own resolvers, one attempt per second, and fail fast with SERVFAIL
 * instead of hanging.
 *
 * Blocked names are answered locally before this policy is consulted, so fallback queries
 * only ever carry names the user is allowed to resolve.
 */
object DnsUpstreamPolicy {

    /** One retransmission per second per upstream candidate. */
    const val RETRY_INTERVAL_MS = 1_000L

    /** Three sends total (first send + two retries), then SERVFAIL to the device. */
    const val MAX_ATTEMPTS = 3

    /**
     * Family-matched, primary-first, deduplicated candidate list. [networkDns] is the active
     * non-VPN network's resolver set; [exclude] drops the sinkhole's own advertised resolvers
     * so a fallback query can never be forwarded back into the TUN.
     */
    fun candidates(
        primary: InetAddress,
        networkDns: List<InetAddress>,
        isV6: Boolean,
        exclude: Set<InetAddress>,
    ): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        for (address in listOf(primary) + networkDns) {
            if ((address is Inet6Address) != isV6) continue
            if (address in exclude) continue
            out.add(address)
        }
        return out.toList()
    }

    /** Round-robin over candidates: attempt 0 -> first candidate, attempt 1 -> second, ... */
    fun pick(candidates: List<InetAddress>, attempt: Int): InetAddress? =
        if (candidates.isEmpty()) null else candidates[attempt % candidates.size]

    /**
     * A dead v6 upstream is only a real outage when the network can actually route v6 across
     * the internet. Link-local (fe80::/10) and site-local (fec0::/10) addresses do not: the
     * emulator and some LANs hand them out with no v6 DNS path at all, and treating them as
     * one wedges health at DEGRADED forever after a single died AAAA query.
     */
    fun hasGlobalV6Path(addresses: List<InetAddress>): Boolean = addresses.any {
        it is Inet6Address && !it.isLinkLocalAddress && !it.isSiteLocalAddress
    }
}
