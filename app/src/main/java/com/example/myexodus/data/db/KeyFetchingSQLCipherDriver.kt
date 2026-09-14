package com.example.myexodus.data.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import java.util.Arrays
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

/**
 * [SQLiteDriver] over SQLCipher whose passphrase is fetched fresh on every connection open.
 *
 * The stock `SQLCipherDriver` holds the passphrase array by reference and passes it to the
 * zetetic `SQLiteDatabase`, which keeps it in its configuration and re-reads it every time its
 * internal pool opens a native connection — WAL enablement (Room's journal-mode configuration)
 * opens exactly such a connection *after* [open] has returned. Device-verified 2026-09-14:
 * zeroing the array on return keyed that connection with zeros → SQLiteNotADatabaseException
 * "file is not a database (code 26)", deterministically, on both the emulator and the X6532.
 * The same applies to the plan §8 Step 1 sketch of clearing the DEK in `onOpen()`: pool opens
 * keep happening for the database's whole lifetime. The raw key must therefore stay valid as
 * long as the database is open; [open] wraps the connection and zeroes it on [SQLiteConnection.close],
 * which closes the underlying database.
 *
 * Each Room-driven open still requests the DEK fresh, so a reset (key material dropped) is
 * picked up on the next connection — the connection pool has a single long-lived connection.
 */
class KeyFetchingSQLCipherDriver(
    private val dekProvider: () -> ByteArray,
) : SQLiteDriver {

    override fun open(fileName: String): SQLiteConnection {
        System.loadLibrary("sqlcipher")
        val dek = dekProvider()
        val connection = try {
            SQLCipherDriver(dek, null, null).open(fileName)
        } catch (t: Throwable) {
            Arrays.fill(dek, 0.toByte())
            throw t
        }
        return ZeroingConnection(connection, dek)
    }

    /** Mirrors SQLCipherDriver's own answer; the connection pool is what the native driver expects. */
    override val hasConnectionPool: Boolean get() = true

    /** Holds the open key for the database's lifetime and zeroes it once the database closes. */
    private class ZeroingConnection(
        private val delegate: SQLiteConnection,
        private val dek: ByteArray,
    ) : SQLiteConnection by delegate {
        override fun close() {
            try {
                delegate.close()
            } finally {
                Arrays.fill(dek, 0.toByte())
            }
        }
    }
}
