package com.example.myexodus.accountability.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPD #43: any server-side termination is delivery to a member that still holds the
 *  enrollment — account deletion, guest identity switch, or a plain end of the partnership. */
class PairUidResolverTest {

    @Test
    fun terminatedStatus_isTheServerTerminationSignal() {
        assertTrue("any reason — deletion, guest switch, or plain end — reaches the survivor", isServerTermination("terminated"))
        assertFalse(isServerTermination("active"))
        assertFalse(isServerTermination(null))
    }
}
