package com.example.myexodus.accountability.crypto

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.enrollment.EnrollmentRecord
import com.example.myexodus.accountability.state.AccountabilityDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §7.6/§8.2: rotation IS revocation — both hardware keys are rotated, the identity epoch
 * advances, and every enrollment is cleared so each pair must re-enroll against the new
 * attested key id. A failed rotation leaves everything untouched (no partial revocation).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdentityMigrationTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase
    private lateinit var epochStore: IdentityEpochStore
    private lateinit var migration: IdentityMigration

    private var rotated = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName)
            .addMigrations(AccountabilityDatabase.MIGRATION_1_2)
            .build()
        epochStore = IdentityEpochStore(db.identityEpochDao())
        migration = IdentityMigration(db.enrollmentDao(), epochStore)
        runBlocking {
            db.enrollmentDao().upsert(record("pair-a"))
            db.enrollmentDao().upsert(record("pair-b"))
        }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate_rotatesKeysBumpsEpochAndClearsEnrollments() = runTest {
        val result = migration.migrate { rotated = true }

        assertEquals(IdentityMigration.MigrationResult.Migrated(epoch = 1, reEnrollmentRequired = 2), result)
        assertTrue(rotated)
        assertEquals(1, epochStore.current())
        assertTrue(db.enrollmentDao().all().isEmpty())
    }

    @Test
    fun rotateFailure_returnsFailed_leavesStateUntouched() = runTest {
        val result = migration.migrate { error("StrongBox unavailable") }

        assertEquals(IdentityMigration.MigrationResult.Failed("StrongBox unavailable"), result)
        assertEquals(0, epochStore.current())
        assertEquals(2, db.enrollmentDao().all().size)
    }

    private fun record(pairId: String) = EnrollmentRecord(
        pairId = pairId,
        mySenderKeyId = "my-key-$pairId",
        partnerSigningKeyId = "partner-key-$pairId",
        partnerSigningPubKey = ByteArray(64) { 1 },
        partnerAgreementPubKey = ByteArray(91) { 2 },
        createdAtEpochMillis = 1_700_000_000_000L,
    )
}
