package com.example.myexodus.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
}
