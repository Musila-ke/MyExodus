package com.example.myexodus.accountability.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §8.10: below the published minimum blocks; at or above it, never. */
class ClientCompatibilityTest {

    @Test
    fun `no published minimum never blocks`() {
        assertFalse(ClientCompatibility(minimumVersionCode = null, updateMessage = null).blocks(1))
    }

    @Test
    fun `a version below the minimum blocks`() {
        assertTrue(ClientCompatibility(minimumVersionCode = 2L, updateMessage = "update").blocks(1))
    }

    @Test
    fun `the minimum version itself and newer versions pass`() {
        val compatibility = ClientCompatibility(minimumVersionCode = 2L, updateMessage = "update")
        assertFalse(compatibility.blocks(2))
        assertFalse(compatibility.blocks(3))
    }
}
