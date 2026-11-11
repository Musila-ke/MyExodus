package com.example.myexodus.data.db.dao

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.example.myexodus.data.db.ProtectedPackageException
import com.example.myexodus.data.db.SchemaConstraints
import com.example.myexodus.data.db.entity.PartnerEventJournal
import com.example.myexodus.data.db.entity.RestrictionRule
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RestrictionRuleDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRaw(rule: RestrictionRule)

    @Update(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun updateRaw(rule: RestrictionRule)

    @Delete abstract suspend fun delete(rule: RestrictionRule)

    @Query("SELECT * FROM restriction_rules WHERE rule_id = :ruleId")
    abstract suspend fun getById(ruleId: String): RestrictionRule?

    @Query("SELECT * FROM restriction_rules WHERE profile_id = :profileId")
    abstract fun observeByProfile(profileId: String): Flow<List<RestrictionRule>>

    /** Snapshot caps (Enhanced Mode spec §8 Step 2): enforce before insert. */
    @Query("SELECT count(*) FROM restriction_rules WHERE profile_id = :profileId AND target_type = :targetType")
    abstract suspend fun countByProfileAndType(profileId: String, targetType: String): Long

    /** Rules of the single active profile — the only ones any engine enforces. The profile is
     *  resolved with the same `ORDER BY user_id LIMIT 1` pick as [UserProfileDao.observeActive];
     *  a JOIN over `is_active = 1` would union a second active row's rules (spike-harness
     *  residue on the rig, 2026-10-04) into enforcement while the UI shows only the first
     *  profile's rules. Keep the two queries aligned. */
    @Query(
        "SELECT * FROM restriction_rules WHERE profile_id = " +
            "(SELECT user_id FROM user_profile WHERE is_active = 1 ORDER BY user_id LIMIT 1)",
    )
    abstract suspend fun getActiveRules(): List<RestrictionRule>

    /** The accounting engine's per-package rule read; unique index guarantees at most one. */
    @Query("SELECT * FROM restriction_rules WHERE profile_id = :profileId AND blocked_package = :packageName LIMIT 1")
    abstract suspend fun getByProfileAndPackage(profileId: String, packageName: String): RestrictionRule?

    @Query("SELECT * FROM restriction_rules WHERE batch_id = :batchId ORDER BY blocked_package COLLATE NOCASE")
    abstract suspend fun getByBatch(batchId: String): List<RestrictionRule>

    /** Rule 4: the CHECK constraint rejects the app's own package; translate to a typed exception. */
    suspend fun insert(rule: RestrictionRule) = translate { insertRaw(rule) }

    suspend fun update(rule: RestrictionRule) = translate { updateRaw(rule) }

    /** Blocking-core plan §6: rule write + PolicyVersion bump commit in one transaction;
     *  any failure (constraint, Rule 4) rolls the bump back with the write. Plan §2.1 req 3
     *  adds the lifecycle journal rows to the same transaction — a failed journal insert
     *  rolls the rule mutation back, so no partner-visible change can be silently lost. */
    @Transaction
    open suspend fun insertAndBump(
        rule: RestrictionRule,
        versionDao: PolicyVersionDao,
        journalDao: PartnerEventJournalDao,
        journal: List<PartnerEventJournal>,
    ) {
        insert(rule)
        versionDao.bump()
        journal.forEach { journalDao.insert(it) }
    }

    @Transaction
    open suspend fun updateAndBump(
        rule: RestrictionRule,
        versionDao: PolicyVersionDao,
        journalDao: PartnerEventJournalDao,
        journal: List<PartnerEventJournal>,
    ) {
        update(rule)
        versionDao.bump()
        journal.forEach { journalDao.insert(it) }
    }

    @Transaction
    open suspend fun deleteAndBump(
        rule: RestrictionRule,
        versionDao: PolicyVersionDao,
        journalDao: PartnerEventJournalDao,
        journal: List<PartnerEventJournal>,
    ) {
        delete(rule)
        versionDao.bump()
        journal.forEach { journalDao.insert(it) }
    }

    // Batch writes (owner mandate 2026-10-31): all members and the ONE batch-level journal
    // row commit together, so the partner can never learn about half a batch.

    @Transaction
    open suspend fun insertBatchAndBump(
        rules: List<RestrictionRule>,
        versionDao: PolicyVersionDao,
        journalDao: PartnerEventJournalDao,
        journal: List<PartnerEventJournal>,
    ) {
        rules.forEach { insert(it) }
        versionDao.bump()
        journal.forEach { journalDao.insert(it) }
    }

    @Transaction
    open suspend fun updateBatchAndBump(
        rules: List<RestrictionRule>,
        versionDao: PolicyVersionDao,
        journalDao: PartnerEventJournalDao,
        journal: List<PartnerEventJournal>,
    ) {
        rules.forEach { update(it) }
        versionDao.bump()
        journal.forEach { journalDao.insert(it) }
    }

    @Transaction
    open suspend fun deleteBatchAndBump(
        rules: List<RestrictionRule>,
        versionDao: PolicyVersionDao,
        journalDao: PartnerEventJournalDao,
        journal: List<PartnerEventJournal>,
    ) {
        rules.forEach { delete(it) }
        versionDao.bump()
        journal.forEach { journalDao.insert(it) }
    }

    /** A batch edit: members that left, members that stayed (already carrying the new
     *  fields), members that joined, and the batch-level journal row — one transaction. */
    @Transaction
    open suspend fun editBatchAndBump(
        removed: List<RestrictionRule>,
        updated: List<RestrictionRule>,
        added: List<RestrictionRule>,
        versionDao: PolicyVersionDao,
        journalDao: PartnerEventJournalDao,
        journal: List<PartnerEventJournal>,
    ) {
        removed.forEach { delete(it) }
        updated.forEach { update(it) }
        added.forEach { insert(it) }
        versionDao.bump()
        journal.forEach { journalDao.insert(it) }
    }

    private suspend fun <T> translate(block: suspend () -> T): T = try {
        block()
    } catch (e: SQLiteConstraintException) {
        if (e.message?.contains(SchemaConstraints.CHECK_SELF_BLOCK) == true) {
            Log.w(TAG, "Rule 4 rejection: self-block write refused")
            throw ProtectedPackageException()
        }
        throw e
    }

    private companion object {
        const val TAG = "MyExodusDb"
    }
}
