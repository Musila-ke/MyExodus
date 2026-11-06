package com.example.myexodus.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan §12: replay/conflict floors, manual-downgrade retention, nullable and independent state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateStateStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = UpdateStateStore(context)

    @Test
    fun lowerPolicySequenceIsRejectedAndFloorsStay() {
        assertEquals(FloorResult.Accepted, store.recordVerifiedPolicy(offer(sequence = 10, version = 101), "hash-10", 100))
        assertEquals(FloorResult.Rollback, store.recordVerifiedPolicy(offer(sequence = 9, version = 105), "hash-9", 100))
        assertEquals(10L, store.highestPolicySequence())
        assertEquals(101L, store.highestVersionCode())
    }

    @Test
    fun equalSequenceWithChangedPayloadConflicts() {
        store.recordVerifiedPolicy(offer(sequence = 10, version = 101), "hash-a", 100)
        assertEquals(
            FloorResult.Conflict,
            store.recordVerifiedPolicy(offer(sequence = 10, version = 101), "hash-b", 100),
        )
        assertEquals(10L, store.highestPolicySequence())
    }

    @Test
    fun replayOfTheSameSequenceAndPayloadIsAccepted() {
        assertEquals(FloorResult.Accepted, store.recordVerifiedPolicy(offer(10, 101), "hash-a", 100))
        assertEquals(FloorResult.Accepted, store.recordVerifiedPolicy(offer(10, 101), "hash-a", 100))
    }

    @Test
    fun versionFloorSurvivesManualDowngradeAndNeverLowers() {
        store.recordVerifiedPolicy(offer(sequence = 10, version = 110), "hash", 100)
        assertEquals(110L, store.highestVersionCode())
        store.ensureVersionFloor(105)
        assertEquals(110L, store.highestVersionCode())
        store.recordVerifiedPolicy(offer(sequence = 11, version = 104), "hash-11", 104)
        assertEquals(110L, store.highestVersionCode())
    }

    @Test
    fun cohortIdIs128BitsAndStableAcrossStoreInstances() {
        val first = store.cohortId()
        assertEquals(16, first.size)
        assertArrayEquals(first, UpdateStateStore(context).cohortId())
    }

    @Test
    fun checkAndAttemptRecordsAreIndependent() {
        val attempt = attempt()
        store.saveAttempt(attempt)
        store.recordCheckSuccess(7_000L, DisplayStatus.AVAILABLE, "{}")
        assertEquals(attempt, store.activeAttempt())
        assertEquals(CheckStatus.SUCCESS, store.checkStatus())
        assertEquals(DisplayStatus.AVAILABLE, store.displayStatus())
        assertEquals(7_000L, store.lastSuccessAtMs())
        store.saveAttempt(attempt.copy(state = AttemptState.FAILED, error = AttemptError.DOWNLOAD_FAILED))
        assertEquals(CheckStatus.SUCCESS, store.checkStatus())
    }

    @Test
    fun attemptRoundTripKeepsSdkValuesAndNullableFields() {
        val full = attempt().copy(
            downloadId = 42L,
            destination = "/tmp/release.apk",
            sessionId = 9,
            meteredConsent = true,
            committed = true,
            error = AttemptError.INSTALL_TIMEOUT,
            userActionIntentUri = "intent://install#Intent;scheme=package;end",
        )
        assertEquals(full, UpdateAttempt.fromJson(full.toJson()))

        val bare = attempt()
        val restored = UpdateAttempt.fromJson(bare.toJson())!!
        assertNull(restored.downloadId)
        assertNull(restored.destination)
        assertNull(restored.sessionId)
        assertNull(restored.error)
        assertNull(restored.userActionIntentUri)
        assertEquals(26, restored.minSdk)
        assertEquals(36, restored.targetSdk)
    }

    @Test
    fun malformedAttemptJsonIsIgnoredRatherThanCrashing() {
        store.clearAttempt()
        assertNull(store.activeAttempt())
        assertNull(UpdateAttempt.fromJson("{"))
    }

    @Test
    fun upgradeLaunchIsReportedOnlyAboveTheLastSeenVersion() {
        assertTrue(!store.recordLaunch(2))
        assertTrue(store.recordLaunch(3))
        assertTrue(!store.recordLaunch(3))
        assertTrue(!store.recordLaunch(2))
    }

    @Test
    fun upgradeLaunchReadsTheLegacyIntEncodingWithoutCrashing() {
        context.getSharedPreferences("update_state", Context.MODE_PRIVATE)
            .edit().putInt("last_seen_version_code", 2).commit()
        assertTrue(store.recordLaunch(3))
        assertTrue(!store.recordLaunch(3))
    }

    @Test
    fun autoDownloadDefaultsOffAndSurvivesANewStoreInstance() {
        assertTrue(!store.autoDownloadEnabled())
        store.setAutoDownloadEnabled(true)
        assertTrue(UpdateStateStore(context).autoDownloadEnabled())
        store.setAutoDownloadEnabled(false)
        assertTrue(!UpdateStateStore(context).autoDownloadEnabled())
    }

    @Test
    fun availabilityNotificationAndPromptAreTrackedPerVersion() {
        assertEquals(0L, store.lastAvailableNotifiedVersionCode())
        assertEquals(0L, store.availablePromptShownVersionCode())
        store.markAvailableNotified(5)
        store.markAvailablePromptShown(7)
        assertEquals(5L, UpdateStateStore(context).lastAvailableNotifiedVersionCode())
        assertEquals(7L, UpdateStateStore(context).availablePromptShownVersionCode())
    }

    private fun offer(sequence: Long, version: Long): UpdatePolicy = UpdatePolicy(
        policySequence = sequence,
        mode = PolicyMode.OFFER,
        rolloutPercent = 100,
        cohortSalt = "0123456789abcdef0123456789abcdef",
        issuedAtEpochSeconds = 1_794_000_000,
        release = UpdateRelease(
            versionCode = version,
            versionName = "1.0.$version",
            minSdk = 26,
            targetSdk = 36,
            apkUrl = "https://github.com/Musila-ke/myexodus-releases/releases/download/artifact-v$version/myexodus-v$version-universal.apk",
            apkSizeBytes = 45_678_901,
            apkSha256Hex = "a".repeat(64),
            releaseNotes = "Notes.",
        ),
    )

    private fun attempt(): UpdateAttempt = UpdateAttempt(
        attemptId = "attempt-1",
        state = AttemptState.READY_TO_INSTALL,
        policySequence = 10,
        versionCode = 101,
        versionName = "1.0.1",
        apkUrl = "https://github.com/Musila-ke/myexodus-releases/releases/download/artifact-v101/app.apk",
        apkSha256Hex = "a".repeat(64),
        apkSizeBytes = 1_234,
        minSdk = 26,
        targetSdk = 36,
        createdAtMs = 1_000,
        updatedAtMs = 2_000,
    )
}
