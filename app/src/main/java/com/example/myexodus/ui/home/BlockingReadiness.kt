package com.example.myexodus.ui.home

import android.content.Context
import com.example.myexodus.blocking.OverlayController
import com.example.myexodus.blocking.ProtectionAuthority
import com.example.myexodus.blocking.hasUsageAccess
import com.example.myexodus.settings.isBlockerAccessibilityEnabled
import com.example.myexodus.vpn.VpnLifecycle

/**
 * Owner correction 2026-10-21: accessibility is not the only permission a block needs. These
 * are the grants without which enforcement cannot actually happen, each with its own remedy:
 * usage access (Path B detection and time accounting), display-over-other-apps (the block
 * screen itself), and VPN consent (the tunnel that enforces website rules). The accessibility
 * service is the fourth, read from [ProtectionAuthority]. Battery exemption and notifications
 * are reliability/alert mechanisms, not blocking prerequisites, so they keep their own rows.
 */
data class BlockingReadiness(
    val usageAccess: Boolean,
    val overlayGranted: Boolean,
    /** Live website rules exist and Android holds no VPN consent for this app. */
    val websiteConsentMissing: Boolean,
    /**
     * The platform says the blocker service is enabled — it names it in the enabled list AND
     * the accessibility master switch is not off. False is definitive — the service cannot
     * bind; true alone does not prove it is alive (the list flaps on XOS, which is why
     * [ProtectionAuthority] stays the liveness source). Not part of [essentialGap]: a listing
     * flap must never downgrade a live Path A to LIMITED.
     */
    val accessibilityEnabledByPlatform: Boolean,
) {
    val essentialGap: Boolean
        get() = !usageAccess || !overlayGranted || websiteConsentMissing
}

/** Best-effort read using the ProtectionChecks rule: undeterminable reads as needing attention,
 *  never as granted. */
internal fun readBlockingReadiness(
    context: Context,
    hasLiveWebsiteRule: Boolean,
): BlockingReadiness = BlockingReadiness(
    usageAccess = runCatching { context.hasUsageAccess() }.getOrDefault(false),
    overlayGranted = runCatching { OverlayController.overlayPermissionGranted(context) }
        .getOrDefault(false),
    websiteConsentMissing = hasLiveWebsiteRule && !VpnLifecycle.consented(context),
    accessibilityEnabledByPlatform = isBlockerAccessibilityEnabled(context),
)

/**
 * The usage/overlay fixes are Path A gaps: with the accessibility service alive the authority
 * is FULL even when either grant is gone. Under polling-only both are necessarily held, and
 * under no-detection the generic protection row owns the causes.
 */
internal fun showUsageFix(authority: ProtectionAuthority?, readiness: BlockingReadiness?): Boolean =
    authority == ProtectionAuthority.FULL && readiness?.usageAccess == false

internal fun showOverlayFix(authority: ProtectionAuthority?, readiness: BlockingReadiness?): Boolean =
    authority == ProtectionAuthority.FULL && readiness?.overlayGranted == false

/** FULL is only the whole truth when every essential grant is present. */
internal fun limitedBeyondAuthority(
    authority: ProtectionAuthority?,
    readiness: BlockingReadiness?,
): Boolean = authority == ProtectionAuthority.FULL && readiness?.essentialGap == true
