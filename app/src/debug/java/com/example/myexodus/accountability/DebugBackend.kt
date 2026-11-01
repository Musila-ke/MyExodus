package com.example.myexodus.accountability

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions

/**
 * Debug-only backend redirect for the two-device local-suite rig (2026-09-28). When a host is
 * stored (adb broadcast `set-backend`), FirebaseAuth/Firestore/Functions dial that host's
 * local emulators (auth 9099 / firestore 8080 / functions 5001) instead of the live project —
 * the only way an emulator can complete the pairing ceremony, because the live backend
 * rejects its SOFTWARE-attested keys by design (ADR #96). Applied at process start from
 * [DebugAppCheckProvider], before any client can be touched; removable with `clear-backend`
 * plus a process restart. Never compiled into release.
 */
object DebugBackend {
    const val PREFS = "debug_backend"
    const val KEY_HOST = "host"
    const val AUTH_PORT = 9099
    const val FIRESTORE_PORT = 8080
    const val FUNCTIONS_PORT = 5001

    /** True while this debug build dials the local suite (the two-device rig). */
    fun isLocalRig(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOST, null)?.isNotEmpty() == true

    fun apply(context: Context) {
        val host = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOST, null)
            ?.takeIf { it.isNotEmpty() }
            ?: return
        runCatching {
            // Idempotent vs FirebaseInitProvider (providers may init in either order); the
            // default app must exist before any useEmulator call.
            FirebaseApp.initializeApp(context)
            FirebaseAuth.getInstance().useEmulator(host, AUTH_PORT)
            FirebaseFirestore.getInstance().useEmulator(host, FIRESTORE_PORT)
            FirebaseFunctions.getInstance().useEmulator(host, FUNCTIONS_PORT)
            Log.i(TAG, "debug backend -> $host (auth $AUTH_PORT, firestore $FIRESTORE_PORT, functions $FUNCTIONS_PORT)")
        }.onFailure { Log.w(TAG, "debug backend wiring failed", it) }
    }

    /**
     * Firestore-only re-wire for [com.example.myexodus.account.LegacyCachePurge]: terminate()
     * destroys the emulator-wired client and getInstance() builds a production one. Auth and
     * Functions instances are never deregistered, so re-calling their useEmulator (which may
     * reject a second call) is deliberately avoided.
     */
    fun reapplyFirestore(context: Context) {
        val host = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOST, null)
            ?.takeIf { it.isNotEmpty() }
            ?: return
        runCatching {
            FirebaseFirestore.getInstance().useEmulator(host, FIRESTORE_PORT)
            Log.i(TAG, "debug backend firestore re-wired -> $host:$FIRESTORE_PORT")
        }.onFailure { Log.w(TAG, "debug backend firestore re-wire failed", it) }
    }

    private const val TAG = "MyExodusDebugBackend"
}
