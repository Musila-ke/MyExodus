package com.example.myexodus.update

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Local, bounded updater diagnostics (plan §14): no cohort ID, account, partner, screen or
 * protection data. Only the user-initiated copy action leaves the app.
 */
internal class UpdateDiagnostics(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(
        code: String,
        policySequence: Long? = null,
        mode: String? = null,
        versionCode: Long? = null,
        bytes: Long? = null,
        durationMs: Long? = null,
        downloadManagerStatus: String? = null,
        installerStatus: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val entries = bounded(read(), nowMs)
        entries.put(
            JSONObject().apply {
                put("at", nowMs)
                put("code", code)
                put("api", android.os.Build.VERSION.SDK_INT)
                policySequence?.let { put("policy", it) }
                mode?.let { put("mode", it) }
                versionCode?.let { put("version", it) }
                bytes?.let { put("bytes", it) }
                durationMs?.let { put("ms", it) }
                downloadManagerStatus?.let { put("dm", it) }
                installerStatus?.let { put("inst", it) }
            },
        )
        prefs.edit { putString(KEY_ENTRIES, bounded(entries, nowMs).toString()) }
    }

    fun summary(): String {
        val entries = bounded(read(), System.currentTimeMillis())
        return buildString {
            appendLine("MyExodus updater diagnostics")
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                append(Instant.ofEpochMilli(entry.optLong("at")).toString())
                entry.optString("code").takeIf { it.isNotBlank() }?.let { append(" $it") }
                entry.optLong("policy", 0).takeIf { it > 0 }?.let { append(" policy=$it") }
                entry.optString("mode").takeIf { it.isNotBlank() }?.let { append(" mode=$it") }
                entry.optLong("version", 0).takeIf { it > 0 }?.let { append(" version=$it") }
                entry.optInt("api", 0).takeIf { it > 0 }?.let { append(" api=$it") }
                entry.optLong("bytes", 0).takeIf { it > 0 }?.let { append(" bytes=$it") }
                entry.optLong("ms", 0).takeIf { it > 0 }?.let { append(" ms=$it") }
                entry.optString("dm").takeIf { it.isNotBlank() }?.let { append(" dm=$it") }
                entry.optString("inst").takeIf { it.isNotBlank() }?.let { append(" inst=$it") }
                appendLine()
            }
        }
    }

    private fun read(): JSONArray = runCatching {
        JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
    }.getOrDefault(JSONArray())

    private fun bounded(entries: JSONArray, nowMs: Long): JSONArray {
        val cutoff = nowMs - MAX_AGE_MS
        val kept = JSONArray()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            if (entry.optLong("at") >= cutoff) kept.put(entry)
        }
        val bounded = JSONArray()
        val first = maxOf(0, kept.length() - MAX_ENTRIES)
        for (index in first until kept.length()) bounded.put(kept.optJSONObject(index))
        return bounded
    }

    private companion object {
        const val PREFS = "update_diagnostics"
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 200
        const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    }
}
