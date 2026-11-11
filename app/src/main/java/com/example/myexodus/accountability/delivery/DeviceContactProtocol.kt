package com.example.myexodus.accountability.delivery

/**
 * §6.3 client half of the stateless signed contact: the exact wire shape the server's
 * `validateContact`/`contactMessage` accept, the counter bound, and the reply mapping. Pure —
 * no Android or Firebase types — so JVM tests pin every byte of the signed message.
 */
data class EnforcementHealthSnapshot(
    val accessibilityEnabled: Boolean,
    val vpnActive: Boolean,
    val deviceAdminActive: Boolean,
    val notificationPermissionGranted: Boolean,
    /** Effective monitoring policy: only capabilities a live rule actually needs. */
    val requiredCapabilities: List<String>,
)

data class DeviceContactV1(
    val ownerUid: String,
    val installationId: String,
    val keyId: String,
    val heartbeatCounter: Long,
    val deviceReportedAtMs: Long,
    val health: EnforcementHealthSnapshot,
    val probeId: String? = null,
    val v: Int = DeviceContactProtocol.VERSION,
)

object DeviceContactProtocol {
    const val VERSION = 1

    /** JCS-safe integer ceiling — the same bound the server's `isValidCounter` enforces. */
    const val MAX_COUNTER = 9_007_199_254_740_991L

    const val CAPABILITY_ACCESSIBILITY = "ACCESSIBILITY"
    const val CAPABILITY_VPN = "VPN"
    const val CAPABILITY_DEVICE_ADMIN = "DEVICE_ADMIN"
    val CAPABILITIES = listOf(CAPABILITY_ACCESSIBILITY, CAPABILITY_VPN, CAPABILITY_DEVICE_ADMIN)

    /** Mirror of the server's PROBE_ID; an id outside this shape would be rejected anyway. */
    val PROBE_ID = Regex("^[A-Za-z0-9_-]{8,160}$")

    sealed interface Reply {
        data object Accepted : Reply
        data class Stale(val serverCounter: Long) : Reply
        data object InvalidProbe : Reply
    }

    /** The exact object whose JCS bytes are signed — identical to the server's contactMessage.
     *  `requiredCapabilities` order is preserved on the wire and must be signed as sent. */
    fun jcsValue(contact: DeviceContactV1): Map<String, Any?> = linkedMapOf(
        "v" to contact.v,
        "ownerUid" to contact.ownerUid,
        "installationId" to contact.installationId,
        "keyId" to contact.keyId,
        "heartbeatCounter" to contact.heartbeatCounter,
        "deviceReportedAtMs" to contact.deviceReportedAtMs,
        "enforcement" to linkedMapOf(
            "accessibilityEnabled" to contact.health.accessibilityEnabled,
            "vpnActive" to contact.health.vpnActive,
            "deviceAdminActive" to contact.health.deviceAdminActive,
            "notificationPermissionGranted" to contact.health.notificationPermissionGranted,
            "requiredCapabilities" to contact.health.requiredCapabilities.toList(),
        ),
    ).apply { if (contact.probeId != null) put("probeId", contact.probeId) }

    fun canonicalBytes(contact: DeviceContactV1): ByteArray =
        Jcs.canonicalize(jcsValue(contact)).toByteArray(Charsets.UTF_8)

    /** §6.3 wire: the flat contact plus its base64url signature, as the callable receives it. */
    fun submitPayload(contact: DeviceContactV1, signature: ByteArray): Map<String, Any?> =
        jcsValue(contact) + ("contactSignature" to PartnerEventProtocol.base64Url(signature))

    /** Unknown or malformed replies are null — the caller counts them as not delivered. */
    fun parseReply(data: Any?): Reply? {
        val root = data as? Map<*, *> ?: return null
        return when (root["outcome"] as? String) {
            "ACCEPTED" -> Reply.Accepted
            "STALE" -> Reply.Stale((root["serverCounter"] as? Number)?.toLong() ?: return null)
            "INVALID_PROBE" -> Reply.InvalidProbe
            else -> null
        }
    }
}
