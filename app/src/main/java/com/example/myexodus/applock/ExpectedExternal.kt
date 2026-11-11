package com.example.myexodus.applock

import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * §8.8: mark a trusted, app-initiated external OS activity, then launch it. The coordinator
 * issues a token only from an UNLOCKED session — any other departure still locks as usual.
 */
fun Context.startExpectedExternal(intent: Intent, reason: String) {
    markExpectedExternal(reason)
    startActivity(intent)
}

/** For ActivityResult launchers, where [startActivity] is the caller's. */
fun Context.markExpectedExternal(reason: String) {
    EntryPointAccessors.fromApplication(applicationContext, ExpectedExternalEntryPoint::class.java)
        .appLockCoordinator()
        .beginExpectedExternalOperation(reason)
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ExpectedExternalEntryPoint {
    fun appLockCoordinator(): AppLockCoordinator
}
