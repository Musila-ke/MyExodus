package com.example.myexodus.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The home-screen widget's own setting: discreet vs. detailed copy (V1 Req 36 FR5/FR6). Discreet
 * is the default and discloses no recovery/addiction context.
 *
 * This is the app-level source; [com.example.myexodus.widget.ProtectionSnapshotWriter] mirrors it
 * into the widget's snapshot store so "what causes a widget refresh" stays one auditable list of
 * writers (widget spec §5 Decision 10).
 */
@Singleton
class WidgetSettings @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    // Lazy: constructed with the Hilt graph at process start, which can be pre-unlock
    // (LOCKED_BOOT_COMPLETED) — CE SharedPreferences throw until first unlock.
    private val prefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var discreetMode: Boolean
        get() = prefs.getBoolean(KEY_DISCREET, true)
        set(value) {
            prefs.edit { putBoolean(KEY_DISCREET, value) }
        }

    private companion object {
        const val PREFS_NAME = "widget_settings"
        const val KEY_DISCREET = "discreet_mode"
    }
}
