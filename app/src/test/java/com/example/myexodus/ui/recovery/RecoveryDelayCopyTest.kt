package com.example.myexodus.ui.recovery

import android.app.Application
import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The delay timer's body copy is a plurals resource (lint `PluralsCandidate`): a 1-minute timer
 * must read "1 minute", not "1 minutes". No catalog technique offers 1 minute today, so only a
 * test can keep the `one` form correct; the `other` form must stay byte-identical to the copy
 * shipped before the plural split.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryDelayCopyTest {

    private val resources: Resources =
        ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun delayBody_readsSingularAtOneMinute() {
        assertEquals(
            "Set the timer for 1 minute. When it ends, decide then — not now.",
            resources.getQuantityString(R.plurals.recovery_delay_body, 1, 1),
        )
    }

    @Test
    fun delayBody_readsPluralAboveOne() {
        assertEquals(
            "Set the timer for 10 minutes. When it ends, decide then — not now.",
            resources.getQuantityString(R.plurals.recovery_delay_body, 10, 10),
        )
    }
}
