package com.example.myexodus.blocking

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Emergency-surface exclusion (Enhanced Mode spec §8 Step 3, Architecture Decision 9): runs
 * first, unconditionally, before every other step of the event pipeline. Fail-open — any
 * null/ambiguous input, system window, or exception resolves to "protected" (never block).
 *
 * Live-protected package sources, refreshed by [refresh] on service connect and
 * `ACTION_DEFAULT_DIALER_CHANGED`:
 * 1. [TelecomManager.getDefaultDialerPackage] / `getSystemDialerPackage` — the live dialer,
 *    available on every supported API level (the plan's `RoleManager.getRoleHolders` was
 *    removed from the API surface of current platform stubs, so it is invoked
 *    reflectively where a runtime still ships it — API 29+ era runtimes — and the
 *    TelecomManager query carries the same semantics on all levels).
 * 2. `RoleManager.getRoleHolders(ROLE_DIALER | ROLE_EMERGENCY)` via reflection, "where
 *    available on the API level" (plan §8 Step 3).
 * 3. Window classification: `TYPE_SYSTEM`, legacy keyguard type, and (API 30+) incoming-call
 *    / keyguard title markers for OEMs that misreport window types.
 * 4. Static OEM fallback list — used ONLY when both live sources come back empty (never
 *    alongside live data).
 */
class EmergencyExclusionManager(
    private val appContext: Context,
    private val roleHolderQuery: (role: String) -> List<String> = { role -> reflectRoleHolders(appContext, role) },
    private val dialerQuery: () -> Set<String> = {
        runCatching {
            val telecom = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecom == null) {
                emptySet()
            } else {
                // Query each accessor separately: getSystemDialerPackage (API 29+) can throw on some builds.
                setOfNotNull(
                    runCatching { telecom.defaultDialerPackage }.getOrNull(),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching { telecom.systemDialerPackage }.getOrNull()
                    } else {
                        null
                    },
                )
            }
        }.getOrDefault(emptySet())
    },
) {
    @Volatile private var liveDialerPackages: Set<String> = emptySet()
    @Volatile private var liveRoleHolders: Set<String> = emptySet()

    /** Re-queries the live sources; call on [AccessibilityService.onServiceConnected] and on default-dialer change. */
    fun refresh() {
        liveDialerPackages = try {
            dialerQuery()
        } catch (e: Throwable) {
            Log.w(TAG, "Dialer package query failed; treating as empty", e)
            emptySet()
        }
        liveRoleHolders = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                roleHolderQuery(ROLE_DIALER).toSet() +
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) roleHolderQuery(ROLE_EMERGENCY).toSet() else emptySet()
            } else {
                emptySet()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Role-holder query failed; treating as empty", e)
            emptySet()
        }
        Log.i(TAG, "Emergency exclusion refreshed: dialer=$liveDialerPackages roles=$liveRoleHolders")
    }

    fun isProtected(packageName: String?, window: AccessibilityWindowInfo?): Boolean = try {
        val pkg = packageName?.trim()
        if (pkg.isNullOrEmpty()) {
            true
        } else if (window != null && isProtectedWindowType(window.type, window.title?.toString())) {
            true
        } else if (pkg in liveDialerPackages || pkg in liveRoleHolders) {
            true
        } else if (liveDialerPackages.isEmpty() && liveRoleHolders.isEmpty() &&
            pkg in FALLBACK_DIALER_PACKAGES
        ) {
            true
        } else {
            false
        }
    } catch (e: Throwable) {
        // Any ambiguity resolves to protected (fail-open — never block).
        Log.w(TAG, "Emergency exclusion check failed; treating as protected", e)
        true
    }

    /**
     * Pure window classification, kept separate for direct testing. Title markers are only
     * trusted on non-application windows: an app fully controls its own window title, so a
     * hostile app could spoof "Incoming call" to exempt itself (2026-08-28 review finding,
     * fixed in this pass). App-hosted call UI (incallui packages) is instead covered by the
     * live dialer query + fallback package list, so nothing legitimate loses protection.
     */
    fun isProtectedWindowType(type: Int, title: String?): Boolean {
        if (type == AccessibilityWindowInfo.TYPE_SYSTEM || type == TYPE_ACCESSIBILITY_OVERLAY) {
            return true
        }
        if (type != AccessibilityWindowInfo.TYPE_APPLICATION &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && title != null
        ) {
            val t = title.lowercase()
            return CALL_UI_MARKERS.any { t.contains(it) } || t.contains("keyguard")
        }
        return false
    }

    companion object {
        const val TAG = "MyExodusEmergency"
        const val ROLE_DIALER = "android.app.role.DIALER"
        const val ROLE_EMERGENCY = "android.app.role.EMERGENCY"

        /**
         * Value 4 is TYPE_ACCESSIBILITY_OVERLAY — a "TYPE_KEYGUARD" window type never existed
         * in any SDK (verified against AOSP 2026-08-28 research pass). Kept as protected
         * anyway: OEMs misreport keyguard/system windows with arbitrary type values, and a
         * third-party accessibility overlay counting as protected is fail-open (safe direction).
         */
        const val TYPE_ACCESSIBILITY_OVERLAY = 4

        val CALL_UI_MARKERS = listOf("incoming call", "in-call", "call in progress")

        /** AOSP + Google Phone + known OEM dialers (incl. Infinix/XOS). Fallback only. */
        val FALLBACK_DIALER_PACKAGES = setOf(
            "com.android.dialer",            // AOSP
            "com.android.incallui",          // AOSP in-call UI
            "com.google.android.dialer",     // Google Phone
            "com.samsung.android.dialer",    // Samsung
            "com.xiaomi.incall.service",     // Xiaomi
            "com.miui.incall",               // MIUI
            "com.oneplus.dialer",            // OnePlus
            "com.oppo.dialer",               // Oppo
            "com.coloros.incallui",          // Oppo/ColorOS
            "com.transsion.incallui",        // Infinix/Tecno/itel (XOS)
            "com.sh.smart.caller",           // XOS default dialer (observed 2026-08-27 via `cmd role`)
        )

        /**
         * `RoleManager.getRoleHolders` is absent from current platform stubs; invoke it
         * reflectively so runtimes that still ship it (API 29+ era) stay covered.
         */
        private fun reflectRoleHolders(context: Context, role: String): List<String> = runCatching {
            // ROLE_SERVICE is API 29; getSystemService(unknown service) returns null below it,
            // which is exactly what the reflective fallback expects.
            @Suppress("InlinedApi")
            val roleManager = context.getSystemService(Context.ROLE_SERVICE)
                ?: return@runCatching emptyList()
            val method = roleManager.javaClass.getMethod("getRoleHolders", String::class.java)
            @Suppress("UNCHECKED_CAST")
            method.invoke(roleManager, role) as? List<String> ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
