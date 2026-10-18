package com.example.myexodus.domainusage

/**
 * FR-1: the metering engine's view of a website rule. [pattern] is the normalized host key;
 * [revision] is opaque to the UI and bounds a rule's identity across pattern edits (§4.5,
 * D-32) so a still-open flow keeps attributing to the revision it opened against.
 *
 * [expiresAt] is the rule's absolute expiry (epoch ms; 0 = never), carried through from
 * [com.example.myexodus.blocking.UrlPattern.blockExpiresAt] so every decision point can consult
 * it with a clock. The snapshot publisher still drops already-expired rules at build time; this
 * field is what stops a rule that lapses *between* rebuilds from being blocked or metered
 * (audit A-02). The `>` comparison matches `RestrictionRule.isExpiredAt` and
 * `BlockDecisionEngine` exactly, so all three authorities agree on the boundary instant.
 */
data class DomainRule(
    val id: String,
    val revision: Long,
    val pattern: String,
    val dailyLimitMs: Long,
    val enforcedToday: Boolean,
    val expiresAt: Long = 0L,
) {
    fun isExpiredAt(nowMillis: Long): Boolean = expiresAt > 0L && nowMillis > expiresAt
}
