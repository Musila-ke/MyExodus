package com.example.myexodus.blocking

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.myexodus.accountability.sync.PartnerSyncSignals
import com.example.myexodus.data.db.AppRulesStore
import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.VaultLockedException
import com.example.myexodus.data.db.entity.AttemptLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * Block/attempt history writes (blocking-core plan §6). Called from the FGS tick at
 * detection time. Writes are best-effort: a failed write (store open failure) is logged,
 * never thrown — the caller is on an enforcement path and must not fail because of
 * telemetry. Partner delivery of the same event is the PartnerSync worker's job (§14
 * reconcile); this class only records.
 *
 * While the App Lock vault is closed the attempt log is unreachable, so a blocked attempt is
 * held in §8.9's bounded outbox — rule id + target + original wall time, never event text —
 * and flushed on the next successful open. CE SharedPreferences is enough: the app lock is an
 * in-app vault lock, not the OS lock, so credential-protected storage stays readable. The
 * outbox is capped at the newest [OUTBOX_CAP] entries; anything older is a bounded loss, never
 * unbounded growth.
 */
@Singleton
class AttemptRecorder @Inject constructor(
    private val storeFactory: AppRulesStoreFactory,
    @ApplicationContext context: Context,
) {
    private val outbox: SharedPreferences = context.applicationContext
        .getSharedPreferences(OUTBOX_PREFS, Context.MODE_PRIVATE)

    suspend fun recordBlocked(ruleId: String, blockedTarget: String) {
        val store = try {
            storeFactory.open()
        } catch (e: VaultLockedException) {
            enqueue(ruleId, blockedTarget)
            return
        } ?: run {
            Log.w(TAG, "attempt log skipped: store unavailable")
            return
        }
        try {
            // The a11y decision and the FGS poll can observe the SAME blocked launch far
            // apart: the wall-launch grace retries a cold-start eviction for up to 10 s
            // (EnforcementForegroundService.BLOCK_LAUNCH_GRACE_MS) and the a11y cover
            // re-samples for a few more, so one wall episode produced two attempt rows and
            // two partner notifications (owner report 2026-11-10). Thirty seconds covers
            // the whole episode while a deliberate re-open a minute later still counts.
            val now = System.currentTimeMillis()
            if (!isDuplicate(store, ruleId, blockedTarget, now)) {
                store.attemptLogDao().insert(
                    AttemptLog(
                        occurredAtEpochMillis = now,
                        ruleId = ruleId,
                        blockedTarget = blockedTarget,
                        outcome = AttemptLog.OUTCOME_BLOCKED,
                    ),
                )
                // Owner 2026-11-02: the partner must hear about a wall now, not at the next
                // 15-min pass. Same kick (and same cold-signing heal) as every other shared write.
                PartnerSyncSignals.activityEmitted.tryEmit(Unit)
                Log.i(TAG, "block logged: rule=$ruleId target=$blockedTarget")
            }
            flushPending(store)
        } catch (e: Exception) {
            Log.w(TAG, "attempt log write failed: $blockedTarget", e)
        }
    }

    /**
     * Drains the locked-vault outbox. Called after every successful live write and from
     * startup upkeep once the vault is open. Each entry keeps the wall time of the original
     * block, so the partner sees the attempt when it happened, and the same 30 s dedupe as the
     * live path decides whether another path already recorded it. A failed insert requeues the
     * remaining entries; nothing is silently dropped except past the cap.
     */
    suspend fun flushPending() {
        val store = try {
            storeFactory.open()
        } catch (e: VaultLockedException) {
            return
        } ?: return
        flushPending(store)
    }

    private suspend fun flushPending(store: AppRulesStore) {
        val pending = takePending()
        if (pending.isEmpty()) return
        var inserted = 0
        for ((index, entry) in pending.withIndex()) {
            try {
                if (!isDuplicate(store, entry.ruleId, entry.target, entry.at)) {
                    store.attemptLogDao().insert(
                        AttemptLog(
                            occurredAtEpochMillis = entry.at,
                            ruleId = entry.ruleId,
                            blockedTarget = entry.target,
                            outcome = AttemptLog.OUTCOME_BLOCKED,
                        ),
                    )
                    inserted++
                }
            } catch (e: Exception) {
                Log.w(TAG, "attempt outbox flush failed at $index/${pending.size}", e)
                requeue(pending.subList(index, pending.size))
                return
            }
        }
        if (inserted > 0) {
            PartnerSyncSignals.activityEmitted.tryEmit(Unit)
            Log.i(TAG, "attempt outbox flushed $inserted/${pending.size}")
        }
    }

    private suspend fun isDuplicate(
        store: AppRulesStore,
        ruleId: String,
        target: String,
        at: Long,
    ): Boolean = store.attemptLogDao().countRecentBlocked(
        ruleId,
        target,
        at - BLOCK_DEDUPE_WINDOW_MS,
        at,
    ) > 0

    private fun enqueue(ruleId: String, blockedTarget: String) {
        synchronized(outbox) {
            val entries = readOutbox() + PendingAttempt(ruleId, blockedTarget, System.currentTimeMillis())
            writeOutbox(entries.takeLast(OUTBOX_CAP))
        }
        Log.i(TAG, "block held for flush: rule=$ruleId target=$blockedTarget")
    }

    private fun takePending(): List<PendingAttempt> = synchronized(outbox) {
        val entries = readOutbox()
        if (entries.isNotEmpty()) writeOutbox(emptyList())
        entries
    }

    private fun requeue(entries: List<PendingAttempt>) = synchronized(outbox) {
        writeOutbox((entries + readOutbox()).takeLast(OUTBOX_CAP))
    }

    private fun readOutbox(): List<PendingAttempt> {
        val raw = outbox.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val entry = array.getJSONObject(i)
                PendingAttempt(entry.getString("rule"), entry.getString("target"), entry.getLong("at"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "attempt outbox unreadable, dropping", e)
            emptyList()
        }
    }

    private fun writeOutbox(entries: List<PendingAttempt>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("rule", entry.ruleId)
                    .put("target", entry.target)
                    .put("at", entry.at),
            )
        }
        outbox.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private data class PendingAttempt(val ruleId: String, val target: String, val at: Long)

    private companion object {
        const val TAG = "MyExodusAttempts"
        const val BLOCK_DEDUPE_WINDOW_MS = 30_000L
        const val OUTBOX_PREFS = "myexodus_attempt_outbox"
        const val KEY_ENTRIES = "entries"
        const val OUTBOX_CAP = 50
    }
}
