package com.example.myexodus.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.account.AuthCoordinator
import com.example.myexodus.account.AuthIdentity
import com.example.myexodus.account.AuthOperations
import com.example.myexodus.account.SessionFingerprint
import com.example.myexodus.backup.BackupAbk
import com.example.myexodus.backup.BackupAutoRecord
import com.example.myexodus.backup.BackupAutoStateStore
import com.example.myexodus.backup.BackupCrypto
import com.example.myexodus.backup.BackupKeyApi
import com.example.myexodus.backup.BackupKeyBundle
import com.example.myexodus.backup.BackupKeyManager
import com.example.myexodus.backup.BackupKeyManager.Unlock
import com.example.myexodus.backup.BackupPair
import com.example.myexodus.backup.BackupProgress
import com.example.myexodus.backup.BackupRestoreMark
import com.example.myexodus.backup.BackupRestoreMarkStore
import com.example.myexodus.backup.BackupRestores
import com.example.myexodus.backup.BackupRevisionStore
import com.example.myexodus.backup.BackupUploadException
import com.example.myexodus.backup.BackupUploadFailure
import com.example.myexodus.backup.BackupUploader
import com.example.myexodus.backup.BackupUploads
import com.example.myexodus.backup.BackupWordlists
import com.example.myexodus.backup.RemoteBundle
import com.example.myexodus.backup.RestorePreview
import com.example.myexodus.backup.RestoreProgress
import com.example.myexodus.backup.RestoreSummary
import com.example.myexodus.backup.WrappedKey
import com.example.myexodus.ui.home.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account plan §6.4/§6.5: Cancel is reachable only while the operation is working, and a
 * callable already on the wire cannot be recalled. The pane must therefore re-read the server
 * and show what actually happened — never the setup form over a committed bundle. Runs the
 * real manager (real Argon2id, fake network seam).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupKeyViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val uid = "uid-fixture"
    private val passphrase = "7Gx!q2-Lm9@Rv4#Tz8"

    private class FakeApi : BackupKeyApi {
        var stored: RemoteBundle? = null
        var commitOnInitialize = false
        var gateInitialize = false
        var initializeCalls = 0
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()

        override suspend fun read(uid: String): RemoteBundle? = stored

        override suspend fun initialize(bundle: BackupKeyBundle): RemoteBundle {
            initializeCalls++
            val committed = RemoteBundle(1, bundle)
            if (commitOnInitialize) stored = committed
            entered.complete(Unit)
            if (gateInitialize) gate.await()
            return committed
        }

        override suspend fun update(expectedRevision: Long, bundle: BackupKeyBundle): RemoteBundle =
            throw UnsupportedOperationException("unused")
    }

    private class FakeRevisions : BackupRevisionStore {
        private val revisions = mutableMapOf<String, Long>()
        override suspend fun highest(uid: String): Long? = revisions[uid]
        override suspend fun record(uid: String, revision: Long) {
            revisions[uid] = maxOf(revisions[uid] ?: 0L, revision)
        }
    }

    private class FakeOperations : AuthOperations {
        val identity = AuthIdentity(
            uid = "uid-fixture",
            isAnonymous = false,
            email = "member@example.com",
            emailVerified = true,
            providerIds = setOf("password"),
        )

        override fun currentIdentity(): AuthIdentity = identity
        override fun observeIdentity(onChange: (AuthIdentity?) -> Unit): () -> Unit = {}

        override suspend fun createWithEmail(email: String, password: String) = error("unused")
        override suspend fun signInWithEmail(email: String, password: String) = error("unused")
        override suspend fun linkWithEmail(email: String, password: String) = error("unused")
        override suspend fun signInWithGoogle(idToken: String) = error("unused")
        override suspend fun linkWithGoogle(idToken: String) = error("unused")
        override suspend fun sendVerificationEmail() = error("unused")
        override suspend fun sendPasswordReset(email: String) = error("unused")
        override suspend fun reloadAndRefreshToken() = error("unused")
        override suspend fun reauthenticateWithEmail(email: String, password: String) = error("unused")
        override suspend fun reauthenticateWithGoogle(idToken: String) = error("unused")
        override suspend fun signOut() = error("unused")
    }

    /** §6.4 items 5-14: the pane's view of the uploader, without a key bundle or a network. */
    private class FakeUploads : BackupUploads {
        var pointer: BackupPair? = null
        var onBackup: suspend (BackupPair?) -> BackupUploader.Result = { success(1) }
        val replaces = mutableListOf<BackupPair?>()
        val unlocks = mutableListOf<Unlock>()
        var currentPointerCalls = 0
        var progress: ((BackupProgress) -> Unit)? = null

        override suspend fun currentPointer(): BackupPair? {
            currentPointerCalls++
            return pointer
        }

        override suspend fun backup(
            remote: RemoteBundle,
            unlock: Unlock,
            replace: BackupPair?,
            onProgress: (BackupProgress) -> Unit,
        ): BackupUploader.Result {
            replaces += replace
            unlocks += unlock
            progress = onProgress
            onProgress(BackupProgress.Preparing)
            return onBackup(replace)
        }

        companion object {
            fun success(revision: Long = 1) =
                BackupUploader.Result(revision, "sha-$revision", replayed = false)
        }
    }

    private fun fixtureBundle() = BackupKeyBundle(
        bundleFormatVersion = BackupCrypto.BUNDLE_FORMAT_VERSION,
        keyId = ByteArray(BackupCrypto.KEY_ID_BYTES),
        argon2 = BackupCrypto.ARGON2,
        passphraseSalt = ByteArray(BackupCrypto.SALT_BYTES),
        passphraseWrapper = WrappedKey(
            ByteArray(BackupCrypto.NONCE_BYTES),
            ByteArray(BackupCrypto.WRAP_BYTES),
        ),
        recoveryKeyId = ByteArray(BackupCrypto.KEY_ID_BYTES),
        recoveryHkdfSalt = ByteArray(BackupCrypto.SALT_BYTES),
        recoveryWrapper = WrappedKey(
            ByteArray(BackupCrypto.NONCE_BYTES),
            ByteArray(BackupCrypto.WRAP_BYTES),
        ),
    )

    private class FakeRestores : BackupRestores {
        var applies = 0
        var discards = 0
        var prepares = 0
        var failWith: BackupUploadException? = null
        var preview = RestorePreview(
            fingerprint = SessionFingerprint("uid-fixture", 1),
            pair = BackupPair(4, "sha-4"),
            summary = RestoreSummary(
                habits = 1, streaks = 0, milestones = 0, attempts = 0,
                sessions = 0, coaching = 1, riskWindows = 0, hasPolicy = false,
            ),
            serverReported = true,
        )

        override suspend fun prepare(
            unlock: Unlock,
            onProgress: (RestoreProgress) -> Unit,
        ): RestorePreview {
            prepares++
            failWith?.let { throw it }
            return preview
        }

        override suspend fun apply(preview: RestorePreview) {
            applies++
        }

        override suspend fun discard() {
            discards++
        }
    }

    private class FakeMarks : BackupRestoreMarkStore {
        var mark: BackupRestoreMark? = null
        override suspend fun get() = mark
        override suspend fun save(mark: BackupRestoreMark) {
            this.mark = mark
        }

        override suspend fun clear() {
            mark = null
        }
    }

    private fun TestScope.viewModel(
        api: FakeApi,
        uploads: FakeUploads = FakeUploads(),
        restores: FakeRestores = FakeRestores(),
        marks: FakeMarks = FakeMarks(),
    ): BackupKeyViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = BackupKeyManager(api, FakeRevisions(), BackupWordlists(context))
        val vm = BackupKeyViewModel(
            manager, uploads, AuthCoordinator(FakeOperations()), restores, marks,
            autoState = FakeAutoState(),
            deviceKey = BackupAbk.None,
            ioDispatcher = mainDispatcherRule.testDispatcher,
            workDispatcher = mainDispatcherRule.testDispatcher,
        )
        pumpUntil { vm.status.value !is BackupKeyViewModel.Status.Loading }
        return vm
    }

    private class FakeAutoState : BackupAutoStateStore {
        override suspend fun read(uid: String) = BackupAutoRecord(null, null)
        override suspend fun recordSuccess(uid: String, contentSha256: String?, atEpochMillis: Long) = Unit
    }

    /** The KDF runs on a real dispatcher; drain the test scheduler while waiting for it. */
    private fun TestScope.pumpUntil(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            assertTrue("timed out after ${timeoutMs}ms", System.nanoTime() < deadline)
            advanceUntilIdle()
            Thread.sleep(5)
        }
    }

    @Test
    fun cancelAfterTheServerCommitted_showsConfiguredNotTheSetupForm() = runTest {
        val api = FakeApi().apply {
            commitOnInitialize = true
            gateInitialize = true
        }
        val vm = viewModel(api)
        assertEquals(BackupKeyViewModel.Status.NotSetUp, vm.status.value)

        vm.setUp(passphrase, generated = false)
        runCurrent()
        assertEquals(BackupKeyViewModel.Operation.Working, vm.operation.value)
        pumpUntil { api.entered.isCompleted }

        vm.cancel()
        assertEquals(BackupKeyViewModel.Operation.Idle, vm.operation.value)
        pumpUntil { vm.status.value is BackupKeyViewModel.Status.Configured }

        // The commit landed despite the cancel: the pane must tell the truth, and the words
        // were never acknowledged, so no reveal is resurrected — rotation can mint new ones.
        assertEquals(BackupKeyViewModel.Status.Configured(1), vm.status.value)
        assertTrue(vm.operation.value is BackupKeyViewModel.Operation.Idle)
        assertEquals(1, api.initializeCalls)
    }

    @Test
    fun cancelBeforeTheServerCommitted_showsNotSetUp() = runTest {
        val api = FakeApi().apply { gateInitialize = true }
        val vm = viewModel(api)

        vm.setUp(passphrase, generated = false)
        runCurrent()
        pumpUntil { api.entered.isCompleted }
        vm.cancel()
        pumpUntil { vm.status.value is BackupKeyViewModel.Status.NotSetUp }

        // The request was dispatched (it cannot be recalled) but nothing was stored server-side.
        assertEquals(1, api.initializeCalls)
        assertEquals(BackupKeyViewModel.Status.NotSetUp, vm.status.value)
        assertEquals(BackupKeyViewModel.Operation.Idle, vm.operation.value)
    }

    @Test
    fun cancelOutsideAWorkingOperation_isIgnoredSoARevealSurvives() = runTest {
        val api = FakeApi().apply { commitOnInitialize = true }
        val vm = viewModel(api)

        vm.setUp(passphrase, generated = false)
        pumpUntil { vm.operation.value is BackupKeyViewModel.Operation.Reveal }

        vm.cancel()
        runCurrent()

        // A stale tap must not wipe the only display of the recovery words.
        assertTrue(vm.operation.value is BackupKeyViewModel.Operation.Reveal)
        assertEquals(BackupKeyViewModel.Status.Configured(1), vm.status.value)
    }

    private fun configured(): FakeApi =
        FakeApi().apply { stored = RemoteBundle(9, fixtureBundle()) }

    @Test
    fun backUpNow_reportsProgressThenSucceededAndForgetsTheSecret() = runTest {
        val uploads = FakeUploads().apply {
            onBackup = {
                progress?.invoke(BackupProgress.Uploading(50, 100))
                FakeUploads.success(7)
            }
        }
        val vm = viewModel(configured(), uploads)
        assertEquals(BackupKeyViewModel.Status.Configured(9), vm.status.value)

        // Virtual time completes the whole flow in one drain, so record the published states
        // eagerly instead of polling for an intermediate one.
        val seen = mutableListOf<BackupKeyViewModel.Backup>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.backup.collect { seen += it }
        }
        vm.backUpNow(Unlock.Passphrase(passphrase))
        pumpUntil { vm.backup.value is BackupKeyViewModel.Backup.Succeeded }
        assertTrue(
            seen.contains(BackupKeyViewModel.Backup.Running(BackupProgress.Uploading(50, 100))),
        )
        assertEquals(7, (vm.backup.value as BackupKeyViewModel.Backup.Succeeded).revision)
        assertEquals(listOf<BackupPair?>(null), uploads.replaces)

        // A terminal outcome drops the unlock secret: a later tap cannot silently reuse it.
        vm.retryBackup()
        runCurrent()
        assertEquals(1, uploads.replaces.size)
        vm.dismissBackup()
        assertEquals(BackupKeyViewModel.Backup.Idle, vm.backup.value)
    }

    @Test
    fun transportFailure_offersTryAgainAndReusesTheHeldSecret() = runTest {
        val uploads = FakeUploads().apply {
            onBackup = { throw BackupUploadException(BackupUploadFailure.NETWORK) }
        }
        val vm = viewModel(configured(), uploads)

        vm.backUpNow(Unlock.Passphrase(passphrase))
        pumpUntil { vm.backup.value is BackupKeyViewModel.Backup.Failed }
        val failed = vm.backup.value as BackupKeyViewModel.Backup.Failed
        assertEquals(BackupUploadFailure.NETWORK, failed.failure)
        assertTrue(failed.retryable)

        uploads.onBackup = { FakeUploads.success(4) }
        vm.retryBackup()
        pumpUntil { vm.backup.value is BackupKeyViewModel.Backup.Succeeded }

        // Retry never re-prompts: the same unlock and no replacement pointer.
        assertEquals(2, uploads.unlocks.size)
        assertEquals(listOf<BackupPair?>(null, null), uploads.replaces)
    }

    @Test
    fun baseConflict_bindsReplaceToTheExactPointerTheServerNamed() = runTest {
        val conflict = BackupPair(5, "sha-5")
        val uploads = FakeUploads().apply {
            onBackup = {
                throw BackupUploadException(
                    BackupUploadFailure.BACKUP_BASE_REQUIRED,
                    current = conflict,
                )
            }
        }
        val vm = viewModel(configured(), uploads)

        vm.backUpNow(Unlock.Passphrase(passphrase))
        pumpUntil { vm.backup.value is BackupKeyViewModel.Backup.Replace }
        assertEquals(conflict, (vm.backup.value as BackupKeyViewModel.Backup.Replace).current)
        assertEquals(0, uploads.currentPointerCalls)

        uploads.onBackup = { FakeUploads.success(6) }
        vm.replaceCloudBackup()
        pumpUntil { vm.backup.value is BackupKeyViewModel.Backup.Succeeded }

        assertEquals(listOf(null, conflict), uploads.replaces)
    }

    @Test
    fun conflictWithoutANamedPointer_rereadsTheCurrentPointer() = runTest {
        val uploads = FakeUploads().apply {
            pointer = BackupPair(5, "sha-5")
            onBackup = { throw BackupUploadException(BackupUploadFailure.BACKUP_CONFLICT) }
        }
        val vm = viewModel(configured(), uploads)

        vm.backUpNow(Unlock.Passphrase(passphrase))
        pumpUntil { vm.backup.value is BackupKeyViewModel.Backup.Replace }

        assertEquals(BackupPair(5, "sha-5"), (vm.backup.value as BackupKeyViewModel.Backup.Replace).current)
        assertEquals(1, uploads.currentPointerCalls)
    }

    @Test
    fun keepCloudBackup_dropsTheSecretAndCannotStartAnotherReplace() = runTest {
        val conflict = BackupPair(5, "sha-5")
        val uploads = FakeUploads().apply {
            onBackup = {
                throw BackupUploadException(
                    BackupUploadFailure.REMOTE_BACKUP_AHEAD,
                    current = conflict,
                )
            }
        }
        val vm = viewModel(configured(), uploads)

        vm.backUpNow(Unlock.Passphrase(passphrase))
        pumpUntil { vm.backup.value is BackupKeyViewModel.Backup.Replace }
        vm.dismissBackup()
        assertEquals(BackupKeyViewModel.Backup.Idle, vm.backup.value)

        vm.replaceCloudBackup()
        vm.retryBackup()
        runCurrent()
        assertEquals(1, uploads.replaces.size)
    }

    @Test
    fun wrongSecret_isNotRetryableAndDropsTheSecret() = runTest {
        val uploads = FakeUploads().apply {
            onBackup = { throw BackupUploadException(BackupUploadFailure.WRONG_SECRET) }
        }
        val vm = viewModel(configured(), uploads)

        vm.backUpNow(Unlock.Passphrase("wrong"))
        pumpUntil { vm.backup.value is BackupKeyViewModel.Backup.Failed }
        assertFalse((vm.backup.value as BackupKeyViewModel.Backup.Failed).retryable)

        vm.retryBackup()
        runCurrent()
        assertEquals(1, uploads.replaces.size)
    }

    @Test
    fun cancelWhileUploading_stopsTheFlowAndForgetsTheSecret() = runTest {
        val gate = CompletableDeferred<Unit>()
        val uploads = FakeUploads().apply {
            onBackup = {
                gate.await()
                FakeUploads.success(3)
            }
        }
        val vm = viewModel(configured(), uploads)

        vm.backUpNow(Unlock.Passphrase(passphrase))
        pumpUntil { vm.backup.value is BackupKeyViewModel.Backup.Running }
        vm.cancelBackup()
        assertEquals(BackupKeyViewModel.Backup.Idle, vm.backup.value)

        gate.complete(Unit)
        advanceUntilIdle()
        // The cancelled job must not resurrect a success card afterwards.
        assertEquals(BackupKeyViewModel.Backup.Idle, vm.backup.value)

        vm.retryBackup()
        runCurrent()
        assertEquals(1, uploads.replaces.size)
    }

    @Test
    fun noBackupKeyYet_failsBeforeAnyUpload() = runTest {
        val uploads = FakeUploads()
        val vm = viewModel(FakeApi(), uploads)
        assertEquals(BackupKeyViewModel.Status.NotSetUp, vm.status.value)

        vm.backUpNow(Unlock.Passphrase(passphrase))
        pumpUntil { vm.backup.value is BackupKeyViewModel.Backup.Failed }

        val failed = vm.backup.value as BackupKeyViewModel.Backup.Failed
        assertEquals(BackupUploadFailure.SIGN_IN_REQUIRED, failed.failure)
        assertFalse(failed.retryable)
        assertEquals(0, uploads.replaces.size)
    }

    @Test
    fun aPreparedRestoreIsShownOnceThenApplied() = runTest {
        val restores = FakeRestores()
        val vm = viewModel(FakeApi(), restores = restores)

        vm.restoreFromCloud(Unlock.Passphrase(passphrase))
        pumpUntil { vm.restore.value is BackupKeyViewModel.Restore.Preview }

        val preview = vm.restore.value as BackupKeyViewModel.Restore.Preview
        assertTrue(preview.serverReported)
        assertEquals(4, preview.revision)
        assertEquals(2, preview.summary.total)

        vm.confirmRestore()
        pumpUntil { vm.restore.value is BackupKeyViewModel.Restore.Applied }
        assertEquals(1, restores.applies)
    }

    @Test
    fun aNonRetryableRestoreFailureDropsTheSecret() = runTest {
        val restores = FakeRestores().apply {
            failWith = BackupUploadException(BackupUploadFailure.PAYLOAD_TAMPERED)
        }
        val vm = viewModel(FakeApi(), restores = restores)

        vm.restoreFromCloud(Unlock.Passphrase(passphrase))
        pumpUntil { vm.restore.value is BackupKeyViewModel.Restore.Failed }

        val failed = vm.restore.value as BackupKeyViewModel.Restore.Failed
        assertEquals(BackupUploadFailure.PAYLOAD_TAMPERED, failed.failure)
        assertFalse(failed.retryable)

        // Without the unlock secret a retry has nothing to re-run with.
        vm.retryRestore()
        runCurrent()
        assertTrue(vm.restore.value is BackupKeyViewModel.Restore.Failed)
        assertEquals(1, restores.prepares)
    }

    @Test
    fun theCompletionCardIsShownOnceAndCleared() = runTest {
        val marks = FakeMarks().apply { mark = BackupRestoreMark(uid, 4, "sha-4", 1L) }
        val vm = viewModel(FakeApi(), marks = marks)
        pumpUntil { vm.completedRestore.value != null }

        vm.dismissCompletedRestore()

        assertNull(vm.completedRestore.value)
        pumpUntil { marks.mark == null }
    }
}
