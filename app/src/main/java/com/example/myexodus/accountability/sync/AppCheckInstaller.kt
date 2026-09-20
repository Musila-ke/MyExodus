package com.example.myexodus.accountability.sync

import android.content.Context
import android.os.UserManager
import android.util.Log
import com.google.firebase.appcheck.AppCheckProviderFactory
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

    /**
     * Substitution point for builds that cannot attest (debug source set only — the emulator
     * has no Play Integrity, and Firestore enforcement would reject every write). Set by the
     * debug manifest's provider, which runs before `MyExodusApp.onCreate` — providers are
     * created before the Application. Always null in release: the classes that assign it are
     * not on the release classpath.
     */
    @Volatile
    var factoryOverride: AppCheckProviderFactory? = null

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
        if (installed.get()) return
        try {
            // javap-verified on 19.4.1: instance method on the default-app FirebaseAppCheck.
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                factoryOverride ?: PlayIntegrityAppCheckProviderFactory.getInstance(),
            )
            // Latched only on success: a failed install (no FirebaseApp — e.g. Robolectric unit
            // tests, where the manifest's FirebaseInitProvider never runs) must stay retryable,
            // so a later call in the same process can still install.
            installed.set(true)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "App Check not installed; will retry on next call", e)
        }
    }

    private const val TAG = "AppCheckInstaller"
}
