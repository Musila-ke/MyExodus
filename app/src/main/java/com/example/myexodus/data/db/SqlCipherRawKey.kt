package com.example.myexodus.data.db

import android.util.Log
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteNotADatabaseException

/**
 * Raw-key mode for the app's SQLCipher files (ADR #153). SQLCipher skips PBKDF2 entirely
 * when the passphrase is the hex-literal form `x'<64 hex>'` — the codec checks the
 * passphrase buffer for the x-prefix and uses the 64 hex digits as the key directly. Our
 * DEK is already a random 256-bit key wrapped by Keystore, so the KDF added no security
 * here and cost 5.7 s per connection on the X6532 (cold-start capture 2026-10-15: five
 * keyings, ~20 s to first frame).
 *
 * Keying state lives in the file itself, not a flag, so an interrupted migration
 * self-heals: [ensureRawKeyed] probes with the raw passphrase first (a wrong-key open
 * fails on the header read and cannot modify the file), and only a legacy file takes the
 * one-time [rekeyLegacyToRaw] path. Every array passed in is owned by the caller.
 */
internal object SqlCipherRawKey {

    private const val TAG = "MyExodusSqlcipher"
    private const val HEX_DIGITS = "0123456789abcdef"

    /** 67-byte ASCII `x'<64 lowercase hex>'` passphrase for a raw 256-bit key. */
    fun passphraseFor(dek: ByteArray): ByteArray {
        require(dek.size == 32) { "raw passphrase requires a 256-bit key" }
        val sb = StringBuilder(67)
        sb.append("x'")
        for (b in dek) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0xF])
        }
        sb.append('\'')
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /**
     * True for SQLCipher's wrong-key failure (SQLITE_NOTADB, code 26) — by class, or by
     * message so a wrapped exception (Transsion hubcore interposers load in-process on
     * XOS) still classifies.
     */
    fun isWrongKey(t: Throwable): Boolean =
        t is SQLiteNotADatabaseException ||
            t.message?.contains("file is not a database") == true

    /**
     * Probes [path]'s keying and returns true when a legacy→raw rekey was performed.
     * A missing file is created raw-keyed by the probe itself — first-run files never
     * see PBKDF2 at all.
     */
    fun ensureRawKeyed(path: File, dek: ByteArray, raw: ByteArray): Boolean {
        if (probeOpens(path, raw)) return false
        rekeyLegacyToRaw(path, dek, raw)
        return true
    }

    /** Opens [path] with [key] and reads; false only for a wrong-key failure. */
    private fun probeOpens(path: File, key: ByteArray): Boolean {
        val handle = try {
            SQLiteDatabase.openOrCreateDatabase(path, key, null, null)
        } catch (t: Throwable) {
            if (isWrongKey(t)) return false
            throw t
        }
        try {
            // The read forces key validation — some zetetic builds open lazily and only
            // fail at the first statement.
            handle.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            return true
        } catch (t: Throwable) {
            if (isWrongKey(t)) return false
            throw t
        } finally {
            handle.close()
        }
    }

    private fun rekeyLegacyToRaw(path: File, dek: ByteArray, raw: ByteArray) {
        // A file that fails the legacy key too (corrupt/foreign) throws here and the
        // exception propagates to the store's existing error handling — same class as the
        // direct open would have produced before raw-key mode.
        val legacy = SQLiteDatabase.openOrCreateDatabase(path, dek, null, null)
        try {
            legacy.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
            try {
                // Flush the WAL so the rekey only re-encrypts the main file. Non-WAL files
                // error here, which is harmless.
                legacy.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            } catch (t: Throwable) {
                Log.w(TAG, "rekey ${path.name}: wal_checkpoint skipped: ${t.message}")
            }
            // rawExecSQL, not execSQL: PRAGMA rekey emits a result row, and execSQL throws
            // "Queries can be performed using SQLiteDatabase query or rawQuery methods only"
            // AFTER the rekey has already executed natively — device-captured 2026-10-15, the
            // migration then "failed" into a retry that only worked by luck.
            legacy.rawExecSQL("PRAGMA rekey = \"${String(raw, Charsets.US_ASCII)}\"")
        } finally {
            legacy.close()
        }
        // The rekeyed file must open and read under the raw key before any caller touches it.
        val verify = SQLiteDatabase.openOrCreateDatabase(path, raw, null, null)
        try {
            verify.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        } finally {
            verify.close()
        }
        Log.i(TAG, "rekeyed ${path.name} legacy→raw; PBKDF2 retired (one-time, ADR #153)")
    }
}
