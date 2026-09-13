package com.example.myexodus.blocking

import android.util.Log
import com.example.myexodus.data.db.entity.RestrictionRule
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable, fully materialized ruleset published via [AtomicReference] (Enhanced Mode spec
 * §8 Step 2, Architecture Decision 4): atomic consistent reads during concurrent rule
 * updates, no partial-update states. The blocking engine reads ONLY this object — never the
 * database — on the accessibility hot path.
 *
 * Serialized to the on-disk fast-path cache (see [writeTo]/[readFrom]) as JSON. Written
 * atomically (temp file + rename) so a crash mid-write can never corrupt the last-known-good
 * snapshot the fast path loads synchronously at service connect.
 */
data class RulesSnapshot(
    val appRules: Map<String, Rule>,
    val urlRules: List<UrlPattern>,
    val version: Long,
) {
    /** Empty-but-published snapshot: published by the fast path when no cache exists yet. */
    companion object {
        val EMPTY = RulesSnapshot(emptyMap(), emptyList(), 0L)

        /** Ruleset caps (plan §8 Step 2): linear URL matching is fine at this scale. */
        const val MAX_APP_RULES = 2000
        const val MAX_URL_RULES = 2000

        fun build(rules: List<RestrictionRule>, version: Long): RulesSnapshot {
            val apps = LinkedHashMap<String, Rule>()
            val urls = mutableListOf<UrlPattern>()
            for (rule in rules) {
                // Paused rules drop out of enforcement entirely; the single filter point so
                // no engine path can decide against a disabled rule.
                if (!rule.isEnabled) continue
                when (rule.targetType) {
                    "APP" -> apps[rule.blockedPackage] = Rule(
                        ruleId = rule.ruleId,
                        packageName = rule.blockedPackage,
                        isAlwaysOn = rule.isAlwaysOn,
                        dailyLimitMs = rule.dailyLimitMs,
                        timeRangeStart = rule.timeRangeStart,
                        timeRangeEnd = rule.timeRangeEnd,
                        activeDaysOfWeek = rule.activeDaysOfWeek,
                        timezone = rule.timezone,
                        blockExpiresAt = rule.blockExpiresAt,
                        severityLevel = rule.severityLevel,
                    )
                    "WEBSITE" -> UrlPattern.from(rule)?.let(urls::add)
                    else -> Log.w(TAG, "Snapshot build: unknown target_type ${rule.targetType} for ${rule.ruleId}")
                }
            }
            return RulesSnapshot(apps, urls, version)
        }

        private const val TAG = "MyExodusRules"

        fun readFrom(file: File): RulesSnapshot? {
            if (!file.exists()) return null
            return try {
                val json = JSONObject(file.readText())
                val expected = json.optString("checksum", "")
                if (expected.isNotEmpty()) {
                    // Hash the body WITHOUT the checksum field, matching writeTo's hash.
                    json.remove("checksum")
                    if (expected != sha256(json.toString())) {
                        Log.e(TAG, "Snapshot cache checksum mismatch (${file.name}); discarding")
                        file.delete()
                        null
                    } else {
                        fromJson(json)
                    }
                } else {
                    fromJson(json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Snapshot cache unreadable (${file.name}); discarding", e)
                file.delete()
                null
            }
        }

        private fun sha256(text: String): String = try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "SHA-256 unavailable; skipping cache checksum", e)
            ""
        }

        private fun fromJson(json: JSONObject): RulesSnapshot {
            val apps = LinkedHashMap<String, Rule>()
            val appsJson = json.optJSONArray("apps") ?: JSONArray()
            for (i in 0 until appsJson.length()) {
                val o = appsJson.getJSONObject(i)
                val rule = Rule(
                    ruleId = o.getString("ruleId"),
                    packageName = o.getString("packageName"),
                    isAlwaysOn = o.getBoolean("alwaysOn"),
                    dailyLimitMs = o.getLong("dailyLimitMs"),
                    timeRangeStart = o.optLong("timeStart").takeIf { o.has("timeStart") },
                    timeRangeEnd = o.optLong("timeEnd").takeIf { o.has("timeEnd") },
                    activeDaysOfWeek = o.getString("days"),
                    timezone = o.optString("timezone", "UTC"),
                    blockExpiresAt = o.getLong("expiresAt"),
                    severityLevel = o.getString("severity"),
                )
                apps[rule.packageName] = rule
            }
            val urls = mutableListOf<UrlPattern>()
            val urlsJson = json.optJSONArray("urls") ?: JSONArray()
            for (i in 0 until urlsJson.length()) {
                val o = urlsJson.getJSONObject(i)
                urls += UrlPattern(
                    ruleId = o.getString("ruleId"),
                    scheme = o.optString("scheme").takeIf { o.has("scheme") },
                    port = o.optInt("port").takeIf { o.has("port") },
                    hostUnicode = o.getString("hostUnicode"),
                    hostAscii = o.getString("hostAscii"),
                    pathPrefix = o.optString("pathPrefix").takeIf { o.has("pathPrefix") },
                    isAlwaysOn = o.getBoolean("alwaysOn"),
                    dailyLimitMs = o.getLong("dailyLimitMs"),
                    timeRangeStart = o.optLong("timeStart").takeIf { o.has("timeStart") },
                    timeRangeEnd = o.optLong("timeEnd").takeIf { o.has("timeEnd") },
                    activeDaysOfWeek = o.getString("days"),
                    timezone = o.optString("timezone", "UTC"),
                    blockExpiresAt = o.getLong("expiresAt"),
                    severityLevel = o.getString("severity"),
                )
            }
            return RulesSnapshot(apps, urls, json.getLong("version"))
        }
    }

    /** Atomic cache write: temp file + rename, so a crash never leaves a torn snapshot. */
    fun writeTo(file: File) {
        val json = JSONObject().apply {
            put("version", version)
            put("apps", JSONArray(appRules.values.map { r ->
                JSONObject().apply {
                    put("ruleId", r.ruleId)
                    put("packageName", r.packageName)
                    put("alwaysOn", r.isAlwaysOn)
                    put("dailyLimitMs", r.dailyLimitMs)
                    r.timeRangeStart?.let { put("timeStart", it) }
                    r.timeRangeEnd?.let { put("timeEnd", it) }
                    put("days", r.activeDaysOfWeek)
                    put("timezone", r.timezone)
                    put("expiresAt", r.blockExpiresAt)
                    put("severity", r.severityLevel)
                }
            }))
            put("urls", JSONArray(urlRules.map { p ->
                JSONObject().apply {
                    put("ruleId", p.ruleId)
                    p.scheme?.let { put("scheme", it) }
                    p.port?.let { put("port", it) }
                    put("hostUnicode", p.hostUnicode)
                    put("hostAscii", p.hostAscii)
                    p.pathPrefix?.let { put("pathPrefix", it) }
                    put("alwaysOn", p.isAlwaysOn)
                    put("dailyLimitMs", p.dailyLimitMs)
                    p.timeRangeStart?.let { put("timeStart", it) }
                    p.timeRangeEnd?.let { put("timeEnd", it) }
                    put("days", p.activeDaysOfWeek)
                    put("timezone", p.timezone)
                    put("expiresAt", p.blockExpiresAt)
                    put("severity", p.severityLevel)
                }
            }))
        }
        // Checksum over the body WITHOUT the checksum field itself (plan §8.5: versioned cache
        // with checksum — detects silent corruption beyond what atomic-rename prevents).
        val body = json.toString()
        json.put("checksum", sha256(body))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            Log.e(TAG, "Snapshot cache rename failed for ${file.name}")
        }
    }
}
