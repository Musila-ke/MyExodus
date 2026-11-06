package com.example.myexodus.update

import android.app.PendingIntent
import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallFlowTest {

    @Test
    fun statusPendingIntentIsMutableFromApi31() {
        listOf(31, 34, 36).forEach { sdk ->
            assertTrue(
                "sdk $sdk",
                UpdateInstaller.statusPendingIntentFlags(sdk) and PendingIntent.FLAG_MUTABLE != 0,
            )
        }
    }

    @Test
    fun statusPendingIntentHasNoMutabilityFlagBeforeApi31() {
        listOf(26, 30).forEach { sdk ->
            assertEquals(0, UpdateInstaller.statusPendingIntentFlags(sdk))
        }
    }

    @Test
    fun everyInstallerStatusMapsToOneStoredFailure() {
        assertEquals(
            InstallStatus.FAILED_ABORTED,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_ABORTED),
        )
        assertEquals(
            InstallStatus.FAILED_BLOCKED,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_BLOCKED),
        )
        assertEquals(
            InstallStatus.FAILED_CONFLICT,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_CONFLICT),
        )
        assertEquals(
            InstallStatus.FAILED_INCOMPATIBLE,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE),
        )
        assertEquals(
            InstallStatus.FAILED_INVALID,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_INVALID),
        )
        assertEquals(
            InstallStatus.FAILED_STORAGE,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_STORAGE),
        )
        assertEquals(
            InstallStatus.FAILED_TIMEOUT,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_TIMEOUT),
        )
        assertEquals(
            InstallStatus.FAILED_UNKNOWN,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE),
        )
    }
}
