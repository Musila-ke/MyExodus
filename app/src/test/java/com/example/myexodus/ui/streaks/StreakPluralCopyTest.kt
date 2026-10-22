package com.example.myexodus.ui.streaks

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
 * Two streak lines carry a count that can be 1 (lint `PluralsCandidate`): a best run of one day
 * and a rung one day away. They must read "1 day", never "1 days"; the `other` forms stay
 * byte-identical to the copy shipped before the plural split.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StreakPluralCopyTest {

    private val resources: Resources =
        ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun bestRun_isSingularAtOneDay() {
        assertEquals(
            "Best run: 1 day — that record stays with you.",
            resources.getQuantityString(R.plurals.streaks_best_run, 1, 1),
        )
        assertEquals(
            "Best run: 2 days — that record stays with you.",
            resources.getQuantityString(R.plurals.streaks_best_run, 2, 2),
        )
    }

    @Test
    fun rungDistance_isSingularAtOneDay() {
        assertEquals("1 day away", resources.getQuantityString(R.plurals.streaks_rung_days_away, 1, 1))
        assertEquals("6 days away", resources.getQuantityString(R.plurals.streaks_rung_days_away, 6, 6))
    }
}
