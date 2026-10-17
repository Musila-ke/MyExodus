package com.example.myexodus.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Home's own view preferences. Today one: whether the "Your blocks" list is hidden — owner
 * request 2026-10-17, so the app's most identifying surface (the blocked apps and sites) can be
 * concealed from someone glancing at the phone without leaving Home. Hidden is *not* the default;
 * the toggle in the section header is the only writer.
 */
@Singleton
class HomeSettings @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    // Lazy: constructed with the Hilt graph at process start, which can be pre-unlock
    // (LOCKED_BOOT_COMPLETED) — CE SharedPreferences throw until first unlock.
    private val prefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var blocksHidden: Boolean
        get() = prefs.getBoolean(KEY_BLOCKS_HIDDEN, false)
        set(value) {
            prefs.edit { putBoolean(KEY_BLOCKS_HIDDEN, value) }
        }

    private companion object {
        const val PREFS_NAME = "home_settings"
        const val KEY_BLOCKS_HIDDEN = "blocks_hidden"
    }
}
