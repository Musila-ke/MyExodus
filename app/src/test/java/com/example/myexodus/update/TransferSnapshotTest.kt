package com.example.myexodus.update

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferSnapshotTest {
    private val unmetered = NetworkState(connected = true, metered = false, roaming = false)

    @Test
    fun offlineMeteredWithoutConsentOrRoamingPauses() {
        assertEquals(
            DownloadSnapshot.Paused(500),
            transferSnapshot(unmetered.copy(connected = false), meteredConsent = true, 500, 1000),
        )
        assertEquals(
            DownloadSnapshot.Paused(500),
            transferSnapshot(unmetered.copy(metered = true), meteredConsent = false, 500, 1000),
        )
        assertEquals(
            DownloadSnapshot.Paused(500),
            transferSnapshot(unmetered.copy(roaming = true), meteredConsent = true, 500, 1000),
        )
    }

    @Test
    fun allowedNetworksReportFileProgress() {
        assertEquals(DownloadSnapshot.Running(500, 1000), transferSnapshot(unmetered, false, 500, 1000))
        assertEquals(
            DownloadSnapshot.Running(500, 1000),
            transferSnapshot(unmetered.copy(metered = true), meteredConsent = true, 500, 1000),
        )
    }

    @Test
    fun bytesClampToTheSignedSize() {
        assertEquals(DownloadSnapshot.Running(1000, 1000), transferSnapshot(unmetered, false, 4096, 1000))
        assertEquals(DownloadSnapshot.Running(0, 1000), transferSnapshot(unmetered, false, -1, 1000))
    }
}
