package com.example.myexodus.accountability

import android.content.Context
import android.util.Log
import com.example.myexodus.accountability.crypto.AttestationVerifier
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.security.cert.X509Certificate
import javax.inject.Singleton

/**
 * Local-suite rig (task #39, 2026-09-28): while this debug build dials the local emulator suite
 * ([DebugBackend.isLocalRig]), a PEER may be an emulator whose key is SOFTWARE-attested
 * ("Droid Unregistered Device CA1" root) — the live posture rejects exactly those by design
 * (ADR #96, pinned Google roots), and the two-device ceremony cannot complete without accepting
 * that one case locally. Server half of the same rig: `ATTESTATION_STUB=1` in functions/index.js.
 * With no host pref (live backend) this delegates to the real verifier. Never in release builds.
 */
class LocalRigAttestationVerifier(private val context: Context) : AttestationVerifier() {

    override fun verify(chain: List<X509Certificate>, expectedChallenge: ByteArray): VerificationResult {
        if (chain.isEmpty() || !DebugBackend.isLocalRig(context)) {
            return super.verify(chain, expectedChallenge)
        }
        Log.w(
            TAG,
            "local rig: accepting peer chain the live posture would reject — " +
                "${chain.size} cert(s), leaf subject ${chain[0].subjectX500Principal.name}",
        )
        return VerificationResult.Verified(SecurityLevel.TRUSTED_ENVIRONMENT)
    }

    private companion object {
        const val TAG = "MyExodusAttestation"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LocalRigAttestationModule {
    @Provides
    @Singleton
    fun attestationVerifier(@ApplicationContext context: Context): AttestationVerifier =
        LocalRigAttestationVerifier(context)
}
