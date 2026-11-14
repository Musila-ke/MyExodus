package com.example.myexodus.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.account.GuestSnapshot
import com.example.myexodus.account.SessionFence
import com.example.myexodus.account.SessionFingerprint
import com.example.myexodus.backup.BackupKeyManager.Unlock
import com.example.myexodus.data.db.entity.CoachingStep
import java.io.ByteArrayOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR #310: the secret-free daily pass. Runs the real manager (real Argon2id on setup, fake
 * network and upload seams) and proves the decision table: signed-out, unconfigured, waiting
 * for a device key, unchanged, committed, and the retryable/non-retryable failure answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutomaticBackupTest {

    private lateinit var api: FakeKeyApi
    private lateinit var keys: BackupKeyManager
    private lateinit var uploads: FakeUploads
    private lateinit var state: FakeAutoState
    private lateinit var deviceKey: FakeDeviceKey

    private val uid = "uid-fixture"
    private val passphrase = "7Gx!q2-Lm9@Rv4#Tz8"
    private var fenceValue: SessionFingerprint? = SessionFingerprint(uid, 1)
    private var snapshot: GuestSnapshot = snapshotWithCoaching()

    @Before
    fun setUp() = runBlocking {
        api = FakeKeyApi()
        keys = BackupKeyManager(api, FakeRevisions(), BackupWordlists(context()))
        keys.create(uid, passphrase)
        uploads = FakeUploads()
        state = FakeAutoState()
        deviceKey = FakeDeviceKey().apply { save(uid, ByteArray(16) { 1 }, ByteArray(32) { 2 }) }
    }

    private fun automatic() = AutomaticBackup(
        keys = keys,
        uploads = uploads,
        fence = SessionFence { fenceValue },
        state = state,
        deviceKey = deviceKey,
        snapshot = { snapshot },
    )

    @Test
    fun signedOut_doesNothing() = runBlocking {
        fenceValue = null
        assertEquals(AutomaticBackup.Outcome.NotSignedIn, automatic().run())
        assertEquals(0, uploads.calls)
    }

    @Test
    fun noBundle_isNotConfigured() = runBlocking {
        api.stored = null
        assertEquals(AutomaticBackup.Outcome.NotConfigured, automatic().run())
        assertEquals(0, uploads.calls)
    }

    @Test
    fun noAdoptedDeviceKey_waitsForAManualUnlock() = runBlocking {
        deviceKey.clear()
        assertEquals(AutomaticBackup.Outcome.DeviceKeyMissing, automatic().run())
        assertEquals(0, uploads.calls)
    }

    @Test
    fun sameContentDigest_skipsTheUpload() = runBlocking {
        state.record = BackupAutoRecord(digestOf(snapshot), 123L)
        assertEquals(AutomaticBackup.Outcome.Unchanged, automatic().run())
        assertEquals(0, uploads.calls)
    }

    @Test
    fun emptySnapshot_hasNothingToBackUp() = runBlocking {
        snapshot = GuestSnapshot(
            habits = emptyList(), streaks = emptyList(), milestones = emptyList(),
            attempts = emptyList(), sessions = emptyList(), coaching = emptyList(),
            riskWindows = emptyList(), policy = null,
        )
        assertEquals(AutomaticBackup.Outcome.Unchanged, automatic().run())
        assertEquals(0, uploads.calls)
    }

    @Test
    fun changedContent_uploadsWithTheDeviceKeyAndNoReplacement() = runBlocking {
        uploads.result = BackupUploader.Result(7, "server-sha", replayed = false)
        val outcome = automatic().run()
        assertEquals(AutomaticBackup.Outcome.Uploaded(7), outcome)
        assertEquals(1, uploads.calls)
        assertEquals(Unlock.DeviceKey, uploads.unlock)
        assertNull(uploads.replace)
    }

    @Test
    fun wrongSecret_parksUntilTheUserUnlocks() = runBlocking {
        uploads.failure = BackupUploadException(BackupUploadFailure.WRONG_SECRET)
        assertEquals(AutomaticBackup.Outcome.DeviceKeyMissing, automatic().run())
    }

    @Test
    fun unreadableAutoState_isRetryable() = runBlocking {
        state.failRead = true
        assertEquals(AutomaticBackup.Outcome.Failed(retryable = true), automatic().run())
        assertEquals(0, uploads.calls)
    }

    @Test
    fun networkFailure_isRetryable_conflictIsNot() = runBlocking {
        uploads.failure = BackupUploadException(BackupUploadFailure.NETWORK)
        assertEquals(AutomaticBackup.Outcome.Failed(retryable = true), automatic().run())
        uploads.failure = BackupUploadException(BackupUploadFailure.BACKUP_CONFLICT)
        assertEquals(AutomaticBackup.Outcome.Failed(retryable = false), automatic().run())
    }

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private fun digestOf(value: GuestSnapshot): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BackupPayload.write(value, DigestOutputStream(ByteArrayOutputStream(), digest))
        return digest.digest().toLowerHex()
    }

    private fun snapshotWithCoaching() = GuestSnapshot(
        habits = emptyList(),
        streaks = emptyList(),
        milestones = emptyList(),
        attempts = emptyList(),
        sessions = emptyList(),
        coaching = listOf(
            CoachingStep(
                stepId = "step-1",
                profileId = "profile-1",
                stepOrder = 1,
                actionText = "Breathe for one minute",
                associatedTriggerWindow = "",
            ),
        ),
        riskWindows = emptyList(),
        policy = null,
    )

    private class FakeKeyApi : BackupKeyApi {
        var stored: RemoteBundle? = null
        override suspend fun read(uid: String): RemoteBundle? = stored
        override suspend fun initialize(bundle: BackupKeyBundle): RemoteBundle =
            RemoteBundle(1, bundle).also { stored = it }
        override suspend fun update(expectedRevision: Long, bundle: BackupKeyBundle): RemoteBundle =
            error("unused")
    }

    private class FakeRevisions : BackupRevisionStore {
        private val values = mutableMapOf<String, Long>()
        override suspend fun highest(uid: String): Long? = values[uid]
        override suspend fun record(uid: String, revision: Long) {
            values[uid] = maxOf(values[uid] ?: 0L, revision)
        }
    }

    private class FakeUploads : BackupUploads {
        var calls = 0
        var unlock: Unlock? = null
        var replace: BackupPair? = null
        var result = BackupUploader.Result(1, "sha", replayed = false)
        var failure: BackupUploadException? = null

        override suspend fun currentPointer(): BackupPair? = null

        override suspend fun backup(
            remote: RemoteBundle,
            unlock: Unlock,
            replace: BackupPair?,
            onProgress: (BackupProgress) -> Unit,
        ): BackupUploader.Result {
            calls++
            this.unlock = unlock
            this.replace = replace
            failure?.let { throw it }
            return result
        }
    }

    private class FakeAutoState : BackupAutoStateStore {
        var record = BackupAutoRecord(null, null)
        var failRead = false

        override suspend fun read(uid: String) =
            if (failRead) error("state read failed") else record

        override suspend fun recordSuccess(uid: String, contentSha256: String?, atEpochMillis: Long) {
            record = BackupAutoRecord(contentSha256, atEpochMillis)
        }
    }

    private class FakeDeviceKey : BackupAbk {
        private var uid: String? = null
        private var keyId: ByteArray? = null
        private var abk: ByteArray? = null

        fun clear() {
            uid = null
            keyId = null
            abk = null
        }

        override fun save(uid: String, keyId: ByteArray, abk: ByteArray) {
            this.uid = uid
            this.keyId = keyId.copyOf()
            this.abk = abk.copyOf()
        }

        override fun load(uid: String, keyId: ByteArray): ByteArray? =
            if (has(uid) && this.keyId!!.contentEquals(keyId)) abk!!.copyOf() else null

        override fun has(uid: String): Boolean = abk != null && this.uid == uid
    }
}
