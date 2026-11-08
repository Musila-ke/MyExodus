package com.example.myexodus.accountability.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPD #43: only a terminated pair whose reason is an account deletion may trigger a purge. */
class PairUidResolverTest {

    @Test
    fun accountDeletionReason_isOnlyATerminatedDeletionPair() {
        assertTrue(isAccountDeletionTermination("terminated", "ACCOUNT_DELETED"))
        assertTrue(isAccountDeletionTermination("terminated", "GUEST_ACCOUNT_DELETION"))
        assertFalse("an ordinary unpair carries no reason", isAccountDeletionTermination("terminated", null))
        assertFalse(isAccountDeletionTermination("terminated", "SOMETHING_ELSE"))
        assertFalse(isAccountDeletionTermination("active", "ACCOUNT_DELETED"))
        assertFalse(isAccountDeletionTermination(null, null))
    }
}
