package com.example.myexodus.accountability.sync

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.installations.FirebaseInstallations
import kotlinx.coroutines.tasks.await

/**
 * Plan §8.12 / AD-18a: registers this device as a push installation under
 * `/users/{uid}/push_installations/{installationId}` so the relay can wake it. The
 * installation id is the device's Firebase Installation ID (FID, SOS Step 5) — the same
 * value the push service dispatches to — so one identity spans registrations and mailbox
 * acks. Pre-SOS releases keyed the document by ANDROID_ID; that legacy document is deleted
 * once, best-effort, after the first successful FID registration (it cannot ack under
 * AD-18b's per-installation contract and is not a dispatchable FID).
 *
 * The registration also carries `pushEligible` (§8.12): app notifications enabled AND the
 * `partner_nudges_v3` channel present with importance != NONE. It is the latest known device
 * capability snapshot that reduces unnecessary sends — never authoritative truth.
 *
 * Idempotent: every call rewrites the row (full replace, so a server-written `stale: true`
 * from AD-18c clears) and refreshes `updatedAt`, which is what keeps the 90-day staleness
 * clock at bay.
 *
 * Collaborators are function-typed seams (same house pattern as [PartnerSyncCoordinator]),
 * so the orchestration is unit-testable without Firebase or FCM.
 *
 * Never throws: a registration failure is a degraded-delivery condition, not a crash —
 * unregistered devices still receive events via foreground/periodic sync (AD-05).
 */
class PushInstallationRegistrar(
    private val ensureInstallationId: suspend () -> String?,
    private val platform: String,
    private val appVersion: String,
    private val ensureUid: suspend () -> String?,
    private val fetchToken: suspend () -> String?,
    private val pushEligible: () -> Boolean,
    private val write: suspend (uid: String, installationId: String, data: Map<String, Any>) -> Unit,
    private val legacyInstallationId: String? = null,
    private val deleteLegacy: suspend (uid: String, installationId: String) -> Unit = { _, _ -> },
) {

    suspend fun register(): Boolean {
        return try {
            val uid = ensureUid()
            if (uid == null) {
                Log.w(TAG, "registration skipped: no uid (auth unavailable)")
                return false
            }
            // FID after auth: the first fetch may need network, and there is no point
            // resolving one for a device that cannot claim it.
            val installationId = ensureInstallationId()
            if (installationId == null) {
                Log.w(TAG, "registration skipped: no installation id (FID unavailable)")
                return false
            }
            val token = fetchToken()?.takeIf { it.isNotBlank() }
            if (token == null) {
                Log.w(TAG, "registration skipped: no FCM token")
                return false
            }
            write(
                uid,
                installationId,
                FirestoreSchema.pushInstallationData(
                    provider = PROVIDER_FCM,
                    token = token,
                    platform = platform,
                    appVersion = appVersion,
                    pushEligible = pushEligible(),
                ),
            )
            Log.i(TAG, "push installation registered")
            // Only after the replacement landed: removing the legacy row first would leave
            // the device unregistered if this write then failed. Best-effort from here on.
            val legacyId = legacyInstallationId
            if (legacyId != null && legacyId != installationId) {
                runCatching { deleteLegacy(uid, legacyId) }
                    .onFailure { Log.w(TAG, "legacy installation cleanup failed", it) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "push installation registration failed", e)
            false
        }
    }

    companion object {
        const val PROVIDER_FCM = "fcm"
        private const val TAG = "MyExodusPushRegistrar"

        /**
         * §8.12: the one installation identity — the FID from FirebaseInstallations. Null
         * (fetch failed, e.g. offline on a first run with no cached id) reads as "cannot
         * register right now"; the AD-05 sync fallback still delivers and the next pass
         * retries.
         */
        suspend fun installationIdOf(): String? = runCatching {
            FirebaseInstallations.getInstance().id.await()
        }.getOrNull()

        /**
         * §8.12 pushEligible: app notifications enabled AND the `partner_nudges_v3` channel
         * exists AND its importance is not NONE. The channel is created in the SOS
         * notification step; before that the formula reports false, which is accurate —
         * nothing could render a nudge yet. Re-evaluated on every registration refresh.
         */
        fun pushEligibleOf(context: Context): Boolean {
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(PartnerNotifications.CHANNEL_PARTNER_NUDGES)
            return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                channel != null &&
                channel.importance != NotificationManager.IMPORTANCE_NONE
        }

        /** Legacy (pre-SOS) installation key. Read only to delete the old document during the
         *  FID migration and by the debug verification harness. ANDROID_ID is unique per
         *  app-signing key + user + device and survives app updates and data clears. */
        // Suppressed: AD-18a's original key (rules contract); not used for advertising or
        // analytics.
        @SuppressLint("HardwareIds")
        fun androidIdOf(context: Context): String? =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
    }
}
