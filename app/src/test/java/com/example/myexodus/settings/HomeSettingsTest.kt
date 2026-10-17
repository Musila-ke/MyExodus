package com.example.myexodus.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun blocksAreVisibleByDefault() {
        assertFalse(HomeSettings(context).blocksHidden)
    }

    @Test
    fun hidingPersistsAcrossInstances() {
        HomeSettings(context).blocksHidden = true
        assertTrue(HomeSettings(context).blocksHidden)
    }

    @Test
    fun showingPersists() {
        HomeSettings(context).blocksHidden = true
        HomeSettings(context).blocksHidden = false
        assertFalse(HomeSettings(context).blocksHidden)
    }
}
