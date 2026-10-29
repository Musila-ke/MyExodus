package com.example.myexodus.account

import android.content.Context
import android.util.Base64
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import org.json.JSONObject

/**
 * Account plan §4.1/§8.3: Credential Manager acquisition of a Google ID token.
 *
 * The registration's Web Application Client ID is passed as the server client ID; the raw
 * one-time nonce from [generateNonce] goes through `GetGoogleIdOption.setNonce`, and the ID
 * token returned by Google must carry the identical `nonce` claim — a missing or different
 * claim is rejected before Firebase sees the token. (Official guide: the request and response
 * nonce must be identical; Firebase never validates the nonce itself, so this is the only
 * place it is enforced.) Cancellation is a normal outcome, never an error and never a trigger
 * for a browser fallback; a missing Play-services provider is provider-unavailable, which the
 * UI answers by offering email/password.
 */
class GoogleCredentialProvider(private val webClientId: String) {

    /** Google is offered only when a Web Client ID was compiled in. */
    val available: Boolean get() = webClientId.isNotBlank()

    suspend fun requestCredential(context: Context): GoogleCredentialOutcome {
        if (!available) return GoogleCredentialOutcome.Unavailable(AuthFailure.PROVIDER_UNAVAILABLE)
        val nonce = generateNonce()
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setNonce(nonce)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(context).getCredential(context, request).credential
        } catch (_: GetCredentialCancellationException) {
            return GoogleCredentialOutcome.Cancelled
        } catch (_: NoCredentialException) {
            return GoogleCredentialOutcome.Unavailable(AuthFailure.PROVIDER_UNAVAILABLE)
        } catch (_: GetCredentialException) {
            return GoogleCredentialOutcome.Unavailable(AuthFailure.PROVIDER_UNAVAILABLE)
        }
        val idToken = try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (_: GoogleIdTokenParsingException) {
            return GoogleCredentialOutcome.Unavailable(AuthFailure.SECURITY_CHECK_FAILED)
        } catch (_: Exception) {
            return GoogleCredentialOutcome.Unavailable(AuthFailure.SECURITY_CHECK_FAILED)
        }
        if (nonceClaimOf(idToken) != nonce) {
            return GoogleCredentialOutcome.Unavailable(AuthFailure.SECURITY_CHECK_FAILED)
        }
        return GoogleCredentialOutcome.Success(idToken)
    }

    companion object {
        private const val NONCE_BYTES = 32

        /** 32 random bytes, base64url, unpadded — the format the official guide uses. */
        internal fun generateNonce(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(NONCE_BYTES)
            random.nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        }

        /** Reads the `nonce` claim from a JWT payload; null for any malformed token. */
        internal fun nonceClaimOf(idToken: String): String? {
            val parts = idToken.split('.')
            if (parts.size != 3) return null
            return try {
                val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
                val json = JSONObject(payload)
                if (json.has("nonce") && !json.isNull("nonce")) json.getString("nonce") else null
            } catch (_: Exception) {
                null
            }
        }
    }
}

sealed interface GoogleCredentialOutcome {
    data class Success(val idToken: String) : GoogleCredentialOutcome

    /** User dismissed the picker: return to the previous screen, no fallback flow. */
    data object Cancelled : GoogleCredentialOutcome

    data class Unavailable(val failure: AuthFailure) : GoogleCredentialOutcome
}
