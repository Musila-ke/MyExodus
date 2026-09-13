package com.example.myexodus.data.db

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * UUID generated once on first launch, stored in its own private SharedPreferences file
 * (separate from the wrapped DEK) so DomainEvent sync idempotency survives a database reset.
 */
class InstallationIdProvider(context: Context) {

    private val prefs =
        context.getSharedPreferences("myexodus_installation", Context.MODE_PRIVATE)

    fun get(): String {
        prefs.getString(KEY, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY, it) }
        }
    }

    private companion object {
        const val KEY = "installation_id"
    }
}
