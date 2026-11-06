package com.example.myexodus.update

import org.json.JSONObject
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

internal enum class PolicyMode { OFFER, HALT }

internal data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val targetSdk: Int,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256Hex: String,
    val releaseNotes: String,
)

internal data class UpdatePolicy(
    val policySequence: Long,
    val mode: PolicyMode,
    val rolloutPercent: Int,
    val cohortSalt: String,
    val issuedAtEpochSeconds: Long,
    val release: UpdateRelease?,
)

internal sealed interface PolicyVerification {
    data class Valid(val policy: UpdatePolicy, val payloadHash: String) : PolicyVerification
    data object NotConfigured : PolicyVerification
    data class Invalid(val detail: String) : PolicyVerification
}

private val ENVELOPE_KEYS = setOf("format", "keyId", "payloadBase64", "signatureBase64")
private val OFFER_KEYS = setOf(
    "schemaVersion", "packageName", "channel", "policySequence", "mode", "rolloutPercent",
    "cohortSalt", "issuedAtEpochSeconds", "release",
)
private val HALT_KEYS = OFFER_KEYS - "release"
private val RELEASE_KEYS = setOf(
    "versionCode", "versionName", "minSdk", "targetSdk", "apkUrl", "apkSizeBytes",
    "apkSha256Hex", "releaseNotes",
)
private val SHA256_HEX = Regex("[0-9a-f]{64}")
private val COHORT_SALT_HEX = Regex("[0-9a-f]{32}")
private val SAFE_APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.apk")
private const val SIGNATURE_MAX_BYTES = 512

/**
 * Verifies one signed release-policy envelope (plan §8.1). The signature covers the exact
 * decoded payload bytes, so nothing is trusted before [Signature.verify] succeeds.
 */
internal fun verifyPolicyEnvelope(
    envelopeJson: String,
    publicKeyBase64: String,
    expectedPackageName: String,
    releaseRepository: String,
): PolicyVerification {
    if (publicKeyBase64.isBlank() || releaseRepository.isBlank()) return PolicyVerification.NotConfigured
    if (envelopeJson.toByteArray(Charsets.UTF_8).size > UpdateConfig.MAX_ENVELOPE_BYTES) {
        return PolicyVerification.Invalid("envelope size")
    }
    return try {
        val envelope = JSONObject(envelopeJson)
        if (envelope.keysSet() != ENVELOPE_KEYS) return PolicyVerification.Invalid("envelope keys")
        if (envelope.getString("format") != UpdateConfig.MANIFEST_FORMAT) {
            return PolicyVerification.Invalid("envelope format")
        }
        if (envelope.getString("keyId") != UpdateConfig.MANIFEST_KEY_ID) {
            return PolicyVerification.Invalid("key id")
        }
        val payloadBase64 = envelope.getString("payloadBase64")
        val signatureBase64 = envelope.getString("signatureBase64")
        val payload = Base64.getDecoder().decode(payloadBase64)
        val signature = Base64.getDecoder().decode(signatureBase64)
        if (payload.size > UpdateConfig.MAX_PAYLOAD_BYTES) return PolicyVerification.Invalid("payload size")
        if (signature.isEmpty() || signature.size > SIGNATURE_MAX_BYTES) {
            return PolicyVerification.Invalid("signature size")
        }
        // The decoder tolerates some non-canonical padding; require the exact canonical text.
        if (Base64.getEncoder().encodeToString(payload) != payloadBase64) {
            return PolicyVerification.Invalid("payload encoding")
        }
        if (Base64.getEncoder().encodeToString(signature) != signatureBase64) {
            return PolicyVerification.Invalid("signature encoding")
        }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(decodePublicKey(publicKeyBase64))
        verifier.update(payload)
        if (!verifier.verify(signature)) return PolicyVerification.Invalid("signature")

        val body = JSONObject(String(payload, Charsets.UTF_8))
        val mode = when (body.optString("mode")) {
            "OFFER" -> PolicyMode.OFFER
            "HALT" -> PolicyMode.HALT
            else -> return PolicyVerification.Invalid("mode")
        }
        if (body.keysSet() != if (mode == PolicyMode.OFFER) OFFER_KEYS else HALT_KEYS) {
            return PolicyVerification.Invalid("payload keys")
        }
        if (body.getInt("schemaVersion") != 1) return PolicyVerification.Invalid("schemaVersion")
        if (body.getString("packageName") != expectedPackageName) {
            return PolicyVerification.Invalid("packageName")
        }
        if (body.getString("channel") != UpdateConfig.CHANNEL) {
            return PolicyVerification.Invalid("channel")
        }
        val policySequence = body.positiveLong("policySequence")
            ?: return PolicyVerification.Invalid("policySequence")
        val rolloutPercent = body.opt("rolloutPercent") as? Int
            ?: return PolicyVerification.Invalid("rolloutPercent")
        if (rolloutPercent !in 0..100) return PolicyVerification.Invalid("rolloutPercent")
        if (mode == PolicyMode.HALT && rolloutPercent != 0) {
            return PolicyVerification.Invalid("halt rollout")
        }
        val cohortSalt = body.getString("cohortSalt")
        if (!COHORT_SALT_HEX.matches(cohortSalt)) return PolicyVerification.Invalid("cohortSalt")
        body.positiveLong("issuedAtEpochSeconds")
            ?: return PolicyVerification.Invalid("issuedAtEpochSeconds")

        val release = if (mode == PolicyMode.OFFER) {
            parseRelease(body.getJSONObject("release"), releaseRepository)
                ?: return PolicyVerification.Invalid("release")
        } else {
            null
        }
        PolicyVerification.Valid(
            UpdatePolicy(
                policySequence = policySequence,
                mode = mode,
                rolloutPercent = rolloutPercent,
                cohortSalt = cohortSalt,
                issuedAtEpochSeconds = body.getLong("issuedAtEpochSeconds"),
                release = release,
            ),
            sha256Hex(payload),
        )
    } catch (e: Exception) {
        PolicyVerification.Invalid(e.javaClass.simpleName)
    }
}

private fun parseRelease(obj: JSONObject, releaseRepository: String): UpdateRelease? {
    if (obj.keysSet() != RELEASE_KEYS) return null
    val versionCode = obj.positiveLong("versionCode") ?: return null
    val versionName = obj.getString("versionName")
    if (!printable(versionName, UpdateConfig.MAX_VERSION_NAME_CHARS, allowNewlines = false)) return null
    val minSdk = (obj.opt("minSdk") as? Int)?.takeIf { it > 0 } ?: return null
    val targetSdk = (obj.opt("targetSdk") as? Int)?.takeIf { it >= minSdk } ?: return null
    val apkUrl = obj.getString("apkUrl")
    if (!validateApkUrl(apkUrl, versionCode, releaseRepository)) return null
    val apkSizeBytes = obj.positiveLong("apkSizeBytes") ?: return null
    if (apkSizeBytes > UpdateConfig.MAX_APK_BYTES) return null
    val apkSha256Hex = obj.getString("apkSha256Hex")
    if (!SHA256_HEX.matches(apkSha256Hex)) return null
    val releaseNotes = obj.getString("releaseNotes")
    if (!printable(releaseNotes, UpdateConfig.MAX_RELEASE_NOTES_CHARS, allowNewlines = true)) return null
    return UpdateRelease(
        versionCode = versionCode,
        versionName = versionName,
        minSdk = minSdk,
        targetSdk = targetSdk,
        apkUrl = apkUrl,
        apkSizeBytes = apkSizeBytes,
        apkSha256Hex = apkSha256Hex,
        releaseNotes = releaseNotes,
    )
}

/**
 * Plan §8.1: HTTPS on github.com, the configured releases-only repository, and a concrete
 * version-pinned artifact tag. No credentials, query, fragment, port or traversal.
 */
internal fun validateApkUrl(raw: String, versionCode: Long, releaseRepository: String): Boolean {
    if (releaseRepository.isBlank()) return false
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (!uri.rawAuthority.equals("github.com", ignoreCase = true)) return false
    if (uri.userInfo != null || uri.query != null || uri.fragment != null) return false
    val path = uri.rawPath ?: return false
    val prefix = "/$releaseRepository/releases/download/artifact-v$versionCode/"
    if (!path.startsWith(prefix)) return false
    val name = path.removePrefix(prefix)
    if ('/' in name || '\\' in name || name == "." || name == "..") return false
    return SAFE_APK_NAME.matches(name)
}

/** Stable 0..9999 bucket from the local cohort ID and the signed per-release salt. */
internal fun cohortBucket(cohortId: ByteArray, cohortSalt: String): Int {
    val digest = MessageDigest.getInstance("SHA-256").run {
        update(cohortId)
        update(cohortSalt.toByteArray(Charsets.UTF_8))
        digest()
    }
    return BigInteger(1, digest.copyOf(8)).mod(BigInteger.valueOf(10_000)).toInt()
}

internal fun isInRollout(cohortId: ByteArray, policy: UpdatePolicy): Boolean =
    cohortBucket(cohortId, policy.cohortSalt) < policy.rolloutPercent * 100

private fun JSONObject.keysSet(): Set<String> = keys().asSequence().toSet()

private fun JSONObject.positiveLong(key: String): Long? {
    val value = when (val raw = opt(key)) {
        is Int -> raw.toLong()
        is Long -> raw
        else -> return null
    }
    return value.takeIf { it > 0 }
}

private fun printable(value: String, max: Int, allowNewlines: Boolean): Boolean =
    value.length <= max && value.none {
        it.isISOControl() && !(allowNewlines && (it == '\n' || it == '\r' || it == '\t'))
    }
