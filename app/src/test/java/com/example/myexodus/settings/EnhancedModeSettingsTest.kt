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
class EnhancedModeSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun enabledByDefault() {
        assertTrue(EnhancedModeSettings(context).enhancedModeEnabled)
    }

    @Test
    fun disablePersistsAcrossInstances() {
        EnhancedModeSettings(context).enhancedModeEnabled = false
        assertFalse(EnhancedModeSettings(context).enhancedModeEnabled)
    }

    @Test
    fun reEnablePersists() {
        EnhancedModeSettings(context).enhancedModeEnabled = false
        EnhancedModeSettings(context).enhancedModeEnabled = true
        assertTrue(EnhancedModeSettings(context).enhancedModeEnabled)
    }
}
