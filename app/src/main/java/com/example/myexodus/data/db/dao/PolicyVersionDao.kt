package com.example.myexodus.data.db.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PolicyVersionDao {

    @Query("SELECT version FROM policy_version WHERE id = 0")
    abstract fun observe(): Flow<Long>

    @Query("SELECT version FROM policy_version WHERE id = 0")
    protected abstract suspend fun load(): Long?

    @Query("UPDATE policy_version SET version = version + 1 WHERE id = 0")
    protected abstract suspend fun bumpRaw(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun seed(row: com.example.myexodus.data.db.entity.PolicyVersion)

    suspend fun current(): Long = load() ?: 0L

    /** Self-healing: if the row is somehow absent, seed it at version 1 instead of throwing. */
    suspend fun bump() {
        if (bumpRaw() == 0) seed(com.example.myexodus.data.db.entity.PolicyVersion(0, 1))
    }
}
