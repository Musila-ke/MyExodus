package com.example.myexodus.data.db

import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import java.io.File
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

/**
 * One-time plaintext→encrypted conversion of the rules database (habit-streaks plan §8
 * Step 0, Decision 16 — deliberately not a Room `Migration`, which cannot change the
 * encryption scheme).
 *
 * Order of operations: checkpoint the plaintext source (folds any WAL into the main file so
 * the `.bak` copy and the row read see one complete database) → copy the source to `.bak` →
 * bring the source up to the current schema ([migrateSource]) → build the encrypted
 * destination with Room at a temp path (correct schema + identity hash) → copy every table's
 * rows inside one transaction on the destination, verifying row counts before commit → hand
 * the temp file to [RecoveryManager] for the atomic swap. Any failure short of the swap
 * leaves the plaintext store usable (the migration runs what every normal open runs anyway);
 * the caller surfaces a pending state and retries on the next launch.
 */
class PlaintextToEncryptedMigration(
    private val keyManager: RulesStoreKeyManager,
    private val recoveryManager: RecoveryManager,
) {

    /** Unencrypted SQLite files start with this magic; SQLCipher 4 encrypts from byte 0. */
    fun isPlaintextDatabase(file: File): Boolean {
        if (!file.exists() || file.length() < SQLITE_MAGIC.size) return false
        return try {
            file.inputStream().use { stream ->
                val head = ByteArray(SQLITE_MAGIC.size)
                var read = 0
                while (read < head.size) {
                    val n = stream.read(head, read, head.size - read)
                    if (n < 0) return false
                    read += n
                }
                head.contentEquals(SQLITE_MAGIC)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read database header", e)
            false
        }
    }

    fun run(buildDestination: (File) -> Unit, migrateSource: () -> Unit = {}): Boolean {
        val source = recoveryManager.rulesDbFile
        val temp = recoveryManager.rulesDbTempFile
        recoveryManager.deleteConversionTemp()
        return try {
            AndroidSQLiteDriver().open(source.absolutePath).use { src ->
                checkpointTruncate(src)
                if (!recoveryManager.backupPlaintextRules()) return false
            }
            // A pre-encryption file can predate the current schema by any number of versions
            // (encryption shipped at v7), and the shared-column copy cannot invent NOT NULL
            // columns added since (v8's day anchors are one). Room migrates a file only when
            // Room opens it, so bring the source current here — after the backup, so a
            // migration defect can never cost the pre-migration rows. Failure defers the
            // conversion; the plaintext open path then surfaces the underlying cause.
            try {
                migrateSource()
            } catch (e: Throwable) {
                Log.e(TAG, "Plaintext source migration failed; conversion deferred", e)
                return false
            }
            AndroidSQLiteDriver().open(source.absolutePath).use { src ->
                // The migration's open/close normally checkpoints; re-assert so the swap's
                // sidecar delete can never drop committed migration writes.
                checkpointTruncate(src)
                buildDestination(temp)
                if (!copyAllTables(src, temp)) {
                    recoveryManager.deleteConversionTemp()
                    return false
                }
            }
            if (!recoveryManager.swapInEncryptedRules()) {
                recoveryManager.deleteConversionTemp()
                return false
            }
            Log.i(TAG, "Plaintext→encrypted conversion complete")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Conversion failed; plaintext database left untouched", e)
            recoveryManager.deleteConversionTemp()
            false
        }
    }

    private fun checkpointTruncate(conn: SQLiteConnection) {
        // TRUNCATE folds the WAL into the main file and empties the sidecar, so the .bak copy
        // below is a single complete file. Our connection is the only one to this database.
        conn.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use { it.step() }
    }

    private fun copyAllTables(src: SQLiteConnection, temp: File): Boolean {
        val destDriver = KeyFetchingSQLCipherDriver { keyManager.getOrCreateDek() }
        destDriver.open(temp.absolutePath).use { dest ->
            // FK enforcement is off for the raw copy: rows already satisfy the constraints
            // (they come from a valid database) and insert order is otherwise not guaranteed.
            dest.prepare("PRAGMA foreign_keys = OFF").use { it.step() }
            dest.prepare("BEGIN IMMEDIATE").use { it.step() }
            var committed = false
            try {
                val tables = tableNames(src)
                if (tables.isEmpty()) return fail("source has no tables")
                for (table in tables) {
                    val destColumns = columnNames(dest, table)
                        ?: return fail("destination is missing table $table")
                    val columns = columnNames(src, table).orEmpty().filter { it in destColumns }
                    if (columns.isEmpty()) return fail("no shared columns for table $table")
                    copyTable(src, dest, table, columns)
                    val expected = count(src, table)
                    val actual = count(dest, table)
                    if (expected != actual) {
                        return fail("row count mismatch for $table: $expected != $actual")
                    }
                    Log.i(TAG, "Conversion verify $table: $expected rows")
                }
                dest.prepare("COMMIT").use { it.step() }
                committed = true
                return true
            } finally {
                if (!committed) {
                    runCatching { dest.prepare("ROLLBACK").use { it.step() } }
                }
            }
        }
    }

    private fun copyTable(
        src: SQLiteConnection,
        dest: SQLiteConnection,
        table: String,
        columns: List<String>,
    ) {
        val names = columns.joinToString(", ") { "\"$it\"" }
        val marks = columns.joinToString(", ") { "?" }
        // OR REPLACE: the destination's Room onCreate seeds singleton rows (policy_version,
        // recovery_policy) that also exist in the source; the source is the user's real state,
        // so its values win. No other collisions are possible — the destination is otherwise empty.
        src.prepare("SELECT $names FROM \"$table\"").use { reader ->
            dest.prepare("INSERT OR REPLACE INTO \"$table\" ($names) VALUES ($marks)").use { writer ->
                while (reader.step()) {
                    columns.indices.forEach { bind(reader, writer, it) }
                    writer.step()
                    // Reuse across rows requires reset() per the androidx driver contract: the
                    // zetetic statement executes once per cursor and step() only advances it
                    // afterwards, so without reset only the first row of each table was copied
                    // (device-verified 2026-09-14: "row count mismatch: 2 != 1").
                    writer.reset()
                }
            }
        }
    }

    /**
     * Reader and writer have different index bases in the androidx.sqlite driver API: reads are
     * 0-based (the driver delegates to a framework cursor — the AndroidSQLiteDriver throws
     * SQLITE_RANGE "column index out of range" for index == columnCount), binds are 1-based.
     */
    private fun bind(reader: SQLiteStatement, writer: SQLiteStatement, readIndex: Int) {
        val writeIndex = readIndex + 1
        if (reader.isNull(readIndex)) {
            writer.bindNull(writeIndex)
            return
        }
        when (reader.getColumnType(readIndex)) {
            TYPE_INTEGER -> writer.bindLong(writeIndex, reader.getLong(readIndex))
            TYPE_FLOAT -> writer.bindDouble(writeIndex, reader.getDouble(readIndex))
            TYPE_TEXT -> writer.bindText(writeIndex, reader.getText(readIndex))
            TYPE_BLOB -> writer.bindBlob(writeIndex, reader.getBlob(readIndex))
            else -> error(
                "Unexpected column type ${reader.getColumnType(readIndex)} at $readIndex",
            )
        }
    }

    private fun tableNames(conn: SQLiteConnection): List<String> =
        stringColumn(
            conn,
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
        ).filterNot { it.startsWith("sqlite_") || it in DESTINATION_OWNED_TABLES }

    private fun columnNames(conn: SQLiteConnection, table: String): List<String>? {
        if (!tableExists(conn, table)) return null
        return stringColumn(conn, "PRAGMA table_info(\"$table\")", nameIndex = 1)
    }

    private fun stringColumn(conn: SQLiteConnection, sql: String, nameIndex: Int = 0): List<String> {
        val out = mutableListOf<String>()
        conn.prepare(sql).use { stmt ->
            while (stmt.step()) stmt.getText(nameIndex)?.let(out::add)
        }
        return out
    }

    private fun tableExists(conn: SQLiteConnection, table: String): Boolean =
        conn.prepare("SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?").use { stmt ->
            stmt.bindText(1, table)
            stmt.step()
            stmt.getLong(0) > 0
        }

    private fun count(conn: SQLiteConnection, table: String): Long =
        conn.prepare("SELECT count(*) FROM \"$table\"").use { it.step(); it.getLong(0) }

    private fun fail(reason: String): Boolean {
        Log.e(TAG, "Conversion aborted: $reason")
        return false
    }

    private companion object {
        const val TAG = "MyExodusMigration"
        const val TYPE_INTEGER = 1
        const val TYPE_FLOAT = 2
        const val TYPE_TEXT = 3
        const val TYPE_BLOB = 4

        /** "SQLite format 3\0". */
        val SQLITE_MAGIC = byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
        )

        /**
         * Tables the destination owns itself: Room's identity table (its hash must stay the
         * destination's), the auto-created sequence table (row inserts with explicit ids
         * update it), and the legacy locale table (absent from Room 3 schemas).
         */
        val DESTINATION_OWNED_TABLES = setOf("room_master_table", "android_metadata")
    }
}
