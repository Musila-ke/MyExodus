package com.example.myexodus.update

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckerTest {

    private val keyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }
    private val publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    /** What the next manifest request answers. Guarded by the handler being the only caller. */
    @Volatile
    private var responseCode = 200

    @Volatile
    private var responseBody = ""

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/${UpdateConfig.MANIFEST_ASSET}") { exchange ->
            val body = responseBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(responseCode, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}/"
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun manifestPayload(versionCode: Long, sha: String = "a".repeat(64)): String = JSONObject()
        .put("schema", 1)
        .put("versionCode", versionCode)
        .put("versionName", "1.2")
        .put("apkAsset", "myexodus-1.2.apk")
        .put("apkSha256", sha)
        .put("apkSizeBytes", 1024L)
        .put("minSupportedVersionCode", 0L)
        .put("notes", "")
        .toString()

    private fun envelope(payload: String, signedPayload: String = payload): String {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(signedPayload.toByteArray(Charsets.UTF_8))
            sign()
        }
        return JSONObject()
            .put("payload", Base64.getEncoder().encodeToString(payload.toByteArray(Charsets.UTF_8)))
            .put("signature", Base64.getEncoder().encodeToString(signature))
            .toString()
    }

    private fun checker(key: String = publicKey) = UpdateChecker(baseUrl, key)

    @Test
    fun newerSignedManifestIsAvailable() {
        responseBody = envelope(manifestPayload(3))
        val result = checker().check(installedVersionCode = 2)
        val available = result as UpdateCheckResult.Available
        assertEquals(3L, available.manifest.versionCode)
        assertEquals(responseBody, available.envelopeJson)
    }

    @Test
    fun sameOrOlderVersionIsUpToDate() {
        responseBody = envelope(manifestPayload(2))
        assertTrue(checker().check(2) is UpdateCheckResult.UpToDate)
        responseBody = envelope(manifestPayload(1))
        assertTrue(checker().check(2) is UpdateCheckResult.UpToDate)
    }

    @Test
    fun missingReleaseIsNoRelease() {
        responseCode = 404
        responseBody = "Not Found"
        val result = checker().check(2) as UpdateCheckResult.Failure
        assertEquals(UpdateFailure.NO_RELEASE, result.reason)
    }

    @Test
    fun serverErrorIsServerFailure() {
        responseCode = 500
        responseBody = "boom"
        val result = checker().check(2) as UpdateCheckResult.Failure
        assertEquals(UpdateFailure.SERVER, result.reason)
    }

    @Test
    fun unverifiedManifestIsRejected() {
        responseBody = envelope(manifestPayload(4), signedPayload = manifestPayload(3))
        val result = checker().check(2) as UpdateCheckResult.Failure
        assertEquals(UpdateFailure.INVALID_MANIFEST, result.reason)
    }

    @Test
    fun blankKeySkipsTheNetworkEntirely() {
        assertTrue(checker(key = "").check(2) is UpdateCheckResult.NotConfigured)
    }

    @Test
    fun unreachableHostIsOffline() {
        server.stop(0)
        val result = checker().check(2) as UpdateCheckResult.Failure
        assertEquals(UpdateFailure.OFFLINE, result.reason)
    }
}
