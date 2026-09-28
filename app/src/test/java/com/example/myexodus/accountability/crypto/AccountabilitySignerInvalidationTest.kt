package com.example.myexodus.accountability.crypto

import android.app.Application
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §9 error table, the two key-state detections the UI branches on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountabilitySignerInvalidationTest {

    private val signer = AccountabilitySigner()

    @Test
    fun userNotAuthenticated_onlyForThatException() {
        assertTrue(signer.isUserNotAuthenticated(UserNotAuthenticatedException()))
        assertFalse(signer.isUserNotAuthenticated(KeyPermanentlyInvalidatedException()))
        assertFalse(signer.isUserNotAuthenticated(IllegalStateException()))
    }

    @Test
    fun permanentlyInvalidated_onlyForThatException() {
        assertTrue(signer.isPermanentlyInvalidated(KeyPermanentlyInvalidatedException()))
        assertFalse(signer.isPermanentlyInvalidated(UserNotAuthenticatedException()))
        assertFalse(signer.isPermanentlyInvalidated(IllegalStateException()))
    }
}
