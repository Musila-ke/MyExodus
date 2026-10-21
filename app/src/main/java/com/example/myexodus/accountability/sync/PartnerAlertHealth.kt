package com.example.myexodus.accountability.sync

import android.app.NotificationManager
import android.content.Context
import com.example.myexodus.notifications.NotificationAccess
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Plan Step 6 notification health: the questions the user can act on, in the order that makes
 * the answer actionable — can the app post at all, is the partner-alerts channel muted, can
 * this device receive a push. The server-side `pushEligible` snapshot ([PushInstallationRegistrar])
 * is a different contract (nudge capability); this object is the user-facing reading and adds
 * the channel the accountability events actually post to.
 *
 * Registration/token failures are deliberately NOT a state: they are transient (offline, token
 * rotation) and the mailbox pull still delivers every event on the next pass. Only a device
 * that cannot run the push provider at all is a persistent condition worth a banner.
 */
object PartnerAlertHealth {

    enum class State {
        OK,

        /** POST_NOTIFICATIONS denied or app-level notifications off. */
        NOTIFICATIONS_OFF,

        /** The `partner_accountability_events_v1` channel exists and its importance is NONE. */
        CHANNEL_OFF,

        /** Google Play services is missing or unavailable — FCM cannot register on this device. */
        PROVIDER_UNSUPPORTED,
    }

    /** Pure precedence: a fixable, local switch outranks a device capability. */
    fun state(canPost: Boolean, alertsChannelEnabled: Boolean, providerSupported: Boolean): State =
        when {
            !canPost -> State.NOTIFICATIONS_OFF
            !alertsChannelEnabled -> State.CHANNEL_OFF
            !providerSupported -> State.PROVIDER_UNSUPPORTED
            else -> State.OK
        }

    fun of(context: Context): State = state(
        canPost = NotificationAccess.canPost(context),
        alertsChannelEnabled = alertsChannelEnabled(context),
        providerSupported = providerSupported(context),
    )

    /** A channel the app has not created yet is not a channel the user muted. */
    private fun alertsChannelEnabled(context: Context): Boolean {
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(PartnerNotifications.CHANNEL_ACCOUNTABILITY_EVENTS)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun providerSupported(context: Context): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
            ConnectionResult.SUCCESS
}
