package com.example.myexodus.blocking

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.telemetry.CrashReporting
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Boot-reconciliation state (plan §8 Step 9) against real device-protected SharedPreferences:
 * the §12 dedup row ("boot-receiver and cold-start paths do not both trigger reconciliation
 * for the same boot count") plus the once-per-boot guard and its retry behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootReconciliationStoreTest {

    private lateinit var store: BootReconciliationStore

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        store = BootReconciliationStore(context, CrashReporting(context))
    }

    @Test
    fun firstEverLaunch_reconcilesOnceThenStaysSilent() = runTest {
        var work = 0
        assertTrue(store.reconcileOnce(7) { work++ })
        assertFalse(store.reconcileOnce(7) { work++ })
        assertEquals(1, work)
    }

    @Test
    fun reboot_reconcilesAgainForTheNewBootCount() = runTest {
        var work = 0
        store.reconcileOnce(7) { work++ }
        assertTrue(store.reconcileOnce(8) { work++ })
        assertEquals(2, work)
    }

    @Test
    fun receiverMarkAndColdStart_doNotBothReconcile_sameBoot() = runTest {
        var work = 0
        store.markPending(9)
        assertTrue(store.reconcileOnce(9) { work++ })
        assertFalse(store.reconcileOnce(9) { work++ })
        assertEquals(1, work)
    }

    @Test
    fun failedReconcile_isRetriedByTheNextTrigger() = runTest {
        var work = 0
        runCatching {
            store.reconcileOnce(9) {
                work++
                throw IllegalStateException("db unavailable")
            }
        }
        assertTrue(store.reconcileOnce(9) { work++ })
        assertEquals(2, work)
    }
}
