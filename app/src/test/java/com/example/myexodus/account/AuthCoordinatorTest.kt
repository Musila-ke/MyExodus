package com.example.myexodus.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account plan §12.1 (step 8): the coordinator's state machine, cancellation, the guest-link
 * UID preservation rule and the collision rule — a collision must never sign out, unlink or
 * mutate anything beyond the guest's own session.
 */
private val guest =
    AuthIdentity("guest-1", isAnonymous = true, email = null, emailVerified = false, providerIds = emptySet())
private val member =
    AuthIdentity("member-1", isAnonymous = false, email = "user@example.com", emailVerified = true, providerIds = setOf("password"))

@OptIn(ExperimentalCoroutinesApi::class)
class AuthCoordinatorTest {

    private class FakeOperations(initial: AuthIdentity?) : AuthOperations {
        var identity: AuthIdentity? = initial
        private val listeners = mutableListOf<(AuthIdentity?) -> Unit>()

        var createCalls = 0
        var linkEmailCalls = 0
        var signInEmailCalls = 0
        var linkGoogleCalls = 0
        var signInGoogleCalls = 0
        var verificationCalls = 0
        var reloadCalls = 0
        var reauthGoogleCalls = 0
        var signOutCalls = 0
        var reloadVerified = true
        var throwOnSignOut = false
        val resetEmails = mutableListOf<String>()
        val reauthEmailArgs = mutableListOf<Pair<String, String>>()

        /** One-shot failure raised by the next credential operation. */
        var nextFailure: AuthFailure? = null

        /** Lets a test hold a link in flight to observe cancellation. */
        var linkGate: CompletableDeferred<Unit>? = null

        override fun currentIdentity(): AuthIdentity? = identity

        override fun observeIdentity(onChange: (AuthIdentity?) -> Unit): () -> Unit {
            listeners += onChange
            return { listeners -= onChange }
        }

        private fun failIfAsked() {
            nextFailure?.let { nextFailure = null; throw AuthOperationException(it) }
        }

        override suspend fun createWithEmail(email: String, password: String): AuthIdentity {
            createCalls++
            failIfAsked()
            return AuthIdentity("created-1", false, email, false, setOf("password")).also { identity = it }
        }

        override suspend fun signInWithEmail(email: String, password: String): AuthIdentity {
            signInEmailCalls++
            failIfAsked()
            return member.copy(email = email).also { identity = it }
        }

        override suspend fun linkWithEmail(email: String, password: String): AuthIdentity {
            linkEmailCalls++
            linkGate?.await()
            failIfAsked()
            val current = identity ?: error("link without a session")
            return current.copy(
                isAnonymous = false,
                email = email,
                emailVerified = false,
                providerIds = setOf("password"),
            ).also { identity = it }
        }

        override suspend fun signInWithGoogle(idToken: String): AuthIdentity {
            signInGoogleCalls++
            failIfAsked()
            return member.copy(email = "google@example.com", providerIds = setOf("google.com")).also { identity = it }
        }

        override suspend fun linkWithGoogle(idToken: String): AuthIdentity {
            linkGoogleCalls++
            failIfAsked()
            val current = identity ?: error("link without a session")
            return current.copy(
                isAnonymous = false,
                email = "google@example.com",
                emailVerified = true,
                providerIds = setOf("google.com"),
            ).also { identity = it }
        }

        override suspend fun sendVerificationEmail(): AuthIdentity {
            verificationCalls++
            return identity ?: error("no session")
        }

        override suspend fun sendPasswordReset(email: String) {
            resetEmails += email
        }

        override suspend fun reloadAndRefreshToken(): AuthIdentity {
            reloadCalls++
            val current = identity ?: error("no session")
            return current.copy(emailVerified = reloadVerified).also { identity = it }
        }

        override suspend fun reauthenticateWithEmail(email: String, password: String) {
            reauthEmailArgs += email to password
            failIfAsked()
        }

        override suspend fun reauthenticateWithGoogle(idToken: String) {
            reauthGoogleCalls++
            failIfAsked()
        }

        override suspend fun signOut() {
            signOutCalls++
            if (throwOnSignOut) throw IllegalStateException("sdk")
            identity = null
        }
    }

    private fun TestScope.collectEvents(coordinator: AuthCoordinator): List<AuthEvent> {
        val events = mutableListOf<AuthEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.events.collect { events += it }
        }
        return events
    }

    @Test
    fun initialState_mirrorsCurrentIdentity() {
        assertEquals(AuthState.SignedOut, AuthCoordinator(FakeOperations(null)).state.value)
        assertEquals(AuthState.GuestActive("guest-1"), AuthCoordinator(FakeOperations(guest)).state.value)
        assertEquals(
            AuthState.PersistentActive("member-1", "user@example.com", true, setOf(AuthProvider.PASSWORD)),
            AuthCoordinator(FakeOperations(member)).state.value,
        )
    }

    @Test
    fun createFromGuest_linksInPlace_preservingUid() = runTest {
        val fake = FakeOperations(guest)
        val coordinator = AuthCoordinator(fake)
        val events = collectEvents(coordinator)

        coordinator.createAccount("  new@example.com ", "secret1")

        assertEquals(
            AuthState.PersistentActive("guest-1", "new@example.com", false, setOf(AuthProvider.PASSWORD)),
            coordinator.state.value,
        )
        assertEquals(1, fake.linkEmailCalls)
        assertEquals(0, fake.createCalls)
        assertEquals(listOf<AuthEvent>(AuthEvent.EmailVerificationSent, AuthEvent.SignedIn(true, false)), events)
    }

    @Test
    fun createFromSignedOut_createsTheAccount() = runTest {
        val fake = FakeOperations(null)
        val coordinator = AuthCoordinator(fake)

        coordinator.createAccount("new@example.com", "secret1")

        assertEquals(1, fake.createCalls)
        assertEquals(0, fake.linkEmailCalls)
        assertTrue(coordinator.state.value is AuthState.PersistentActive)
    }

    @Test
    fun createFromPersistent_isRefused() = runTest {
        val fake = FakeOperations(member)
        val coordinator = AuthCoordinator(fake)

        coordinator.createAccount("other@example.com", "secret1")

        assertEquals(
            AuthState.AuthError(AccountOperation.CREATE_ACCOUNT, AuthFailure.ALREADY_SIGNED_IN),
            coordinator.state.value,
        )
        assertEquals(0, fake.createCalls)
        assertEquals(0, fake.linkEmailCalls)
    }

    @Test
    fun collision_keepsTheGuestSession_andEmitsNeutralGuidance() = runTest {
        val fake = FakeOperations(guest).apply { nextFailure = AuthFailure.CREDENTIAL_ALREADY_REGISTERED }
        val coordinator = AuthCoordinator(fake)
        val events = collectEvents(coordinator)

        coordinator.createAccount("taken@example.com", "secret1")

        assertEquals(
            AuthState.AuthError(AccountOperation.CREATE_ACCOUNT, AuthFailure.CREDENTIAL_ALREADY_REGISTERED),
            coordinator.state.value,
        )
        coordinator.dismissTransient()
        assertEquals(AuthState.GuestActive("guest-1"), coordinator.state.value)
        assertTrue("no SignedIn may be claimed", events.none { it is AuthEvent.SignedIn })
        assertEquals(listOf<AuthEvent>(AuthEvent.Collision), events)
        assertEquals("a collision must never sign the guest out", 0, fake.signOutCalls)
        assertEquals(0, fake.reloadCalls)
        assertEquals("the guest identity is untouched", guest, fake.identity)
    }

    @Test
    fun googleCollisionFromGuest_keepsTheGuestSession() = runTest {
        val fake = FakeOperations(guest).apply { nextFailure = AuthFailure.CREDENTIAL_ALREADY_REGISTERED }
        val coordinator = AuthCoordinator(fake)
        val events = collectEvents(coordinator)

        coordinator.continueWithGoogle("id-token")

        assertEquals(1, fake.linkGoogleCalls)
        assertEquals(0, fake.signOutCalls)
        assertEquals(guest, fake.identity)
        assertEquals(listOf<AuthEvent>(AuthEvent.Collision), events)
    }

    @Test
    fun googleFromSignedOut_signsIn_andFromGuest_links() = runTest {
        val signedOut = FakeOperations(null)
        AuthCoordinator(signedOut).continueWithGoogle("id-token")
        assertEquals(1, signedOut.signInGoogleCalls)
        assertEquals(0, signedOut.linkGoogleCalls)

        val guestFake = FakeOperations(guest)
        val coordinator = AuthCoordinator(guestFake)
        coordinator.continueWithGoogle("id-token")
        assertEquals(1, guestFake.linkGoogleCalls)
        assertEquals(
            AuthState.PersistentActive("guest-1", "google@example.com", true, setOf(AuthProvider.GOOGLE)),
            coordinator.state.value,
        )
    }

    @Test
    fun signInFromGuest_requiresAPreparedTransition() = runTest {
        val fake = FakeOperations(guest)
        val coordinator = AuthCoordinator(fake)

        coordinator.signInWithEmail("other@example.com", "secret1")

        assertEquals(
            AuthState.AuthError(AccountOperation.SIGN_IN, AuthFailure.TRANSITION_REQUIRED),
            coordinator.state.value,
        )
        assertEquals("no credential may be sent before consent", 0, fake.signInEmailCalls)

        coordinator.setIdentitySwitchAllowed(true)
        coordinator.signInWithEmail("other@example.com", "secret1")
        assertTrue(coordinator.state.value is AuthState.PersistentActive)
        assertEquals(1, fake.signInEmailCalls)
    }

    @Test
    fun signInWhilePersistent_isRefused() = runTest {
        val fake = FakeOperations(member)
        val coordinator = AuthCoordinator(fake)

        coordinator.signInWithEmail("other@example.com", "secret1")

        assertEquals(
            AuthState.AuthError(AccountOperation.SIGN_IN, AuthFailure.ALREADY_SIGNED_IN),
            coordinator.state.value,
        )
        assertEquals(0, fake.signInEmailCalls)
    }

    @Test
    fun cancellation_restoresTheStableState() = runTest {
        val fake = FakeOperations(guest).apply { linkGate = CompletableDeferred() }
        val coordinator = AuthCoordinator(fake)

        val job = launch { coordinator.createAccount("new@example.com", "secret1") }
        runCurrent()
        assertEquals(AuthState.Authenticating(AccountOperation.CREATE_ACCOUNT), coordinator.state.value)

        job.cancelAndJoin()

        assertEquals(AuthState.GuestActive("guest-1"), coordinator.state.value)
        assertEquals(guest, fake.identity)
    }

    @Test
    fun reentrantCallDuringAuthenticating_isIgnored() = runTest {
        val fake = FakeOperations(guest).apply { linkGate = CompletableDeferred() }
        val coordinator = AuthCoordinator(fake)

        val job = launch { coordinator.createAccount("new@example.com", "secret1") }
        runCurrent()
        coordinator.signInWithEmail("other@example.com", "secret1")

        assertEquals(0, fake.signInEmailCalls)
        assertEquals(1, fake.linkEmailCalls)
        job.cancel()
    }

    @Test
    fun signInFailure_surfacesGenericError_thenDismissRestoresSignedOut() = runTest {
        val fake = FakeOperations(null).apply { nextFailure = AuthFailure.INVALID_CREDENTIALS }
        val coordinator = AuthCoordinator(fake)

        coordinator.signInWithEmail("a@example.com", "secret1")

        assertEquals(
            AuthState.AuthError(AccountOperation.SIGN_IN, AuthFailure.INVALID_CREDENTIALS),
            coordinator.state.value,
        )
        coordinator.dismissTransient()
        assertEquals(AuthState.SignedOut, coordinator.state.value)
    }

    @Test
    fun unexpectedException_mapsToUnknown() = runTest {
        val throwing = object : AuthOperations by FakeOperations(null) {
            override suspend fun signInWithEmail(email: String, password: String): AuthIdentity =
                throw IllegalStateException("boom")
        }
        val coordinator = AuthCoordinator(throwing)

        coordinator.signInWithEmail("a@example.com", "secret1")

        assertEquals(
            AuthState.AuthError(AccountOperation.SIGN_IN, AuthFailure.UNKNOWN),
            coordinator.state.value,
        )
    }

    @Test
    fun reauthEmail_usesTheSignedInEmail_andForceRefreshes() = runTest {
        val fake = FakeOperations(member)
        val coordinator = AuthCoordinator(fake)
        val events = collectEvents(coordinator)

        coordinator.reauthenticateWithEmail("pw123456")

        assertEquals(listOf("user@example.com" to "pw123456"), fake.reauthEmailArgs)
        assertEquals("the token must be refreshed after reauth", 1, fake.reloadCalls)
        assertEquals(listOf<AuthEvent>(AuthEvent.Reauthenticated), events)
        assertTrue(coordinator.state.value is AuthState.PersistentActive)
    }

    @Test
    fun reauth_whenSignedOut_failsWithoutCallingTheProvider() = runTest {
        val fake = FakeOperations(null)
        val coordinator = AuthCoordinator(fake)

        coordinator.reauthenticateWithEmail("pw123456")

        assertEquals(
            AuthState.AuthError(AccountOperation.REAUTHENTICATE, AuthFailure.NOT_SIGNED_IN),
            coordinator.state.value,
        )
        assertTrue(fake.reauthEmailArgs.isEmpty())
    }

    @Test
    fun requireReauth_showsTheProvidersForm_andDismissReturnsToBase() = runTest {
        val coordinator = AuthCoordinator(FakeOperations(member))

        coordinator.requireReauth(AccountOperation.BACKUP)

        assertEquals(
            AuthState.ReauthRequired(AccountOperation.BACKUP, "user@example.com"),
            coordinator.state.value,
        )
        coordinator.dismissTransient()
        assertTrue(coordinator.state.value is AuthState.PersistentActive)
    }

    @Test
    fun refreshVerification_emitsOnlyWhenTheRecordIsVerified() = runTest {
        val fake = FakeOperations(member).apply { reloadVerified = false }
        val coordinator = AuthCoordinator(fake)
        val events = collectEvents(coordinator)

        coordinator.refreshVerification()
        assertTrue(events.none { it == AuthEvent.EmailVerified })

        fake.reloadVerified = true
        coordinator.refreshVerification()
        assertEquals(listOf<AuthEvent>(AuthEvent.EmailVerified), events)
    }

    @Test
    fun passwordReset_isTrimmed_andEmitsTheNeutralEvent() = runTest {
        val fake = FakeOperations(null)
        val coordinator = AuthCoordinator(fake)
        val events = collectEvents(coordinator)

        coordinator.sendPasswordReset("  user@example.com ")

        assertEquals(listOf("user@example.com"), fake.resetEmails)
        assertEquals(listOf<AuthEvent>(AuthEvent.PasswordResetRequested), events)
    }

    @Test
    fun signOut_returnsToSignedOut_andKeepsEverythingElseLocal() = runTest {
        val fake = FakeOperations(member)
        val coordinator = AuthCoordinator(fake)
        val events = collectEvents(coordinator)

        coordinator.signOut()

        assertEquals(AuthState.SignedOut, coordinator.state.value)
        assertEquals(1, fake.signOutCalls)
        assertEquals(listOf<AuthEvent>(AuthEvent.SignedOut), events)
    }

    @Test
    fun signOut_swallowsSdkFailure_soTheSessionNeverTrapsTheUser() = runTest {
        val fake = FakeOperations(member).apply { throwOnSignOut = true }
        val coordinator = AuthCoordinator(fake)

        coordinator.signOut()

        assertEquals(AuthState.SignedOut, coordinator.state.value)
    }

    @Test
    fun aFailedSignIn_doesNotRevokeThePreparedSwitch() = runTest {
        val fake = FakeOperations(guest).apply { nextFailure = AuthFailure.INVALID_CREDENTIALS }
        val coordinator = AuthCoordinator(fake)
        coordinator.setIdentitySwitchAllowed(true)

        coordinator.signInWithEmail("a@example.com", "wrong")
        assertEquals(
            AuthState.AuthError(AccountOperation.SIGN_IN, AuthFailure.INVALID_CREDENTIALS),
            coordinator.state.value,
        )

        // §8.4: a typo must not burn the prepared switch — the retry stays open.
        coordinator.signInWithEmail("a@example.com", "right")
        assertTrue(coordinator.state.value is AuthState.PersistentActive)
        assertEquals(2, fake.signInEmailCalls)
    }

    @Test
    fun switchToGoogle_signsInRatherThanLinks_andRequiresThePreparedTransition() = runTest {
        val fake = FakeOperations(guest)
        val coordinator = AuthCoordinator(fake)

        coordinator.switchToGoogle("id-token")
        assertEquals(
            AuthState.AuthError(AccountOperation.SIGN_IN, AuthFailure.TRANSITION_REQUIRED),
            coordinator.state.value,
        )
        assertEquals(0, fake.signInGoogleCalls)

        coordinator.setIdentitySwitchAllowed(true)
        coordinator.switchToGoogle("id-token")

        assertEquals(1, fake.signInGoogleCalls)
        assertEquals("a switch must never link the guest into the target", 0, fake.linkGoogleCalls)
        assertTrue(coordinator.state.value is AuthState.PersistentActive)
    }

    @Test
    fun switchAllowance_isClearedOnceTheIdentityStopsBeingAnonymous() = runTest {
        val fake = FakeOperations(guest)
        val coordinator = AuthCoordinator(fake)
        coordinator.setIdentitySwitchAllowed(true)

        coordinator.createAccount("new@example.com", "secret1")

        assertNull(fake.nextFailure)
        assertFalse("nothing may remain allowed after the guest became persistent", fake.identity!!.isAnonymous)
        coordinator.signInWithEmail("someone@example.com", "secret1")
        assertEquals(
            AuthState.AuthError(AccountOperation.SIGN_IN, AuthFailure.ALREADY_SIGNED_IN),
            coordinator.state.value,
        )
    }
}
