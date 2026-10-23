package com.example.myexodus.notifications

import android.net.Uri
import androidx.core.net.toUri

/**
 * Tap-intent identity for every notification surface. PendingIntent identity is (request
 * code, Intent filterEquals) — **extras do not count**. Two posts sharing a request code with
 * no data therefore share ONE PendingIntent, and whichever posts last under
 * FLAG_UPDATE_CURRENT rewrites every other notification's extras. Owner warning 2026-10-23
 * ("we don't want collisions"): that class was real twice over — a partner-event post stripped
 * the reach-out nudge's routing extras, and the VPN/domain-usage taps (Rules) could rewrite a
 * protection alert's tap (Settings). A data URI is part of filterEquals, so giving each
 * distinct tap target its own makes the identities collision-proof by construction.
 *
 * The NotificationManager key for the entries themselves is (tag, id), scoped separately in
 * each surface (see `PartnerNotifications.partnerTag` and `StreakNotifications.milestoneTag`).
 */
object NotificationTapTarget {

    private fun uri(key: String): Uri = "myexodus://notification/${Uri.encode(key)}".toUri()

    /** A singleton surface's tap target (protection, VPN, domain usage). */
    fun of(key: String): Uri = uri(key)

    /** Multi-habit surfaces carry the exact habit in identity, never in extras alone. */
    fun habit(habitId: String): Uri = "myexodus://habit/${Uri.encode(habitId)}".toUri()
}
