package com.example.myexodus.data.db

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VaultGateTest {

    private lateinit var recovery: RecoveryManager
    private lateinit var factory: AppRulesStoreFactory
    private var allowed = true

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context,
            FakeKeyManager(),
            ActivityLogKeyManager(context),
            RulesStoreKeyManager(context),
            healthStore,
        )
        recovery.resetAllData()
        factory = AppRulesStoreFactory(context, recovery, healthStore, vaultAccessAllowed = { allowed })
    }

    @After
    fun tearDown() {
        allowed = true
        factory.lockVault()
    }

    @Test
    fun `disabled feature keeps the vault accessible`() {
        assertNotNull(factory.open())
    }

    @Test
    fun `closed gate refuses open and withVault`() {
        allowed = false
        assertThrows(VaultLockedException::class.java) { factory.open() }
        assertThrows(VaultLockedException::class.java) { factory.withVault { 1 } }
    }

    @Test
    fun `withVault returns the block result`() {
        assertEquals(7, factory.withVault { 7 })
    }

    @Test
    fun `checkpoint while locked is a silent no-op`() {
        allowed = false
        factory.checkpoint()
    }

    @Test
    fun `lock refuses new leases, drains the active one, then closes`() {
        assertNotNull("warm the first open before measuring drain", factory.open())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val generationBefore = factory.sessionGeneration
        val worker = thread {
            try {
                factory.withVault {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            } finally {
                entered.countDown()
            }
        }
        assertTrue(entered.await(30, TimeUnit.SECONDS))

        assertFalse(factory.lockVault(timeoutMs = 150))
        assertThrows(VaultLockedException::class.java) { factory.open() }

        release.countDown()
        worker.join(5_000)
        assertFalse(worker.isAlive)

        assertTrue(factory.lockVault(timeoutMs = 1_000))
        assertEquals(generationBefore + 2, factory.sessionGeneration)
        assertNotNull("a fresh handle opens after the gate re-admits access", factory.open())
    }

    @Test
    fun `undrained lock stays closed until a later retry succeeds`() {
        assertNotNull("warm the first open before measuring drain", factory.open())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = thread {
            try {
                factory.withVault {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            } finally {
                entered.countDown()
            }
        }
        assertTrue(entered.await(30, TimeUnit.SECONDS))

        assertFalse(factory.lockVault(timeoutMs = 100))
        assertThrows(VaultLockedException::class.java) { factory.open() }

        release.countDown()
        worker.join(5_000)
        assertTrue(factory.lockVault(timeoutMs = 1_000))
    }

    @Test
    fun `concurrent leases survive lock and reopen churn`() {
        assertNotNull("warm the first open before measuring churn", factory.open())
        val workerCount = 6
        val iterations = 25
        val reads = AtomicInteger()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val workers = (1..workerCount).map {
            thread {
                start.await(30, TimeUnit.SECONDS)
                repeat(iterations) {
                    try {
                        val result = factory.withVault { store ->
                            runBlocking { store.restrictionRuleDao().getActiveRules() }
                        }
                        if (result != null) reads.incrementAndGet()
                    } catch (_: VaultLockedException) {
                        // Expected while the gate is closing; the lease model permits refusal.
                    } catch (t: Throwable) {
                        failures.add(t)
                    }
                }
            }
        }
        val locker = thread {
            repeat(5) {
                Thread.sleep(100)
                try {
                    factory.lockVault(timeoutMs = 2_000)
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
        }
        start.countDown()
        workers.forEach { it.join(60_000) }
        locker.join(60_000)

        assertTrue("workers must terminate", workers.none { it.isAlive } && !locker.isAlive)
        assertTrue("unexpected failures: ${failures.map { it.stackTraceToString() }}", failures.isEmpty())
        assertTrue("at least one lease must have completed a read, saw ${reads.get()}", reads.get() > 0)
        assertTrue("a final lock with no leases must succeed", factory.lockVault(timeoutMs = 2_000))
        assertNotNull("a fresh handle opens after the churn", factory.open())
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }
}
