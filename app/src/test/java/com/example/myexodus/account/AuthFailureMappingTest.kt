package com.example.myexodus.account

import android.util.Base64
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account plan §8.3/§12.1: the SDK error vocabulary maps to the app's generic failures (no
 * enumeration oracle), and the Google nonce contract — a one-time 32-byte base64url value that
 * must come back identical in the ID token's `nonce` claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthFailureMappingTest {

    @Test
    fun collisionClass_mapsToAlreadyRegistered() {
        val e = FirebaseAuthUserCollisionException("ERROR_EMAIL_ALREADY_IN_USE", "collision")
        assertEquals(AuthFailure.CREDENTIAL_ALREADY_REGISTERED, FirebaseAuthOperations.classify(e))
    }

    @Test
    fun weakPassword_mapsToWeakPassword() {
        val e = FirebaseAuthWeakPasswordException("ERROR_WEAK_PASSWORD", "weak", "too short")
        assertEquals(AuthFailure.WEAK_PASSWORD, FirebaseAuthOperations.classify(e))
    }

    @Test
    fun recentLogin_mapsToReauthRequired() {
        val e = FirebaseAuthRecentLoginRequiredException("ERROR_REQUIRES_RECENT_LOGIN", "recent")
        assertEquals(AuthFailure.REQUIRES_RECENT_LOGIN, FirebaseAuthOperations.classify(e))
    }

    @Test
    fun invalidCredentialAndUnknownUser_shareOneGenericFailure() {
        assertEquals(
            AuthFailure.INVALID_CREDENTIALS,
            FirebaseAuthOperations.classify(
                FirebaseAuthInvalidCredentialsException("ERROR_INVALID_CREDENTIAL", "bad"),
            ),
        )
        assertEquals(
            AuthFailure.INVALID_CREDENTIALS,
            FirebaseAuthOperations.classify(
                FirebaseAuthInvalidUserException("ERROR_USER_NOT_FOUND", "missing"),
            ),
        )
    }

    @Test
    fun coarseCodes_mapWithoutLeakingExistence() {
        assertEquals(
            AuthFailure.TOO_MANY_REQUESTS,
            FirebaseAuthOperations.classify(FirebaseAuthException("ERROR_TOO_MANY_REQUESTS", "slow down")),
        )
        assertEquals(
            AuthFailure.PROVIDER_UNAVAILABLE,
            FirebaseAuthOperations.classify(FirebaseAuthException("ERROR_OPERATION_NOT_ALLOWED", "off")),
        )
        assertEquals(
            AuthFailure.NETWORK,
            FirebaseAuthOperations.classify(FirebaseAuthException("ERROR_NETWORK_REQUEST_FAILED", "offline")),
        )
        assertEquals(
            AuthFailure.NETWORK,
            FirebaseAuthOperations.classify(FirebaseNetworkException("offline")),
        )
    }

    @Test
    fun unrecognizedCodes_stayUnknown_neverInvalidCredentials() {
        assertEquals(
            AuthFailure.UNKNOWN,
            FirebaseAuthOperations.classify(FirebaseAuthException("ERROR_SOMETHING_NEW", "?")),
        )
    }

    @Test
    fun nonce_is32RandomBase64UrlBytes() {
        val first = GoogleCredentialProvider.generateNonce()
        val second = GoogleCredentialProvider.generateNonce()

        assertEquals(43, first.length)
        assertTrue("base64url alphabet only", first.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(32, Base64.decode(first, Base64.URL_SAFE).size)
        assertNotEquals("each picker launch gets a fresh nonce", first, second)
    }

    @Test
    fun nonceClaim_roundTripsThroughAFabricatedIdToken() {
        val nonce = GoogleCredentialProvider.generateNonce()
        val payload = JSONObject().put("nonce", nonce).put("aud", "client-id").toString()
        val token = "header.${b64(payload)}.signature"

        assertEquals(nonce, GoogleCredentialProvider.nonceClaimOf(token))
    }

    @Test
    fun nonceClaim_missingMismatchedOrMalformed_isNull() {
        assertNull(GoogleCredentialProvider.nonceClaimOf("header.${b64("""{"aud":"x"}""")}.sig"))
        assertNull(GoogleCredentialProvider.nonceClaimOf("header.${b64("""{"nonce":null}""")}.sig"))
        assertNull(GoogleCredentialProvider.nonceClaimOf("not-a-jwt"))
        assertNull(GoogleCredentialProvider.nonceClaimOf("a.%%%.c"))
    }

    private fun b64(value: String): String = Base64.encodeToString(
        value.toByteArray(StandardCharsets.UTF_8),
        Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING,
    )
}
