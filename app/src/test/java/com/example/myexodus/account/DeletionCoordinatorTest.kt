package com.example.myexodus.account

import android.app.Application
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
import javax.inject.Provider
import kotlinx.coroutines.test.runTest

/**
 * Account plan §6.8/§6.9.3: the deletion choreography. The marker must exist before the
 * sign-out (the purge runs after the relaunch), a refused sign-out must never strand a marker
 * behind a live session, and no failure may leave the user signed out of a live account.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DeletionCoordinatorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeDeletionApi(var failure: DeletionFailure? = null) : DeletionApi {
        val calls = mutableListOf<String>()

        override suspend fun requestAccountDeletion(confirmation: String): String =
            record("account", confirmation)

        override suspend fun requestGuestDeletion(confirmation: String): String =
            record("guest", confirmation)

        private fun record(kind: String, confirmation: String): String {
            failure?.let { throw DeletionException(it) }
            calls += "$kind:$confirmation"
            return "QUEUED"
        }
    }

    private class RefusingSessions : SessionTransitions {
        var refuse = true
        var approved = false

        override fun isCloudReady(): Boolean = true

        override suspend fun beginTransition(signOut: Boolean): TransitionStart =
            if (refuse) TransitionStart.Refused(TransitionRefusal.PENDING_WRITES)
            else TransitionStart.Started

        override fun completeTransition(newUid: String?) = Unit

        override suspend fun abortTransition() = Unit

        override fun approvePendingWriteDiscard() {
            approved = true
            refuse = false
        }
    }

    private inner class Harness(
        ops: FakeAuthOps,
        val api: FakeDeletionApi = FakeDeletionApi(),
        sessions: SessionTransitions = SessionTransitions.Unfenced,
    ) {
        val local = DeletionLocalData(
            context,
            Provider { throw UnsupportedOperationException("uploads") },
            Provider { throw UnsupportedOperationException("marks") },
            Provider { throw UnsupportedOperationException("quarantine") },
            Provider { throw UnsupportedOperationException("staging") },
            Provider { throw UnsupportedOperationException("accountability") },
            Provider { throw UnsupportedOperationException("anchors") },
        )
        val ops = ops
        val auth = AuthCoordinator(ops, sessions)
        val deletion = DeletionCoordinator(context, auth, api, local, Relauncher { })
    }

    @Test
    fun accountDeletionReauthenticatesThenJournalsThenSignsOut() = runTest {
        val h = Harness(FakeAuthOps(accountMemberUser))

        h.deletion.deleteAccount(DeletionPhrases.ACCOUNT, DeletionCredential.Password("secret"))

        assertEquals(1, h.ops.reauthEmailCalls)
        assertEquals(listOf("account:${DeletionPhrases.ACCOUNT}"), h.api.calls)
        assertTrue(h.local.hasPending())
        assertNull(h.ops.identity)
        assertTrue(h.auth.state.value is AuthState.SignedOut)
        assertEquals(DeletionState.Accepted(signOutBlocked = false), h.deletion.state.value)
    }

    @Test
    fun failedReauthenticationRequestsNothingAndJournalsNothing() = runTest {
        val h = Harness(FakeAuthOps(null))

        h.deletion.deleteAccount(DeletionPhrases.ACCOUNT, DeletionCredential.Password("secret"))

        assertTrue(h.api.calls.isEmpty())
        assertFalse(h.local.hasPending())
        assertTrue(h.auth.state.value is AuthState.SignedOut)
        assertEquals(DeletionState.Failed(DeletionFailure.REAUTH_FAILED), h.deletion.state.value)
    }

    @Test
    fun serverRefusalLeavesTheSessionAndTheDatabaseAlone() = runTest {
        val h = Harness(
            FakeAuthOps(accountMemberUser),
            api = FakeDeletionApi(DeletionFailure.CONFIRMATION_MISMATCH),
        )

        h.deletion.deleteAccount(DeletionPhrases.ACCOUNT, DeletionCredential.Password("secret"))

        assertFalse(h.local.hasPending())
        assertTrue(h.auth.state.value is AuthState.PersistentActive)
        assertEquals(
            DeletionState.Failed(DeletionFailure.CONFIRMATION_MISMATCH),
            h.deletion.state.value,
        )
    }

    @Test
    fun guestDeletionRequestsTheGuestPhraseAndSignsOut() = runTest {
        val h = Harness(FakeAuthOps(accountGuestUser))

        h.deletion.deleteGuestData(DeletionPhrases.GUEST)

        assertEquals(listOf("guest:${DeletionPhrases.GUEST}"), h.api.calls)
        assertTrue(h.local.hasPending())
        assertTrue(h.auth.state.value is AuthState.SignedOut)
        assertEquals(DeletionState.Accepted(signOutBlocked = false), h.deletion.state.value)
    }

    @Test
    fun refusedSignOutKeepsTheMarkerForTheForcedFinish() = runTest {
        val sessions = RefusingSessions()
        val h = Harness(FakeAuthOps(accountMemberUser), sessions = sessions)

        h.deletion.deleteAccount(DeletionPhrases.ACCOUNT, DeletionCredential.Password("secret"))

        assertTrue("the purge must survive the refused sign-out", h.local.hasPending())
        assertTrue(h.auth.state.value is AuthState.PersistentActive)
        assertEquals(DeletionState.Accepted(signOutBlocked = true), h.deletion.state.value)

        h.deletion.finishAcceptedSignOut(discardQueuedWrites = true)

        assertTrue(sessions.approved)
        assertTrue(h.auth.state.value is AuthState.SignedOut)
        assertNull(h.ops.identity)
        assertEquals(DeletionState.Accepted(signOutBlocked = false), h.deletion.state.value)
    }

    @Test
    fun refusedLocalRemovalWithdrawsItsMarker() = runTest {
        val h = Harness(FakeAuthOps(accountMemberUser), sessions = RefusingSessions())

        h.deletion.removeLocalData()

        assertFalse("a refused teardown must leave no purge marker", h.local.hasPending())
        assertTrue(h.auth.state.value is AuthState.PersistentActive)
        assertEquals(DeletionState.Failed(DeletionFailure.PENDING_WRITES), h.deletion.state.value)
    }

    @Test
    fun localRemovalJournalsBeforeTheSignOut() = runTest {
        val h = Harness(FakeAuthOps(accountMemberUser))

        h.deletion.removeLocalData()

        assertTrue(h.local.hasPending())
        assertTrue(h.auth.state.value is AuthState.SignedOut)
        assertEquals(DeletionState.Accepted(signOutBlocked = false), h.deletion.state.value)
    }

    @Test
    fun retainedRemovalWithoutARetainedScopeIsANoOp() = runTest {
        val h = Harness(FakeAuthOps(null))

        h.deletion.removeRetainedLocalData()

        assertEquals(DeletionState.Idle, h.deletion.state.value)
    }

    @Test
    fun markerRoundTripKeepsOtherIdentitiesIntact() = runTest {
        val h = Harness(FakeAuthOps(null))
        assertFalse(h.local.hasPending())

        h.local.markPending("uid-42")
        assertTrue(h.local.hasPending())

        h.local.clearPending("some-other-uid")
        assertTrue("a different identity's marker must not be cleared", h.local.hasPending())

        h.local.clearPending("uid-42")
        assertFalse(h.local.hasPending())
    }
}
