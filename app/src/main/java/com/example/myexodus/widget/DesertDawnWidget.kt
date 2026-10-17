package com.example.myexodus.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import com.example.myexodus.BuildConfig
import com.example.myexodus.MainActivity
import com.example.myexodus.blocking.systemBootCount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "MyExodusWidget"

/**
 * The home-screen widget (spec §5 Decision 1: glance-appwidget + DataStore, no app-process coupling
 * beyond the flow it reads).
 *
 * The composition observes the snapshot store rather than re-reading it, which is what makes the
 * widget honest: the writer's `updateAll()` re-enters `provideGlance` and re-renders, but Glance
 * does not restart a running session, so an already-live widget only re-renders if this flow emits
 * (glance-appwidget 1.1.1 `ContentReceiver.provideContent`).
 */
class DesertDawnWidget : GlanceAppWidget() {

    /** Four breakpoints: a 2×1 has room for one line; a 4×2 and a tall 2×2 get the day count plus
     *  the band card; a resized full-height cell gets its own band composition (spec §7 Step 5;
     *  TALL_SIZE rationale in `WidgetContent`). `WidgetContent` branches on the reported height,
     *  so whatever size a launcher actually reports picks the right variant; [NARROW_SIZE] exists
     *  so a tall 2×2 maps to the expanded variant instead of stretching the compact one, and
     *  [TALL_SIZE] so a user-resized card does not stretch the 110dp preset into a hero over a
     *  void (finding #11, user-flagged 2026-09-27). */
    override val sizeMode: SizeMode =
        SizeMode.Responsive(setOf(COMPACT_SIZE, EXPANDED_SIZE, NARROW_SIZE, TALL_SIZE))

    /** Spec §14: composition failures in the app/widget-processing path are the app's to report —
     *  the launcher's own inflation failures are not observable from here and are not claimed. */
    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable,
    ) {
        widgetTelemetry(context)
            .nonFatal("widget_composition_failed", cause = throwable.javaClass.simpleName)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Read the first value before composing so the widget's very first frame is real content,
        // not an unverified placeholder flicker. IO: DataStore and the boot-count binder read both
        // block, and this is the main thread (spec NFR1).
        val initial = withContext(Dispatchers.IO) { readView(context) }
        if (BuildConfig.DEBUG) {
            // The size the launcher is actually handing this instance, for the on-device sizing
            // loop: without it a wrong variant is indistinguishable from a wrong preset mapping.
            runCatching {
                val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
                val options = AppWidgetManager.getInstance(context)
                    .getAppWidgetOptions(appWidgetId)
                Log.i(TAG, "widget options: " + options.keySet().joinToString { "$it=${options.get(it)}" })
            }
        }
        // Built outside the composition on purpose: a flow operator chain inside a composable would
        // be re-created on every recomposition, and lint's FlowOperatorInvokedInComposition check
        // is right to flag it. One chain per session, observed for the session's lifetime.
        val views = context.widgetSnapshotDataStore.data
            .map { viewFor(context, snapshotFrom(it)) }
            .catch {
                // Same failure class as the pre-read below, on the long-lived path (spec §14).
                widgetTelemetry(context)
                    .nonFatal("widget_snapshot_read_failed", cause = it.javaClass.simpleName)
                emit(viewFor(context, null))
            }
            .distinctUntilChanged()
            .flowOn(Dispatchers.IO)

        provideContent {
            GlanceTheme(colors = DesertDawnGlanceColors) {
                if (BuildConfig.DEBUG) {
                    val size = LocalSize.current
                    Log.i(TAG, "widget size: ${size.width} x ${size.height}")
                }
                val view by views.collectAsState(initial = initial)
                DesertDawnWidgetContent(
                    view = view,
                    openStreaks = actionStartActivity(
                        appLaunchIntent(context)
                            .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_STREAKS),
                    ),
                    openApp = actionStartActivity(appLaunchIntent(context)),
                    // The icon controls' hold-confirm surface (widget v2.2). The relapse control
                    // binds the exact track the day count above it displays — no bound habit,
                    // no control. The urge log is a habitless recovery-session row (the app's
                    // own log sheet writes the same one), and reach-out has no track at all.
                    logRelapse = view.habitId?.let { habitId ->
                        actionStartActivity(
                            widgetActionIntent(context, WidgetActionActivity.TARGET_RELAPSE, habitId),
                        )
                    },
                    logUrge = actionStartActivity(
                        widgetActionIntent(context, WidgetActionActivity.TARGET_URGE),
                    ),
                    reachOut = actionStartActivity(
                        widgetActionIntent(context, WidgetActionActivity.TARGET_SOS),
                    ),
                )
            }
        }
    }
}

/**
 * Both tap targets must resolve to one Activity instance and one task, so they share this intent:
 * a widget tap arrives from another process, which needs NEW_TASK, and SINGLE_TOP delivers the
 * extra through `onNewIntent` when the app is already open instead of stacking a second copy.
 */
private fun appLaunchIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Reads once, for the pre-composition frame. Ongoing updates come from the flow above. An
 *  unreadable store at this point is "no sample", which renders UNVERIFIED (spec §9) — the pre-unlock
 *  case, not a crash. */
private suspend fun readView(context: Context): WidgetView =
    viewFor(
        context,
        runCatching { snapshotFrom(context.widgetSnapshotDataStore.data.first()) }
            // A failed read is a state the gate is designed for (UNVERIFIED), but silently eating
            // it would hide a genuinely corrupt store behind an honest-looking "no sample" (spec §9).
            .onFailure {
                widgetTelemetry(context)
                    .nonFatal("widget_snapshot_read_failed", cause = it.javaClass.simpleName)
            }
            .getOrNull(),
    )

/** Where the freshness policy meets the copy the user sees. */
private fun viewFor(context: Context, snapshot: ProtectionSnapshot?): WidgetView {
    val nowUtc = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtime()
    val nowBootCount = systemBootCount(context)
    // streakDays is the ordinal display day (ADR #128); the rung math stays in completed days,
    // so convert here. coerceAtLeast guards a stale pre-ruling snapshot that still reads 0.
    val milestone = nextMilestone(snapshot?.streakDays?.minus(1)?.coerceAtLeast(0))
    val view = WidgetView(
        status = displayedStatusFor(
            snapshot = snapshot,
            nowUtc = nowUtc,
            nowElapsed = nowElapsed,
            nowBootCount = nowBootCount,
        ),
        streakDays = snapshot?.streakDays,
        habitLabel = snapshot?.habitLabel,
        habitId = snapshot?.habitId,
        // The same predicate the writer uses to decide a re-read is owed — asked here of the
        // value the user is looking at. True means this number cannot be proven current, so it
        // renders with its age attached rather than silently ageing (spec §16 Q5, closed
        // 2026-09-27). Guarded on the count itself: with nothing to show, there is nothing to
        // qualify — and the predicate is answering a writer's question then, not the user's.
        streakCheckedAgoMillis = if (snapshot?.streakDays != null &&
            needsStreakRefresh(snapshot, nowElapsed, nowBootCount)
        ) {
            // A record written before the verification pair existed still knows when it was
            // written: use that rather than claiming a check that never happened.
            nowUtc - (snapshot.streakSample?.verifiedUtc ?: snapshot.evalUtc)
        } else {
            null
        },
        nextMilestoneDay = milestone?.first,
        nextMilestoneDaysAway = milestone?.second,
        discreet = snapshot?.discreet ?: true,
    )
    if (BuildConfig.DEBUG) {
        // What a render actually puts on the home screen, for the on-device loop.
        Log.i(
            TAG,
            "widget view: status=${view.status} days=${view.streakDays}" +
                " checkedAgo=${view.streakCheckedAgoMillis} milestone=${view.nextMilestoneDay}" +
                " discreet=${view.discreet}",
        )
    }
    return view
}

/**
 * The manifest receiver. `exported="true"` is mandatory for an app widget provider — the host
 * launcher broadcasts from a different process/UID and would be blocked otherwise (spec §10) — and
 * the intent filter carries only framework actions.
 *
 * `onReceive` chains to `super` first so Glance routes its own actions, then forces a render on the
 * two broadcasts the snapshot cannot express: a reboot invalidates every sample's boot count, and
 * an app update can change the layout itself. Neither is a data change, so the writer's change gate
 * would not fire for them.
 *
 * Deliberately no `onEnabled`/`onDisabled` scheduling: the app's existing 15-minute
 * `HealthCheckWorker` already refreshes the arbiter and therefore already drives the writer, which
 * is the backstop the spec asks for (spec §5 Decision 3′; deviation recorded in ADR #40).
 */
class DesertDawnWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DesertDawnWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            // Short work on a background scope: the library owns goAsync for its own dispatch and
            // documents that this method must not hold the broadcast open itself.
            CoroutineScope(Dispatchers.IO).launch { glanceAppWidget.updateAll(context) }
        }
    }
}
