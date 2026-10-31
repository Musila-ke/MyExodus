package com.example.myexodus.data.db

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import android.util.Log
import androidx.core.content.edit
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Arrays
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * Account plan §7 step 7 / §8.1: moves the three live databases from the fixed legacy names to
 * paths derived from a stable hash of the owning UID, before any store opens. The source files
 * are copied (never moved) and kept as a rollback copy for seven days.
 *
 * The journal's single completion marker is [KEY_SCOPE_HASH]: it is written only after every
 * present source was copied and verified, so its presence means the operation is done for good.
 * A failed or crashed attempt leaves it absent, and the next launch deletes the disposable
 * target copies and recopies from the intact legacy sources. A target that disappears later
 * (user reset, corruption rename) is therefore never "re-migrated" from the retained source —
 * that would resurrect data the user already destroyed.
 *
 * The scope hash is stored once and reused, so an ADR #125 guest identity rotation keeps this
 * install's local data. A genuinely different account must never inherit it; the explicit
 * account-transition flow (Release 1 steps 9-11) owns that switch and must call into this class
 * when it is built.
 */
internal object LocalDatabaseMigration {

    internal data class Scope(val uidHash: String) {
        val rulesName: String get() = "myexodus_rules_$uidHash.db"
        val activityName: String get() = "myexodus_activity_$uidHash.db"
        val accountabilityName: String get() = "myexodus_accountability_$uidHash.db"
        val keySuffix: String get() = "_$uidHash"
    }

    private const val TAG = "MyExodusDbScope"
    private const val PREFS = "local_db_migration"
    private const val KEY_SCOPE_HASH = "scope_uid_hash"
    /** Plan §7 step 20: where the current identity's stores live. Written by a switch; the
     *  journal marker above describes the install-wide migration, not the active identity. */
    private const val KEY_ACTIVE_HASH = "active_scope_uid_hash"
    private const val KEY_LAST_UID_HASH = "last_uid_hash"
    /** ADR #239: the orphaned scope on offer, and which identity it was promised to (null: any). */
    private const val KEY_RECOVERY_HASH = "recovery_scope_hash"
    private const val KEY_RECOVERY_UID_HASH = "recovery_uid_hash"
    private const val KEY_OP_ID = "operation_id"
    private const val KEY_ACTIVATED_AT = "activated_at"
    private const val KEY_CLEANED = "legacy_cleaned"
    private const val ROLLBACK_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
    private val SQLITE_MAGIC = "SQLite format 3".toByteArray(Charsets.ISO_8859_1) + 0.toByte()

    private val lock = Any()

    @Volatile private var activeScope: Scope? = null

    /**
     * Runs the migration once per process, before the first store opens. Safe to call again.
     * The first call after an update copies three files; every later call is a prefs read.
     */
    fun start(context: Context) {
        synchronized(lock) {
            if (activeScope == null) {
                if (!userUnlocked(context)) return // CE prefs and the Keystore DEKs are unreachable
                val prefs = prefs(context)
                val storedHash = prefs.getString(KEY_SCOPE_HASH, null)
                val activeHash = prefs.getString(KEY_ACTIVE_HASH, null)
                val currentHash =
                    runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()?.let(::uidHash)
                // The switch pointer wins: after an identity change the install journal still
                // records the migration's original scope. An install that never migrated (the
                // user started signed out) adopts the live identity's scope on this launch.
                val hash = activeHash ?: storedHash ?: currentHash ?: return // no identity yet: legacy paths
                val scope = Scope(hash)
                if (storedHash == null) {
                    if (!migrateAll(context, scope, prefs, currentHash)) {
                        Log.w(TAG, "scope migration incomplete; legacy paths stay active this launch")
                        return
                    }
                } else if (currentHash != null && currentHash != hash &&
                    prefs.getString(KEY_LAST_UID_HASH, null) != currentHash
                ) {
                    Log.w(
                        TAG,
                        "current identity differs from the active scope; retained until the " +
                            "session manager owns the switch",
                    )
                    prefs.edit(commit = true) { putString(KEY_LAST_UID_HASH, currentHash) }
                }
                activeScope = scope
                Log.i(TAG, "local databases scoped to uid hash ${hash.take(8)}…")
            }
            cleanupExpired(context)
        }
    }

    fun scopeOrNull(): Scope? = activeScope

    fun rulesDbName(context: Context): String =
        scopeOrNull()?.rulesName ?: AppRulesStoreFactory.DB_NAME

    fun activityDbName(context: Context): String =
        scopeOrNull()?.activityName ?: ActivityLogSchema.DB_NAME

    fun accountabilityDbName(context: Context): String =
        scopeOrNull()?.accountabilityName ?: AccountabilityNames.LEGACY

    fun keySuffix(): String = scopeOrNull()?.keySuffix ?: ""

    /** The scope a switch has pointed the process at, or null if none ever ran. */
    fun activeScopeHash(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_HASH, null)

    /** The scope a store opened right now would use — the switch pointer wins over the journal. */
    fun effectiveScopeHash(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_HASH, null)
            ?: prefs(context).getString(KEY_SCOPE_HASH, null)

    /** Domain-separated hash for a uid, so the session layer can compare pointers cheaply. */
    fun scopeHashOf(uid: String): String = uidHash(uid)

    /**
     * ADR #239: remembers a scope this install no longer serves but must not lose. [forUid]
     * null means the next persistent account may take it (a guest signed out — that anonymous
     * UID can never authenticate again).
     */
    fun recordRecoveryOffer(context: Context, retainedHash: String, forUid: String?) {
        synchronized(lock) {
            prefs(context).edit(commit = true) {
                putString(KEY_RECOVERY_HASH, retainedHash)
                if (forUid == null) remove(KEY_RECOVERY_UID_HASH) else putString(KEY_RECOVERY_UID_HASH, uidHash(forUid))
            }
        }
    }

    /** The retained scope still on offer for [uid], or null when none is (or it was taken). */
    fun recoveryOffer(context: Context, uid: String?): String? = synchronized(lock) {
        val prefs = prefs(context)
        val hash = prefs.getString(KEY_RECOVERY_HASH, null) ?: return null
        if (hash == effectiveScopeHash(context)) return null
        val boundTo = prefs.getString(KEY_RECOVERY_UID_HASH, null)
        if (boundTo != null && (uid == null || boundTo != uidHash(uid))) return null
        if (!File(databaseDir(context), Scope(hash).rulesName).exists()) return null
        hash
    }

    /** ADR #240: the offer only while an account owns it — an unbound copy keeps the card. */
    fun boundRecoveryOffer(context: Context, uid: String?): String? = synchronized(lock) {
        if (prefs(context).getString(KEY_RECOVERY_UID_HASH, null) == null) return null
        recoveryOffer(context, uid)
    }

    /** Retires the offer; with [onlyIfHash], a newer offer recorded meanwhile is left alone. */
    fun clearRecoveryOffer(context: Context, onlyIfHash: String? = null) {
        synchronized(lock) {
            val prefs = prefs(context)
            if (onlyIfHash != null && prefs.getString(KEY_RECOVERY_HASH, null) != onlyIfHash) return
            prefs.edit(commit = true) {
                remove(KEY_RECOVERY_HASH)
                remove(KEY_RECOVERY_UID_HASH)
            }
        }
    }

    private fun databaseDir(context: Context): File? = context.getDatabasePath("x").parentFile

    /**
     * Plan §7 step 20: points the next process start at [uid]'s stores. Never deletes anything —
     * the previous scope's files stay on disk for the R1.11 import/expiry sweep. The caller
     * restarts the process so no open store keeps the previous UID's files or keys.
     */
    fun switchScopeTo(context: Context, uid: String) {
        synchronized(lock) {
            val hash = uidHash(uid)
            prefs(context).edit(commit = true) { putString(KEY_ACTIVE_HASH, hash) }
            Log.i(TAG, "active database scope switched to uid hash ${hash.take(8)}…")
        }
    }

    /** Domain-separated, filename-safe: 32 hex chars of SHA-256 over the UID. */
    internal fun uidHash(uid: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("myexodus-db-scope-v1 $uid".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    // --- migration ---------------------------------------------------------------------------

    private data class StoreSpec(val name: String, val source: File, val target: File)

    private data class SourceMeta(val sha256: String, val userVersion: Long, val counts: Map<String, Long>)

    private fun migrateAll(
        context: Context,
        scope: Scope,
        prefs: SharedPreferences,
        currentUidHash: String?,
    ): Boolean {
        // First SQLCipher touch in the process: the stores that normally load the library
        // have not opened yet (X6532 2026-10-29 — UnsatisfiedLinkError at the raw-key probe).
        System.loadLibrary("sqlcipher")
        val dbDir = context.getDatabasePath("x").parentFile ?: return false
        val specs = listOf(
            StoreSpec("rules", File(dbDir, AppRulesStoreFactory.DB_NAME), File(dbDir, scope.rulesName)),
            StoreSpec("activity", File(dbDir, ActivityLogSchema.DB_NAME), File(dbDir, scope.activityName)),
            StoreSpec("accountability", File(dbDir, AccountabilityNames.LEGACY), File(dbDir, scope.accountabilityName)),
        )
        // Copies from a previous attempt are disposable; the legacy sources are the authority.
        specs.forEach { deleteDatabaseFile(it.target) }
        val copied = mutableListOf<StoreSpec>()
        return try {
            val rulesKey = ScopedKey(
                legacy = KeystoreWrappedDekManager(context, RulesStoreKeyManager.LEGACY_KEK_ALIAS, RulesStoreKeyManager.LEGACY_PREFS),
                scoped = KeystoreWrappedDekManager(context, RulesStoreKeyManager.LEGACY_KEK_ALIAS + scope.keySuffix, RulesStoreKeyManager.LEGACY_PREFS + scope.keySuffix),
            )
            val activityKey = ScopedKey(
                legacy = KeystoreWrappedDekManager(context, ActivityLogKeyManager.LEGACY_KEK_ALIAS, ActivityLogKeyManager.LEGACY_PREFS),
                scoped = KeystoreWrappedDekManager(context, ActivityLogKeyManager.LEGACY_KEK_ALIAS + scope.keySuffix, ActivityLogKeyManager.LEGACY_PREFS + scope.keySuffix),
            )
            for (spec in specs) {
                if (!spec.source.exists()) continue
                when (spec.name) {
                    "rules" -> copyStore(spec, rulesKey)
                    "activity" -> copyStore(spec, activityKey)
                    else -> copyPlain(spec)
                }
                copied += spec
            }
            prefs.edit(commit = true) {
                putString(KEY_SCOPE_HASH, scope.uidHash)
                putString(KEY_LAST_UID_HASH, currentUidHash)
                putString(KEY_OP_ID, prefs.getString(KEY_OP_ID, null) ?: UUID.randomUUID().toString())
                putLong(KEY_ACTIVATED_AT, System.currentTimeMillis())
                putBoolean(KEY_CLEANED, false)
            }
            true
        } catch (e: Throwable) {
            Log.e(TAG, "scope migration failed; legacy sources preserved", e)
            copied.forEach { deleteDatabaseFile(it.target) }
            false
        }
    }

    /** A plaintext store (rules conversion still pending) is copied as-is; the open path converts it. */
    private fun copyStore(spec: StoreSpec, keys: ScopedKey) {
        if (isPlaintext(spec.source)) copyPlain(spec) else copyEncrypted(spec, keys)
    }

    /**
     * Rules and activity stores are SQLCipher files. The copy is made raw-keyed under the same
     * DEK, which is re-wrapped under a UID-scoped Keystore alias — the plan's "different UID,
     * different key" rule without rekeying the payload.
     */
    private fun copyEncrypted(spec: StoreSpec, keys: ScopedKey) {
        val dek = keys.requireScopedDek()
        val raw = SqlCipherRawKey.passphraseFor(dek)
        try {
            SqlCipherRawKey.ensureRawKeyed(spec.source, dek, raw)
            val meta = sourceMeta(spec.source, raw)
            copyWithSidecars(spec)
            if (sha256(spec.target) != meta.sha256) throw IOException("${spec.name}: copy checksum mismatch")
            verifySqlCipher(spec, raw, meta)
        } finally {
            Arrays.fill(raw, 0.toByte())
            Arrays.fill(dek, 0.toByte())
        }
    }

    /** The accountability store is plain-FBE Room (no SQLCipher key), unlike the other two. */
    private fun copyPlain(spec: StoreSpec) {
        val meta = try {
            AndroidSQLiteDriver().open(spec.source.absolutePath).use { conn ->
                checkpoint(conn)
                sourceMeta(conn)
            }
        } catch (e: Throwable) {
            throw IOException("${spec.name}: source unreadable", e)
        }
        copyWithSidecars(spec)
        if (sha256(spec.target) != meta.sha256) throw IOException("${spec.name}: copy checksum mismatch")
        AndroidSQLiteDriver().open(spec.target.absolutePath).use { conn -> verify(conn, spec.name, meta) }
    }

    private fun verifySqlCipher(spec: StoreSpec, raw: ByteArray, meta: SourceMeta) {
        val db = try {
            SQLiteDatabase.openOrCreateDatabase(spec.target, raw, null, null)
        } catch (e: Throwable) {
            throw IOException("${spec.name}: scoped key does not open the copy", e)
        }
        try {
            val version = queryLong(db, "PRAGMA user_version")
            if (version != meta.userVersion) {
                throw IOException("${spec.name}: schema version ${meta.userVersion} became $version")
            }
            val integrity = queryText(db, "PRAGMA integrity_check")
            if (integrity != "ok") throw IOException("${spec.name}: integrity_check=$integrity")
            if (hasRows(db, "PRAGMA foreign_key_check")) {
                throw IOException("${spec.name}: foreign_key_check reported violations")
            }
            val counts = tableCounts(db)
            if (counts != meta.counts) {
                throw IOException("${spec.name}: row counts differ (source=${meta.counts}, copy=$counts)")
            }
        } finally {
            db.close()
        }
    }

    private fun verify(conn: SQLiteConnection, name: String, meta: SourceMeta) {
        val version = conn.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }
        if (version != meta.userVersion) {
            throw IOException("$name: schema version ${meta.userVersion} became $version")
        }
        val integrity = conn.prepare("PRAGMA integrity_check").use {
            it.step()
            it.getText(0)
        }
        if (integrity != "ok") throw IOException("$name: integrity_check=$integrity")
        val fk = conn.prepare("PRAGMA foreign_key_check").use { it.step() }
        if (fk) throw IOException("$name: foreign_key_check reported violations")
        if (tableCounts(conn) != meta.counts) {
            throw IOException("$name: row counts differ (source=${meta.counts}, copy=${tableCounts(conn)})")
        }
    }

    private fun sourceMeta(file: File, raw: ByteArray): SourceMeta {
        val db = try {
            SQLiteDatabase.openOrCreateDatabase(file, raw, null, null)
        } catch (e: Throwable) {
            throw IOException("source will not open with its own key", e)
        }
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            return SourceMeta(sha256(file), queryLong(db, "PRAGMA user_version"), tableCounts(db))
        } finally {
            db.close()
        }
    }

    private fun sourceMeta(conn: SQLiteConnection): SourceMeta {
        val path = conn.prepare("PRAGMA database_list").use { stmt ->
            stmt.step()
            stmt.getText(2)
        } ?: throw IOException("cannot resolve source path")
        return SourceMeta(
            sha256(File(path)),
            conn.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) },
            tableCounts(conn),
        )
    }

    private fun checkpoint(conn: SQLiteConnection) {
        conn.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { it.step() }
    }

    private fun tableCounts(db: SQLiteDatabase): Map<String, Long> =
        queryTables(db).associateWith { table ->
            db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use { it.moveToFirst(); it.getLong(0) }
        }

    private fun tableCounts(conn: SQLiteConnection): Map<String, Long> {
        val tables = mutableListOf<String>()
        conn.prepare(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        ).use { stmt ->
            while (stmt.step()) stmt.getText(0)?.let { tables += it }
        }
        return tables.associateWith { table ->
            conn.prepare("SELECT COUNT(*) FROM \"$table\"").use { it.step(); it.getLong(0) }
        }
    }

    private fun queryTables(db: SQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            null,
        ).use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
        return tables
    }

    private fun queryLong(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { it.moveToFirst(); it.getLong(0) }

    private fun queryText(db: SQLiteDatabase, sql: String): String? =
        db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun hasRows(db: SQLiteDatabase, sql: String): Boolean =
        db.rawQuery(sql, null).use { it.moveToFirst() }

    // --- files -------------------------------------------------------------------------------

    private fun copyWithSidecars(spec: StoreSpec) {
        val tmp = File(spec.target.path + ".tmp")
        tmp.delete()
        try {
            copyFile(spec.source, tmp)
            // A TRUNCATE checkpoint normally leaves no WAL; if the platform kept one, it carries
            // committed rows and must travel with the copy. -shm is rebuilt by SQLite on open.
            listOf("-wal", "-journal").forEach { suffix ->
                val sidecar = File(spec.source.path + suffix)
                if (sidecar.exists() && sidecar.length() > 0) copyFile(sidecar, File(tmp.path + suffix))
            }
            if (!tmp.renameTo(spec.target)) throw IOException("rename ${tmp.name} -> ${spec.target.name} failed")
        } catch (e: Throwable) {
            tmp.delete()
            listOf("-wal", "-journal").forEach { File(tmp.path + it).delete() }
            throw e
        }
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun deleteDatabaseFile(file: File): Boolean =
        listOf("", "-wal", "-shm", "-journal", ".lck").map { suffix ->
            val target = File(file.path + suffix)
            !target.exists() || target.delete()
        }.all { it }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isPlaintext(file: File): Boolean {
        if (!file.exists() || file.length() < SQLITE_MAGIC.size) return false
        val head = ByteArray(SQLITE_MAGIC.size)
        val read = file.inputStream().use { it.read(head) }
        return read == head.size && head.contentEquals(SQLITE_MAGIC)
    }

    private fun userUnlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java).isUserUnlocked

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- rollback window / reset -------------------------------------------------------------

    /**
     * User-confirmed wipe inside the seven-day window: [RecoveryManager] deletes the scoped
     * files, and the retained pre-scope copies and keys go here too. Only meaningful once a
     * scope is active — without one the legacy names ARE the live stores.
     */
    fun discardRetainedSources(context: Context) {
        synchronized(lock) {
            if (activeScope == null) return
            val dbDir = context.getDatabasePath("x").parentFile ?: return
            legacySources(dbDir).forEach { deleteDatabaseFile(it) }
            resetLegacyKeys(context)
            prefs(context).edit(commit = true) { putBoolean(KEY_CLEANED, true) }
            Log.i(TAG, "user reset: retained pre-scope copies and keys removed")
        }
    }

    /**
     * Account plan §6.8/§6.9.3: erases one identity's local stores. The retained pre-scope
     * copies hold the same person's data, so they go too — and only once every file is gone is
     * the journal marker cleared, which keeps a failed delete from re-copying them later.
     * Returns true when nothing is left to delete.
     */
    fun forgetScope(context: Context, uidHashValue: String): Boolean = synchronized(lock) {
        val scope = Scope(uidHashValue)
        val dbDir = context.getDatabasePath("x").parentFile
        var deleted = true
        if (dbDir != null) {
            listOf(scope.rulesName, scope.activityName, scope.accountabilityName).forEach {
                if (!deleteDatabaseFile(File(dbDir, it))) deleted = false
            }
            legacySources(dbDir).forEach { if (!deleteDatabaseFile(it)) deleted = false }
        }
        runCatching {
            KeystoreWrappedDekManager(
                context,
                RulesStoreKeyManager.LEGACY_KEK_ALIAS + "_$uidHashValue",
                RulesStoreKeyManager.LEGACY_PREFS + "_$uidHashValue",
            ).deleteKeyMaterial()
            KeystoreWrappedDekManager(
                context,
                ActivityLogKeyManager.LEGACY_KEK_ALIAS + "_$uidHashValue",
                ActivityLogKeyManager.LEGACY_PREFS + "_$uidHashValue",
            ).deleteKeyMaterial()
        }.onFailure { Log.w(TAG, "scoped key deletion failed", it) }
        if (deleted) {
            resetLegacyKeys(context)
            val prefs = prefs(context)
            val scopePointsHere = prefs.getString(KEY_SCOPE_HASH, null) == uidHashValue
            prefs.edit(commit = true) {
                if (scopePointsHere) {
                    remove(KEY_SCOPE_HASH)
                    remove(KEY_ACTIVATED_AT)
                }
                if (prefs.getString(KEY_ACTIVE_HASH, null) == uidHashValue) remove(KEY_ACTIVE_HASH)
                if (prefs.getString(KEY_LAST_UID_HASH, null) == uidHashValue) remove(KEY_LAST_UID_HASH)
                if (prefs.getString(KEY_RECOVERY_HASH, null) == uidHashValue) {
                    remove(KEY_RECOVERY_HASH)
                    remove(KEY_RECOVERY_UID_HASH)
                }
                if (prefs.getString(KEY_RECOVERY_UID_HASH, null) == uidHashValue) {
                    remove(KEY_RECOVERY_UID_HASH)
                }
                putBoolean(KEY_CLEANED, true)
            }
            if (activeScope?.uidHash == uidHashValue) activeScope = null
        }
        Log.i(TAG, "local data for uid hash ${uidHashValue.take(8)}… erased=$deleted")
        deleted
    }

    /** The plan's bounded rollback copy: legacy files and their keys go after seven days. */
    private fun cleanupExpired(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_CLEANED, false)) return
        if (prefs.getString(KEY_SCOPE_HASH, null) == null) return
        val activatedAt = prefs.getLong(KEY_ACTIVATED_AT, 0)
        if (activatedAt == 0L || nowMs < activatedAt + ROLLBACK_WINDOW_MS) return
        val dbDir = context.getDatabasePath("x").parentFile ?: return
        legacySources(dbDir).forEach { deleteDatabaseFile(it) }
        resetLegacyKeys(context)
        prefs.edit(commit = true) { putBoolean(KEY_CLEANED, true) }
        Log.i(TAG, "rollback window over; legacy copies and keys removed")
    }

    private fun legacySources(dbDir: File): List<File> = listOf(
        File(dbDir, AppRulesStoreFactory.DB_NAME),
        File(dbDir, ActivityLogSchema.DB_NAME),
        File(dbDir, AccountabilityNames.LEGACY),
    )

    private fun resetLegacyKeys(context: Context) {
        runCatching {
            KeystoreWrappedDekManager(context, RulesStoreKeyManager.LEGACY_KEK_ALIAS, RulesStoreKeyManager.LEGACY_PREFS).resetKeyMaterial()
            KeystoreWrappedDekManager(context, ActivityLogKeyManager.LEGACY_KEK_ALIAS, ActivityLogKeyManager.LEGACY_PREFS).resetKeyMaterial()
        }.onFailure { Log.w(TAG, "legacy key reset failed", it) }
    }

    // --- test seams --------------------------------------------------------------------------

    /** Same stored-hash-wins rule as [start], without the Firebase/lock-state preconditions. */
    internal fun migrateForTest(context: Context, uid: String): Boolean = synchronized(lock) {
        val prefs = prefs(context)
        val desiredHash = uidHash(uid)
        val storedHash = prefs.getString(KEY_SCOPE_HASH, null)
        val scope = Scope(storedHash ?: desiredHash)
        val ok = storedHash != null ||
            runCatching { migrateAll(context, scope, prefs, desiredHash) }.getOrDefault(false)
        if (ok) activeScope = scope
        ok
    }

    /**
     * Forgets the completion marker so a test can exercise the crash-resume redo path, and the
     * scope pointers with it: a live install on the same device may have left a switched-account
     * pointer in these prefs (retainedScope/recoveryOffer read it), which is not this test's state.
     */
    internal fun resetJournalForTest(context: Context) {
        synchronized(lock) {
            prefs(context).edit(commit = true) {
                remove(KEY_SCOPE_HASH)
                remove(KEY_ACTIVATED_AT)
                remove(KEY_ACTIVE_HASH)
                remove(KEY_LAST_UID_HASH)
                putBoolean(KEY_CLEANED, false)
            }
            activeScope = null
        }
    }

    internal fun cleanupForTest(context: Context, nowMs: Long) = synchronized(lock) {
        cleanupExpired(context, nowMs)
    }

    internal fun resetForTest() {
        synchronized(lock) {
            activeScope = null
        }
    }

    private class ScopedKey(
        private val legacy: KeystoreWrappedDekManager,
        private val scoped: KeystoreWrappedDekManager,
    ) {
        /**
         * Returns the DEK the copy must be keyed with. Never generates a replacement for a lost
         * legacy key: a database whose key is gone is preserved for recovery, not reset here.
         */
        fun requireScopedDek(): ByteArray {
            if (!legacy.hasWrappedKey()) {
                throw IOException("legacy key material missing for an encrypted store")
            }
            val dek = legacy.getOrCreateKey()
            try {
                if (!scoped.hasWrappedKey()) scoped.adoptKey(dek)
                val adopted = scoped.getOrCreateKey()
                if (!adopted.contentEquals(dek)) {
                    Arrays.fill(adopted, 0.toByte())
                    throw IOException("scoped key does not wrap the legacy DEK")
                }
                Arrays.fill(adopted, 0.toByte())
                return dek.copyOf()
            } finally {
                Arrays.fill(dek, 0.toByte())
            }
        }
    }
}

/** Mirrors `AccountabilityDatabase.DB_NAME` without importing the accountability domain package. */
internal object AccountabilityNames {
    const val LEGACY = "myexodus_accountability.db"
}
