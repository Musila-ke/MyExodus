package com.example.myexodus.data.db.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index

/**
 * Recipient-local record of a grant the partner's phone lost while a rule assigned to this
 * device needed it (owner ask 2026-11-10: the Rules & blocks page and its feed must show the
 * permission loss the notification announced). One row per (event, capability); the rule
 * names ride along as newline-joined sanitized labels. Written only when at least one live
 * rule needs the grant, matching the notification's own gate. Detail is retained 30 days.
 */
@Entity(
    tableName = "partner_protection_gap",
    primaryKeys = ["pair_id", "event_id", "capability"],
    indices = [Index(value = ["occurred_at_ms"])],
)
data class PartnerProtectionGap(
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    /** The lost grant, as its [com.example.myexodus.accountability.serialization.MonitoringCapability.wire] token. */
    @ColumnInfo(name = "capability") val capability: String,
    @ColumnInfo(name = "rule_names") val ruleNames: String,
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long,
) {
    fun ruleNameList(): List<String> = ruleNames.split('\n').filter { it.isNotBlank() }

    companion object {
        fun encodeRuleNames(names: List<String>): String = names.joinToString("\n")
    }
}
