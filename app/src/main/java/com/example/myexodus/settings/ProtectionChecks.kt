package com.example.myexodus.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.myexodus.accountability.crypto.AccountabilityKeyStore
import com.example.myexodus.blocking.OverlayController
import com.example.myexodus.blocking.hasUsageAccess

/**
 * Platform-status snapshot behind the Settings status rows (plan §2/§8.6). Every row is a
 * separate mechanism with a separate remedy — Android battery optimization and OEM background
 * restrictions are deliberately never merged into one boolean. Reads are best-effort: a status
 * that cannot be determined reads as needing attention, never as granted.
 */
data class ProtectionChecks(
    val usageAccess: Boolean,
    val batteryExempt: Boolean,
    val overlayGranted: Boolean,
    val integrity: IntegrityStatus,
)

/**
 * Keystore/attestation health as a soft signal (plan §5/§10): Verified = hardware-backed
 * device key present in StrongBox; Unverified = present but TEE-only; Unavailable = no device
 * identity yet (accountability not set up); Failed = the keystore read itself errored. Never
 * "root detected" — that claim needs corroborating evidence this signal does not carry.
 */
enum class IntegrityStatus { VERIFIED, UNVERIFIED, UNAVAILABLE, FAILED }

/**
 * The platform's own answer to "is this app exempt from battery optimization", best-effort:
 * an unreadable state reads as not exempt (needing attention), never as granted. Shared by the
 * Settings row and the §8.7 battery-prompt gate so the two can never disagree.
 */
fun isBatteryExempt(context: Context): Boolean = runCatching {
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName)
}.getOrNull() ?: false

fun readProtectionChecks(context: Context): ProtectionChecks {
    return ProtectionChecks(
        usageAccess = runCatching { context.hasUsageAccess() }.getOrDefault(false),
        batteryExempt = isBatteryExempt(context),
        // Required on every device since the 2026-09-15 fold: Path B always runs and its wall
        // and warning banner are TYPE_APPLICATION_OVERLAY (enforcement plan v2 §8 Steps 5/7),
        // so stock Android needs the "display over other apps" grant too.
        overlayGranted = runCatching { OverlayController.overlayPermissionGranted(context) }.getOrDefault(false),
        integrity = integrityStatus(),
    )
}

/**
 * API 33+ runtime grant; below 33, the app-level toggle. Notifications are the secondary
 * warning channel only (plan §8 Step 7) — blocking never depends on this.
 */
fun notificationsGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

private fun integrityStatus(): IntegrityStatus = runCatching {
    val keyStore = AccountabilityKeyStore()
    when {
        !keyStore.hasSigningKey() -> IntegrityStatus.UNAVAILABLE
        keyStore.isStrongBoxBacked() -> IntegrityStatus.VERIFIED
        else -> IntegrityStatus.UNVERIFIED
    }
}.getOrElse { IntegrityStatus.FAILED }

fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

fun usageAccessSettingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

/** Opens the battery-optimization list. It does NOT grant the exemption (plan §5) — the user
 *  still makes the change there. */
fun batteryOptimizationSettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

/**
 * §8.7/D1's direct exemption request — the one place in the app that asks for it, reachable
 * only from the outage prompt's Fix row after a corroborated interruption (never at first
 * launch, never from the plain status row). Falls back to the exemption list when the intent
 * does not resolve on a given build: that is the plan's settings-guidance path, and a dead
 * intent must not be a dead end.
 */
fun requestBatteryExemption(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        packageUri(context),
    )
    runCatching { context.startActivity(direct) }
        .onFailure { context.startActivity(batteryOptimizationSettingsIntent()) }
}

fun overlaySettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context))

fun appInfoSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

/**
 * Plan §5.20: a device with no BIOMETRIC_STRONG-class authenticator cannot approve or unpair —
 * the one honest fix is enrolling one, so the explicit message routes here. API 30+ has the
 * dedicated enroll screen; below it, and on OEM builds that skip the enroll screen, the
 * security settings page hosts the same setting, so a dead intent is not a dead end.
 */
fun biometricEnrollSettingsIntent(context: Context) {
    val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_BIOMETRIC_ENROLL)
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }
    runCatching { context.startActivity(direct) }
        .onFailure { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
}

/**
 * OEM background-restriction screen. No API reports OEM restriction state, so this is a
 * best-effort deep link (plan §8.6, informational only) keyed on `Build.MANUFACTURER`; an
 * entry that does not resolve on a given OEM build falls back to App info at the call site.
 * Component names are the widely-published per-OEM screens; unknown manufacturers get App info.
 */
fun oemRestrictionSettingsIntent(context: Context): Intent {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val component = OEM_RESTRICTION_SCREENS.entries
        .firstOrNull { (key, _) -> manufacturer.startsWith(key) }
        ?.value
    if (component == null) return appInfoSettingsIntent(context)
    val (packageName, className) = component.split('/')
    return Intent().setComponent(ComponentName(packageName, className))
}

private val OEM_RESTRICTION_SCREENS: Map<String, String> = mapOf(
    "xiaomi" to "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
    "redmi" to "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
    "poco" to "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
    "oppo" to "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
    "realme" to "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
    "oneplus" to "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
    "vivo" to "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    "iqoo" to "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    "samsung" to "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
    "huawei" to "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "honor" to "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "asus" to "com.asus.mobilemanager/com.asus.mobilemanager.powersaver.PowerSaverSettings",
)

private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)
