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

    // Urge Assist preferences (plan §H2). All local, optional, and resettable; none of them is
    // ever mirrored to a partner.

    /** null = not answered. Unknown is never read as "cannot move". */
    var canMove: Boolean?
        get() = when (prefs.getString(KEY_CAN_MOVE, null)) {
            VALUE_YES -> true
            VALUE_NO -> false
            else -> null
        }
        set(value) = prefs.edit {
            when (value) {
                true -> putString(KEY_CAN_MOVE, VALUE_YES)
                false -> putString(KEY_CAN_MOVE, VALUE_NO)
                null -> remove(KEY_CAN_MOVE)
            }
        }

    var quietPreferred: Boolean
        get() = prefs.getBoolean(KEY_QUIET, false)
        set(value) = prefs.edit { putBoolean(KEY_QUIET, value) }

    /** Crisis-directory region, or null to follow the device locale's country. */
    var crisisRegion: String?
        get() = prefs.getString(KEY_CRISIS_REGION, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_CRISIS_REGION) else putString(KEY_CRISIS_REGION, value)
        }

    /** Actions the user tapped "Not for me" on; hidden until "Show this again". */
    var dislikedActions: Set<String>
        get() = prefs.getStringSet(KEY_DISLIKED, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_DISLIKED, value) }

    /** Actions whose check-in came back better; the engine promotes them. */
    var helpfulActions: Set<String>
        get() = prefs.getStringSet(KEY_HELPFUL, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_HELPFUL, value) }

    fun markDisliked(actionId: String) {
        dislikedActions = dislikedActions + actionId
    }

    fun clearDisliked(actionId: String) {
        dislikedActions = dislikedActions - actionId
    }

    fun markHelpful(actionId: String) {
        helpfulActions = helpfulActions + actionId
    }

    private companion object {
        const val PREFS_NAME = "recovery_prefs"
        const val KEY_HAPTIC = "haptic_pacing"
        const val KEY_BANNER_SHOWN = "gate_banner_shown"
        const val KEY_CAN_MOVE = "assist_can_move"
        const val KEY_QUIET = "assist_quiet_preferred"
        const val KEY_CRISIS_REGION = "crisis_region"
        const val KEY_DISLIKED = "assist_disliked_actions"
        const val KEY_HELPFUL = "assist_helpful_actions"
        const val VALUE_YES = "yes"
        const val VALUE_NO = "no"
    }
}
