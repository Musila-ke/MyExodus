package com.example.myexodus.blocking

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import com.example.myexodus.telemetry.CrashReporting
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Settings.Global.BOOT_COUNT, -1 when unreadable. One implementation for the usage services'
 *  reboot-mid-session checks (§8 Steps 4/5) and boot reconciliation (§8 Step 9). */
internal fun systemBootCount(context: Context): Int =
    runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
        .getOrDefault(-1)

/**
 * Plan §8 Step 9 reconciliation state, in device-protected storage only (Decision 8) — the
 * one storage a pre-unlock process may touch; never Room, never the Keystore. Two persisted
 * values: the boot count the last reconciliation ran for (the plan's "reconciled for boot
 * count N" guard), and the locked-boot receiver's pre-unlock pending mark (kept solely so a
 * duplicate trigger can be reported, §14).
 *
 * **Recorded deviation (2026-09-15), source-verified:** the plan's locked-boot receiver was
 * also to re-enqueue the maintenance WorkManager job pre-unlock. That is impossible on
 * WorkManager 2.11.2: its workdb lives in `noBackupFilesDir` of the credential-encrypted app
 * context and every WM component is `directBootAware="false"` (dependency sources + merged
 * manifest). The receiver therefore only writes the DE mark; the re-enqueue happens here,
 * post-unlock, in the reconcile block.
 */
@Singleton
class BootReconciliationStore @Inject constructor(
    @ApplicationContext context: Context,
    private val crashReporting: CrashReporting,
) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Pre-unlock mark (locked-boot receiver only). commit(): the recording process is
     *  usually killed right after the receiver finishes. */
    fun markPending(bootCount: Int) {
        prefs.edit(commit = true) { putInt(KEY_PENDING_BOOT, bootCount) }
    }

    /**
     * Runs [reconcile] exactly once per boot count and returns whether it ran. A second
     * trigger for an already-reconciled boot is a no-op (§12 dedup row); if that duplicate
     * follows the receiver's pre-unlock mark, it is reported once (§14 "dedup path taken",
     * and the mark is consumed so the next plain cold start stays silent). The mark is
     * stamped only after [reconcile] returns, so a failed attempt retries at the next trigger.
     */
    suspend fun reconcileOnce(bootCount: Int, reconcile: suspend () -> Unit): Boolean {
        if (reconciledBootCount == bootCount) {
            if (pendingBootCount == bootCount) {
                prefs.edit(commit = true) { putInt(KEY_PENDING_BOOT, NONE) }
                crashReporting.nonFatal("boot_reconciliation_dedup", cause = "already_reconciled")
            }
            return false
        }
        reconcile()
        prefs.edit(commit = true) { putInt(KEY_RECONCILED_BOOT, bootCount) }
        return true
    }

    private var reconciledBootCount: Int
        get() = prefs.getInt(KEY_RECONCILED_BOOT, NONE)
        set(value) = prefs.edit(commit = true) { putInt(KEY_RECONCILED_BOOT, value) }

    private var pendingBootCount: Int
        get() = prefs.getInt(KEY_PENDING_BOOT, NONE)
        set(value) = prefs.edit(commit = true) { putInt(KEY_PENDING_BOOT, value) }

    private companion object {
        const val PREFS_NAME = "boot_reconciliation"
        const val KEY_RECONCILED_BOOT = "reconciled_boot_count"
        const val KEY_PENDING_BOOT = "pending_boot_count"
        const val NONE = -1
    }
}
