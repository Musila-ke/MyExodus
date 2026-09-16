package com.example.myexodus.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-side Enhanced Mode master switch (plan §8 Step 11: Strict Mode gatekeeps disabling
 * Enhanced Mode from the app's own settings screen — this switch is that control). The
 * accessibility service reads it per event; OS-level Settings/Accessibility remains the
 * real enable/disable path and is never intercepted.
 */
@Singleton
class EnhancedModeSettings @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    // Lazy: constructed with the Hilt graph at process start, which can be pre-unlock
    // (LOCKED_BOOT_COMPLETED, plan §8.5) — CE SharedPreferences throw until first unlock.
    // Device-caught 2026-09-15: eager init here killed the pre-unlock process through the
    // ProtectionStatusMonitor dependency chain (reboot leg h1: FATAL on cold boot).
    private val prefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var enhancedModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit { putBoolean(KEY_ENABLED, value) }
        }

    private companion object {
        const val PREFS_NAME = "enhanced_mode_settings"
        const val KEY_ENABLED = "enabled"
    }
}
