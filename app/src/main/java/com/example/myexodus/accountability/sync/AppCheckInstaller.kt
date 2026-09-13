package com.example.myexodus.accountability.sync

import android.content.Context
import android.os.UserManager
import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * §8.5: installs the Play Integrity App Check provider before any Firestore operation.
 * Idempotent — app startup ([MyExodusApp.onCreate]) and the relay's drain both call [install]
 * freely; bytecode-verified (firebase-appcheck 19.4.1) that installing after Firebase init
 * does not throw. Console-side enforcement (App Check demand) is a deployment step (§18).
 */
object AppCheckInstaller {

    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        // Pre-unlock (LOCKED_BOOT_COMPLETED, plan §8.5): App Check's StorageHelper reads CE
        // SharedPreferences on its own background thread, which throws pre-unlock and crashes
        // the process. No Firestore op can run pre-unlock anyway, so skip WITHOUT setting
        // installed — a post-unlock call in the same process still installs.
        val userManager = context.getSystemService(UserManager::class.java)
        if (userManager != null && !userManager.isUserUnlocked) {
            Log.i(TAG, "App Check install deferred: user not unlocked")
            return
        }
        if (installed.compareAndSet(false, true)) {
            try {
                // javap-verified on 19.4.1: instance method on the default-app FirebaseAppCheck.
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance(),
                )
            } catch (e: IllegalStateException) {
                // No FirebaseApp — e.g. Robolectric unit tests, where the FirebaseInitProvider
                // from the manifest does not run. In production FirebaseInitProvider always
                // initializes before Application.onCreate, so this is a test-env-only skip.
                Log.w(TAG, "App Check install skipped: Firebase not initialized", e)
            }
        }
    }

    private const val TAG = "AppCheckInstaller"
}
