package com.example.myexodus.accountability.sync

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Plan §8 Step 7 (client side) / AD-18a: registers this device as a push installation under
 * `/users/{uid}/push_installations/{installationId}` so the relay can wake it. The
 * installation id is the device's ANDROID_ID — stable across app updates, unique per
 * device+signing-key+user, and requiring no extra storage. Idempotent: every call rewrites
 * the row (full replace, so a server-written `stale: true` from AD-18c clears) and refreshes
 * `updatedAt`, which is what keeps the 90-day staleness clock at bay.
 *
 * Collaborators are function-typed seams (same house pattern as [PartnerSyncCoordinator]),
 * so the orchestration is unit-testable without Firebase or FCM.
 *
 * Never throws: a registration failure is a degraded-delivery condition, not a crash —
 * unregistered devices still receive events via foreground/periodic sync (AD-05).
 */
class PushInstallationRegistrar(
    private val installationId: String?,
    private val platform: String,
    private val appVersion: String,
    private val ensureUid: suspend () -> String?,
    private val fetchToken: suspend () -> String?,
    private val write: suspend (uid: String, installationId: String, data: Map<String, Any>) -> Unit,
) {

    suspend fun register(): Boolean {
        return try {
            val installationId = installationId
            if (installationId == null) {
                Log.w(TAG, "registration skipped: device reports no ANDROID_ID")
                return false
            }
            val uid = ensureUid()
            if (uid == null) {
                Log.w(TAG, "registration skipped: no uid (auth unavailable)")
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
                ),
            )
            Log.i(TAG, "push installation registered ($installationId)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "push installation registration failed", e)
            false
        }
    }

    companion object {
        const val PROVIDER_FCM = "fcm"
        private const val TAG = "MyExodusPushRegistrar"

        /** ANDROID_ID is unique per app-signing key + user + device and survives app
         *  updates and data clears. A null reads as "cannot register" — the AD-05 sync
         *  fallback still delivers; no persisted-UUID fallback until a device needs one. */
        // Suppressed: AD-18a keys the installation doc by this id (rules contract); it is
        // not used for advertising or analytics.
        @SuppressLint("HardwareIds")
        fun androidIdOf(context: Context): String? =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
    }
}
