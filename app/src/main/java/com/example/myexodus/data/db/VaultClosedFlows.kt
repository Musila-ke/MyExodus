package com.example.myexodus.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * App Lock §8.3: a composition-scoped Room Flow collector built or restarted by the activity
 * lifecycle (ON_START/recomposition) while the vault is closed must not crash the process — the
 * lock gate is about to hide the tree, so the flow completes quietly. Using a lambda defers the
 * DAO call into collection: Room 3 throws its closed-object error both eagerly inside the DAO
 * call (InvalidationTracker.createFlow) and while collecting. The error is an untyped
 * IllegalStateException; its message is the only discriminator
 * (androidx.room3 RoomDatabaseKt.throwIfClosed).
 */
fun <T> vaultClosedFlow(create: () -> Flow<T>): Flow<T> = flow {
    val upstream = try {
        create()
    } catch (e: IllegalStateException) {
        if (e.isVaultClosedError()) return@flow
        throw e
    }
    emitAll(
        upstream.catch { e ->
            if (e.isVaultClosedError()) return@catch
            throw e
        },
    )
}

private fun Throwable.isVaultClosedError(): Boolean =
    this is IllegalStateException && message?.contains("Database is closed") == true
