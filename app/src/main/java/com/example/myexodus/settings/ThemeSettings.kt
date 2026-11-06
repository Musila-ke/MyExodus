package com.example.myexodus.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** How Desert Dawn is picked: follow the phone until the user says otherwise. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Singleton
class ThemeSettings @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    // Lazy for the same reason as WidgetSettings: the Hilt graph can start pre-unlock.
    private val prefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var mode: ThemeMode
        get() = ThemeMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) }
            ?: ThemeMode.SYSTEM
        set(value) {
            prefs.edit { putString(KEY_MODE, value.name) }
        }

    private companion object {
        const val PREFS_NAME = "theme_settings"
        const val KEY_MODE = "mode"
    }
}
