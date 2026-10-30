package com.example.myexodus.ui.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.account.AuthCoordinator
import com.example.myexodus.account.AuthIdentity
import com.example.myexodus.account.AuthOperations
import com.example.myexodus.backup.BackupKeyApi
import com.example.myexodus.backup.BackupKeyBundle
import com.example.myexodus.backup.BackupKeyManager
import com.example.myexodus.backup.BackupRevisionStore
import com.example.myexodus.backup.BackupWordlists
import com.example.myexodus.backup.RemoteBundle
import com.example.myexodus.ui.home.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private fun TestScope.viewModel(api: FakeApi): BackupKeyViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = BackupKeyManager(api, FakeRevisions(), BackupWordlists(context))
        val vm = BackupKeyViewModel(manager, AuthCoordinator(FakeOperations()))
        pumpUntil { vm.status.value !is BackupKeyViewModel.Status.Loading }
        return vm
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
}
