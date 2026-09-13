package com.example.myexodus.accountability.enrollment

import android.app.Application
import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ChainEvent
import com.example.myexodus.accountability.state.ChainEventDao
import com.example.myexodus.accountability.state.ChainHead
import com.example.myexodus.accountability.state.ChainHeadDao
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EnrollmentDaoTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun record(pairId: String, seed: Int = 0) = EnrollmentRecord(
        pairId = pairId,
        mySenderKeyId = "my$seed",
        partnerSigningKeyId = "partner$seed",
        partnerSigningPubKey = ByteArray(64) { seed.toByte() },
        partnerAgreementPubKey = ByteArray(91) { seed.toByte() },
        createdAtEpochMillis = 1_700_000_000_000L + seed,
    )

    private fun openDatabase(): AccountabilityDatabase = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName)
        .addMigrations(
            AccountabilityDatabase.MIGRATION_1_2,
            AccountabilityDatabase.MIGRATION_2_3,
            AccountabilityDatabase.MIGRATION_3_4, // watermark column (mailbox quota fix)
        ).build()

    @Test
    fun upsert_get_roundTrips() = runTest {
        db = openDatabase()
        val r = record("a1b2c3d4e5f60718")
        db.enrollmentDao().upsert(r)
        val loaded = db.enrollmentDao().getByPairId(r.pairId)
        assertEquals(r.pairId, loaded?.pairId)
        assertEquals(r.mySenderKeyId, loaded?.mySenderKeyId)
        assertEquals(r.partnerSigningKeyId, loaded?.partnerSigningKeyId)
        assertArrayEquals(r.partnerSigningPubKey, loaded?.partnerSigningPubKey)
        assertArrayEquals(r.partnerAgreementPubKey, loaded?.partnerAgreementPubKey)
        assertEquals(r.createdAtEpochMillis, loaded?.createdAtEpochMillis)
    }

    @Test
    fun all_ordersByCreatedAtDescending() = runTest {
        db = openDatabase()
        db.enrollmentDao().upsert(record("pair-old", seed = 1))
        db.enrollmentDao().upsert(record("pair-new", seed = 2))
        assertEquals(listOf("pair-new", "pair-old"), db.enrollmentDao().all().map { it.pairId })
    }

    @Test
    fun delete_removesRecord() = runTest {
        db = openDatabase()
        val r = record("pair-1")
        db.enrollmentDao().upsert(r)
        db.enrollmentDao().delete(r)
        assertNull(db.enrollmentDao().getByPairId(r.pairId))
    }

    /** A v1 database opens cleanly under v2 via the real production migration, chain data intact. */
    @Test
    fun migration_1To2_preservesV1SchemaAndAddsEnrollments() = runTest {
        val v1 = Room.databaseBuilder(context, V1Database::class.java, dbName).build()
        v1.chainEventDao().insert(
            ChainEvent(
                pairId = "pair-1",
                senderKeyId = "s",
                epoch = 0,
                sequence = 1,
                previousEventHash = null,
                eventHash = ByteArray(32) { 1 },
                envelope = byteArrayOf(1, 2, 3),
                direction = ChainEvent.DIRECTION_SENT,
                createdAtEpochMillis = 1_700_000_000_000L,
                syncState = ChainEvent.SYNC_STATE_PENDING,
            ),
        )
        v1.close()
        db = openDatabase() // Room validates the schema and runs the 1->2->3 migration chain
        assertEquals(1, db.chainEventDao().chainFor("pair-1").size)
        db.enrollmentDao().upsert(record("a1b2c3d4e5f60718"))
        assertEquals("a1b2c3d4e5f60718", db.enrollmentDao().getByPairId("a1b2c3d4e5f60718")?.pairId)
    }
}

/** The v1 schema exactly as Room generated it when only the chain tables existed. */
@Database(entities = [ChainEvent::class, ChainHead::class], version = 1, exportSchema = false)
abstract class V1Database : RoomDatabase() {
    abstract fun chainEventDao(): ChainEventDao
    abstract fun chainHeadDao(): ChainHeadDao
}
