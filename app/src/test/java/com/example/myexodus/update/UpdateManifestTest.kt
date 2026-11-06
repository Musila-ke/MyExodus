package com.example.myexodus.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateManifestTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)

    private fun payload(
        versionCode: Long = 3L,
        versionName: String = "1.2",
        asset: String = "myexodus-1.2.apk",
        sha: String = "a".repeat(64),
        size: Long = 1024L,
        minSupported: Long = 0L,
        notes: String = "Notes",
    ): String = JSONObject()
        .put("schema", 1)
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("apkAsset", asset)
        .put("apkSha256", sha)
        .put("apkSizeBytes", size)
        .put("minSupportedVersionCode", minSupported)
        .put("notes", notes)
        .toString()

    private fun envelope(
        payload: String,
        signedPayload: String = payload,
        signingKey: PrivateKey = keyPair.private,
    ): String {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(signingKey)
            update(signedPayload.toByteArray(Charsets.UTF_8))
            sign()
        }
        return JSONObject()
            .put("payload", Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8)))
            .put("signature", Base64.getEncoder().encodeToString(signature))
            .toString()
    }

    private fun verify(envelopeJson: String, key: String = publicKey) =
        verifyManifest(envelopeJson, key)

    private fun assertInvalid(envelopeJson: String) {
        val result = verify(envelopeJson)
        assertTrue("expected Invalid, was $result", result is ManifestVerification.Invalid)
    }

    @Test
    fun validEnvelopeParsesEveryField() {
        val result = verify(envelope(payload(minSupported = 1L, notes = "Fixed a bug")))
        val valid = result as ManifestVerification.Valid
        assertEquals(3L, valid.manifest.versionCode)
        assertEquals("1.2", valid.manifest.versionName)
        assertEquals("myexodus-1.2.apk", valid.manifest.apkAssetName)
        assertEquals("a".repeat(64), valid.manifest.apkSha256)
        assertEquals(1024L, valid.manifest.apkSizeBytes)
        assertEquals(1L, valid.manifest.minSupportedVersionCode)
        assertEquals("Fixed a bug", valid.manifest.notes)
    }

    @Test
    fun blankKeyIsNotConfigured() {
        assertTrue(verify(envelope(payload()), key = "") is ManifestVerification.NotConfigured)
    }

    @Test
    fun payloadEditedAfterSigningIsRejected() {
        val signed = payload()
        val tampered = signed.replace("\"versionCode\":3", "\"versionCode\":4")
        assertInvalid(envelope(tampered, signedPayload = signed))
    }

    @Test
    fun signatureFromAnotherKeyIsRejected() {
        val other = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        assertInvalid(envelope(payload(), signingKey = other.private))
    }

    @Test
    fun malformedEnvelopeIsRejected() {
        assertInvalid("not json")
        assertInvalid(JSONObject().put("payload", "!!").put("signature", "!!").toString())
    }

    @Test
    fun unsupportedSchemaIsRejected() {
        val body = payload().replace("\"schema\":1", "\"schema\":2")
        assertInvalid(envelope(body))
    }

    @Test
    fun assetNamesThatEscapeTheReleaseDirectoryAreRejected() {
        listOf("../evil.apk", "/data/evil.apk", "dir/evil.apk", "evil (1).apk", "evil.apk/../x.apk")
            .forEach { name -> assertInvalid(envelope(payload(asset = name))) }
    }

    @Test
    fun digestMustBeSixtyFourHexCharacters() {
        assertInvalid(envelope(payload(sha = "abc")))
        assertInvalid(envelope(payload(sha = "z".repeat(64))))
    }

    @Test
    fun digestMayArriveUppercase() {
        val result = verify(envelope(payload(sha = "A".repeat(64))))
        assertEquals("a".repeat(64), (result as ManifestVerification.Valid).manifest.apkSha256)
    }

    @Test
    fun sizeIsBoundedOnBothEnds() {
        assertInvalid(envelope(payload(size = 0)))
        assertInvalid(envelope(payload(size = UpdateConfig.MAX_APK_BYTES + 1)))
    }

    @Test
    fun versionFieldsAreConsistent() {
        assertInvalid(envelope(payload(versionCode = 0)))
        assertInvalid(envelope(payload(versionCode = 3, minSupported = 4)))
        assertInvalid(envelope(payload(versionCode = 3, minSupported = -1)))
    }

    @Test
    fun versionNameIsBoundedAndPrintable() {
        assertInvalid(envelope(payload(versionName = " ")))
        assertInvalid(envelope(payload(versionName = "1".repeat(33))))
        assertInvalid(envelope(payload(versionName = "1.2\u0000")))
    }

    @Test
    fun notesAreBoundedAndPrintable() {
        assertInvalid(envelope(payload(notes = "n".repeat(4001))))
        assertInvalid(envelope(payload(notes = "bad \u0007")))
        val allowed = verify(envelope(payload(notes = "line one\nline two\ttabbed")))
        assertTrue(allowed is ManifestVerification.Valid)
    }
}
