package com.example.myexodus.applock

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The durable half of the lock (plan §4.2): feature status and migration journal, nothing
 * else. CE SharedPreferences are excluded from every backup rule and are already unreadable
 * before the first unlock, which matches the app's existing boot semantics.
 */
@Singleton
class AppLockStatusStore @Inject constructor(@ApplicationContext context: Context) {

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun startupState(): AppLockState = startupState { key -> prefs.getString(key, null) }

    companion object {
        const val PREFS_NAME = "app_lock_status"
        const val KEY_FEATURE_STATUS = "feature_status"
        const val KEY_MIGRATION_JOURNAL = "migration_journal"

        private const val STATUS_DISABLED = "disabled"
        private const val STATUS_ENABLED = "enabled"
        private const val JOURNAL_NONE = "none"
        private const val JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK = "active_pending_first_unlock"
        private const val JOURNAL_FIRST_UNLOCK_VERIFIED = "first_unlock_verified"

        /**
         * Unknown or half-written durable state never degrades to "feature off" — that would be
         * a silent encryption bypass once the feature is active (INV-10). An interrupted
         * migration maps to RECOVERY_REQUIRED until the journal's forward-completion executor
         * (Phase 5) runs; it never maps to a fresh, blank database.
         */
        fun startupState(read: (String) -> String?): AppLockState =
            when (read(KEY_FEATURE_STATUS)) {
                null, STATUS_DISABLED -> AppLockState.DISABLED
                STATUS_ENABLED -> when (read(KEY_MIGRATION_JOURNAL)) {
                    JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK, JOURNAL_FIRST_UNLOCK_VERIFIED ->
                        AppLockState.LOCKED
                    JOURNAL_NONE, null -> AppLockState.RECOVERY_REQUIRED
                    else -> AppLockState.RECOVERY_REQUIRED
                }
                else -> AppLockState.RECOVERY_REQUIRED
            }
    }
}
