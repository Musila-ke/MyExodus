package com.example.myexodus.domainusage

/**
 * S2.5: well-known DoH/DoT resolver hosts. A limit on one of these would block the resolver
 * the whole device depends on, so rule creation rejects them (S9) and [RuleIndex.match]
 * returns null for them. Exact hosts and their subdomains.
 */
object EncryptedDnsResolvers {

    private val HOSTS = setOf(
        "dns.google",
        "cloudflare-dns.com",
        "one.one.one.one",
        "mozilla.cloudflare-dns.com",
        "dns.quad9.net",
        "dns.adguard-dns.com",
        "doh.opendns.com",
        "dns.nextdns.io",
    )

    /** Exact host or any subdomain of an entry. */
    fun matches(host: String): Boolean = HOSTS.any { host == it || host.endsWith(".$it") }
}
