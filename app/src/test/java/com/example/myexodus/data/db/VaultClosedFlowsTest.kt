package com.example.myexodus.data.db

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultClosedFlowsTest {

    @Test
    fun closedAtCreation_completesQuietly() = runBlocking {
        // Room 3 throws eagerly inside the DAO call (InvalidationTracker.createFlow) when the
        // vault already closed before this recomposition-time flow was built.
        val emitted = vaultClosedFlow<Int> { throw IllegalStateException("Database is closed") }
            .toList()

        assertEquals(emptyList<Int>(), emitted)
    }

    @Test
    fun closedDuringCollection_completesQuietly() = runBlocking {
        val emitted = vaultClosedFlow {
            flow {
                emit(1)
                throw IllegalStateException("Database is closed")
            }
        }.toList()

        assertEquals(listOf(1), emitted)
    }

    @Test
    fun anyOtherFailure_stillPropagates() {
        val atCreation = runCatching {
            runBlocking {
                vaultClosedFlow<Int> { throw IllegalStateException("boom") }.toList()
            }
        }.exceptionOrNull()
        val duringCollection = runCatching {
            runBlocking {
                vaultClosedFlow { flow<Int> { throw IllegalStateException("boom") } }.toList()
            }
        }.exceptionOrNull()

        assertTrue(atCreation is IllegalStateException && atCreation.message == "boom")
        assertTrue(duringCollection is IllegalStateException && duringCollection.message == "boom")
    }
}
