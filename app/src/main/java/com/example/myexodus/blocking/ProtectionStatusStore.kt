package com.example.myexodus.blocking

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Why protection is degraded (blocking-core plan §9); null = healthy. */
enum class ProtectionDegradation {
    /** Usage-access (PACKAGE_USAGE_STATS) revoked — app-block detection is impossible. */
    USAGE_ACCESS_REVOKED,

    /** App was stopped via the Android 13+ Task Manager "Stop" action (§8.2). */
    APP_STOPPED,

    /** POST_NOTIFICATIONS denied — the FGS runs but its status notification cannot be shown. */
    NOTIFICATIONS_OFF,

    /** SYSTEM_ALERT_WINDOW revoked on Transsion (TYPE_APPLICATION_OVERLAY path) — the
     *  blocking overlay can't be shown; re-grant restores it. */
    OVERLAY_REVOKED,
}

/**
 * Shared-prefs operational state for app-block protection (plan §8.2/§9). Read by the
 * foreground service, MainActivity's banner, and the notification action receiver. The
 * authoritative block *policy* stays in Room; this holds only degradation bookkeeping.
 */
@Singleton
class ProtectionStatusStore @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var degradation: ProtectionDegradation?
        get() = prefs.getString(KEY_DEGRADATION, null)?.let { runCatching { ProtectionDegradation.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_DEGRADATION) else putString(KEY_DEGRADATION, value.name)
        }

    /** After a Task-Manager Stop, blocking stays off until the user explicitly re-enables. */
    var requiresExplicitReEnable: Boolean
        get() = prefs.getBoolean(KEY_REENABLE_REQUIRED, false)
        set(value) = prefs.edit { putBoolean(KEY_REENABLE_REQUIRED, value) }

    /** Watermark for the §8.2 exit-reason scan: updated on every clean FGS startup. */
    var lastKnownGoodExitCheckEpochMillis: Long
        get() = prefs.getLong(KEY_LAST_KNOWN_GOOD, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_KNOWN_GOOD, value) }

    /** Explicit user re-enable (banner button / notification action): clears both flags. */
    fun reenable() {
        degradation = null
        requiresExplicitReEnable = false
    }

    private companion object {
        const val PREFS_NAME = "protection_status"
        const val KEY_DEGRADATION = "degradation"
        const val KEY_REENABLE_REQUIRED = "reenable_required"
        const val KEY_LAST_KNOWN_GOOD = "last_known_good_epoch_millis"
    }
}
