package com.example.myexodus.account

import android.app.Application
import com.example.myexodus.data.db.LocalDatabaseMigration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account plan §7 step 10 / §7 step 20 / §12.4: the session fence, the two-phase switch, the
 * crash-window reconciliation and the persisted facts that must survive process death.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalSessionManagerTest {

    private val guest =
        AuthIdentity("guest-1", isAnonymous = true, email = null, emailVerified = false, providerIds = emptySet())
    private val rotatedGuest =
        AuthIdentity("guest-2", isAnonymous = true, email = null, emailVerified = false, providerIds = emptySet())
    private val member =
        AuthIdentity("member-1", isAnonymous = false, email = "user@example.com", emailVerified = true, providerIds = setOf("password"))

    private class FakeOperations(initial: AuthIdentity?) : AuthOperations {
        var identity: AuthIdentity? = initial
        private val listeners = mutableListOf<(AuthIdentity?) -> Unit>()

        override fun currentIdentity(): AuthIdentity? = identity

        override fun observeIdentity(onChange: (AuthIdentity?) -> Unit): () -> Unit {
            listeners += onChange
            return { listeners -= onChange }
        }

        /** Simulates the SDK's auth-state callback firing after a credential/sign-out change. */
        fun emit(next: AuthIdentity?) {
            identity = next
            listeners.toList().forEach { it(next) }
        }

        override suspend fun createWithEmail(email: String, password: String) = notUsed()
        override suspend fun signInWithEmail(email: String, password: String) = notUsed()
        override suspend fun linkWithEmail(email: String, password: String) = notUsed()
        override suspend fun signInWithGoogle(idToken: String) = notUsed()
        override suspend fun linkWithGoogle(idToken: String) = notUsed()
        override suspend fun sendVerificationEmail() = notUsed()
        override suspend fun sendPasswordReset(email: String) = notUsed()
        override suspend fun reloadAndRefreshToken() = notUsed()
        override suspend fun reauthenticateWithEmail(email: String, password: String) = notUsed()
        override suspend fun reauthenticateWithGoogle(idToken: String) = notUsed()
        override suspend fun signOut() = notUsed()

        private fun notUsed(): Nothing = error("the session manager never calls an auth operation directly")
    }

    private class InMemoryStore(
        initialGeneration: Long = 0,
        initialSignedOut: Boolean = false,
        initialPendingUnregister: String? = null,
        private val purged: Boolean = true,
    ) : SessionStore {
        var generationValue = initialGeneration
        var signedOutValue = initialSignedOut
        var pendingUnregisterValue = initialPendingUnregister

        override fun generation(): Long = generationValue
        override fun setGeneration(value: Long) { generationValue = value }
        override fun signedOut(): Boolean = signedOutValue
        override fun setSignedOut(value: Boolean) { signedOutValue = value }
        override fun pendingUnregisterUid(): String? = pendingUnregisterValue
        override fun setPendingUnregister(uid: String?) { pendingUnregisterValue = uid }
        override fun legacyCachePurged(): Boolean = purged
    }

    private class RecordingScopes(initialUid: String?) : DatabaseScopeSwitch {
        var hash: String? = initialUid?.let(LocalDatabaseMigration::scopeHashOf)
        val switches = mutableListOf<String>()

        override fun activeHash(): String? = hash
        override fun switchTo(uid: String) {
            switches += uid
            hash = LocalDatabaseMigration.scopeHashOf(uid)
        }
    }

    private class RecordingPush(private val fail: Boolean = false) : PushMapping {
        val unregistered = mutableListOf<String>()

        override suspend fun unregister(uid: String) {
            unregistered += uid
            if (fail) error("offline")
        }
    }

    private class RecordingWork : SessionWork {
        var cancelled = 0
        override fun cancelAccountScoped() { cancelled++ }
    }

    private class RecordingRelauncher : Relauncher {
        var relaunched = 0
        override fun relaunch() { relaunched++ }
    }

    private class FakeCloud(var flushOk: Boolean = true) : SessionCloud {
        var flushes = 0
        override suspend fun flushPendingWrites(timeoutMillis: Long): Boolean {
            flushes++
            return flushOk
        }
    }

    private class Harness(
        initial: AuthIdentity?,
        signedOut: Boolean = false,
        scopeUid: String? = null,
        pendingUnregister: String? = null,
        purged: Boolean = true,
        flushOk: Boolean = true,
        pushFails: Boolean = false,
    ) {
        val operations = FakeOperations(initial)
        val store = InMemoryStore(
            initialSignedOut = signedOut,
            initialPendingUnregister = pendingUnregister,
            purged = purged,
        )
        val scopes = RecordingScopes(scopeUid)
        val push = RecordingPush(fail = pushFails)
        val work = RecordingWork()
        val relauncher = RecordingRelauncher()
        val cloud = FakeCloud(flushOk)
        val manager = LocalSessionManager(
            operations, cloud, store, scopes, push, work, relauncher,
        )
    }

    @Test
    fun fenceIsNullUntilTheAppStartSeedsIt() = runBlocking {
        val h = Harness(guest, scopeUid = "guest-1")
        assertNull(h.manager.current())
        assertFalse(h.manager.mayMintGuest())

        h.manager.onAppStart(this)
        assertEquals(SessionFingerprint("guest-1", 0), h.manager.current())
        assertTrue(h.manager.mayMintGuest())
    }

    @Test
    fun theFirstGuestMint_adoptsWithoutRestarting() = runBlocking {
        val h = Harness(initial = null)
        h.manager.onAppStart(this)

        h.operations.emit(guest)

        assertEquals(SessionFingerprint("guest-1", 0), h.manager.current())
        assertEquals(0, h.relauncher.relaunched)
        assertTrue(h.scopes.switches.isEmpty())
    }

    @Test
    fun anAnonymousUidRotation_keepsTheScopeAndTheSession() = runBlocking {
        val h = Harness(guest, scopeUid = "guest-1")
        h.manager.onAppStart(this)

        h.operations.emit(rotatedGuest)

        // ADR #125: the re-minted guest keeps its data; nothing is torn down or restarted.
        assertEquals(SessionFingerprint("guest-2", 0), h.manager.current())
        assertEquals(0, h.relauncher.relaunched)
        assertTrue(h.scopes.switches.isEmpty())
        assertTrue(h.push.unregistered.isEmpty())
    }

    @Test
    fun aGuestSigningIntoAnExistingAccount_switchesScopeAndRestartsTheProcess() = runBlocking {
        val h = Harness(guest, scopeUid = "guest-1")
        h.manager.onAppStart(this)

        assertEquals(TransitionStart.Started, h.manager.beginTransition(signOut = false))
        assertNull("the fence freezes remote work during the transition", h.manager.current())
        assertEquals(1, h.cloud.flushes)
        assertEquals(1L, h.store.generationValue)
        assertEquals(listOf("guest-1"), h.push.unregistered)

        h.operations.emit(member)

        assertEquals(listOf("member-1"), h.scopes.switches)
        assertEquals(LocalDatabaseMigration.scopeHashOf("member-1"), h.scopes.hash)
        assertEquals(1, h.work.cancelled)
        assertEquals(1, h.relauncher.relaunched)
    }

    @Test
    fun aRefusedPendingWriteFlush_leavesTheSessionUsable() = runBlocking {
        val h = Harness(guest, scopeUid = "guest-1", flushOk = false)
        h.manager.onAppStart(this)

        assertEquals(
            TransitionStart.Refused(TransitionRefusal.PENDING_WRITES),
            h.manager.beginTransition(signOut = false),
        )
        assertEquals(SessionFingerprint("guest-1", 0), h.manager.current())
        assertEquals(0L, h.store.generationValue)
        assertTrue(h.push.unregistered.isEmpty())
    }

    @Test
    fun anExplicitDiscard_letsTheSwitchProceed() = runBlocking {
        val h = Harness(guest, scopeUid = "guest-1", flushOk = false)
        h.manager.onAppStart(this)

        h.manager.approvePendingWriteDiscard()

        assertEquals(TransitionStart.Started, h.manager.beginTransition(signOut = false))
        assertEquals(1L, h.store.generationValue)
    }

    @Test
    fun aFailedCredential_abortsBackToTheOldSession() = runBlocking {
        val h = Harness(guest, scopeUid = "guest-1")
        h.manager.onAppStart(this)

        h.manager.beginTransition(signOut = false)
        h.manager.abortTransition()

        assertEquals("guest-1", h.manager.current()?.uid)
        assertTrue(h.scopes.switches.isEmpty())
        assertEquals(0, h.relauncher.relaunched)
    }

    @Test
    fun beginTransition_isRefusedWhileAnotherTransitionHoldsTheFence() = runBlocking {
        val h = Harness(guest, scopeUid = "guest-1")
        h.manager.onAppStart(this)

        h.manager.beginTransition(signOut = false)

        assertEquals(
            TransitionStart.Refused(TransitionRefusal.ALREADY_TRANSITIONING),
            h.manager.beginTransition(signOut = false),
        )
    }

    @Test
    fun signOut_persistsTheChoice_andCompletesWithoutAScopeChange() = runBlocking {
        val h = Harness(member, scopeUid = "member-1")
        h.manager.onAppStart(this)

        assertEquals(TransitionStart.Started, h.manager.beginTransition(signOut = true))
        assertTrue("a restart must not silently mint a fresh guest", h.store.signedOutValue)
        assertNull(h.manager.current())
        assertFalse(h.manager.mayMintGuest())

        h.manager.completeTransition(null)

        assertEquals(listOf("member-1"), h.push.unregistered)
        assertEquals(1, h.work.cancelled)
        assertEquals(1, h.relauncher.relaunched)
        assertTrue(h.scopes.switches.isEmpty())
        assertTrue("the retained database stays the signed-out account's", h.store.signedOutValue)
    }

    @Test
    fun aSignedOutInstall_neverMintsAGuest() = runBlocking {
        val h = Harness(initial = null, signedOut = true)
        h.manager.onAppStart(this)

        assertNull(h.manager.current())
        assertFalse(h.manager.mayMintGuest())
    }

    @Test
    fun theCrashWindow_isReconciledAtStart() = runBlocking {
        // The credential changed last process but the scope pointer never moved.
        val h = Harness(member, scopeUid = "guest-1")
        h.manager.onAppStart(this)

        assertEquals(listOf("member-1"), h.scopes.switches)
        assertEquals(LocalDatabaseMigration.scopeHashOf("member-1"), h.scopes.hash)
    }

    @Test
    fun aPendingPushDelete_isRetriedAtStart_onTheSameIdentity() = runBlocking {
        val h = Harness(member, scopeUid = "member-1", pendingUnregister = "member-1")
        h.manager.onAppStart(this)
        yield() // the retry is launched, not inline

        assertEquals(listOf("member-1"), h.push.unregistered)
        assertNull(h.store.pendingUnregisterValue)
    }

    @Test
    fun aPendingPushDeleteForAGoneIdentity_isDropped() = runBlocking {
        val h = Harness(member, scopeUid = "member-1", pendingUnregister = "old-uid")
        h.manager.onAppStart(this)
        yield()

        assertTrue(h.push.unregistered.isEmpty())
        assertNull(h.store.pendingUnregisterValue)
    }

    @Test
    fun aFailedPushDelete_isPersistedForTheNextStart() = runBlocking {
        val h = Harness(guest, scopeUid = "guest-1", pushFails = true)
        h.manager.onAppStart(this)

        h.manager.beginTransition(signOut = false)

        assertEquals("guest-1", h.store.pendingUnregisterValue)
    }

    @Test
    fun anUnpurgedLegacyCache_blocksEveryCloudIdentityChange() = runBlocking {
        val h = Harness(guest, scopeUid = "guest-1", purged = false)
        h.manager.onAppStart(this)

        assertEquals(CloudAccess.BLOCKED, h.manager.cloudAccess.value)
        assertFalse(h.manager.isCloudReady())
        assertNull(h.manager.current())
        assertFalse(h.manager.mayMintGuest())
        assertEquals(
            TransitionStart.Refused(TransitionRefusal.MIGRATION_BLOCKED),
            h.manager.beginTransition(signOut = false),
        )
    }
}
