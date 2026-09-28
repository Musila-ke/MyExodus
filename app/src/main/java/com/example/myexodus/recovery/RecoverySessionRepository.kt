package com.example.myexodus.recovery

import com.example.myexodus.data.db.AppRulesStoreFactory
import com.example.myexodus.data.db.dao.SessionCount
import com.example.myexodus.data.db.entity.RecoverySession
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The urge/recovery session log (v3 W7). Writes are best-effort by contract (FR-5): the
 * intervention UI is never gated on storage, so every method tolerates a store that is not
 * open and simply skips the write — a logged session is a nicety, the practice itself is not.
 */
class RecoverySessionRepository(private val storeFactory: AppRulesStoreFactory) {

    /** See [abandonQuietly] — outlives any single composition. */
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Starts a technique session; returns its id whether or not the row could be written. */
    suspend fun start(
        technique: String,
        intensityStart: Int?,
        entry: String,
        zoneId: String,
        nowEpochMs: Long,
    ): String {
        val id = UUID.randomUUID().toString()
        val store = storeFactory.open() ?: return id
        store.recoverySessionDao().insert(
            RecoverySession(
                id = id,
                technique = technique,
                intensityStart = intensityStart,
                startedAtEpochMs = nowEpochMs,
                zoneId = zoneId,
                entry = entry,
            ),
        )
        return id
    }

    /** Terminal transition. [state] must be COMPLETED or ABANDONED — INTERRUPTED is the
     *  app-start sweep's, and STARTED is not an end. No-op when the row never landed. */
    suspend fun finish(id: String, state: String, intensityEnd: Int?, nowEpochMs: Long) {
        require(state == RecoverySession.STATE_COMPLETED || state == RecoverySession.STATE_ABANDONED) {
            "finish() takes a terminal state, not $state"
        }
        val store = storeFactory.open() ?: return
        val dao = store.recoverySessionDao()
        val session = dao.byId(id) ?: return
        // First terminal state wins: a UI end racing the app-start sweep (or a surface leaving
        // composition right after "done") must never relabel a finished session.
        if (session.isTerminal) return
        dao.update(
            session.copy(
                state = state,
                intensityEnd = intensityEnd,
                endedAtEpochMs = nowEpochMs,
                durationSec = ((nowEpochMs - session.startedAtEpochMs) / 1000).coerceAtLeast(0).toInt(),
            ),
        )
    }

    /**
     * FR-5 best-effort abandon: the surface that knew about the session is leaving composition
     * (tab switch, back navigation), so its own coroutine scope dies with it. This write runs on
     * a scope that outlives the UI — the row must not be left STARTED for the sweep to relabel.
     */
    fun abandonQuietly(id: String) {
        writes.launch { finish(id, RecoverySession.STATE_ABANDONED, null, System.currentTimeMillis()) }
    }

    /** The "log it and go home" path (Req 10): no technique was practised, so the row is born
     *  terminal and is deliberately never mirrored (the drain query skips [RecoverySession.TECHNIQUE_NONE]). */
    suspend fun logOnly(
        intensity: Int?,
        entry: String,
        zoneId: String,
        nowEpochMs: Long,
    ) {
        val store = storeFactory.open() ?: return
        store.recoverySessionDao().insert(
            RecoverySession(
                id = UUID.randomUUID().toString(),
                technique = RecoverySession.TECHNIQUE_NONE,
                intensityStart = intensity,
                state = RecoverySession.STATE_COMPLETED,
                startedAtEpochMs = nowEpochMs,
                endedAtEpochMs = nowEpochMs,
                durationSec = 0,
                zoneId = zoneId,
                entry = entry,
            ),
        )
    }

    /** v2 M2.4 adapted: rows a process death left STARTED past [RecoverySession.STALE_SESSION_MS]
     *  close as INTERRUPTED. Terminal rows are untouched, so a session cannot be double-ended;
     *  the mirror needs no reconciling event because a STARTED row was never mirrorable (the
     *  drain requires a terminal state) and its interruption simply makes it drainable now. */
    suspend fun interruptStale(nowEpochMs: Long): Int {
        val store = storeFactory.open() ?: return 0
        return store.recoverySessionDao().interruptStale(
            cutoffEpochMs = nowEpochMs - RecoverySession.STALE_SESSION_MS,
            staleMs = RecoverySession.STALE_SESSION_MS,
            staleSec = (RecoverySession.STALE_SESSION_MS / 1000).toInt(),
        )
    }

    suspend fun page(limit: Int, offset: Int): List<RecoverySession> =
        storeFactory.open()?.recoverySessionDao()?.page(limit, offset) ?: emptyList()

    suspend fun count(): Long = storeFactory.open()?.recoverySessionDao()?.count() ?: 0

    /** Per-(state, technique) counts at or after [fromMs] (`0` = all time), for the history summary. */
    suspend fun countsByStateSince(fromMs: Long): List<SessionCount> =
        storeFactory.open()?.recoverySessionDao()?.countsByStateSince(fromMs) ?: emptyList()

    /** Mirror queue for the sync pass (W5). */
    suspend fun pendingMirror(limit: Int): List<RecoverySession> =
        storeFactory.open()?.recoverySessionDao()?.pendingMirror(limit) ?: emptyList()

    suspend fun linkEvent(id: String, sequence: Long) {
        storeFactory.open()?.recoverySessionDao()?.linkEvent(id, sequence)
    }
}
