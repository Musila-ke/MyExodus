package com.example.myexodus.widget

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import com.example.myexodus.MainActivity
import com.example.myexodus.blocking.systemBootCount
import com.example.myexodus.data.db.entity.RecoverySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Three breakpoints: a 2×1 has room for one line, anything taller gets the day count plus the
     *  status word and the deep link (spec §7 Step 5). `WidgetContent` branches on height alone, so
     *  whatever size a launcher actually reports picks the right variant; [NARROW_SIZE] exists so a
     *  tall 2×2 maps to the expanded variant instead of stretching the compact one (finding #11). */
    override val sizeMode: SizeMode =
        SizeMode.Responsive(setOf(COMPACT_SIZE, EXPANDED_SIZE, NARROW_SIZE))

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
                val view by views.collectAsState(initial = initial)
                DesertDawnWidgetContent(
                    view = view,
                    manageUrge = actionStartActivity(
                        appLaunchIntent(context)
                            .putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TAB_RECOVERY)
                            .putExtra(MainActivity.EXTRA_RECOVERY_ENTRY, RecoverySession.ENTRY_WIDGET),
                    ),
                    openApp = actionStartActivity(appLaunchIntent(context)),
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
private fun viewFor(context: Context, snapshot: ProtectionSnapshot?): WidgetView = WidgetView(
    status = displayedStatusFor(
        snapshot = snapshot,
        nowUtc = System.currentTimeMillis(),
        nowElapsed = SystemClock.elapsedRealtime(),
        nowBootCount = systemBootCount(context),
    ),
    streakDays = snapshot?.streakDays,
    habitLabel = snapshot?.habitLabel,
    discreet = snapshot?.discreet ?: true,
)

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
