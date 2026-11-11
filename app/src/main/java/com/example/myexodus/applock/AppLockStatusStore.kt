package com.example.myexodus.applock

import android.content.Context
import androidx.core.content.edit
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

    fun isFeatureEnabled(): Boolean = prefs.getString(KEY_FEATURE_STATUS, null) == STATUS_ENABLED

    fun journal(): String? = prefs.getString(KEY_MIGRATION_JOURNAL, null)

    /** Committed vault whose old wrapping material may not be removed yet (§6.1 step 8). */
    fun isFirstUnlockPending(): Boolean =
        isFeatureEnabled() && journal() == JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK

    fun isFirstUnlockVerified(): Boolean =
        isFeatureEnabled() && journal() == JOURNAL_FIRST_UNLOCK_VERIFIED

    /** §6.1 step 3: durable pre-commit marker. The feature stays off until the active commit. */
    fun beginEnrollmentStaging() {
        prefs.edit(commit = true) {
            putString(KEY_FEATURE_STATUS, STATUS_DISABLED)
            putString(KEY_MIGRATION_JOURNAL, JOURNAL_STAGING)
        }
    }

    /** §6.1 step 7: one atomic edit — enabled and a usable journal must never be observed apart. */
    fun commitEnrollmentActive() {
        prefs.edit(commit = true) {
            putString(KEY_FEATURE_STATUS, STATUS_ENABLED)
            putString(KEY_MIGRATION_JOURNAL, JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK)
        }
    }

    /** Pre-commit failure/rollback: the feature was never on, so nothing was migrated. */
    fun rollbackEnrollment() {
        prefs.edit(commit = true) {
            putString(KEY_FEATURE_STATUS, STATUS_DISABLED)
            putString(KEY_MIGRATION_JOURNAL, JOURNAL_NONE)
        }
    }

    fun markFirstUnlockVerified() {
        prefs.edit(commit = true) {
            putString(KEY_MIGRATION_JOURNAL, JOURNAL_FIRST_UNLOCK_VERIFIED)
        }
    }

    /**
     * Recovery for a disable that died before its commit: the active auth-bound envelope is
     * still valid (§8.11), so the journal returns to the pending state and the next real
     * unlock (or startup sweep) finishes the old-wrap removal. Never marks the unlock verified.
     */
    fun restoreActivePendingJournal() {
        prefs.edit(commit = true) {
            putString(KEY_MIGRATION_JOURNAL, JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK)
        }
    }

    /**
     * §8.11 exclusive journaled inverse migration. The active auth-bound envelope stays valid
     * until the disable commit, so an interrupted disable rolls back to LOCKED.
     */
    fun beginDisabling() {
        prefs.edit(commit = true) { putString(KEY_MIGRATION_JOURNAL, JOURNAL_DISABLING) }
    }

    fun rollbackDisabling(previousJournal: String?) {
        prefs.edit(commit = true) {
            if (previousJournal == null) {
                remove(KEY_MIGRATION_JOURNAL)
            } else {
                putString(KEY_MIGRATION_JOURNAL, previousJournal)
            }
        }
    }

    fun commitDisabled() {
        prefs.edit(commit = true) {
            putString(KEY_FEATURE_STATUS, STATUS_DISABLED)
            putString(KEY_MIGRATION_JOURNAL, JOURNAL_NONE)
        }
    }

    companion object {
        const val PREFS_NAME = "app_lock_status"
        const val KEY_FEATURE_STATUS = "feature_status"
        const val KEY_MIGRATION_JOURNAL = "migration_journal"

        private const val STATUS_DISABLED = "disabled"
        private const val STATUS_ENABLED = "enabled"
        internal const val JOURNAL_NONE = "none"
        internal const val JOURNAL_STAGING = "staging"
        internal const val JOURNAL_DISABLING = "disabling"
        internal const val JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK = "active_pending_first_unlock"
        internal const val JOURNAL_FIRST_UNLOCK_VERIFIED = "first_unlock_verified"

        /**
         * Unknown or half-written durable state never degrades to "feature off" — that would be
         * a silent encryption bypass once the feature is active (INV-10). An interrupted
         * migration maps to RECOVERY_REQUIRED until the journal's forward-completion executor
         * (Phase 5) runs; it never maps to a fresh, blank database.
         *
         * `enabled + disabling` is a rollback, not a recovery case: the disable commit is the
         * last step, so the still-present auth-bound envelope remains valid (§8.11).
         */
        fun startupState(read: (String) -> String?): AppLockState =
            when (read(KEY_FEATURE_STATUS)) {
                null, STATUS_DISABLED -> AppLockState.DISABLED
                STATUS_ENABLED -> when (read(KEY_MIGRATION_JOURNAL)) {
                    JOURNAL_ACTIVE_PENDING_FIRST_UNLOCK, JOURNAL_FIRST_UNLOCK_VERIFIED,
                    JOURNAL_DISABLING -> AppLockState.LOCKED
                    // JOURNAL_STAGING paired with enabled is not a state any writer produces.
                    else -> AppLockState.RECOVERY_REQUIRED
                }
                else -> AppLockState.RECOVERY_REQUIRED
            }
    }
}
