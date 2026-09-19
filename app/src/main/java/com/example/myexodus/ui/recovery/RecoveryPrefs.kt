package com.example.myexodus.ui.recovery

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recovery surfaces' two UI preferences (plan v3 W1/W6). Both are device-local conveniences,
 * not settings the plan promotes to a screen: the pacer's haptic tick (default off, FR-4) and
 * the one-time "no secure lock" banner the privacy gate owes a device that cannot be gated.
 */
@Singleton
class RecoveryPrefs @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    // Lazy: constructed with the Hilt graph at process start, which can be pre-unlock
    // (LOCKED_BOOT_COMPLETED) — CE SharedPreferences throw until first unlock.
    private val prefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var hapticPacing: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, false)
        set(value) = prefs.edit { putBoolean(KEY_HAPTIC, value) }

    /** True the first call after install/bypass only — the banner explains itself once. */
    fun consumeNoLockBanner(): Boolean {
        if (prefs.getBoolean(KEY_BANNER_SHOWN, false)) return false
        prefs.edit { putBoolean(KEY_BANNER_SHOWN, true) }
        return true
    }

    private companion object {
        const val PREFS_NAME = "recovery_prefs"
        const val KEY_HAPTIC = "haptic_pacing"
        const val KEY_BANNER_SHOWN = "gate_banner_shown"
    }
}
