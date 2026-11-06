package com.example.myexodus.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateStateStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = UpdateStateStore(context)

    private val manifest = UpdateManifest(
        versionCode = 3L,
        versionName = "1.2",
        apkAssetName = "myexodus-1.2.apk",
        apkSha256 = "a".repeat(64),
        apkSizeBytes = 1024L,
        minSupportedVersionCode = 0L,
        notes = "",
    )
    private val available = UpdateCheckResult.Available(manifest, "{\"payload\":\"x\"}")

    @Test
    fun newStoreHasNoState() {
        assertEquals(0L, store.lastCheckAtMs())
        assertNull(store.availableEnvelope())
        assertNull(store.lastFailure())
        assertNull(store.installStatus())
        assertEquals(0L, store.installStartedAtMs())
    }

    @Test
    fun firstLaunchIsNotAnUpgrade() {
        assertFalse(store.recordLaunch(2))
    }

    @Test
    fun aNewerVersionCodeIsAnUpgradeExactlyOnce() {
        store.recordLaunch(2)
        assertTrue(store.recordLaunch(3))
        assertFalse(store.recordLaunch(3))
        assertFalse(store.recordLaunch(2))
    }

    @Test
    fun availableCheckStoresEnvelopeAndClearsFailure() {
        store.recordCheck(UpdateCheckResult.Failure(UpdateFailure.OFFLINE), 10L)
        store.recordCheck(available, 20L)
        assertEquals("{\"payload\":\"x\"}", store.availableEnvelope())
        assertNull(store.lastFailure())
        assertEquals(20L, store.lastCheckAtMs())
    }

    @Test
    fun upToDateClearsEverythingButTheTimestamp() {
        store.recordCheck(available, 10L)
        store.recordCheck(UpdateCheckResult.UpToDate, 20L)
        assertNull(store.availableEnvelope())
        assertNull(store.lastFailure())
        assertEquals(20L, store.lastCheckAtMs())
    }

    @Test
    fun failureKeepsTheStoredUpdateForALaterRetry() {
        store.recordCheck(available, 10L)
        store.recordCheck(UpdateCheckResult.Failure(UpdateFailure.OFFLINE), 20L)
        assertEquals("{\"payload\":\"x\"}", store.availableEnvelope())
        assertEquals(UpdateFailure.OFFLINE, store.lastFailure())
    }

    @Test
    fun installStatusRoundTripsAndClears() {
        store.recordInstallStart(100L)
        assertEquals(InstallStatus.PENDING, store.installStatus())
        assertEquals(100L, store.installStartedAtMs())
        store.recordInstallStatus(InstallStatus.SUCCEEDED)
        assertEquals(InstallStatus.SUCCEEDED, store.installStatus())
        store.clearInstallStatus()
        assertNull(store.installStatus())
        assertEquals(0L, store.installStartedAtMs())
    }
}
