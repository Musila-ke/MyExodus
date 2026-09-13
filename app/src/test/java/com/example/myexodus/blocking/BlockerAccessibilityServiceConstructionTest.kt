package com.example.myexodus.blocking

import com.example.myexodus.MyExodusApp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MyExodusApp::class)
class BlockerAccessibilityServiceConstructionTest {

    @Test
    fun serviceConstructs_withoutReadingInjectedFieldsInInitializers() {
        // Regression (2026-08-26, on-device crash): the scanner's field initializer read
        // `this.engine` before Hilt's onCreate injection ran →
        // UninitializedPropertyAccessException on accessibility bind. Construction exercises
        // exactly the field-initializer path; injection happens later in onCreate.
        Robolectric.buildService(BlockerAccessibilityService::class.java).create()
    }
}
