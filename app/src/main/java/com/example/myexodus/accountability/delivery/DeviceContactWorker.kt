package com.example.myexodus.accountability.delivery

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.myexodus.util.canExpediteWork
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * §6.3 signed-contact worker: one report per opportunity, never a retained or replayed call.
 * The periodic 15-minute request is the steady cadence; an immediate request covers foreground
 * and boot, and a probe request answers the server's short window. Every run allocates a fresh
 * counter, so a skipped or failed contact is simply superseded by the next one.
 */
@HiltWorker
class DeviceContactWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val client: DeviceContactClient,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val rawProbeId = inputData.getString(DEVICE_CONTACT_PROBE_ID)
        val probeId = rawProbeId?.takeIf { DeviceContactProtocol.PROBE_ID.matches(it) }
        if (rawProbeId != null && probeId == null) {
            Log.w(TAG, "invalid probe id — not sending")
            return Result.success()
        }
        val outcome = client.sendContact(probeId)
        Log.i(TAG, "contact: $outcome")
        return when (outcome) {
            is DeviceContactClient.Result.Failed, DeviceContactClient.Result.Stale ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    private companion object {
        const val TAG = "MyExodusDeviceContact"
        // The periodic cadence is the real retry; a couple of in-window attempts only matter
        // for the probe, whose answer window is 20 minutes.
        const val MAX_RETRIES = 2
    }
}

/** The probe's input key — shared with [com.example.myexodus.accountability.sync.PartnerMessagingService]. */
internal const val DEVICE_CONTACT_PROBE_ID = "probeId"

/** §6.3 steady cadence. UPDATE, not KEEP, for the same specification-refresh reason as the
 *  partner sync schedule: devices already holding the old request adopt the connected
 *  constraint instead of keeping it forever. */
fun enqueueDeviceContact(context: Context) {
    val request = PeriodicWorkRequestBuilder<DeviceContactWorker>(15, TimeUnit.MINUTES)
        .setConstraints(contactConstraints())
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        DEVICE_CONTACT_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
    )
}

internal const val DEVICE_CONTACT_WORK_NAME = "device-contact"

/** Foreground / boot: one immediate contact without disturbing the periodic schedule. KEEP so
 *  a contact already queued is never duplicated. */
fun enqueueDeviceContactNow(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        DEVICE_CONTACT_NOW_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<DeviceContactWorker>()
            .setConstraints(contactConstraints())
            .build(),
    )
}

internal const val DEVICE_CONTACT_NOW_WORK_NAME = "device-contact-now"

/** §8.7 probe reply. Expedited only on Android 12+ per the plan; below that the ordinary
 *  request still runs inside the 20-minute window. Top-level internal so a JVM test can pin
 *  both branches. */
internal fun deviceContactProbeRequest(
    probeId: String,
    expedited: Boolean = canExpediteWork(),
): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<DeviceContactWorker>()
        .setInputData(workDataOf(DEVICE_CONTACT_PROBE_ID to probeId))
        .setConstraints(contactConstraints())
        .apply {
            if (expedited) {
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
        }
        .build()

internal fun enqueueDeviceContactProbe(context: Context, probeId: String) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        DEVICE_CONTACT_PROBE_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        deviceContactProbeRequest(probeId),
    )
}

internal const val DEVICE_CONTACT_PROBE_WORK_NAME = "device-contact-probe"

private fun contactConstraints(): Constraints =
    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
