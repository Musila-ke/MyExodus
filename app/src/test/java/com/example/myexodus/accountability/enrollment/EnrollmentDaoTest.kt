package com.example.myexodus.accountability.enrollment

import android.app.Application
import android.content.Context
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.accountability.state.ChainEvent
import com.example.myexodus.accountability.state.ChainEventDao
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

    // The single registry — a hand-written list silently lags the next schema bump (the
    // AppRulesStore drift class, ADR #50/#58; this list had been missing 13→14).
    private fun openDatabase(): AccountabilityDatabase = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName)
        .addMigrations(*AccountabilityDatabase.ALL_MIGRATIONS)
        .build()

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
            ChainEventV1(
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
        db = openDatabase() // Room validates the schema and runs the full 1->…->14 migration chain
        assertEquals(1, db.chainEventDao().chainFor("pair-1").size)
        db.enrollmentDao().upsert(record("a1b2c3d4e5f60718"))
        assertEquals("a1b2c3d4e5f60718", db.enrollmentDao().getByPairId("a1b2c3d4e5f60718")?.pairId)
    }
}

/** The v1 schema exactly as Room generated it when only the chain tables existed. */
@Database(entities = [ChainEventV1::class, ChainHeadV1::class], version = 1, exportSchema = false)
abstract class V1Database : RoomDatabase() {
    abstract fun chainEventDao(): ChainEventV1Dao
}

/**
 * The v1 `events` shape — no `event_id` (added by MIGRATION_12_13). The live [ChainEvent]
 * entity cannot stand in here: its generated DDL already carries the column, so the seed file
 * would be v13-shaped and the migration's ADD COLUMN would fail as a duplicate.
 */
@Entity(
    tableName = "events",
    indices = [Index(value = ["pair_id", "sender_key_id", "sequence"], unique = true)],
)
data class ChainEventV1(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "sender_key_id") val senderKeyId: String,
    @ColumnInfo(name = "epoch") val epoch: Int,
    @ColumnInfo(name = "sequence") val sequence: Long,
    @ColumnInfo(name = "previous_event_hash") val previousEventHash: ByteArray?,
    @ColumnInfo(name = "event_hash") val eventHash: ByteArray,
    @ColumnInfo(name = "envelope") val envelope: ByteArray,
    @ColumnInfo(name = "direction") val direction: String,
    @ColumnInfo(name = "created_at") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "sync_state") val syncState: String,
)

@Dao
interface ChainEventV1Dao {
    @Insert
    suspend fun insert(row: ChainEventV1)
}

/**
 * The v1 `chain_heads` shape — no `key_epoch` (added by MIGRATION_4_5). The live [ChainHead]
 * entity cannot stand in here: its generated DDL already carries the column, which would make
 * the seed file a v5-shaped table and turn the migration's ADD COLUMN into a duplicate-column
 * error.
 */
@Entity(tableName = "chain_heads", primaryKeys = ["pair_id", "sender_key_id"])
data class ChainHeadV1(
    @ColumnInfo(name = "pair_id") val pairId: String,
    @ColumnInfo(name = "sender_key_id") val senderKeyId: String,
    @ColumnInfo(name = "last_sequence") val lastSequence: Long,
    @ColumnInfo(name = "last_event_hash") val lastEventHash: ByteArray,
    @ColumnInfo(name = "forked") val forked: Boolean = false,
)
