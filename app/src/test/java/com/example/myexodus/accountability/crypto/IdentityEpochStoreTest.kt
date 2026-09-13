package com.example.myexodus.accountability.crypto

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.example.myexodus.accountability.state.AccountabilityDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** §8.2: the identity epoch must survive restarts so envelopes stay truthful across them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdentityEpochStoreTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var db: AccountabilityDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "test_${System.nanoTime()}.db"
        db = Room.databaseBuilder(context, AccountabilityDatabase::class.java, dbName).build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun current_defaultsToZero() = runTest {
        assertEquals(0, store().current())
    }

    @Test
    fun bump_incrementsAndPersists() = runTest {
        val store = store()
        assertEquals(1, store.bump())
        assertEquals(2, store.bump())
        // A fresh DAO handle over the same database file sees the persisted value.
        assertEquals(2, store().current())
    }

    private fun store(): IdentityEpochStore = IdentityEpochStore(db.identityEpochDao())
}
