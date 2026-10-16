package com.example.myexodus.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.glance.appwidget.updateAll
import com.example.myexodus.BuildConfig
import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.blocking.ProtectionStatusMonitor
import com.example.myexodus.blocking.systemBootCount
import com.example.myexodus.settings.WidgetSettings
import com.example.myexodus.streaks.HabitRepository
import com.example.myexodus.streaks.HabitState
import com.example.myexodus.streaks.StreakTimer
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 *
 * The streak rides the store's raw accumulator ([StreakSample]) rather than being re-read every
 * heartbeat: the store is the cache, and [needsStreakRefresh] decides — purely, from the stored
 * pair — when the cached day count can no longer be *proven* current, which is only ever a day
 * boundary or a reboot. Everything before that boundary is still today's number, so a process
 * start with a young sample owes the store no read at all (user-flagged 2026-09-27: the widget
 * re-opened the durable store on every process start, and XOS's freezer makes those frequent).
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
    private val publishMutex = Mutex()

    /**
     * The last observed [StreakSample], set only where a read actually happened — never at publish
     * time. A value held in memory for hours must not be stamped "verified now": the day-boundary
     * proof extrapolates the accumulator from exactly this observed pair.
     */
    @Volatile
    private var streakSample: StreakSample? = null

    init {
        scope.launch {
            publish(statusMonitor.authority.value)
        }
        scope.launch {
            statusMonitor.authority.collect { publish(it) }
        }
        scope.launch {
            // Every emission is a real observation: the repository only changes `state` after a
            // store read or write, so this is where the verification stamp belongs — the read and
            // the stamping clocks are milliseconds apart, and both are recorded here.
            habits.state.collect { state ->
                if (state != null || habits.resolved.value) streakSample = state?.let(::sampleOf)
                publish(statusMonitor.authority.value)
            }
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

    /**
     * Serialized: a refresh publishes through the state flow's collector while the publish that
     * triggered it is still running, and letting the two interleave would re-read the store for a
     * boundary it just crossed and render twice for one change.
     */
    private suspend fun publish(authority: ProtectionAuthority) = publishMutex.withLock {
        publishLocked(authority)
    }

    private suspend fun publishLocked(authority: ProtectionAuthority) {
        val nowUtc = System.currentTimeMillis()
        try {
            val existing = snapshotFrom(appContext.widgetSnapshotDataStore.data.first())
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowBootCount = systemBootCount(appContext)
            // The only durable read this class performs, and only when the cache cannot prove the
            // day count anymore. Every other publish is served from the store.
            val refresh = needsStreakRefresh(existing, nowElapsed, nowBootCount)
            if (refresh) refreshStreak()
            val state = habits.state.value
            val streak = streakFieldsFor(
                state = state,
                resolved = habits.resolved.value,
                existing = existing,
                sample = streakSample ?: state?.let(::sampleOf),
            )
            val snapshot = ProtectionSnapshot(
                enforcement = authority.toEnforcementStatus(),
                // currentDay is the same whole-day count the streaks UI shows — reused rather than
                // re-derived, so the widget can never disagree with the app (DRY).
                // Known limitation (user-flagged 2026-09-17, V1 Req 36): this is the app's *displayed*
                // habit only. The widget has no habit switcher, and the snapshot store is global (not
                // per-widget) — so every instance shows the same habit. The label rides along so the
                // surface names which track the number belongs to (finding #12); the switcher stays
                // deferred and is recorded in the same breath.
                streakDays = streak?.days,
                habitLabel = streak?.label,
                streakSample = streak?.sample,
                discreet = settings.discreetMode,
                evalUtc = nowUtc,
                evalElapsed = nowElapsed,
                evalBootCount = nowBootCount,
            )
            val write = shouldRestamp(existing, snapshot, nowUtc)
            if (BuildConfig.DEBUG) {
                // The widget's state on the wire, for the on-device loop: which render a publish
                // produces, and whether a read was owed (the flicker was a publish nobody owed).
                Log.i(
                    TAG,
                    "widget publish: state=${state != null} resolved=${habits.resolved.value}" +
                        " refresh=$refresh days=${snapshot.streakDays}" +
                        " sample=${snapshot.streakSample?.elapsedMs}" +
                        " carried=${streak?.sample === existing?.streakSample}" +
                        " write=$write",
                )
            }
            if (!write) return
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

    /**
     * Re-reads the streak from the durable store and re-stamps the verification. The state flow's
     * collector stamps too, but asynchronously — a publish returning from here would otherwise
     * carry the new day count with the old stamp, and render the caption over fresh data for a
     * frame.
     */
    private suspend fun refreshStreak() {
        habits.refreshActive()
        // `resolved` distinguishes the two nulls: a store that was opened and has no habit
        // (honest clear) from a store that never opened (pre-unlock — nothing was learned, so
        // the previous sample stands).
        if (habits.resolved.value) streakSample = habits.state.value?.let(::sampleOf)
    }

    /**
     * The streak fields to publish. A resolved repository is an answer, including "no habit";
     * an unresolved one is not a reading at all — it is a process that has not looked yet — so
     * its null must never overwrite a known streak. That distinction is the "No streak yet"
     * flicker: at every process start `state` is null until the first read lands, and the
     * publish triggered by that initial null used to blank a good sample.
     */
    private fun sampleOf(state: HabitState): StreakSample = StreakSample(
        elapsedMs = state.currentStreakElapsedMs,
        verifiedUtc = System.currentTimeMillis(),
        verifiedElapsed = SystemClock.elapsedRealtime(),
        verifiedBoot = systemBootCount(appContext),
    )
}

/**
 * The streak fields to publish. A resolved repository is an answer, including "no habit"; an
 * unresolved one is not a reading at all — it is a process that has not looked yet — so its null
 * must never overwrite a known streak.
 *
 * That distinction is the "No streak yet" flicker (user report 2026-09-27): at every process start
 * `state` is null until the first read lands, and the publish triggered by that initial null used
 * to blank a good sample. [state]/[resolved] come from the repository, [sample] is the verification
 * pair for [state] (the writer's own observed sample, or a fresh one taken by the caller), and
 * [existing] is the stored record a carry has to preserve.
 */
internal fun streakFieldsFor(
    state: HabitState?,
    resolved: Boolean,
    existing: ProtectionSnapshot?,
    sample: StreakSample?,
): StreakFields? = when {
    state != null -> StreakFields(state.currentDay, state.targetLabel, sample)
    resolved -> null
    else -> existing?.let { StreakFields(it.streakDays, it.habitLabel, it.streakSample) }
}

internal data class StreakFields(
    val days: Int?,
    val label: String?,
    val sample: StreakSample?,
)

private const val TAG = "MyExodusWidget"

/**
 * Whether the stored streak fields must be re-read before the next publish, i.e. whether the
 * cached day count can no longer be proven current. Pure: the caller supplies the clocks, so the
 * day-boundary and reboot cases are unit-testable.
 *
 * The proof uses the stored pair ([StreakSample]): the accumulator advances 1:1 with
 * elapsedRealtime (Decision 17 — 24 real hours, never a calendar boundary), so the next whole day
 * crosses `DAY_MS - (elapsedMs % DAY_MS)` after the reading. Until then the stored day count is
 * provably unchanged, and a re-read could only confirm it.
 *
 * Fail-closed everywhere: an unreadable boot count, an absent sample, or an elapsed regression
 * all resolve to "read again" rather than trusting a number that may be a day behind.
 */
internal fun needsStreakRefresh(
    existing: ProtectionSnapshot?,
    nowElapsed: Long,
    nowBootCount: Int,
): Boolean {
    if (existing == null) return true
    // Nothing to keep current: no habit was set up. A streak appearing later arrives through the
    // state flow, which is in-process by definition.
    if (existing.streakDays == null) return false
    val sample = existing.streakSample ?: return true
    if (sample.verifiedBoot != nowBootCount) return true
    if (nowElapsed < sample.verifiedElapsed) return true
    val untilNextDay = StreakTimer.DAY_MS - (sample.elapsedMs % StreakTimer.DAY_MS)
    return nowElapsed - sample.verifiedElapsed >= untilNextDay
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
    if (existing.habitLabel != next.habitLabel) return true
    // The verification pair moves on every real read, and the widget's "checked" caption is
    // rendered from it — a fresh read must land even when the day count happens to match.
    if (existing.streakSample != next.streakSample) return true
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
