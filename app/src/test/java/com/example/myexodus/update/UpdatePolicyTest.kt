package com.example.myexodus.update

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan §12: the cross-tool fixture is a fixed payload signed by OpenSSL
 * (`openssl dgst -sha256 -sign`), verified by JCA `SHA256withECDSA`. Dynamic cases reuse the
 * same fixture private key, which exists only in this test and never signs a release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdatePolicyTest {

    private val repository = "Musila-ke/myexodus-releases"

    @Test
    fun opensslFixtureVerifies() {
        val result = verify(FIXTURE_ENVELOPE)
        assertTrue(result is PolicyVerification.Valid)
        val policy = (result as PolicyVerification.Valid).policy
        assertEquals(100L, policy.policySequence)
        assertEquals(PolicyMode.OFFER, policy.mode)
        assertEquals(25, policy.rolloutPercent)
        assertEquals(100001L, policy.release?.versionCode)
        assertEquals("1.0.1", policy.release?.versionName)
        assertEquals(WithKnownHash, policy.release?.apkSha256Hex)
    }

    @Test
    fun corruptSignatureIsRejected() {
        val envelope = JSONObject(FIXTURE_ENVELOPE)
        val signature = Base64.getDecoder().decode(envelope.getString("signatureBase64"))
        signature[10] = (signature[10].toInt() xor 1).toByte()
        envelope.put("signatureBase64", Base64.getEncoder().encodeToString(signature))
        assertInvalid(envelope.toString(), "signature")
    }

    @Test
    fun wrongPinnedKeyIsRejected() {
        val otherPin = Base64.getEncoder().encodeToString(
            KeyPairGenerator.getInstance("EC").apply { initialize(256) }
                .generateKeyPair().public.encoded,
        )
        assertInvalid(FIXTURE_ENVELOPE, "signature", pin = otherPin)
    }

    @Test
    fun malformedBase64IsRejected() {
        val envelope = JSONObject(FIXTURE_ENVELOPE)
            .put("payloadBase64", "not base64!!")
        assertInvalid(envelope.toString(), "IllegalArgumentException")
    }

    @Test
    fun nonCanonicalBase64IsRejected() {
        val payload = Base64.getDecoder().decode(JSONObject(FIXTURE_ENVELOPE).getString("payloadBase64"))
        val unpadded = Base64.getEncoder().withoutPadding().encodeToString(payload)
        val envelope = JSONObject(FIXTURE_ENVELOPE).put("payloadBase64", unpadded)
        assertInvalid(envelope.toString(), "payload encoding")
    }

    @Test
    fun malformedJsonIsRejected() {
        assertTrue(verify("{") is PolicyVerification.Invalid)
    }

    @Test
    fun oversizedEnvelopeIsRejectedBeforeParsing() {
        assertInvalid(" ".repeat(UpdateConfig.MAX_ENVELOPE_BYTES + 1), "envelope size")
    }

    @Test
    fun wrongEnvelopeKeysAreRejected() {
        val envelope = JSONObject(FIXTURE_ENVELOPE).put("extra", "value")
        assertInvalid(envelope.toString(), "envelope keys")
    }

    @Test
    fun wrongFormatAndKeyIdAreRejected() {
        assertInvalid(envelope(baseOffer(), format = "other-format").toString(), "envelope format")
        assertInvalid(envelope(baseOffer(), keyId = "other-key").toString(), "key id")
    }

    @Test
    fun wrongPackageAndChannelAreRejected() {
        assertInvalid(signed(offer(packageName = "com.example.other")), "packageName")
        assertInvalid(signed(offer(channel = "beta")), "channel")
    }

    @Test
    fun invalidSchemaVersionAndModeAreRejected() {
        assertInvalid(signed(offer(schemaVersion = 2)), "schemaVersion")
        assertInvalid(signed(offer(mode = "ROLLBACK")), "mode")
    }

    @Test
    fun rolloutMustBeAnIntegerInRange() {
        assertInvalid(signed(offer(rolloutPercent = 101)), "rolloutPercent")
        assertInvalid(signed(offer(rolloutPercent = -1)), "rolloutPercent")
    }

    @Test
    fun haltWithReleaseOrNonZeroRolloutIsRejected() {
        assertInvalid(signed(halt(rolloutPercent = 5)), "halt rollout")
        assertInvalid(signed(halt(includeRelease = true)), "payload keys")
    }

    @Test
    fun offerWithoutReleaseIsRejected() {
        assertInvalid(signed(offer(includeRelease = false)), "payload")
    }

    @Test
    fun incorrectDeviceClockDoesNotAffectVerification() {
        assertTrue(verify(signed(offer(issuedAt = 1))) is PolicyVerification.Valid)
        assertTrue(verify(signed(offer(issuedAt = 4_102_444_800L))) is PolicyVerification.Valid)
    }

    @Test
    fun policySequenceAndIssueTimeMustBePositive() {
        assertInvalid(signed(offer(policySequence = 0)), "policySequence")
        assertInvalid(signed(offer(issuedAt = 0)), "issuedAtEpochSeconds")
    }

    @Test
    fun cohortSaltMustBe32LowercaseHex() {
        assertInvalid(signed(offer(cohortSalt = "0123456789ABCDEF0123456789abcdef")), "cohortSalt")
        assertInvalid(signed(offer(cohortSalt = "short")), "cohortSalt")
    }

    @Test
    fun validHaltParsesWithoutRelease() {
        val result = verify(signed(halt()))
        assertTrue(result is PolicyVerification.Valid)
        val policy = (result as PolicyVerification.Valid).policy
        assertEquals(PolicyMode.HALT, policy.mode)
        assertEquals(null, policy.release)
    }

    @Test
    fun invalidApkUrlsAreRejected() {
        val bad = listOf(
            "http://github.com/$repository/releases/download/artifact-v100001/app.apk",
            "https://evil.example/$repository/releases/download/artifact-v100001/app.apk",
            "https://github.com/other/repo/releases/download/artifact-v100001/app.apk",
            "https://github.com/$repository/releases/download/artifact-v100003/app.apk",
            "https://github.com/$repository/releases/latest/download/app.apk",
            "https://github.com/$repository/releases/download/artifact-v100001/app.apk?x=1",
            "https://github.com/$repository/releases/download/artifact-v100001/app.apk#frag",
            "https://user:pass@github.com/$repository/releases/download/artifact-v100001/app.apk",
            "https://github.com:8443/$repository/releases/download/artifact-v100001/app.apk",
            "https://github.com/$repository/releases/download/artifact-v100001/../app.apk",
            "https://github.com/$repository/releases/download/artifact-v100001/app",
            "https://github.com/$repository/releases/download/artifact-v100001/nested/app.apk",
        )
        bad.forEach { url ->
            assertInvalid(signed(offer(apkUrl = url)), "release", url)
        }
    }

    @Test
    fun apkSizeBoundaryIsEnforced() {
        assertTrue(verify(signed(offer(apkSize = UpdateConfig.MAX_APK_BYTES))) is PolicyVerification.Valid)
        assertInvalid(
            signed(offer(apkSize = UpdateConfig.MAX_APK_BYTES + 1)),
            "release",
        )
    }

    @Test
    fun sdkValuesMustBeConsistentAndPositive() {
        assertInvalid(signed(offer(minSdk = 0)), "release")
        assertInvalid(signed(offer(minSdk = 30, targetSdk = 29)), "release")
    }

    @Test
    fun versionNameAndNotesAreBoundedPlainText() {
        assertInvalid(signed(offer(versionName = "v".repeat(UpdateConfig.MAX_VERSION_NAME_CHARS + 1))), "release")
        assertInvalid(
            signed(offer(notes = "x".repeat(UpdateConfig.MAX_RELEASE_NOTES_CHARS + 1))),
            "release",
        )
        assertInvalid(signed(offer(notes = "line\u0000break")), "release")
        assertTrue(
            verify(signed(offer(notes = "line one\nline two"))) is PolicyVerification.Valid,
        )
    }

    @Test
    fun payloadWithExtraOrMissingKeyIsRejected() {
        val extra = baseOffer().trimEnd().removeSuffix("}") +
            ",\"unexpected\":true}"
        assertInvalid(signed(extra), "payload keys")
    }

    @Test
    fun cohortBucketIsDeterministicAndSaltSensitive() {
        val id = ByteArray(16) { (it + 1).toByte() }
        val first = cohortBucket(id, SALT_A)
        assertEquals(first, cohortBucket(id, SALT_A))
        assertNotEquals(first, cohortBucket(id, SALT_B))
        assertEquals(expectedBucket(id, SALT_A), first)
    }

    @Test
    fun rolloutBoundariesMatchTheBucket() {
        val id = ByteArray(16) { 7 }
        val bucket = cohortBucket(id, SALT_A)
        assertFalse(isInRollout(id, policyAt(0, SALT_A)))
        assertTrue(isInRollout(id, policyAt(100, SALT_A)))
        val percent = (bucket / 100) + 1
        assertTrue("bucket $bucket percent $percent", isInRollout(id, policyAt(percent, SALT_A)))
        if (bucket >= 100) {
            assertFalse(isInRollout(id, policyAt(bucket / 100, SALT_A)))
        }
    }

    @Test
    fun fiveAndTwentyFivePercentFollowTheBucket() {
        for (seed in 0 until 64) {
            val id = ByteArray(16) { seed.toByte() }
            listOf(5, 25).forEach { percent ->
                assertEquals(
                    "seed $seed percent $percent",
                    cohortBucket(id, SALT_A) < percent * 100,
                    isInRollout(id, policyAt(percent, SALT_A)),
                )
            }
        }
    }

    @Test
    fun aDifferentSaltCanMoveTheSameDeviceAcrossTheRollout() {
        var moved = false
        for (seed in 0 until 64) {
            val id = ByteArray(16) { seed.toByte() }
            if (isInRollout(id, policyAt(50, SALT_A)) != isInRollout(id, policyAt(50, SALT_B))) {
                moved = true
                break
            }
        }
        assertTrue("same cohort id must be able to land in a different bucket", moved)
    }

    private fun verify(
        json: String,
        pin: String = FIXTURE_PUBLIC_KEY,
    ): PolicyVerification = verifyPolicyEnvelope(
        envelopeJson = json,
        publicKeyBase64 = pin,
        expectedPackageName = "com.example.myexodus",
        releaseRepository = repository,
    )

    private fun assertInvalid(
        json: String,
        detail: String,
        message: String = "",
        pin: String = FIXTURE_PUBLIC_KEY,
    ) {
        val result = verify(json, pin)
        assertTrue("$message expected Invalid($detail) but was $result", result is PolicyVerification.Invalid)
        assertTrue(
            "$message expected detail containing $detail but was ${(result as PolicyVerification.Invalid).detail}",
            result.detail.contains(detail),
        )
    }

    private fun signed(payload: String): String = envelope(payload)

    private fun envelope(
        signedPayload: String,
        format: String = UpdateConfig.MANIFEST_FORMAT,
        keyId: String = UpdateConfig.MANIFEST_KEY_ID,
    ): String {
        val payloadBytes = signedPayload.toByteArray(Charsets.UTF_8)
        val signature = signature(payloadBytes)
        return JSONObject().apply {
            put("format", format)
            put("keyId", keyId)
            put("payloadBase64", Base64.getEncoder().encodeToString(payloadBytes))
            put("signatureBase64", Base64.getEncoder().encodeToString(signature))
        }.toString()
    }

    private fun signature(bytes: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(FIXTURE_PRIVATE_KEY)))
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(bytes)
            sign()
        }
    }

    private fun baseOffer(): String = offer()

    private fun offer(
        schemaVersion: Int = 1,
        packageName: String = "com.example.myexodus",
        channel: String = "stable",
        policySequence: Long = 101,
        rolloutPercent: Int = 100,
        cohortSalt: String = SALT_A,
        issuedAt: Long = 1794000000,
        includeRelease: Boolean = true,
        versionCode: Long = 100002,
        versionName: String = "1.0.2",
        minSdk: Int = 26,
        targetSdk: Int = 36,
        apkUrl: String = "https://github.com/$repository/releases/download/artifact-v100002/myexodus-v100002-universal.apk",
        apkSize: Long = 45678901,
        notes: String = "Stability.",
        mode: String = "OFFER",
    ): String = buildString {
        append("{\"schemaVersion\":").append(schemaVersion)
        append(",\"packageName\":\"").append(packageName).append('"')
        append(",\"channel\":\"").append(channel).append('"')
        append(",\"policySequence\":").append(policySequence)
        append(",\"mode\":\"").append(mode).append('"')
        append(",\"rolloutPercent\":").append(rolloutPercent)
        append(",\"cohortSalt\":\"").append(cohortSalt).append('"')
        append(",\"issuedAtEpochSeconds\":").append(issuedAt)
        if (includeRelease) {
            append(",\"release\":{")
            append("\"versionCode\":").append(versionCode)
            append(",\"versionName\":\"").append(versionName).append('"')
            append(",\"minSdk\":").append(minSdk)
            append(",\"targetSdk\":").append(targetSdk)
            append(",\"apkUrl\":\"").append(apkUrl).append('"')
            append(",\"apkSizeBytes\":").append(apkSize)
            append(",\"apkSha256Hex\":\"").append(WithKnownHash).append('"')
            append(",\"releaseNotes\":\"").append(notes.replace("\n", "\\n")).append('"')
            append('}')
        }
        append('}')
    }

    private fun halt(
        rolloutPercent: Int = 0,
        includeRelease: Boolean = false,
        policySequence: Long = 102,
    ): String = buildString {
        append("{\"schemaVersion\":1")
        append(",\"packageName\":\"com.example.myexodus\"")
        append(",\"channel\":\"stable\"")
        append(",\"policySequence\":").append(policySequence)
        append(",\"mode\":\"HALT\"")
        append(",\"rolloutPercent\":").append(rolloutPercent)
        append(",\"cohortSalt\":\"").append(SALT_A).append('"')
        append(",\"issuedAtEpochSeconds\":1794000000")
        if (includeRelease) {
            append(",\"release\":{\"versionCode\":100002}")
        }
        append('}')
    }

    private fun policyAt(percent: Int, salt: String): UpdatePolicy = UpdatePolicy(
        policySequence = 1,
        mode = PolicyMode.OFFER,
        rolloutPercent = percent,
        cohortSalt = salt,
        issuedAtEpochSeconds = 1,
        release = null,
    )

    private fun expectedBucket(id: ByteArray, salt: String): Int {
        val digest = java.security.MessageDigest.getInstance("SHA-256").run {
            update(id)
            update(salt.toByteArray(Charsets.UTF_8))
            digest()
        }
        return BigInteger(1, digest.copyOf(8)).mod(BigInteger.valueOf(10_000)).toInt()
    }

    private companion object {
        const val WithKnownHash =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SALT_A = "0123456789abcdef0123456789abcdef"
        const val SALT_B = "fedcba9876543210fedcba9876543210"

        // Test-only identity generated with OpenSSL; never used to sign a release.
        const val FIXTURE_PUBLIC_KEY =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEDl70+UkmSfn3JKZ5fKObgBfa2t4WUh9V9gNcCP90dcyNSgMDD7dF6MgR2fm0294Yo/2UlxcBqy1GhnV8QJxAYg=="
        const val FIXTURE_PRIVATE_KEY =
            "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg11jBW/a8MEn40ZWWEfCYwCoPsmavKIobq/Hi0xdNkrGhRANCAAQOXvT5SSZJ+fckpnl8o5uAF9ra3hZSH1X2A1wI/3R1zI1KAwMPt0XoyBHZ+bTb3hij/ZSXFwGrLUaGdXxAnEBi"
        const val FIXTURE_PAYLOAD_BASE64 =
            "eyJzY2hlbWFWZXJzaW9uIjoxLCJwYWNrYWdlTmFtZSI6ImNvbS5leGFtcGxlLm15ZXhvZHVzIiwiY2hhbm5lbCI6InN0YWJsZSIsInBvbGljeVNlcXVlbmNlIjoxMDAsIm1vZGUiOiJPRkZFUiIsInJvbGxvdXRQZXJjZW50IjoyNSwiY29ob3J0U2FsdCI6IjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg5YWJjZGVmIiwiaXNzdWVkQXRFcG9jaFNlY29uZHMiOjE3OTQwMDAwMDAsInJlbGVhc2UiOnsidmVyc2lvbkNvZGUiOjEwMDAwMSwidmVyc2lvbk5hbWUiOiIxLjAuMSIsIm1pblNkayI6MjYsInRhcmdldFNkayI6MzYsImFwa1VybCI6Imh0dHBzOi8vZ2l0aHViLmNvbS9NdXNpbGEta2UvbXlleG9kdXMtcmVsZWFzZXMvcmVsZWFzZXMvZG93bmxvYWQvYXJ0aWZhY3QtdjEwMDAwMS9teWV4b2R1cy12MTAwMDAxLXVuaXZlcnNhbC5hcGsiLCJhcGtTaXplQnl0ZXMiOjQ1Njc4OTAxLCJhcGtTaGEyNTZIZXgiOiJhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhIiwicmVsZWFzZU5vdGVzIjoiUmVsaWFiaWxpdHkgYW5kIHNlY3VyaXR5IGltcHJvdmVtZW50cy4ifX0="

        const val FIXTURE_SIGNATURE_BASE64 =
            "MEUCIQDk6Wa7XY0AMS7TgnWehWc2IrOdL0FjNwQTMykJri3UnAIgcTT2XaoiDu5Xr5vYZUMbBRTWIzq5r9RQHnXIiMOBEHA="

        val FIXTURE_ENVELOPE: String = JSONObject().apply {
            put("format", "myexodus-update-envelope-v1")
            put("keyId", "primary-v1")
            put("payloadBase64", FIXTURE_PAYLOAD_BASE64)
            put("signatureBase64", FIXTURE_SIGNATURE_BASE64)
        }.toString()
    }
}
