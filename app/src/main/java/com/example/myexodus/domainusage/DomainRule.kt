package com.example.myexodus.domainusage

/**
 * FR-1: the metering engine's view of a website rule. [pattern] is the normalized host key;
 * [revision] is opaque to the UI and bounds a rule's identity across pattern edits (§4.5,
 * D-32) so a still-open flow keeps attributing to the revision it opened against.
 */
data class DomainRule(
    val id: String,
    val revision: Long,
    val pattern: String,
    val dailyLimitMs: Long,
    val enforcedToday: Boolean,
)
