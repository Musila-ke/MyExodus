package com.example.myexodus.accountability.delivery

import android.util.Log
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException

/**
 * §6.3 sender for the stateless signed contact. Deliberately outside the event outbox: a
 * contact is a fresh latest-wins report, never a retained event — nothing here touches
 * `pending_partner_events`, and nothing is replayed after an offline failure. Every skip is a
 * legitimate state (no account, not registered, unreadable policy); only the server call and
 * signing failures count as failures.
 */
class DeviceContactClient(
    private val outbox: ServiceEventOutbox,
    private val keyStore: PartnerEventKeyStore,
    private val clock: PartnerDeliveryClock,
    private val ownerUid: () -> String?,
    /** Null when any capability probe failed: absence of a report means unavailable, never a
     *  fabricated "all fine" or "all revoked" contact. */
    private val healthSnapshot: suspend () -> EnforcementHealthSnapshot?,
    private val send: suspend (Map<String, Any?>) -> Any?,
) {
    sealed interface Result {
        data object Accepted : Result
        data object Skipped : Result
        data object InvalidProbe : Result

        /** The server already holds a higher counter; local state was aligned forward. */
        data object Stale : Result
        data class Failed(val errorCode: String) : Result
    }

    suspend fun sendContact(probeId: String?): Result {
        val uid = ownerUid() ?: return Result.Skipped
        val installation = outbox.installation(uid) ?: return Result.Skipped
        if (installation.registrationState != InstallationState.REGISTRATION_ACTIVE) {
            return Result.Skipped
        }
        val keyId = runCatching { keyStore.keyId(installation.installationId) }.getOrNull()
            ?: return Result.Skipped
        if (keyId != installation.activeKeyId) return Result.Skipped
        val health = healthSnapshot() ?: return Result.Skipped
        // Persisted before the signature exists: a crash mid-send burns the counter instead of
        // ever reusing it, and a STALE reply aligns forward.
        val counter = outbox.allocateHeartbeatCounter(installation.installationId)
            ?: return Result.Skipped
        return try {
            val contact = DeviceContactV1(
                ownerUid = uid,
                installationId = installation.installationId,
                keyId = keyId,
                heartbeatCounter = counter,
                deviceReportedAtMs = clock.wallClockMs(),
                health = health,
                probeId = probeId,
            )
            val signature = keyStore.sign(
                installation.installationId,
                DeviceContactProtocol.canonicalBytes(contact),
            )
            when (val reply = DeviceContactProtocol.parseReply(
                send(DeviceContactProtocol.submitPayload(contact, signature)),
            )) {
                DeviceContactProtocol.Reply.Accepted -> Result.Accepted
                DeviceContactProtocol.Reply.InvalidProbe -> Result.InvalidProbe
                is DeviceContactProtocol.Reply.Stale -> {
                    outbox.alignHeartbeatCounter(installation.installationId, reply.serverCounter)
                    Result.Stale
                }
                null -> Result.Failed("REPLY_UNRECOGNIZED")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? FirebaseFunctionsException)?.message
                ?: e.javaClass.simpleName
            Log.w(TAG, "device contact failed: $code")
            Result.Failed(code)
        }
    }

    private companion object {
        const val TAG = "MyExodusDeviceContact"
    }
}
