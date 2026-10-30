package com.example.myexodus.account

import org.json.JSONArray
import org.json.JSONObject

/**
 * Account plan §8.4: the wire form of one quarantine record, stored only inside the encrypted
 * file. Versioned and strict on decode — an unknown or malformed payload is "unreadable", never
 * a partial import (§6.3.6: no silently dropped entities).
 *
 * The entity fields are the shared [LogicalSnapshotCodec] allowlist, which the backup payload
 * also uses (plan §2.1 item 11). Attempt delivery bookkeeping (`event_sequence`) is deliberately
 * not carried: the guest pairs it referred to are terminated by the same transition (plan
 * §6.2.9), so an imported row starts with no audience and can never be re-delivered.
 */
internal object GuestQuarantineCodec {

    const val VERSION = 1

    fun encode(record: GuestQuarantineRecord): String {
        val json = JSONObject()
            .put("version", VERSION)
            .put("transitionId", record.transitionId)
            .put("guestUid", record.guestUid)
            .put("importOperationId", record.importOperationId)
            .put("intent", record.intent.name)
            .put("stagedAtMs", record.stagedAtMs)
            .putOpt("targetUid", record.targetUid)
            .putOpt("importedAtMs", record.importedAtMs)
            .putOpt("summary", record.summary?.let(::encodeSummary))

        val s = record.snapshot
        json.put("habits", JSONArray().apply { s.habits.forEach { put(LogicalSnapshotCodec.habit(it)) } })
        json.put("streaks", JSONArray().apply { s.streaks.forEach { put(LogicalSnapshotCodec.streak(it)) } })
        json.put("milestones", JSONArray().apply { s.milestones.forEach { put(LogicalSnapshotCodec.milestone(it)) } })
        json.put("attempts", JSONArray().apply { s.attempts.forEach { put(LogicalSnapshotCodec.attempt(it)) } })
        json.put("sessions", JSONArray().apply { s.sessions.forEach { put(LogicalSnapshotCodec.session(it)) } })
        json.put("coaching", JSONArray().apply { s.coaching.forEach { put(LogicalSnapshotCodec.coaching(it)) } })
        json.put("riskWindows", JSONArray().apply { s.riskWindows.forEach { put(LogicalSnapshotCodec.riskWindow(it)) } })
        json.putOpt("policy", s.policy?.let(LogicalSnapshotCodec::policy))
        return json.toString()
    }

    /** @throws org.json.JSONException on any structural mismatch. */
    fun decode(text: String): GuestQuarantineRecord {
        val json = JSONObject(text)
        require(json.getInt("version") == VERSION) { "unsupported quarantine version" }
        return GuestQuarantineRecord(
            transitionId = json.getString("transitionId"),
            guestUid = json.getString("guestUid"),
            importOperationId = json.getString("importOperationId"),
            intent = TransitionIntent.valueOf(json.getString("intent")),
            stagedAtMs = json.getLong("stagedAtMs"),
            targetUid = json.optNullableString("targetUid"),
            importedAtMs = json.optNullableLong("importedAtMs"),
            summary = json.optJSONObject("summary")?.let(::decodeSummary),
            snapshot = GuestSnapshot(
                habits = json.getJSONArray("habits").mapObjects(LogicalSnapshotCodec::habit),
                streaks = json.getJSONArray("streaks").mapObjects(LogicalSnapshotCodec::streak),
                milestones = json.getJSONArray("milestones").mapObjects(LogicalSnapshotCodec::milestone),
                attempts = json.getJSONArray("attempts").mapObjects(LogicalSnapshotCodec::attempt),
                sessions = json.getJSONArray("sessions").mapObjects(LogicalSnapshotCodec::session),
                coaching = json.getJSONArray("coaching").mapObjects(LogicalSnapshotCodec::coaching),
                riskWindows = json.getJSONArray("riskWindows").mapObjects(LogicalSnapshotCodec::riskWindow),
                policy = json.optJSONObject("policy")?.let(LogicalSnapshotCodec::policy),
            ),
        )
    }

    private fun encodeSummary(s: ImportSummary) = JSONObject()
        .put("inserted", s.inserted)
        .put("updated", s.updated)
        .put("deduplicated", s.deduplicated)
        .put("conflicts", s.conflicts)

    private fun decodeSummary(j: JSONObject) = ImportSummary(
        inserted = j.getInt("inserted"),
        updated = j.getInt("updated"),
        deduplicated = j.getInt("deduplicated"),
        conflicts = j.getInt("conflicts"),
    )
}
