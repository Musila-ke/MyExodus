package com.example.myexodus.blocking

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.myexodus.settings.EnhancedModeSettings
import com.example.myexodus.telemetry.CrashReporting
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which detection path holds enforcement authority right now (enforcement plan v2 §8 Step 6 /
 * Decisions 2 and 7; the plan's names FULL / DEGRADED_POLLING_ONLY / DEGRADED_NO_DETECTION).
 * A degraded state models "Accessibility is unavailable" generically — the cause (user toggle,
 * OEM revocation, a platform security feature) changes the response in no way.
 */
enum class ProtectionAuthority {
    /** Path A (the accessibility service) is enabled and alive — the accounting authority. */
    FULL,

    /** Accessibility is unavailable, but polling can see apps and show blocks — Path B is
     *  the accounting authority. */
    DEGRADED_POLLING_ONLY,

    /** Neither path can detect anything: no enforcement is running. */
    DEGRADED_NO_DETECTION,
}

/**
 * The single arbiter of protection state (§8 Step 6): recomputes authority from the platform
 * signals, persists the one user-visible degraded reason to [ProtectionStatusStore], and drives
 * the degraded notification + the Decision 12 telemetry event on transitions.
 *
 * Deviations and app-model additions, all deliberate:
 *  - The plan's "process reports itself alive (e.g., a heartbeat write)" is
 *    [BlockerAccessibilityService.connected] — the accessibility service and this app's
 *    services share one process, so a live object flag is strictly more accurate than a
 *    cross-process heartbeat and has no staleness window. It is also the sole Path A
 *    liveness input: the platform's client-side enabled list flaps on OEM builds
 *    (device-caught on XOS/Transsion 2026-09-19 — see [refresh]), and ANDing it in produced
 *    false "Limited" status while Path A was fully alive.
 *  - The plan has no app-side master switch; this app does ([EnhancedModeSettings] gates both
 *    the accessibility service's per-event work and the foreground service's run). Master off
 *    means neither path is detecting, so authority is DEGRADED_NO_DETECTION — the honest
 *    state, and a deliberate pause is exactly what the strict-mode friction wants visible.
 *  - DEGRADED_POLLING_ONLY posts no alert. Blocking still runs under Path B (at polling
 *    latency), and this app supports running without Enhanced Mode — a "protection is paused"
 *    alert there would be false. The enabled→disabled accessibility transition is already
 *    alerted by [EnhancedModeHealthMonitor].
 */
@Singleton
class ProtectionStatusMonitor @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: EnhancedModeSettings,
    private val store: ProtectionStatusStore,
    private val crashReporting: CrashReporting,
) {
    // Until the first refresh the answer is "nothing is watching" — the same fail-quiet
    // direction as the session machines' drop-on-handoff: a missed credit is visible,
    // two authorities crediting one span is not.
    private val _authority = MutableStateFlow(ProtectionAuthority.DEGRADED_NO_DETECTION)

    /** Current authority; read by Path B's accounting/render gates on every poll. */
    val authority: StateFlow<ProtectionAuthority> = _authority.asStateFlow()

    // Seeded from the store so a cold start renders a persisted banner immediately; kept
    // reactive below so a state change lands on screen the moment it is published.
    private val _degradation = MutableStateFlow(runCatching { store.degradation }.getOrNull())

    /** The one user-visible degraded reason, reactive. The Home banner used to re-read the
     *  store only on resume — but the FGS's start() runs the stop detection AFTER that read,
     *  so a freshly detected stop showed no banner until the next resume/relaunch
     *  (device-caught 2026-10-14: stop → open app → no banner, close/reopen → banner). */
    val degradation: StateFlow<ProtectionDegradation?> = _degradation.asStateFlow()

    /**
     * Recompute + publish. Called on cold start, the maintenance worker's run, the foreground
     * service's 30s permission cycle, and the accessibility connect/disconnect callbacks
     * (§8 Step 6). Never throws — a status probe that fails reads as needing attention, never
     * as granted.
     */
    fun refresh(): ProtectionAuthority {
        val enrolled = settings.enhancedModeEnabled
        // Path A liveness is the in-process service instance, not the platform's client-side
        // enabled list. That list flaps on OEM builds (XOS/Transsion, device-caught
        // 2026-09-19: it read empty while the service was bound and delivering window events,
        // freezing the Home chip on a false "Limited" while Path A was fully alive). A real
        // disable always unbinds, which clears `connected` on the spot — so this direction
        // stays exact.
        val a11yAlive = runCatching { BlockerAccessibilityService.connected.get() }.getOrDefault(false)
        val usageAccess = runCatching { appContext.hasUsageAccess() }.getOrDefault(false)
        val overlayGranted = runCatching { OverlayController.overlayPermissionGranted(appContext) }
            .getOrDefault(false)
        val next = authorityFor(enrolled, a11yAlive, usageAccess, overlayGranted)
        _authority.value = next
        publish(next, causeFor(enrolled, a11yAlive, usageAccess, overlayGranted))
        return next
    }

    /** Store + transition log are change-gated; the notification is re-asserted every refresh. */
    private fun publish(authority: ProtectionAuthority, cause: String) {
        // Pre-unlock process start: CE SharedPreferences throw until first unlock (same guard
        // as EnhancedModeHealthMonitor) — the in-memory authority above is still updated.
        val prior = try {
            store.degradation
        } catch (e: IllegalStateException) {
            return
        }
        val visible = visibleDegradation(
            requiresExplicitReEnable = store.requiresExplicitReEnable,
            authority = authority,
            notificationsDenied = notificationsDenied(),
        )
        if (visible != prior) {
            store.degradation = visible
            logTransition(prior, visible, authority, cause)
        }
        // Both below are unconditional (device-caught 2026-10-14). The StateFlow so a
        // detection landing after the UI's resume read still reaches the banner in-session;
        // the notification so an alert cleared out-of-band — a package update (the app's only
        // distribution channel) or a swipe — cannot stay lost while the state persists.
        // Re-posting identical content is free (ONLY_ALERT_ONCE).
        _degradation.value = visible
        ProtectionNotifications.updateDegraded(appContext, visible)
    }

    private fun notificationsDenied(): Boolean {
        // POST_NOTIFICATIONS exists only on 33+; pre-33 that check reports DENIED for an
        // unknown permission, so the real gate there is areNotificationsEnabled.
        val permissionDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        return permissionDenied || !NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    /** §14 monitoring: transition type, cause where determinable, OEM, API level — never
     *  package names or usage values (Decision 12). Logcat stays as the on-device trace. */
    private fun logTransition(
        prior: ProtectionDegradation?,
        next: ProtectionDegradation?,
        authority: ProtectionAuthority,
        cause: String,
    ) {
        Log.i(
            TAG,
            "protection ${prior ?: "OK"} -> ${next ?: "OK"} " +
                "(authority=$authority cause=$cause oem=${Build.MANUFACTURER} api=${Build.VERSION.SDK_INT})",
        )
        crashReporting.nonFatal(
            event = "protection_transition",
            cause = cause,
            "from" to (prior?.name ?: "OK"),
            "to" to (next?.name ?: "OK"),
            "authority" to authority.name,
            "oem" to Build.MANUFACTURER,
            "api" to Build.VERSION.SDK_INT.toString(),
        )
    }

    companion object {
        const val TAG = "MyExodusProtection"

        /** Decision 2's capability hierarchy. Pure for direct testing. */
        fun authorityFor(
            enhancedModeEnabled: Boolean,
            a11yAlive: Boolean,
            usageAccess: Boolean,
            overlayGranted: Boolean,
        ): ProtectionAuthority = when {
            !enhancedModeEnabled -> ProtectionAuthority.DEGRADED_NO_DETECTION
            a11yAlive -> ProtectionAuthority.FULL
            usageAccess && overlayGranted -> ProtectionAuthority.DEGRADED_POLLING_ONLY
            else -> ProtectionAuthority.DEGRADED_NO_DETECTION
        }

        /**
         * The one user-visible degraded reason (store, notification, Home banner). Precedence:
         * an explicit re-enable requirement outranks everything; then "nothing can be
         * detected"; notifications-off is soft and never masks a harder state. A polling-only
         * authority is deliberately absent — see the class doc. Pure for direct testing.
         */
        fun visibleDegradation(
            requiresExplicitReEnable: Boolean,
            authority: ProtectionAuthority,
            notificationsDenied: Boolean,
        ): ProtectionDegradation? = when {
            requiresExplicitReEnable -> ProtectionDegradation.APP_STOPPED
            authority == ProtectionAuthority.DEGRADED_NO_DETECTION -> ProtectionDegradation.DEGRADED_NO_DETECTION
            notificationsDenied -> ProtectionDegradation.NOTIFICATIONS_OFF
            else -> null
        }

        private fun causeFor(
            enhancedModeEnabled: Boolean,
            a11yAlive: Boolean,
            usageAccess: Boolean,
            overlayGranted: Boolean,
        ): String = when {
            !enhancedModeEnabled -> "enhanced_mode_off"
            a11yAlive -> "path_a"
            !usageAccess && !overlayGranted -> "usage_access+overlay"
            !usageAccess -> "usage_access"
            !overlayGranted -> "overlay"
            else -> "accessibility_unavailable"
        }
    }
}
