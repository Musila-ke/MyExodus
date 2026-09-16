package com.example.myexodus.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the first-run permission wizard (plan §8 Step 7) has been completed. Set once the
 * wizard finishes — or the moment it is evaluated and found unnecessary — so a later
 * permission revocation surfaces through the Settings rows and the degraded banner (Step 6),
 * never by re-showing setup.
 */
@Singleton
class SetupStateStore @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var setupSeen: Boolean
        get() = prefs.getBoolean(KEY_SEEN, false)
        set(value) {
            prefs.edit { putBoolean(KEY_SEEN, value) }
        }

    private companion object {
        const val PREFS_NAME = "setup_state"
        const val KEY_SEEN = "seen"
    }
}
