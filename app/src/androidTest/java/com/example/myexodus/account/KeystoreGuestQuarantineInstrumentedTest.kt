package com.example.myexodus.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.data.db.assumeEmulatorOnly
import com.example.myexodus.data.db.onSanctionedEmulator
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Keystore + real file round trip for the encrypted guest quarantine (§6.2.3/§8.4).
 * Emulator-only: the production directory is the app's own `files/guest_quarantine`, which the
 * instrumentation process shares with the handset's live install (DeviceSafety.kt).
 */
@RunWith(AndroidJUnit4::class)
class KeystoreGuestQuarantineInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val quarantine = KeystoreGuestQuarantine(context)

    @Before
    fun setUp() = runBlocking {
        assumeEmulatorOnly()
        quarantine.deleteAll()
    }

    @After
    fun tearDown() = runBlocking {
        if (onSanctionedEmulator()) quarantine.deleteAll()
    }

    @Test
    fun aSavedRecordRoundTripsExactlyAndIsBoundToItsTarget() = runTest {
        val record = record()

        assertTrue(quarantine.save(record))

        val scan = quarantine.forTarget(TARGET)
        assertEquals(listOf(record), scan.records)
        assertEquals(0, scan.unreadable)
        assertEquals(QuarantineLoad.Present(record), quarantine.load(GUEST))
    }

    @Test
    fun aRecordForAnotherTargetIsNotOfferedAnywhereElse() = runTest {
        assertTrue(quarantine.save(record(targetUid = "someone-else")))

        assertEquals(emptyList<GuestQuarantineRecord>(), quarantine.forTarget(TARGET).records)
    }

    @Test
    fun sweepExpiredRemovesOnlyCopiesPastTheSevenDayWindow() = runTest {
        val now = 1_000_000_000L
        val stale = record(
            guestUid = "guest-old",
            transitionId = "t-old",
            stagedAtMs = now - QUARANTINE_RETENTION_MS - 1,
        )
        val fresh = record(guestUid = "guest-new", transitionId = "t-new", stagedAtMs = now)
        assertTrue(quarantine.save(stale))
        assertTrue(quarantine.save(fresh))

        assertEquals(1, quarantine.sweepExpired(now))

        assertEquals(listOf(fresh), quarantine.forTarget(TARGET).records)
    }

    @Test
    fun anUnreadableCopyIsReportedInsteadOfSilentlyLookingEmpty() = runTest {
        val dir = File(context.filesDir, "guest_quarantine").apply { mkdirs() }
        File(dir, "junk-0000000000000000.bin").writeText("not a sealed record")

        val scan = quarantine.forTarget(TARGET)

        assertEquals(emptyList<GuestQuarantineRecord>(), scan.records)
        assertEquals(1, scan.unreadable)
    }

    private fun record(
        guestUid: String = GUEST,
        transitionId: String = "t-1",
        stagedAtMs: Long = 1_000L,
        targetUid: String = TARGET,
    ) = GuestQuarantineRecord(
        transitionId = transitionId,
        guestUid = guestUid,
        importOperationId = "op-$transitionId",
        intent = TransitionIntent.IMPORT,
        stagedAtMs = stagedAtMs,
        targetUid = targetUid,
        snapshot = GuestSnapshot(
            habits = emptyList(),
            streaks = emptyList(),
            milestones = emptyList(),
            attempts = emptyList(),
            sessions = emptyList(),
            coaching = emptyList(),
            riskWindows = emptyList(),
            policy = null,
        ),
    )

    private companion object {
        const val GUEST = "guest-uid"
        const val TARGET = "target-uid"
    }
}
