package com.example.myexodus.data.db

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.applock.VaultDatabase
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §6.1 step 8: the first unlock retires the legacy wrap enrollment read — unscoped when
 * enrollment happens before any scope exists, and the migration can add a scope-suffixed
 * variant mid-unlock (device-caught 2026-11-11, both were left behind).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SqlCipherVaultKeysTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hash = "ab".repeat(16)

    @Test
    fun `deleteLegacyMaterial retires the unscoped and scope-suffixed wraps idempotently`() {
        context.getSharedPreferences("local_db_migration", Context.MODE_PRIVATE)
            .edit().putString("scope_uid_hash", hash).commit()
        val names = listOf("myexodus_rules_db_keys", "myexodus_activity_log_keys")
            .flatMap { listOf(it, "${it}_$hash") }
        names.forEach(::seedWrappedDek)
        val keys = SqlCipherVaultKeys(context)

        keys.deleteLegacyMaterial(VaultDatabase.RULES)
        keys.deleteLegacyMaterial(VaultDatabase.ACTIVITY)
        keys.deleteLegacyMaterial(VaultDatabase.RULES)

        names.forEach { name ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertFalse(name, prefs.contains("dek_iv"))
            assertFalse(name, prefs.contains("dek_blob"))
        }
    }

    private fun seedWrappedDek(name: String) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("dek_iv", "iv")
            .putString("dek_blob", "blob")
            .commit()
    }
}
