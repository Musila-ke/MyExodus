package com.example.myexodus.domainusage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Engine settings that must outlive a process restart but are not usage data (S-4 governs
 * `rule_usage_daily` only, which holds no such keys). Separate from the encrypted stores — this
 * holds no secret, and the release build ignores the override it carries (S1.3 resolution in
 * [EngineMode.effectiveMode]).
 */
val Context.domainUsageDataStore: DataStore<Preferences> by preferencesDataStore(name = "domain_usage")

object DomainUsageSettings {

    private val KEY_MODE_OVERRIDE = stringPreferencesKey("domainusage_mode_override")
    private val KEY_LAST_DAY_KEY = stringPreferencesKey("domainusage_last_day_key")

    /** Debug-only in effect; release ignores the value (S1.3). */
    suspend fun modeOverride(context: Context): String? =
        context.domainUsageDataStore.data.first()[KEY_MODE_OVERRIDE]

    /** Debug menu setter (§14). */
    suspend fun setModeOverride(context: Context, value: EngineMode?) {
        context.domainUsageDataStore.edit { prefs ->
            if (value == null) prefs.remove(KEY_MODE_OVERRIDE) else prefs[KEY_MODE_OVERRIDE] = value.name
        }
    }

    /** §4.8 `persistedLastDayKey`; null when never written. */
    suspend fun lastDayKey(context: Context): String? =
        context.domainUsageDataStore.data.first()[KEY_LAST_DAY_KEY]

    suspend fun setLastDayKey(context: Context, dayKey: String) {
        context.domainUsageDataStore.edit { it[KEY_LAST_DAY_KEY] = dayKey }
    }
}
