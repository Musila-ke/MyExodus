package com.example.myexodus.blocking

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Why protection is degraded (blocking-core plan §9); null = healthy. The authority-derived
 *  reasons are computed by [ProtectionStatusMonitor] (§8 Step 6) — nothing writes this store
 *  from a permission check anymore. */
enum class ProtectionDegradation {
    /** Neither detection path can see apps (accessibility unavailable AND polling not
     *  possible or not enrolled) — no enforcement is running. */
    DEGRADED_NO_DETECTION,

    /** App was stopped via the Android 13+ Task Manager "Stop" action (§8.2). */
    APP_STOPPED,

    /** POST_NOTIFICATIONS denied — the FGS runs but its status notification cannot be shown. */
    NOTIFICATIONS_OFF,
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
    // Lazy: constructed with the Hilt graph at process start, which can be pre-unlock
    // (LOCKED_BOOT_COMPLETED, plan §8.5) — CE SharedPreferences throw until first unlock.
    // Same device-caught class as the health monitor's prefs (2026-09-15, reboot leg h1).
    private val prefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var degradation: ProtectionDegradation?
        get() = prefs.getString(KEY_DEGRADATION, null)?.let { runCatching { ProtectionDegradation.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_DEGRADATION) else putString(KEY_DEGRADATION, value.name)
        }

    /** After a Task-Manager Stop, blocking stays off until the user explicitly re-enables.
     *
     *  commit (not apply): this flag and the watermark below gate whether an old exit event can
     *  be re-detected, and XOS SIGKILLs this process mid-session routinely — an async apply()
     *  flushed after the kill is a lost write that made a days-old stop re-fire (X6532,
     *  2026-10-14: a 10-12 force-stop was re-detected at the 10-13 and 10-14 starts). */
    var requiresExplicitReEnable: Boolean
        get() = prefs.getBoolean(KEY_REENABLE_REQUIRED, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_REENABLE_REQUIRED, value) }

    /** Watermark for the §8.2 exit-reason scan: updated on every clean FGS startup. */
    var lastKnownGoodExitCheckEpochMillis: Long
        get() = prefs.getLong(KEY_LAST_KNOWN_GOOD, 0L)
        set(value) = prefs.edit(commit = true) { putLong(KEY_LAST_KNOWN_GOOD, value) }

    /** Explicit user re-enable (banner button / notification action).
     *
     *  Advances the watermark with the clear: the tap asserts "from here on", so no exit
     *  event that predates it can ever re-fire — even if the detection-time watermark write
     *  was lost to an old build's async apply(). There is no masking window: a re-enable tap
     *  can only happen while this process is running, and a running process could not have
     *  been stop-killed between its last scan and the tap.
     *
     *  Deliberately clears only the flag, not [degradation]: the degradation value and the
     *  posted alert are owned by [ProtectionStatusMonitor]'s publish, which derives the value
     *  from the flag plus current authority — nulling it here would pre-empt that derivation
     *  (audit finding #14). Both call sites refresh immediately after. */
    fun reenable() {
        // maxOf: a backward clock correction must never regress the watermark — an exit record
        // stamped before the jump would then sit "after" it again (same re-fire class).
        val stamp = maxOf(prefs.getLong(KEY_LAST_KNOWN_GOOD, 0L), System.currentTimeMillis())
        prefs.edit(commit = true) {
            putBoolean(KEY_REENABLE_REQUIRED, false)
            putLong(KEY_LAST_KNOWN_GOOD, stamp)
        }
    }

    private companion object {
        const val PREFS_NAME = "protection_status"
        const val KEY_DEGRADATION = "degradation"
        const val KEY_REENABLE_REQUIRED = "reenable_required"
        const val KEY_LAST_KNOWN_GOOD = "last_known_good_epoch_millis"
    }
}
