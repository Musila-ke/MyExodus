package com.example.myexodus.widget

import android.content.Context
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.glance.appwidget.updateAll
import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.blocking.ProtectionStatusMonitor
import com.example.myexodus.blocking.systemBootCount
import com.example.myexodus.settings.WidgetSettings
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.telemetry.CrashReporting
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Sole writer of the widget's snapshot (widget spec §4/§6). Samples the app's existing protection
 * arbiter, the live streak, and the discreet-mode setting; writes the store; then immediately
 * calls `updateAll()` (spec §5 Decision 8).
 *
 * Triggers are the flows it observes, not a list of call sites — the app's arbiter already
 * recomputes on every relevant event (cold start, the FGS 30 s cycle, the 15-minute health worker,
 * accessibility connect/disconnect), and the snapshot must follow it wherever it moves:
 *  - [ProtectionStatusMonitor.authority] — any change to which detection path holds authority.
 *  - [HabitRepository.state] — streak changes (day rollover, relapse, new habit).
 *  - a heartbeat tick, since the freshness gate needs a recent sample even when nothing changed.
 *
 * The heartbeat is deliberately the liveness signal: it only runs while the app process is alive,
 * which is exactly when the app can truthfully claim it just checked. A dead process stops
 * re-stamping and the widget ages into UNVERIFIED on its own — the honest outcome, with no
 * liveness bookkeeping to maintain.
 */
@Singleton
class ProtectionSnapshotWriter @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val statusMonitor: ProtectionStatusMonitor,
    private val habits: HabitRepository,
    private val settings: WidgetSettings,
    private val crashReporting: CrashReporting,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            // The streaks ViewModel is the only other caller of refreshActive(), so without this a
            // widget-only process start (boot, app update, resize) would publish a null streak and
            // the widget's headline number would read "No streak yet" over a real streak. Once per
            // process start, not per heartbeat: the durable read is not free, and the collect below
            // covers every later change. Pre-unlock it opens nothing and the null stays honest.
            habits.refreshActive()
            publish(statusMonitor.authority.value)
        }
        scope.launch {
            statusMonitor.authority.collect { publish(it) }
        }
        scope.launch {
            habits.state.collect { publish(statusMonitor.authority.value) }
        }
        scope.launch {
            while (true) {
                delay(HEARTBEAT_TICK_MILLIS)
                publish(statusMonitor.authority.value)
            }
        }
    }

    /**
     * Re-samples and, if anything moved or the stored sample has aged past [RESTAMP_AFTER_MILLIS],
     * re-publishes. Called from the heartbeat loop and the observed flows; also the entry point for
     * a discreet-mode toggle change.
     */
    fun publish() {
        scope.launch { publish(statusMonitor.authority.value) }
    }

    private suspend fun publish(authority: ProtectionAuthority) {
        val nowUtc = System.currentTimeMillis()
        try {
            val snapshot = ProtectionSnapshot(
                enforcement = authority.toEnforcementStatus(),
                // currentDay is the same whole-day count the streaks UI shows — reused rather than
                // re-derived, so the widget can never disagree with the app (DRY).
                // Known limitation (user-flagged 2026-09-17, V1 Req 36): this is the app's *displayed*
                // habit only. The widget has no habit switcher, and the snapshot store is global (not
                // per-widget) — so every instance shows the same habit. Deferred, not forgotten.
                streakDays = habits.state.value?.currentDay,
                discreet = settings.discreetMode,
                evalUtc = nowUtc,
                evalElapsed = SystemClock.elapsedRealtime(),
                evalBootCount = systemBootCount(appContext),
            )
            val existing = snapshotFrom(appContext.widgetSnapshotDataStore.data.first())
            if (!shouldRestamp(existing, snapshot, nowUtc)) return
            appContext.widgetSnapshotDataStore.edit { it.putSnapshot(snapshot) }
            crashReporting.nonFatal(
                event = "widget_snapshot_published",
                cause = if (existing == null) "first" else "changed",
                "enforcement" to snapshot.enforcement.name,
                "api" to android.os.Build.VERSION.SDK_INT.toString(),
            )
            // Only after a write: an unchanged sample must not start a fresh Glance session. Its own
            // catch so a failed render is reported as that, not as a failed write (spec §14).
            try {
                DesertDawnWidget().updateAll(appContext)
            } catch (e: Exception) {
                crashReporting.nonFatal("widget_render_failed", cause = e.javaClass.simpleName)
            }
        } catch (e: IllegalStateException) {
            // Pre-unlock process start: CE storage throws until first unlock. Expected, and
            // self-healing — the next tick after unlock publishes (same guard as the monitors).
        } catch (e: Exception) {
            crashReporting.nonFatal("widget_snapshot_failed", cause = e.javaClass.simpleName)
        }
    }
}

/**
 * Whether a sample that differs from [existing] only in its timestamps is nevertheless worth
 * writing. Change-gated so a 30-second arbiter cycle does not write and re-render the widget when
 * nothing moved (spec §7 Decision 7′: the backstop is for freshness, not for churn).
 */
internal fun shouldRestamp(
    existing: ProtectionSnapshot?,
    next: ProtectionSnapshot,
    nowUtc: Long,
): Boolean {
    if (existing == null) return true
    if (existing.enforcement != next.enforcement) return true
    if (existing.streakDays != next.streakDays) return true
    if (existing.discreet != next.discreet) return true
    // The sample must carry the current boot count even when nothing else moved, or the freshness
    // gate would reject it as reboot-stale for as long as the enforcement value happens to match.
    if (existing.evalBootCount != next.evalBootCount) return true
    return nowUtc - existing.evalUtc >= RESTAMP_AFTER_MILLIS
}

/** Re-stamp a sample older than this. With [HEARTBEAT_TICK_MILLIS] the stored age never exceeds
 *  ~15 minutes, comfortably inside the 30-minute render ceiling (spec §5 Decision 7′). */
private const val RESTAMP_AFTER_MILLIS = 10 * 60 * 1000L

private const val HEARTBEAT_TICK_MILLIS = 5 * 60 * 1000L

/**
 * Maps the app's authority tri-state onto the widget's enforcement tri-state (spec §16 Q2 — the
 * authority already IS the genuine enforcement signal; `blocksNow` is a per-rule query and carries
 * no enforcement concept).
 *
 * DEGRADED_POLLING_ONLY renders as ACTIVE: blocking genuinely runs under the polling path (the app
 * treats it as a legitimate accounting authority), and UNVERIFIED there would tell a user with
 * working blocks that something is broken. UNKNOWN is reserved for "no sample at all".
 */
internal fun ProtectionAuthority.toEnforcementStatus(): EnforcementStatus = when (this) {
    ProtectionAuthority.FULL -> EnforcementStatus.ACTIVE
    ProtectionAuthority.DEGRADED_POLLING_ONLY -> EnforcementStatus.ACTIVE
    ProtectionAuthority.DEGRADED_NO_DETECTION -> EnforcementStatus.INACTIVE
}
