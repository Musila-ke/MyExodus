package com.example.myexodus.applock

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.applock.crypto.VaultSecretBundle
import com.example.myexodus.data.db.VaultLockedException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultDekSourceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var source: VaultDekSource

    @Before
    fun setUp() {
        prefs().edit().clear().commit()
        source = VaultDekSource(AppLockStatusStore(context))
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        context.getSharedPreferences(AppLockStatusStore.PREFS_NAME, Context.MODE_PRIVATE)

    private fun enableFeature() {
        prefs().edit()
            .putString(AppLockStatusStore.KEY_FEATURE_STATUS, "enabled")
            .putString(AppLockStatusStore.KEY_MIGRATION_JOURNAL, "active_pending_first_unlock")
            .commit()
    }

    @Test
    fun `feature off falls back to the legacy key path`() {
        assertNull(source.dek(VaultDatabase.RULES))
        assertNull(source.dek(VaultDatabase.ACTIVITY))
        assertNull(source.copyBundle())
    }

    @Test
    fun `enabled but locked throws instead of minting a legacy key`() {
        enableFeature()
        assertThrows(VaultLockedException::class.java) { source.dek(VaultDatabase.RULES) }
        assertThrows(VaultLockedException::class.java) { source.dek(VaultDatabase.ACTIVITY) }
    }

    @Test
    fun `each dek call returns a fresh copy`() {
        enableFeature()
        val bundle = VaultSecretBundle(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        source.adopt(bundle)

        val rules = source.dek(VaultDatabase.RULES)!!
        val activity = source.dek(VaultDatabase.ACTIVITY)!!
        assertArrayEquals(ByteArray(32) { 1 }, rules)
        assertArrayEquals(ByteArray(32) { 2 }, activity)
        assertNotSame(rules, source.dek(VaultDatabase.RULES))
    }

    @Test
    fun `clear zeroes the session and relocks`() {
        enableFeature()
        val rules = ByteArray(32) { 1 }
        val activity = ByteArray(32) { 2 }
        source.adopt(VaultSecretBundle(rules, activity))

        source.clear()

        // adopt copies defensively: the caller's arrays must not be the zeroed session buffers.
        assertArrayEquals(ByteArray(32) { 1 }, rules)
        assertArrayEquals(ByteArray(32) { 2 }, activity)
        assertThrows(VaultLockedException::class.java) { source.dek(VaultDatabase.RULES) }
        assertNull(source.copyBundle())
    }
}
