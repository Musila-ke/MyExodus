package com.example.myexodus.applock

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppLockCoordinatorTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = clearStatus()

    @After
    fun tearDown() = clearStatus()

    private fun clearStatus() {
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        context.getSharedPreferences(AppLockStatusStore.PREFS_NAME, Context.MODE_PRIVATE)

    private fun enroll(journal: String = "active_pending_first_unlock") {
        prefs().edit()
            .putString(AppLockStatusStore.KEY_FEATURE_STATUS, "enabled")
            .putString(AppLockStatusStore.KEY_MIGRATION_JOURNAL, journal)
            .commit()
    }

    private fun coordinator() = AppLockCoordinator(AppLockStatusStore(context))

    /** §4.2: UNLOCKED is published only from OPENING, so tests take the real path. */
    private fun enterUnlocked(lock: AppLockCoordinator) {
        lock.publish(AppLockState.OPENING)
        lock.publishUnlocked()
        assertEquals(AppLockState.UNLOCKED, lock.state.value)
    }

    private fun reader(feature: String?, journal: String?) = { key: String ->
        when (key) {
            AppLockStatusStore.KEY_FEATURE_STATUS -> feature
            AppLockStatusStore.KEY_MIGRATION_JOURNAL -> journal
            else -> null
        }
    }

    @Test
    fun `no durable status keeps the feature disabled`() {
        assertEquals(AppLockState.DISABLED, AppLockStatusStore.startupState(reader(null, null)))
    }

    @Test
    fun `enabled feature starts locked`() {
        assertEquals(
            AppLockState.LOCKED,
            AppLockStatusStore.startupState(reader("enabled", "active_pending_first_unlock")),
        )
        assertEquals(
            AppLockState.LOCKED,
            AppLockStatusStore.startupState(reader("enabled", "first_unlock_verified")),
        )
    }

    @Test
    fun `interrupted migration and unknown status fail closed`() {
        assertEquals(
            AppLockState.RECOVERY_REQUIRED,
            AppLockStatusStore.startupState(reader("enabled", "staging")),
        )
        assertEquals(
            AppLockState.RECOVERY_REQUIRED,
            AppLockStatusStore.startupState(reader("enabled", "committing")),
        )
        assertEquals(
            AppLockState.RECOVERY_REQUIRED,
            AppLockStatusStore.startupState(reader("enabled", null)),
        )
        assertEquals(
            AppLockState.RECOVERY_REQUIRED,
            AppLockStatusStore.startupState(reader("half_written", null)),
        )
    }

    @Test
    fun `only disabled and unlocked compose protected ui`() {
        val exposed = AppLockState.values().filter { it.showsProtectedUi }.toSet()
        assertEquals(setOf(AppLockState.DISABLED, AppLockState.UNLOCKED), exposed)
    }

    @Test
    fun `fresh process with an enabled feature starts locked`() {
        enroll()
        assertEquals(AppLockState.LOCKED, coordinator().state.value)
    }

    @Test
    fun `screen off revokes an unlocked session`() {
        enroll()
        val lock = coordinator()
        enterUnlocked(lock)
        val generation = lock.sessionGeneration
        lock.onScreenOff()
        assertEquals(AppLockState.LOCKED, lock.state.value)
        assertEquals(generation + 1, lock.sessionGeneration)
    }

    @Test
    fun `configuration change keeps the session`() {
        enroll()
        val lock = coordinator()
        enterUnlocked(lock)
        lock.onHostStopped(changingConfigurations = true)
        assertEquals(AppLockState.UNLOCKED, lock.state.value)
    }

    @Test
    fun `ordinary app switch revokes the session`() {
        enroll()
        val lock = coordinator()
        enterUnlocked(lock)
        lock.onHostStopped(changingConfigurations = false)
        assertEquals(AppLockState.LOCKED, lock.state.value)
    }

    @Test
    fun `expected external operation survives a stop once and is single use`() {
        enroll()
        val lock = coordinator()
        enterUnlocked(lock)
        lock.beginExpectedExternalOperation("screen-lock-settings")
        lock.onHostStopped(changingConfigurations = false)
        assertEquals(AppLockState.UNLOCKED, lock.state.value)
        assertTrue(lock.onHostResumed())
        assertEquals(AppLockState.UNLOCKED, lock.state.value)
        lock.onHostStopped(changingConfigurations = false)
        assertEquals(AppLockState.LOCKED, lock.state.value)
    }

    @Test
    fun `external token expires after sixty seconds`() {
        enroll()
        val lock = coordinator()
        enterUnlocked(lock)
        lock.beginExpectedExternalOperation("installer")
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        lock.onHostStopped(changingConfigurations = false)
        assertEquals(AppLockState.LOCKED, lock.state.value)
    }

    @Test
    fun `manual lock defeats a live external token`() {
        enroll()
        val lock = coordinator()
        enterUnlocked(lock)
        lock.beginExpectedExternalOperation("picker")
        lock.lockManually()
        lock.onHostStopped(changingConfigurations = false)
        assertEquals(AppLockState.LOCKED, lock.state.value)
        assertFalse(lock.onHostResumed())
    }

    @Test
    fun `screen off defeats a live external token`() {
        enroll()
        val lock = coordinator()
        enterUnlocked(lock)
        lock.beginExpectedExternalOperation("picker")
        lock.onScreenOff()
        lock.onHostStopped(changingConfigurations = false)
        assertEquals(AppLockState.LOCKED, lock.state.value)
    }

    @Test
    fun `disabled feature ignores lifecycle and screen off`() {
        val lock = coordinator()
        lock.onScreenOff()
        lock.lockManually()
        lock.onHostStopped(changingConfigurations = false)
        assertEquals(AppLockState.DISABLED, lock.state.value)
    }

    @Test
    fun `unlock never survives a new process`() {
        enroll()
        enterUnlocked(coordinator())
        assertEquals(AppLockState.LOCKED, coordinator().state.value)
    }

    @Test
    fun `credential challenge keeps the unlock attempt across the keyguard stop once`() {
        enroll()
        val lock = coordinator()
        lock.publish(AppLockState.AUTHENTICATING)
        val attempt = lock.beginAttempt()
        lock.beginCredentialChallenge()
        lock.onHostStopped(changingConfigurations = false)
        assertEquals(AppLockState.AUTHENTICATING, lock.state.value)
        assertTrue(lock.isAttemptCurrent(attempt))
        assertTrue(lock.onHostResumed())
        assertEquals(AppLockState.AUTHENTICATING, lock.state.value)
        lock.onHostStopped(changingConfigurations = false)
        assertEquals(AppLockState.LOCKED, lock.state.value)
    }

    @Test
    fun `credential challenge covers a disable prompt from an unlocked session`() {
        enroll()
        val lock = coordinator()
        enterUnlocked(lock)
        lock.beginAttempt()
        lock.beginCredentialChallenge()
        lock.onHostStopped(changingConfigurations = false)
        assertEquals(AppLockState.UNLOCKED, lock.state.value)
        assertTrue(lock.onHostResumed())
        assertEquals(AppLockState.UNLOCKED, lock.state.value)
    }

    @Test
    fun `credential challenge expires after sixty seconds`() {
        enroll()
        val lock = coordinator()
        lock.publish(AppLockState.AUTHENTICATING)
        lock.beginAttempt()
        lock.beginCredentialChallenge()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        lock.onHostStopped(changingConfigurations = false)
        assertEquals(AppLockState.LOCKED, lock.state.value)
    }

    @Test
    fun `concluded attempt voids the credential challenge`() {
        enroll()
        val lock = coordinator()
        lock.publish(AppLockState.AUTHENTICATING)
        val attempt = lock.beginAttempt()
        lock.beginCredentialChallenge()
        lock.concludeAttempt(AppLockState.LOCKED)
        assertFalse(lock.isAttemptCurrent(attempt))
        assertFalse(lock.onHostResumed())
        assertEquals(AppLockState.LOCKED, lock.state.value)
    }

    @Test
    fun `screen off defeats a live credential challenge`() {
        enroll()
        val lock = coordinator()
        lock.publish(AppLockState.AUTHENTICATING)
        lock.beginAttempt()
        lock.beginCredentialChallenge()
        lock.onScreenOff()
        assertEquals(AppLockState.LOCKED, lock.state.value)
    }

    @Test
    fun `external return window rejects expiry and backwards time`() {
        assertTrue(externalReturnWithinWindow(startedAtElapsed = 1_000L, nowElapsed = 61_000L))
        assertFalse(externalReturnWithinWindow(startedAtElapsed = 1_000L, nowElapsed = 61_001L))
        assertFalse(externalReturnWithinWindow(startedAtElapsed = 1_000L, nowElapsed = 999L))
    }
}
