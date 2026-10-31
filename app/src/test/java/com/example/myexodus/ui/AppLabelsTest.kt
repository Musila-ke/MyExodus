package com.example.myexodus.ui

import android.content.Context
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotApplyResult
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task #13: the 4000-rule Home ANR was a synchronous PackageManager call per row during
 * composition. These pin the replacement pipeline: a composition-path read never resolves —
 * miss returns null, and the background loader populates the shared cache exactly once per
 * target.
 */
@RunWith(RobolectricTestRunner::class)
class AppLabelsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun pollUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    @Test
    fun missReturnsNullImmediately_thenTheBackgroundLoadPopulatesTheCache() {
        val calls = AtomicInteger(0)
        val key = "cache-populate.test"
        assertNull(cachedAppLabel(key, context, appLabelLoader) { calls.incrementAndGet(); "Label A" })
        pollUntil { peekAppLabel(key) == "Label A" }
        assertEquals("resolver ran exactly once", 1, calls.get())
    }

    @Test
    fun concurrentMissesForOneTargetQueueExactlyOneLoad() {
        val calls = AtomicInteger(0)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val key = "dedup.test"
        repeat(5) {
            cachedAppLabel(key, context, appLabelLoader) {
                started.countDown()
                release.await()
                calls.incrementAndGet()
                "Label B"
            }
        }
        assertTrue("the load started", started.await(5, TimeUnit.SECONDS))
        release.countDown()
        pollUntil { peekAppLabel(key) == "Label B" }
        assertEquals("five misses share one in-flight load", 1, calls.get())
    }

    @Test
    fun preloadResolvesEveryTargetAndShortCircuitsOnceCached() = runBlocking {
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val targets = List(50) { "preload.test.$it" }
        preloadAppLabels(context, targets) { target ->
            calls.computeIfAbsent(target) { AtomicInteger() }.incrementAndGet()
            "Label $target"
        }
        targets.forEach { assertEquals("Label $it", peekAppLabel(it)) }
        preloadAppLabels(context, targets) { target ->
            calls.computeIfAbsent(target) { AtomicInteger() }.incrementAndGet()
            "Label $target"
        }
        assertTrue("second pass resolves nothing", calls.values.all { it.get() == 1 })
    }

    @Test
    fun unreadablePackageFallsBackToTheRawIdentifierOnTheLoaderPath() {
        val key = "com.nonexistent.notinstalled.pkg"
        runBlocking { preloadAppLabels(context, listOf(key)) { appLabel(context, it) } }
        assertEquals(key, peekAppLabel(key))
    }

    @Test
    fun publishesNeverThrowWhileTheMainThreadKeepsApplyingSnapshots() {
        // Task #75 device crash: SnapshotApplyConflictException killed the app three times in
        // the app picker because a background apply lost the race with composition. The fix
        // retries; this keeps a churning applier thread running so conflicts actually happen.
        val churning = AtomicBoolean(true)
        val failures = CopyOnWriteArrayList<Throwable>()
        val churner = Thread {
            while (churning.get()) {
                val snapshot = Snapshot.takeMutableSnapshot()
                try {
                    snapshot.enter { }
                } finally {
                    val result = snapshot.apply()
                    if (result is SnapshotApplyResult.Failure) result.snapshot.dispose()
                }
            }
        }
        churner.start()
        val keys = List(8) { "churn.test.$it" }
        val workers = keys.map { key ->
            Thread {
                try {
                    repeat(200) { publishLabel(key, "Label $key") }
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(30_000) }
        churning.set(false)
        churner.join(30_000)
        assertTrue("no publish threw: $failures", failures.isEmpty())
        keys.forEach { assertEquals("Label $it", peekAppLabel(it)) }

    }

    @Test
    fun seededLabelServesCompositionReadsAndNeverReResolves() {
        // Task #4: both block-fire paths seed the label the overlay card already resolved;
        // the durable screen's title then reads it instead of flashing the raw identifier.
        val key = "seed.test"
        seedAppLabel(key, "Label C")
        assertEquals("Label C", peekAppLabel(key))

        val calls = AtomicInteger(0)
        assertEquals(
            "a composition read hits the seed, not the loader",
            "Label C",
            cachedAppLabel(key, context, appLabelLoader) { calls.incrementAndGet(); "Label D" },
        )
        runBlocking { preloadAppLabels(context, listOf(key)) { calls.incrementAndGet(); "Label D" } }
        assertEquals("a seeded target is never re-resolved", 0, calls.get())
    }
}
