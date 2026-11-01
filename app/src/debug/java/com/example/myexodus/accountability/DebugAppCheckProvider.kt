package com.example.myexodus.accountability

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.myexodus.account.LegacyCachePurge
import com.example.myexodus.accountability.sync.AppCheckInstaller
import com.example.myexodus.accountability.sync.EmulatorCompat
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Emulator-only App Check substitution (task #14/#15 two-device rig, 2026-09-19). The emulator
 * cannot produce a Play Integrity token, and Firestore enforcement (task #21) rejects every
 * tokenless write — pairing, mailbox, approvals. This provider runs before
 * `MyExodusApp.onCreate` (providers are created first), so the debug provider is in place
 * before [AppCheckInstaller.install] reads the override.
 *
 * Gated on being an emulator: the X6532 keeps real Play Integrity attestation, and a registered
 * debug secret is not in the repo (console-only, ADR #57). The debug provider's auto-generated
 * token appears in logcat as "Enter this debug secret into the allow list" — register it once
 * per emulator install.
 */
class DebugAppCheckProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        // Runs in every process before Application.onCreate — the seam for the local-suite
        // backend redirect, which must precede all Firebase client use. Host-gated (no-op
        // unless `set-backend` stored one), so the X6532 is untouched by default.
        val ctx = context ?: return true
        DebugBackend.apply(ctx)
        // The Firestore emulator cannot match explicit nulls, so the rig's inbox query drops
        // those filters and the repository re-checks them client-side (see EmulatorCompat).
        EmulatorCompat.matchesExplicitNulls = !DebugBackend.isLocalRig(ctx)
        // The one-time legacy-cache purge terminates the Firestore client mid-bootstrap and
        // the SDK then rebuilds it with production settings; re-wire the emulator on the
        // replacement (host-gated inside, null-safe in every other build).
        LegacyCachePurge.backendReapply = { DebugBackend.reapplyFirestore(it) }
        if (isEmulator()) {
            AppCheckInstaller.factoryOverride = DebugAppCheckProviderFactory.getInstance()
            Log.i(TAG, "App Check: debug provider substituted (emulator; Play Integrity unavailable)")
        }
        return true
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator") ||
            Build.HARDWARE.contains("ranchu") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.PRODUCT.contains("sdk")

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    private companion object {
        const val TAG = "MyExodusAppCheck"
    }
}
