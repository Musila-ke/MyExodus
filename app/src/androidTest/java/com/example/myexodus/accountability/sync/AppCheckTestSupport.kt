package com.example.myexodus.accountability.sync

import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Task #21 (App Check enforcement is ON): the instrumented suites reach the real Firestore, so
 * a device that cannot attest (emulator, any non-Play-certified GMS) must run under the
 * documented debug-provider path. The debug secret is registered with the project once
 * (`firebase appcheck:debugtokens:create <secret> --project ... --app ...`) and passed at run
 * time:
 *
 *   adb shell am instrument -w -r -e firebaseAppCheckDebugSecret <secret> \
 *     [-e class com.example...Test] com.example.myexodus.test/androidx.test.runner.AndroidJUnitRunner
 *
 * With no secret argument (an attesting device) this is a no-op — the production PlayIntegrity
 * provider installed at app startup stays in effect, which is itself the "valid GMS attestation
 * is accepted under enforcement" evidence. The install is deliberately not reverted: a run
 * either wants the debug provider for every Firestore leg or for none.
 *
 * The secret plumbing (instrumentation arg → provider) comes from the
 * `firebase-appcheck-debug-testing` artifact's registrar; nothing here reads the secret itself.
 */
object AppCheckTestSupport {

    private val debugSecret: String? =
        InstrumentationRegistry.getArguments().getString("firebaseAppCheckDebugSecret")

    /** Call before any Firestore/Functions leg, after the production installer ran. */
    fun useDebugProviderIfRequested(vararg apps: FirebaseApp) {
        if (debugSecret.isNullOrEmpty()) return
        apps.forEach { app ->
            FirebaseAppCheck.getInstance(app)
                .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        }
    }
}
