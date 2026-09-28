package com.example.myexodus.domainusage

/**
 * S2.4: hosts that never match a rule, so they are never subject to FR-8 shaping (INV-11) —
 * the OS's own connectivity probes, plus every host the Recovery Resource UI links to (the
 * escape hatch must stay reachable at all times).
 */
object NeverBlockDomains {

    private val CONNECTIVITY_PROBES = listOf(
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "clients3.google.com",
        "play.googleapis.com",
    )

    /**
     * Hosts of every URL the Recovery Resource UI links to — the `url` fields of
     * `assets/crisis_resources.json`, the only fields `ui/recovery/CrisisScreen.kt` opens
     * (verified 2026-09-20; the entries' `source` citations are rendered as text, not links,
     * so they are deliberately absent). Subdomain matching covers `www.` prefixes.
     */
    val HELP_RESOURCE_DOMAINS = setOf(
        "988lifeline.org",
        "findahelpline.com",
        "befrienderskenya.org",
        "crisistextline.org",
        "redcross.or.ke",
        "samhsa.gov",
    )

    private val HOSTS = CONNECTIVITY_PROBES.toSet() + HELP_RESOURCE_DOMAINS

    /** Exact host or any subdomain of an entry. */
    fun matches(host: String): Boolean = HOSTS.any { host == it || host.endsWith(".$it") }
}
