package com.example.myexodus.telemetry

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** One class so every §14 event groups under a single crash-console issue type. */
class NonFatalEvent(message: String) : RuntimeException(message)

/**
 * Crashlytics transport for plan §14: fatal reports come from the SDK automatically; the
 * named non-fatal events go through [nonFatal]. Decision 12's data contract: metadata only —
 * event name, cause, OEM, API level — never package names, durations, or limits, so callers
 * must pass only Decision-12-shaped fields.
 *
 * Opt-out (Decision 12): collection is on by default; the Settings toggle persists here and
 * [applyStoredPreference] re-applies it at every process start (the FirebaseInitProvider runs
 * before Application.onCreate, so this is what overrides the manifest default).
 */
@Singleton
class CrashReporting @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    // CE storage: pre-unlock process starts (the manifest's direct-boot receiver) can't touch
    // these prefs — they throw. Read as enabled (the default) and skip persistence instead of
    // crashing startup; the stored choice is re-read on the post-unlock start.
    private val prefs: SharedPreferences? by lazy {
        runCatching { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }.getOrNull()
    }

    var enabled: Boolean
        get() = runCatching { prefs?.getBoolean(KEY_ENABLED, true) ?: true }.getOrDefault(true)
        set(value) {
            runCatching { prefs?.edit { putBoolean(KEY_ENABLED, value) } }
            setCollectionEnabled(value)
        }

    /** Called from MyExodusApp.onCreate; also covers a killed process on next start. */
    fun applyStoredPreference() = setCollectionEnabled(enabled)

    /**
     * Best-effort by design: telemetry must never break enforcement (the AttemptRecorder
     * contract). Silently no-ops when opted out and swallows SDK failures.
     */
    fun nonFatal(event: String, cause: String? = null, vararg detail: Pair<String, String>) {
        if (!enabled) return
        val message = buildString {
            append(event)
            cause?.let { append(" cause=").append(it) }
            detail.forEach { (k, v) -> append(' ').append(k).append('=').append(v) }
        }
        runCatching { FirebaseCrashlytics.getInstance().recordException(NonFatalEvent(message)) }
    }

    /**
     * Feature-outcome rail (task #7, owner direction 2026-11-06): non-crash flow outcomes a
     * remote tester can report as "X doesn't work". Same Decision-12 contract as [nonFatal] —
     * event, outcome, reason code, OEM, API level; never labels, package names, durations, or
     * limits — and the same Settings opt-out.
     */
    fun featureOutcome(event: String, outcome: String, reason: String? = null) {
        val detail = buildList {
            reason?.let { add("reason" to it) }
            add("oem" to Build.MANUFACTURER)
            add("api" to Build.VERSION.SDK_INT.toString())
        }
        nonFatal(event, cause = outcome, *detail.toTypedArray())
    }

    private fun setCollectionEnabled(value: Boolean) {
        runCatching { FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(value) }
    }

    private companion object {
        const val PREFS_NAME = "telemetry_settings"
        const val KEY_ENABLED = "crash_reports_enabled"
    }
}
