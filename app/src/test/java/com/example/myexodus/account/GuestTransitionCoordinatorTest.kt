package com.example.myexodus.account

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account plan §12.1 (step 9): the switch state machine — journal-before-call ordering,
 * idempotent replay, the allowance rule (a failed sign-in never revokes a prepared switch) and
 * finalization keyed to the target session.
 */
private val guestUser =
    AuthIdentity("guest-1", isAnonymous = true, email = null, emailVerified = false, providerIds = emptySet())
private val memberUser =
    AuthIdentity("member-1", isAnonymous = false, email = "user@example.com", emailVerified = true, providerIds = setOf("password"))

@OptIn(ExperimentalCoroutinesApi::class)
class GuestTransitionCoordinatorTest {

    private class FakeJournal : GuestTransitionJournal {
        var record: GuestTransitionRecord? = null
        var unreadable = false
        var cleared = false
        var saves = 0

        override fun load(): JournalLoad = when {
            unreadable -> JournalLoad.Unreadable
            record != null -> JournalLoad.Present(record!!)
            else -> JournalLoad.Absent
        }

        override fun save(record: GuestTransitionRecord) {
            this.record = record
            saves++
        }

        override fun clear() {
            record = null
            cleared = true
        }
    }

    private class FakeApi : GuestTransitionApi {
        val prepareStates = ArrayDeque(listOf(SERVER_PREPARED))
        var prepareFailure: TransitionFailure? = null
        var finalizeFailure: TransitionFailure? = null
        var cancelFailure: TransitionFailure? = null
        var onPrepare: (() -> Unit)? = null
        val prepareCalls = mutableListOf<Pair<String, TransitionIntent>>()
        var prepareCapability: String? = null
        var finalizeCalls = 0
        var cancelCalls = 0
        var terminatedPairIds = listOf("pair-a", "pair-b")

        override suspend fun prepare(
            transitionId: String,
            capability: String,
            intent: TransitionIntent,
        ): PrepareOutcome {
            onPrepare?.invoke()
            prepareCalls += transitionId to intent
            prepareCapability = capability
            prepareFailure?.let { prepareFailure = null; throw GuestTransitionException(it) }
            return PrepareOutcome(prepareStates.removeFirst())
        }

        override suspend fun finalize(
            transitionId: String,
            capability: String,
            intent: TransitionIntent,
        ): FinalizeOutcome {
            finalizeCalls++
            finalizeFailure?.let { finalizeFailure = null; throw GuestTransitionException(it) }
            return FinalizeOutcome(terminatedPairIds, "guest-1")
        }

        override suspend fun cancel(transitionId: String, capability: String) {
            cancelCalls++
            cancelFailure?.let { cancelFailure = null; throw GuestTransitionException(it) }
        }
    }

    private class FakeAuthOps(initial: AuthIdentity?) : AuthOperations {
        var identity: AuthIdentity? = initial
        private val listeners = mutableListOf<(AuthIdentity?) -> Unit>()
        var signInCalls = 0
        var signInGoogleCalls = 0
        var linkGoogleCalls = 0

        fun become(next: AuthIdentity?) {
            identity = next
            listeners.toList().forEach { it(next) }
        }

        override fun currentIdentity(): AuthIdentity? = identity

        override fun observeIdentity(onChange: (AuthIdentity?) -> Unit): () -> Unit {
            listeners += onChange
            return { listeners -= onChange }
        }

        override suspend fun createWithEmail(email: String, password: String): AuthIdentity =
            throw UnsupportedOperationException()

        override suspend fun signInWithEmail(email: String, password: String): AuthIdentity {
            signInCalls++
            return memberUser.copy(email = email).also { identity = it }
        }

        override suspend fun linkWithEmail(email: String, password: String): AuthIdentity =
            throw UnsupportedOperationException()

        override suspend fun signInWithGoogle(idToken: String): AuthIdentity {
            signInGoogleCalls++
            return memberUser.also { identity = it }
        }

        override suspend fun linkWithGoogle(idToken: String): AuthIdentity {
            linkGoogleCalls++
            return memberUser.also { identity = it }
        }

        override suspend fun sendVerificationEmail(): AuthIdentity =
            throw UnsupportedOperationException()

        override suspend fun sendPasswordReset(email: String) = Unit

        override suspend fun reloadAndRefreshToken(): AuthIdentity =
            identity ?: throw UnsupportedOperationException()

        override suspend fun reauthenticateWithEmail(email: String, password: String) = Unit

        override suspend fun reauthenticateWithGoogle(idToken: String) = Unit

        override suspend fun signOut() {
            identity = null
        }
    }

    private class Harness(initial: AuthIdentity? = guestUser) {
        val journal = FakeJournal()
        val api = FakeApi()
        val ops = FakeAuthOps(initial)
        val auth = AuthCoordinator(ops)
        val coordinator = GuestTransitionCoordinator(auth, journal, api)
    }

    /** Coroutines-test 1.11 never runs `backgroundScope` work under `advanceUntilIdle`; the
     *  established idiom here is an unconfined scope on the test scheduler (AuthCoordinatorTest). */
    private fun TestScope.switchScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun preparedRecord(
        intent: TransitionIntent = TransitionIntent.IMPORT,
        guestUid: String = "guest-1",
        id: String = "a".repeat(22),
        capability: String = "b".repeat(43),
    ) = GuestTransitionRecord(
        transitionId = id,
        capability = capability,
        intent = intent,
        guestUid = guestUid,
        prepared = true,
    )

    @Test
    fun start_persistsTheJournalBeforeTheCall_andArmsTheSwitch() = runTest {
        val h = Harness()
        var atCall: GuestTransitionRecord? = null
        var preparedAtCall: Boolean? = null
        h.api.onPrepare = {
            atCall = h.journal.record
            preparedAtCall = h.journal.record?.prepared
        }

        h.coordinator.start(TransitionIntent.IMPORT)

        assertEquals(GuestTransitionState.Prepared(TransitionIntent.IMPORT), h.coordinator.state.value)
        assertNotNull(atCall)
        val record = atCall!!
        assertEquals("the journal must already hold the id/capability", record.transitionId, h.journal.record!!.transitionId)
        assertEquals(record.capability, h.journal.record!!.capability)
        assertEquals("the pre-call save is not prepared yet", false, preparedAtCall)
        assertEquals("validated exact shape", 22, record.transitionId.length)
        assertEquals("validated exact shape", 43, record.capability.length)
        assertTrue(h.journal.record!!.prepared)

        // The switch is usable now: the guest guard lets an existing account through.
        h.auth.signInWithEmail("user@example.com", "secret1")
        assertTrue(h.auth.state.value is AuthState.PersistentActive)
        assertEquals(1, h.ops.signInCalls)
    }

    @Test
    fun failedPrepare_keepsTheJournal_andDoesNotPermitTheSwitch() = runTest {
        val h = Harness()
        h.api.prepareFailure = TransitionFailure.NETWORK
        h.coordinator.start(TransitionIntent.DISCARD)

        val failed = h.coordinator.state.value as GuestTransitionState.Failed
        assertEquals(TransitionStep.PREPARE, failed.step)
        assertEquals(TransitionFailure.NETWORK, failed.failure)
        val id = h.journal.record!!.transitionId
        assertFalse(h.journal.record!!.prepared)

        h.auth.signInWithEmail("user@example.com", "secret1")
        assertEquals(
            AuthState.AuthError(AccountOperation.SIGN_IN, AuthFailure.TRANSITION_REQUIRED),
            h.auth.state.value,
        )
        assertEquals(0, h.ops.signInCalls)

        // The retry replays the same record; the user's latest choice rides it.
        h.coordinator.start(TransitionIntent.IMPORT)
        assertEquals(id, h.journal.record!!.transitionId)
        assertEquals(TransitionIntent.IMPORT, h.api.prepareCalls.last().second)
        assertEquals(GuestTransitionState.Prepared(TransitionIntent.IMPORT), h.coordinator.state.value)
    }

    @Test
    fun start_withAPreparedRecord_replaysTheServersIntent_notTheNewParameter() = runTest {
        val h = Harness()
        h.journal.record = preparedRecord(intent = TransitionIntent.IMPORT)

        h.coordinator.start(TransitionIntent.DISCARD)

        assertEquals(TransitionIntent.IMPORT, h.api.prepareCalls.single().second)
        assertEquals(GuestTransitionState.Prepared(TransitionIntent.IMPORT), h.coordinator.state.value)
        assertEquals(TransitionIntent.IMPORT, h.journal.record!!.intent)
        assertEquals(h.journal.record!!.transitionId, h.api.prepareCalls.single().first)
    }

    @Test
    fun aCancelledServerRecord_isReplacedOnceByAFreshPreparation() = runTest {
        val h = Harness()
        h.journal.record = preparedRecord(id = "c".repeat(22))
        h.api.prepareStates.addFirst(SERVER_CANCELLED)

        h.coordinator.start(TransitionIntent.IMPORT)

        assertEquals(2, h.api.prepareCalls.size)
        assertNotEquals("c".repeat(22), h.journal.record!!.transitionId)
        assertTrue(h.journal.record!!.prepared)
        assertEquals(GuestTransitionState.Prepared(TransitionIntent.IMPORT), h.coordinator.state.value)
    }

    @Test
    fun anUnreadableJournal_failsWithoutCallingOrClearing() = runTest {
        val h = Harness()
        h.journal.unreadable = true
        h.journal.record = preparedRecord()

        h.coordinator.start(TransitionIntent.IMPORT)

        val failed = h.coordinator.state.value as GuestTransitionState.Failed
        assertEquals(TransitionFailure.JOURNAL_UNREADABLE, failed.failure)
        assertTrue(h.api.prepareCalls.isEmpty())
        assertFalse("a transient Keystore failure must not destroy the only copy", h.journal.cleared)
    }

    @Test
    fun targetSignIn_finalizesClearsAndReportsTheTerminatedPairs() = runTest {
        val h = Harness()
        h.journal.record = preparedRecord()
        h.coordinator.attach(switchScope())

        h.ops.become(memberUser)
        advanceUntilIdle()

        assertEquals(1, h.api.finalizeCalls)
        assertNull(h.journal.record)
        assertEquals(GuestTransitionState.Completed(TransitionIntent.IMPORT, 2), h.coordinator.state.value)
        // Disarmed: another guest sign-in is refused again.
        h.auth.signOut()
        advanceUntilIdle()
        assertEquals(AuthState.SignedOut, h.auth.state.value)
    }

    @Test
    fun aFailedFinalize_staysRetryableFromTheTargetSession() = runTest {
        val h = Harness()
        h.journal.record = preparedRecord()
        h.api.finalizeFailure = TransitionFailure.NETWORK
        h.coordinator.attach(switchScope())

        h.ops.become(memberUser)
        advanceUntilIdle()
        val failed = h.coordinator.state.value as GuestTransitionState.Failed
        assertEquals(TransitionStep.FINALIZE, failed.step)
        assertNotNull(h.journal.record)

        h.coordinator.resumeFinalize()

        assertEquals(2, h.api.finalizeCalls)
        assertNull(h.journal.record)
        assertEquals(GuestTransitionState.Completed(TransitionIntent.IMPORT, 2), h.coordinator.state.value)
    }

    @Test
    fun reauthRequired_showsTheProviderForm_andFinishesAfterReauthentication() = runTest {
        val h = Harness()
        h.journal.record = preparedRecord()
        h.api.finalizeFailure = TransitionFailure.REAUTH_REQUIRED
        h.coordinator.attach(switchScope())

        h.ops.become(memberUser)
        advanceUntilIdle()

        assertEquals(
            AuthState.ReauthRequired(AccountOperation.GUEST_TRANSITION, "user@example.com"),
            h.auth.state.value,
        )
        assertNotNull("the capability must survive the reauth detour", h.journal.record)
        assertEquals(GuestTransitionState.Prepared(TransitionIntent.IMPORT), h.coordinator.state.value)

        h.auth.reauthenticateWithEmail("pw123456")
        advanceUntilIdle()

        assertEquals(2, h.api.finalizeCalls)
        assertNull(h.journal.record)
        assertEquals(GuestTransitionState.Completed(TransitionIntent.IMPORT, 2), h.coordinator.state.value)
        assertTrue(h.auth.state.value is AuthState.PersistentActive)
    }

    @Test
    fun cancel_clearsTheJournalAndDisarms() = runTest {
        val h = Harness()
        h.journal.record = preparedRecord()
        h.auth.setIdentitySwitchAllowed(true)

        h.coordinator.cancel()

        assertEquals(1, h.api.cancelCalls)
        assertNull(h.journal.record)
        assertEquals(GuestTransitionState.Idle, h.coordinator.state.value)
        h.auth.signInWithEmail("user@example.com", "secret1")
        assertEquals(
            AuthState.AuthError(AccountOperation.SIGN_IN, AuthFailure.TRANSITION_REQUIRED),
            h.auth.state.value,
        )
    }

    @Test
    fun cancel_withNoServerRecord_stillClearsLocally() = runTest {
        val h = Harness()
        h.journal.record = preparedRecord()
        h.api.cancelFailure = TransitionFailure.NOT_FOUND

        h.coordinator.cancel()

        assertNull(h.journal.record)
        assertEquals(GuestTransitionState.Idle, h.coordinator.state.value)
    }

    @Test
    fun aFailedCancel_keepsTheJournalAndSucceedsOnRetry() = runTest {
        val h = Harness()
        h.journal.record = preparedRecord()
        h.api.cancelFailure = TransitionFailure.NETWORK

        h.coordinator.cancel()
        val failed = h.coordinator.state.value as GuestTransitionState.Failed
        assertEquals(TransitionStep.CANCEL, failed.step)
        assertNotNull(h.journal.record)

        h.coordinator.cancel()

        assertEquals(2, h.api.cancelCalls)
        assertNull(h.journal.record)
        assertEquals(GuestTransitionState.Idle, h.coordinator.state.value)
    }

    @Test
    fun aRetainedPreparedJournal_isRestoredForAGuestWithoutAScreen() = runTest {
        val h = Harness()
        h.journal.record = preparedRecord()
        h.coordinator.attach(switchScope())

        advanceUntilIdle()

        assertEquals(GuestTransitionState.Prepared(TransitionIntent.IMPORT), h.coordinator.state.value)
        h.auth.signInWithEmail("user@example.com", "secret1")
        assertTrue(h.auth.state.value is AuthState.PersistentActive)
    }
}
