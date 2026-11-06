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
            AttemptError.INSTALL_FAILED,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_ABORTED),
        )
        assertEquals(
            AttemptError.INSTALL_BLOCKED,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_BLOCKED),
        )
        assertEquals(
            AttemptError.INSTALL_CONFLICT,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_CONFLICT),
        )
        assertEquals(
            AttemptError.RELEASE_INCOMPATIBLE,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE),
        )
        assertEquals(
            AttemptError.INSTALL_FAILED,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_INVALID),
        )
        assertEquals(
            AttemptError.INSUFFICIENT_STORAGE,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_STORAGE),
        )
        assertEquals(
            AttemptError.INSTALL_TIMEOUT,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE_TIMEOUT),
        )
        assertEquals(
            AttemptError.UPDATE_STATUS_UNKNOWN,
            UpdateInstallReceiver.failureFor(PackageInstaller.STATUS_FAILURE),
        )
    }
}
