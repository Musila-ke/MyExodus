package com.example.myexodus.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strict Mode (plan §8 Step 11, Decision 15): a 24-hour cooldown starts the moment Strict
 * Mode is enabled; until it expires, neither the Enhanced Mode master switch nor Strict Mode
 * itself can be turned off from MyExodus's settings screen. The countdown is persisted, so it
 * survives process death. Android's own Settings/Accessibility screen is out of scope here —
 * disabling the service there always succeeds immediately and is never intercepted (Decision 6).
 *
 * The accountability-partner cryptographic-approval path (§16) ships as a feature flag,
 * default off; only the flag exists in v1.
 */
@Singleton
class StrictModeManager internal constructor(
    private val appContext: Context,
    private val nowMillis: () -> Long,
) {

    @Inject constructor(@ApplicationContext appContext: Context) :
        this(appContext, System::currentTimeMillis)

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var strictModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_STRICT, false)
        set(value) {
            prefs.edit {
                putBoolean(KEY_STRICT, value)
                putLong(KEY_COOLDOWN_UNTIL, if (value) nowMillis() + COOLDOWN_MILLIS else 0L)
            }
        }

    val cooldownActive: Boolean
        get() = strictModeEnabled && cooldownRemainingMillis > 0L

    val cooldownRemainingMillis: Long
        get() = if (strictModeEnabled) {
            maxOf(0L, prefs.getLong(KEY_COOLDOWN_UNTIL, 0L) - nowMillis())
        } else {
            0L
        }

    /** The in-app disable controls are locked until the 24h cooldown expires. */
    fun canDisable(): Boolean = !PARTNER_APPROVAL_ENABLED && !cooldownActive

    private companion object {
        const val PREFS_NAME = "strict_mode"
        const val KEY_STRICT = "enabled"
        const val KEY_COOLDOWN_UNTIL = "cooldown_until_epoch_millis"
        const val COOLDOWN_MILLIS = 24L * 60 * 60 * 1000

        // Feature flag (Decision 15): partner-approval protocol ships separately (§16).
        // Flip this and add the approval check in canDisable() when that module lands.
        const val PARTNER_APPROVAL_ENABLED = false
    }
}
