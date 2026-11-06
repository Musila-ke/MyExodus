package com.example.myexodus.update

import org.json.JSONObject
import java.security.Signature
import java.util.Base64

internal data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val apkAssetName: String,
    val apkSha256: String,
    val apkSizeBytes: Long,
    val minSupportedVersionCode: Long,
    val notes: String,
)

internal sealed interface ManifestVerification {
    data class Valid(val manifest: UpdateManifest) : ManifestVerification
    data object NotConfigured : ManifestVerification
    data class Invalid(val detail: String) : ManifestVerification
}

private const val ENVELOPE_MAX_BYTES = 64 * 1024
private const val SIGNATURE_MAX_BYTES = 512
private val ASSET_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.apk")
private val SHA256_HEX = Regex("[0-9a-f]{64}")

/**
 * Verifies the detached-style envelope: payload is base64 JSON bytes, signature is base64 DER
 * ECDSA over those exact bytes. Anything unverified is treated as absent, never as trusted.
 */
internal fun verifyManifest(envelopeJson: String, publicKeyBase64: String): ManifestVerification {
    if (publicKeyBase64.isBlank()) return ManifestVerification.NotConfigured
    if (envelopeJson.toByteArray(Charsets.UTF_8).size > ENVELOPE_MAX_BYTES) {
        return ManifestVerification.Invalid("envelope size")
    }
    return try {
        val envelope = JSONObject(envelopeJson)
        val payload = Base64.getDecoder().decode(envelope.getString("payload"))
        val signature = Base64.getDecoder().decode(envelope.getString("signature"))
        if (payload.size > ENVELOPE_MAX_BYTES || signature.size > SIGNATURE_MAX_BYTES) {
            return ManifestVerification.Invalid("envelope field size")
        }
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(decodePublicKey(publicKeyBase64))
        verifier.update(payload)
        if (!verifier.verify(signature)) return ManifestVerification.Invalid("signature")

        val body = JSONObject(String(payload, Charsets.UTF_8))
        if (body.getInt("schema") != 1) return ManifestVerification.Invalid("schema")
        val versionCode = body.getLong("versionCode")
        val versionName = body.getString("versionName")
        val asset = body.getString("apkAsset")
        val sha = body.getString("apkSha256").lowercase()
        val size = body.getLong("apkSizeBytes")
        val minSupported = body.optLong("minSupportedVersionCode", 0L)
        val notes = body.optString("notes", "")
        when {
            versionCode <= 0 -> ManifestVerification.Invalid("versionCode")
            versionName.isBlank() || versionName.length > 32 || versionName.any { it.isISOControl() } ->
                ManifestVerification.Invalid("versionName")
            !ASSET_NAME.matches(asset) -> ManifestVerification.Invalid("apkAsset")
            !SHA256_HEX.matches(sha) -> ManifestVerification.Invalid("apkSha256")
            size <= 0 || size > UpdateConfig.MAX_APK_BYTES -> ManifestVerification.Invalid("apkSizeBytes")
            minSupported < 0 || minSupported > versionCode ->
                ManifestVerification.Invalid("minSupportedVersionCode")
            notes.length > 4000 ||
                notes.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } ->
                ManifestVerification.Invalid("notes")
            else -> ManifestVerification.Valid(
                UpdateManifest(versionCode, versionName, asset, sha, size, minSupported, notes),
            )
        }
    } catch (e: Exception) {
        ManifestVerification.Invalid(e.javaClass.simpleName)
    }
}
