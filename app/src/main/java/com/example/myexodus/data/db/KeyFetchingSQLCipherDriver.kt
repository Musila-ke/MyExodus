package com.example.myexodus.data.db

import android.os.SystemClock
import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import java.io.File
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
 *
 * ADR #153: the passphrase passed to SQLCipher is the raw-key form `x'<hex>'` (see
 * [SqlCipherRawKey]), which skips PBKDF2 — 5.7 s per keying on the X6532 before the fix.
 * [open] first ensures the file carries raw keying; legacy files take a one-time rekey.
 */
class KeyFetchingSQLCipherDriver(
    private val dekProvider: () -> ByteArray,
) : SQLiteDriver {

    override fun open(fileName: String): SQLiteConnection {
        System.loadLibrary("sqlcipher")
        val t0 = SystemClock.uptimeMillis()
        val dek = dekProvider()
        val t1 = SystemClock.uptimeMillis()
        val raw = SqlCipherRawKey.passphraseFor(dek)
        val migrated = try {
            SqlCipherRawKey.ensureRawKeyed(File(fileName), dek, raw)
        } catch (t: Throwable) {
            Arrays.fill(dek, 0.toByte())
            Arrays.fill(raw, 0.toByte())
            throw t
        }
        Arrays.fill(dek, 0.toByte())
        val t2 = SystemClock.uptimeMillis()
        val connection = try {
            SQLCipherDriver(raw, null, null).open(fileName)
        } catch (t: Throwable) {
            Arrays.fill(raw, 0.toByte())
            throw t
        }
        // One line per connection open (ADR #153): the file, the opening thread, and the
        // keystore-unwrap / raw-key-probe / SQLCipher-keying split. Cold-start attribution
        // for "SQLiteConnection: Database keying operation returned" events reads from these.
        Log.i("MyExodusSqlcipher", "open ${File(fileName).name} thread=${Thread.currentThread().name} " +
            "dek=${t1 - t0}ms probe=${t2 - t1}ms key=${SystemClock.uptimeMillis() - t2}ms" +
            if (migrated) " rekeyed=legacy→raw" else "")
        return ZeroingConnection(connection, raw)
    }

    /** Mirrors SQLCipherDriver's own answer; the connection pool is what the native driver expects. */
    override val hasConnectionPool: Boolean get() = true

    /** Holds the raw passphrase for the database's lifetime and zeroes it once it closes. */
    private class ZeroingConnection(
        private val delegate: SQLiteConnection,
        private val passphrase: ByteArray,
    ) : SQLiteConnection by delegate {
        override fun close() {
            try {
                delegate.close()
            } finally {
                Arrays.fill(passphrase, 0.toByte())
            }
        }
    }
}
