package com.example.myexodus.data.db

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADR #239/#240: the offer exists only while the retained rules file does, and only a copy bound
 * to an account may be imported without the user staging it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RecoveryOfferTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hash = "ab".repeat(16)
    private val memberUid = "member-1"

    @Before
    fun setUp() {
        LocalDatabaseMigration.clearRecoveryOffer(context)
        File(databaseDir(), LocalDatabaseMigration.Scope(hash).rulesName).writeText("retained")
    }

    @After
    fun tearDown() {
        LocalDatabaseMigration.clearRecoveryOffer(context)
        File(databaseDir(), LocalDatabaseMigration.Scope(hash).rulesName).delete()
    }

    @Test
    fun aBoundOffer_isVisibleOnlyToItsAccount_andIsEligibleForAutomaticImport() {
        LocalDatabaseMigration.recordRecoveryOffer(context, hash, forUid = memberUid)

        assertEquals(hash, LocalDatabaseMigration.recoveryOffer(context, memberUid))
        assertEquals(hash, LocalDatabaseMigration.boundRecoveryOffer(context, memberUid))
        assertNull("another account never sees it", LocalDatabaseMigration.recoveryOffer(context, "other"))
        assertNull(LocalDatabaseMigration.boundRecoveryOffer(context, "other"))
    }

    @Test
    fun anUnboundOffer_isClaimableThroughTheCard_butNeverAutomatically() {
        LocalDatabaseMigration.recordRecoveryOffer(context, hash, forUid = null)

        assertEquals(hash, LocalDatabaseMigration.recoveryOffer(context, memberUid))
        assertNull(
            "no account owned this copy, so only the explicit card may claim it",
            LocalDatabaseMigration.boundRecoveryOffer(context, memberUid),
        )
    }

    @Test
    fun anOfferWhoseFileIsGone_isNotOfferedAtAll() {
        LocalDatabaseMigration.recordRecoveryOffer(context, hash, forUid = memberUid)
        File(databaseDir(), LocalDatabaseMigration.Scope(hash).rulesName).delete()

        assertNull(LocalDatabaseMigration.recoveryOffer(context, memberUid))
        assertNull(LocalDatabaseMigration.boundRecoveryOffer(context, memberUid))
    }

    private fun databaseDir(): File =
        context.getDatabasePath("x").parentFile!!.also { it.mkdirs() }
}
