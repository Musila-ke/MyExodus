package com.example.myexodus.ui.partner

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §19.5 requirement 1: which supported user's dashboard the partner surface shows. UI state,
 * not protocol state — so it lives in preferences, survives process death, and never touches
 * the chain database. Null = "no explicit pick"; the dashboard source then defaults to the
 * most recently activated pairing.
 */
@Singleton
class PartnerSelection @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    // Lazy: constructed with the Hilt graph at process start, which can be pre-unlock
    // (LOCKED_BOOT_COMPLETED) — CE SharedPreferences throw until first unlock.
    private val prefs: android.content.SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var selectedPairId: String?
        get() = prefs.getString(KEY_PAIR_ID, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_PAIR_ID) else putString(KEY_PAIR_ID, value)
        }

    private companion object {
        const val PREFS_NAME = "partner_selection"
        const val KEY_PAIR_ID = "selected_pair_id"
    }
}
