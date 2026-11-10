package com.example.myexodus.data.db

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myexodus.account.DeletionLocalData
import com.example.myexodus.accountability.state.AccountabilityDatabase
import com.example.myexodus.backup.BackupRestoreMarkStore
import com.example.myexodus.backup.BackupStaging
import com.example.myexodus.backup.BackupUploadSessionStore
import com.example.myexodus.backup.RemoteBackupAnchorStore
import java.io.File
import java.util.Arrays
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan §7 step 7 / §8.1: upgrades the currently distributed install's three stores to
 * UID-scoped paths. Seeds a non-empty encrypted rules store (WAL mode, pending
 * partner-activity outbox row), a non-empty encrypted activity log, and a plain-FBE
 * accountability store; asserts content, checksums and key adoption survive, that the legacy
 * sources remain as the rollback copy, and that the journal's completion marker is what
 * decides between "recopy from sources" and "never touch this again".
 *
 * "Process kill at each migration step": the operation has exactly two crash windows — before
 * the journal commit (targets disposable, sources authoritative) and after it (done forever).
 * Both are driven here through the journal seams; a real OS kill between them leaves the same
 * on-disk state and takes the same path on the next launch.
 */
@RunWith(AndroidJUnit4::class)
class LocalDatabaseMigrationInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var recovery: RecoveryManager

    @Before
    fun setUp() {
        assumeEmulatorOnly()
        System.loadLibrary("sqlcipher")
        recovery = RecoveryManager(
            context,
            AndroidDatabaseKeyManager(context),
            ActivityLogKeyManager(context),
            RulesStoreKeyManager(context),
            DatabaseHealthStore(),
        )
        prepareCleanState()
    }

    @After
    fun tearDown() {
        if (!onSanctionedEmulator() || !::recovery.isInitialized) return
        prepareCleanState()
    }

    @Test
    fun migratesEveryStoreAndRetainsTheRollbackCopies() {
        val rulesDek = RulesStoreKeyManager(context).getOrCreateDek()
        val activityDek = ActivityLogKeyManager(context).getOrCreateKey()
        seedRules(legacyRulesFile(), rulesDek)
        seedActivity(legacyActivityFile(), activityDek)
        seedPlainAccountability(legacyAccountabilityFile())
        // Crash residue from earlier WAL use: a stale -shm must neither break the upgrade nor
        // be copied (SQLite rebuilds it on open). A surviving non-empty -wal is covered on the
        // live X6532 install, where the store is mid-use at process start.
        File(legacyRulesFile().path + "-shm").writeBytes(ByteArray(32 * 1024))

        assertTrue(LocalDatabaseMigration.migrateForTest(context, UID))
        val scope = scopeOf(UID)
        assertEquals(scope.uidHash, LocalDatabaseMigration.scopeOrNull()?.uidHash)

        val scopedRules = File(databaseDir(), scope.rulesName)
        assertEquals(1L, encryptedCount(scopedRules, rulesDek, "restriction_rules"))
        assertEquals(1L, encryptedCount(scopedRules, rulesDek, "partner_activity_outbox"))
        assertEquals("1234", encryptedValue(scopedRules, rulesDek, "PRAGMA user_version"))
        val scopedActivity = File(databaseDir(), scope.activityName)
        assertEquals(1L, encryptedCount(scopedActivity, activityDek, ActivityLogSchema.TABLE))
        val scopedAccountability = File(databaseDir(), scope.accountabilityName)
        assertEquals(1L, plainCount(scopedAccountability, "chain_event"))
        assertEquals(1L, plainCount(scopedAccountability, "outbox"))

        assertTrue("legacy rules must be retained for the rollback window", legacyRulesFile().exists())
        assertTrue("legacy activity must be retained", legacyActivityFile().exists())
        assertTrue("legacy accountability must be retained", legacyAccountabilityFile().exists())

        // The copy is re-wrapped under a UID-scoped Keystore alias that wraps the same DEK.
        val scopedDek = scopedKeyManager(RulesStoreKeyManager.LEGACY_KEK_ALIAS, RulesStoreKeyManager.LEGACY_PREFS, scope).getOrCreateKey()
        try {
            assertArrayEquals("scoped alias must adopt the legacy DEK", rulesDek, scopedDek)
        } finally {
            Arrays.fill(scopedDek, 0.toByte())
        }
    }

    @Test
    fun completedJournalNeverRecopiesAndAnIncompleteJournalRecoversFromSources() {
        val rulesDek = RulesStoreKeyManager(context).getOrCreateDek()
        seedRules(legacyRulesFile(), rulesDek)
        assertTrue(LocalDatabaseMigration.migrateForTest(context, UID))
        val scope = scopeOf(UID)
        val scopedRules = File(databaseDir(), scope.rulesName)
        assertEquals(1L, encryptedCount(scopedRules, rulesDek, "restriction_rules"))

        // A row appended to the rekeyed legacy source after completion must never come back:
        // the journal marker means the scoped stores are the live data now.
        appendRawRow(legacyRulesFile(), rulesDek, "r2")
        assertTrue(LocalDatabaseMigration.migrateForTest(context, UID))
        assertEquals(1L, encryptedCount(scopedRules, rulesDek, "restriction_rules"))

        // Killed before the journal commit: the targets are disposable and the next launch
        // recopies from the intact sources, now including the appended row.
        LocalDatabaseMigration.resetJournalForTest(context)
        assertTrue(LocalDatabaseMigration.migrateForTest(context, UID))
        assertEquals(2L, encryptedCount(scopedRules, rulesDek, "restriction_rules"))

        // Seven-day rollback window: sources and their keys go, the scoped data stays.
        LocalDatabaseMigration.cleanupForTest(context, System.currentTimeMillis() + EIGHT_DAYS_MS)
        assertFalse(legacyRulesFile().exists())
        assertFalse(
            legacyKeyManager(RulesStoreKeyManager.LEGACY_KEK_ALIAS, RulesStoreKeyManager.LEGACY_PREFS).hasWrappedKey(),
        )
        assertEquals(2L, encryptedCount(scopedRules, rulesDek, "restriction_rules"))
    }

    /**
     * §6.9.3, superseding ADR #244 item 7 (X6532, 2026-11-07): every scope a launch would adopt
     * is offered while signed out. The deletion purge clears the switch pointer but leaves the
     * journal scope, and the next launch reopens it; gating the card on the pointer alone hid a
     * live store (the stale pre-relapse history seen after deleting the account).
     */
    @Test
    fun everyLocalScopeTheNextLaunchWouldAdoptIsOffered() {
        seedRules(legacyRulesFile(), RulesStoreKeyManager(context).getOrCreateDek())
        assertTrue(LocalDatabaseMigration.migrateForTest(context, UID))
        val local = DeletionLocalData(
            context,
            Provider { throw UnsupportedOperationException("uploads") },
            Provider { throw UnsupportedOperationException("marks") },
            Provider { throw UnsupportedOperationException("quarantine") },
            Provider { throw UnsupportedOperationException("staging") },
            Provider { throw UnsupportedOperationException("accountability") },
            Provider { throw UnsupportedOperationException("anchors") },
        )
        assertTrue(
            "the install's own journal scope is data the next launch would open",
            runBlocking { local.removalInfo() }.retainedScope,
        )

        LocalDatabaseMigration.switchScopeTo(context, OTHER_UID)
        assertTrue(runBlocking { local.removalInfo() }.retainedScope)

        assertTrue(LocalDatabaseMigration.forgetScope(context, scopeOf(OTHER_UID).uidHash))
        assertTrue(
            "the install's journal scope outlives the erased account scope",
            runBlocking { local.removalInfo() }.retainedScope,
        )

        assertTrue(LocalDatabaseMigration.forgetScope(context, scopeOf(UID).uidHash))
        assertFalse(
            "with every scope erased, the card must go",
            runBlocking { local.removalInfo() }.retainedScope,
        )
    }

    @Test
    fun aDifferentUidNeverInheritsTheScope() {        val rulesDek = RulesStoreKeyManager(context).getOrCreateDek()
        seedRules(legacyRulesFile(), rulesDek)
        assertTrue(LocalDatabaseMigration.migrateForTest(context, UID))

        assertTrue(LocalDatabaseMigration.migrateForTest(context, OTHER_UID))
        assertEquals(
            "the stored scope wins over a different identity (#125 rotation keeps this install's data)",
            scopeOf(UID).uidHash,
            LocalDatabaseMigration.scopeOrNull()?.uidHash,
        )
        assertFalse(File(databaseDir(), scopeOf(OTHER_UID).rulesName).exists())
    }

    @Test
    fun keyFailurePreservesEverySourceAndLeavesTheJournalIncomplete() {
        val rulesDek = RulesStoreKeyManager(context).getOrCreateDek()
        seedRules(legacyRulesFile(), rulesDek)
        // A lost/unusable key alias: the wrapped DEK is gone while the encrypted file remains.
        legacyKeyManager(RulesStoreKeyManager.LEGACY_KEK_ALIAS, RulesStoreKeyManager.LEGACY_PREFS)
            .resetKeyMaterial()

        assertFalse(LocalDatabaseMigration.migrateForTest(context, UID))
        assertNull("a failed migration must not activate a scope", LocalDatabaseMigration.scopeOrNull())
        assertTrue("the source must never be deleted on a key failure", legacyRulesFile().exists())
        assertFalse(File(databaseDir(), scopeOf(UID).rulesName).exists())
    }

    /**
     * Plan §6.8/§6.9.3: deleting one identity erases its scoped stores, the retained pre-scope
     * copies of the same person's data, and the pointers that would let a later account adopt
     * the path. Nothing here outlives the purge.
     */
    @Test
    fun forgetScopeErasesTheScopeTheRetainedCopiesAndThePointers() {
        val rulesDek = RulesStoreKeyManager(context).getOrCreateDek()
        val activityDek = ActivityLogKeyManager(context).getOrCreateKey()
        seedRules(legacyRulesFile(), rulesDek)
        seedActivity(legacyActivityFile(), activityDek)
        seedPlainAccountability(legacyAccountabilityFile())
        assertTrue(LocalDatabaseMigration.migrateForTest(context, UID))
        val scope = scopeOf(UID)
        val scopedRules = File(databaseDir(), scope.rulesName)
        assertTrue(scopedRules.exists())
        // SQLCipher's zero-byte lock file is written next to the store; it must not outlive it.
        File(scopedRules.path + ".lck").writeBytes(ByteArray(0))
        val otherScope = scopeOf(OTHER_UID)
        val otherRules = File(databaseDir(), otherScope.rulesName)
        otherRules.writeText("another identity")

        assertTrue(LocalDatabaseMigration.forgetScope(context, scope.uidHash))

        assertTrue("another identity's stores are not this purge's business", otherRules.exists())
        assertFalse(scopedRules.exists())
        assertFalse(File(scopedRules.path + ".lck").exists())
        assertFalse(File(databaseDir(), scope.activityName).exists())
        assertFalse(File(databaseDir(), scope.accountabilityName).exists())
        assertFalse("the retained copy belongs to the same identity", legacyRulesFile().exists())
        assertFalse(legacyActivityFile().exists())
        assertFalse(legacyAccountabilityFile().exists())
        assertNull(LocalDatabaseMigration.scopeOrNull())
        assertNull(LocalDatabaseMigration.effectiveScopeHash(context))
        assertFalse(
            scopedKeyManager(RulesStoreKeyManager.LEGACY_KEK_ALIAS, RulesStoreKeyManager.LEGACY_PREFS, scope)
                .hasWrappedKey(),
        )
    }

    /**
     * Pre-1.1.8 installs ran the live accountability store on the legacy name (the wipe
     * detector's eager injection built it before the scope decision — X6532 2026-11-10).
     * The first post-fix start must adopt it into the scoped name, exactly once.
     */
    @Test
    fun preFixInstallAdoptsTheLiveLegacyStoreOnce() {
        seedPlainAccountability(legacyAccountabilityFile())
        seedEnrollmentRows(legacyAccountabilityFile(), 1)
        val scoped = File(databaseDir(), scopeOf(UID).accountabilityName)
        assertFalse(scoped.exists())

        assertTrue(LocalDatabaseMigration.adoptPreFixForTest(context, UID))

        assertEquals(1L, plainCount(scoped, "enrollments"))
        assertTrue("the source stays as the rollback copy", legacyAccountabilityFile().exists())
        assertFalse("adoption is one-time", LocalDatabaseMigration.adoptPreFixForTest(context, UID))
    }

    @Test
    fun adoptionNeverOverwritesALiveScopedStore() {
        seedEnrollmentRows(legacyAccountabilityFile(), 1)
        val scoped = File(databaseDir(), scopeOf(UID).accountabilityName)
        seedEnrollmentRows(scoped, 2)

        assertFalse(LocalDatabaseMigration.adoptPreFixForTest(context, UID))

        assertEquals("the store with more enrollments is the live one", 2L, plainCount(scoped, "enrollments"))
        assertTrue(legacyAccountabilityFile().exists())
    }

    /**
     * The pre-scope files are one install-wide copy claimed by the journal scope. Forgetting a
     * different identity must not delete them (or their keys) — on a pre-1.1.8 install they can
     * be the journal scope's only data.
     */
    @Test
    fun forgettingAnotherIdentityLeavesTheJournalScopesRollbackCopies() {
        seedRules(legacyRulesFile(), RulesStoreKeyManager(context).getOrCreateDek())
        seedPlainAccountability(legacyAccountabilityFile())
        seedEnrollmentRows(legacyAccountabilityFile(), 1)
        assertTrue(LocalDatabaseMigration.migrateForTest(context, UID))

        assertTrue(LocalDatabaseMigration.forgetScope(context, scopeOf(OTHER_UID).uidHash))

        assertTrue(legacyRulesFile().exists())
        assertTrue(legacyAccountabilityFile().exists())
        assertTrue(
            legacyKeyManager(RulesStoreKeyManager.LEGACY_KEK_ALIAS, RulesStoreKeyManager.LEGACY_PREFS)
                .hasWrappedKey(),
        )
        assertEquals(1L, plainCount(File(databaseDir(), scopeOf(UID).accountabilityName), "enrollments"))

        assertTrue(LocalDatabaseMigration.forgetScope(context, scopeOf(UID).uidHash))
        assertFalse("the journal scope's purge still erases them", legacyAccountabilityFile().exists())
    }

    @Test
    fun preEncryptionPlaintextRulesSourceIsCopiedVerbatim() {
        AndroidSQLiteDriver().open(legacyRulesFile().absolutePath).use { conn ->
            exec(conn, "CREATE TABLE restriction_rules (rule_id TEXT PRIMARY KEY NOT NULL, blocked_package TEXT NOT NULL)")
            exec(conn, "INSERT INTO restriction_rules VALUES ('r1', 'com.instagram.android')")
        }

        assertTrue(LocalDatabaseMigration.migrateForTest(context, UID))
        val scoped = File(databaseDir(), scopeOf(UID).rulesName)
        assertEquals("the open path still owns the plaintext→encrypted conversion", 1L, plainCount(scoped, "restriction_rules"))
    }

    // --- fixtures ----------------------------------------------------------------------------

    private fun prepareCleanState() {
        LocalDatabaseMigration.resetForTest()
        LocalDatabaseMigration.resetJournalForTest(context)
        recovery.resetAllDataEmulatorOnly()
        // resetAllData covers the pre-split combined DB, not the accountability store.
        listOf("", "-wal", "-shm", "-journal").forEach { File(legacyAccountabilityFile().path + it).delete() }
        listOf(UID, OTHER_UID).map(::scopeOf).forEach { scope ->
            deleteScopedFiles(scope)
            scopedKeyManager(RulesStoreKeyManager.LEGACY_KEK_ALIAS, RulesStoreKeyManager.LEGACY_PREFS, scope).resetKeyMaterial()
            scopedKeyManager(ActivityLogKeyManager.LEGACY_KEK_ALIAS, ActivityLogKeyManager.LEGACY_PREFS, scope).resetKeyMaterial()
        }
    }

    private fun scopeOf(uid: String) = LocalDatabaseMigration.Scope(LocalDatabaseMigration.uidHash(uid))

    private fun databaseDir(): File = context.getDatabasePath("x").parentFile!!

    private fun legacyRulesFile(): File = context.getDatabasePath(AppRulesStoreFactory.DB_NAME)

    private fun legacyActivityFile(): File = context.getDatabasePath(ActivityLogSchema.DB_NAME)

    private fun legacyAccountabilityFile(): File = context.getDatabasePath(AccountabilityNames.LEGACY)

    private fun scopedKeyManager(alias: String, prefs: String, scope: LocalDatabaseMigration.Scope) =
        KeystoreWrappedDekManager(context, alias + scope.keySuffix, prefs + scope.keySuffix)

    private fun legacyKeyManager(alias: String, prefs: String) =
        KeystoreWrappedDekManager(context, alias, prefs)

    private fun deleteScopedFiles(scope: LocalDatabaseMigration.Scope) {
        listOf(scope.rulesName, scope.activityName, scope.accountabilityName).forEach { name ->
            val file = File(databaseDir(), name)
            listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
        }
    }

    /** Legacy keying (DEK as passphrase), WAL mode, a pending outbox row and a schema version. */
    private fun seedRules(file: File, dek: ByteArray) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, dek, null, null)
        try {
            db.rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE restriction_rules (rule_id TEXT PRIMARY KEY NOT NULL, blocked_package TEXT NOT NULL)")
            db.execSQL("CREATE TABLE partner_activity_outbox (event_id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL)")
            db.execSQL("INSERT INTO restriction_rules VALUES ('r1', 'com.instagram.android')")
            db.execSQL("INSERT INTO partner_activity_outbox VALUES ('e1', 'RELAPSE')")
            db.execSQL("PRAGMA user_version=1234")
        } finally {
            db.close()
        }
    }

    private fun seedActivity(file: File, dek: ByteArray) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, dek, null, null)
        try {
            ActivityLogSchema.applyPragmas(db)
            ActivityLogSchema.createSchema(db)
            db.execSQL(
                "INSERT INTO ${ActivityLogSchema.TABLE} " +
                    "(event_id, installation_id, event_type, payload, sync_status, created_at) " +
                    "VALUES ('a1', 'inst-1', 'block', x'00', 'PENDING', 1)",
            )
        } finally {
            db.close()
        }
    }

    private fun seedEnrollmentRows(file: File, count: Int) {
        AndroidSQLiteDriver().open(file.absolutePath).use { conn ->
            exec(conn, "CREATE TABLE IF NOT EXISTS enrollments (pair_id TEXT PRIMARY KEY NOT NULL)")
            repeat(count) { exec(conn, "INSERT OR IGNORE INTO enrollments VALUES ('pair-$it')") }
        }
    }

    private fun seedPlainAccountability(file: File) {
        AndroidSQLiteDriver().open(file.absolutePath).use { conn ->
            exec(conn, "CREATE TABLE chain_event (event_id INTEGER PRIMARY KEY NOT NULL, payload BLOB NOT NULL)")
            exec(
                conn,
                "CREATE TABLE outbox (message_id TEXT NOT NULL PRIMARY KEY, event_id INTEGER NOT NULL, " +
                    "destination_uid TEXT, envelope_bytes BLOB NOT NULL, status TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, last_attempt_at INTEGER, attempt_count INTEGER NOT NULL)",
            )
            exec(conn, "INSERT INTO chain_event VALUES (1, x'aa')")
            exec(conn, "INSERT INTO outbox VALUES ('m1', 1, 'partner-uid', x'bb', 'PENDING', 1, NULL, 0)")
        }
    }

    /** Post-migration the legacy source is raw-keyed (ADR #153), so appends need the raw form. */
    private fun appendRawRow(file: File, dek: ByteArray, ruleId: String) {
        val raw = SqlCipherRawKey.passphraseFor(dek)
        try {
            val db = SQLiteDatabase.openOrCreateDatabase(file, raw, null, null)
            try {
                db.execSQL("INSERT INTO restriction_rules VALUES ('$ruleId', 'com.example.blocked')")
            } finally {
                db.close()
            }
        } finally {
            Arrays.fill(raw, 0.toByte())
        }
    }

    private fun encryptedCount(file: File, dek: ByteArray, table: String): Long =
        withRaw(file, dek) { db ->
            db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use { it.moveToFirst(); it.getLong(0) }
        }

    private fun encryptedValue(file: File, dek: ByteArray, sql: String): String? =
        withRaw(file, dek) { db ->
            db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else null }
        }

    private fun <T> withRaw(file: File, dek: ByteArray, block: (SQLiteDatabase) -> T): T {
        val raw = SqlCipherRawKey.passphraseFor(dek)
        try {
            val db = SQLiteDatabase.openOrCreateDatabase(file, raw, null, null)
            try {
                return block(db)
            } finally {
                db.close()
            }
        } finally {
            Arrays.fill(raw, 0.toByte())
        }
    }

    private fun plainCount(file: File, table: String): Long =
        AndroidSQLiteDriver().open(file.absolutePath).use { conn ->
            conn.prepare("SELECT COUNT(*) FROM \"$table\"").use { it.step(); it.getLong(0) }
        }

    private fun exec(conn: SQLiteConnection, sql: String) {
        conn.prepare(sql).use { it.step() }
    }

    private companion object {
        const val UID = "scope-test-uid"
        const val OTHER_UID = "scope-test-uid-2"
        const val EIGHT_DAYS_MS = 8L * 24 * 60 * 60 * 1000
    }
}
