package com.example.myexodus.data.db

import android.app.Application
import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Conversion file-state machine (habit-streaks plan §8 Step 0) without SQLCipher: header
 * detection, the backup/swap/restore file operations, two-launch backup confirmation, and the
 * non-destructive pending contract on failure. The stubbed destination build plus Robolectric's
 * lack of the SQLCipher native library make every conversion attempt fail *after* the backup
 * and *before* the swap — which is precisely the mid-conversion failure the plaintext store
 * must survive. The encrypted round trip itself is covered by RulesStoreConversionInstrumentedTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaintextConversionFileOpsTest {

    private lateinit var context: Context
    private lateinit var healthStore: DatabaseHealthStore
    private lateinit var recovery: RecoveryManager
    private lateinit var encryption: RulesStoreEncryption

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        healthStore = DatabaseHealthStore()
        recovery = RecoveryManager(
            context, FakeKeyManager(), ActivityLogKeyManager(context), RulesStoreKeyManager(context), healthStore,
        )
        encryption = RulesStoreEncryption(RulesStoreKeyManager(context), recovery)
        recovery.resetAllData()
    }

    @After
    fun tearDown() {
        recovery.resetAllData()
    }

    @Test
    fun isPlaintextDatabase_detectsMagicNotCiphertext() {
        val migration = migration()
        val main = recovery.rulesDbFile

        writeFile(main, PLAINTEXT_HEADER + ByteArray(64) { 7 })
        assertTrue(migration.isPlaintextDatabase(main))

        writeFile(main, ByteArray(64) { 0xAB.toByte() }) // SQLCipher output is headerless
        assertFalse(migration.isPlaintextDatabase(main))

        writeFile(main, ByteArray(8)) // truncated: not a database
        assertFalse(migration.isPlaintextDatabase(main))

        main.delete()
        assertFalse(migration.isPlaintextDatabase(main))
    }

    @Test
    fun prepareForOpen_freshInstall_isEncryptedWithoutArtifacts() {
        var built = false
        val outcome = encryption.prepareForOpen { built = true }

        assertEquals(ConversionOutcome.Encrypted, outcome)
        assertFalse("fresh install must skip the conversion entirely", built)
        assertFalse(recovery.hasConversionBackup())
        assertFalse(recovery.rulesDbTempFile.exists())
    }

    @Test
    fun prepareForOpen_alreadyEncrypted_skipsConversion() {
        writeFile(recovery.rulesDbFile, ByteArray(64) { 0xAB.toByte() })

        var built = false
        val outcome = encryption.prepareForOpen { built = true }

        assertEquals(ConversionOutcome.Encrypted, outcome)
        assertFalse(built)
        assertFalse(recovery.hasConversionBackup())
    }

    @Test
    fun prepareForOpen_plaintext_failureKeepsPlaintextAndReportsPending() {
        seedPlaintextDatabase()
        val main = recovery.rulesDbFile
        val original = main.readBytes()

        val outcome = encryption.prepareForOpen { temp ->
            writeFile(temp, ByteArray(16) { 0xCD.toByte() })
        }

        assertEquals(ConversionOutcome.Pending, outcome)
        assertArrayEquals("plaintext database must be untouched after a failed conversion", original, main.readBytes())
        assertArrayEquals("the pre-conversion backup must be the original bytes", original, recovery.rulesDbBackupFile.readBytes())
        assertFalse("a failed conversion must clean its temp file", recovery.rulesDbTempFile.exists())
    }

    @Test
    fun prepareForOpen_crashWindow_missingMainWithBackup_restoresIt() {
        seedPlaintextDatabase()
        val main = recovery.rulesDbFile
        val original = main.readBytes()

        // Crash window of the swap's delete+rename fallback: main gone, plaintext .bak is the
        // only copy. A stray temp file from the interrupted run may accompany it.
        main.delete()
        writeFile(recovery.rulesDbBackupFile, original)
        writeFile(recovery.rulesDbTempFile, ByteArray(16) { 1 })

        val outcome = encryption.prepareForOpen { temp -> writeFile(temp, ByteArray(16) { 0xCD.toByte() }) }

        assertEquals(ConversionOutcome.Pending, outcome) // restored, re-converted, failed again here
        assertArrayEquals("the backup must be restored as the live database", original, main.readBytes())
        assertArrayEquals(original, recovery.rulesDbBackupFile.readBytes())
    }

    @Test
    fun onOpenedSuccessfully_backupPresentAtStart_deletesBackup() {
        writeFile(recovery.rulesDbFile, ByteArray(64) { 0xAB.toByte() })
        writeFile(recovery.rulesDbBackupFile, PLAINTEXT_HEADER)

        assertEquals(ConversionOutcome.Encrypted, encryption.prepareForOpen { })
        assertTrue(recovery.hasConversionBackup())

        encryption.onOpenedSuccessfully()

        assertFalse("a launch that started with the backup confirms and removes it", recovery.hasConversionBackup())
    }

    @Test
    fun onOpenedSuccessfully_backupCreatedMidLaunch_keepsBackup() {
        writeFile(recovery.rulesDbFile, ByteArray(64) { 0xAB.toByte() })

        assertEquals(ConversionOutcome.Encrypted, encryption.prepareForOpen { })
        // The conversion launch itself creates the backup; it must survive until a later launch.
        writeFile(recovery.rulesDbBackupFile, PLAINTEXT_HEADER)

        encryption.onOpenedSuccessfully()

        assertTrue("only a cold start that began with the backup may delete it", recovery.hasConversionBackup())
    }

    @Test
    fun swapInEncryptedRules_replacesMainAndDropsSidecars() {
        val main = recovery.rulesDbFile
        writeFile(main, PLAINTEXT_HEADER)
        writeFile(File(main.path + "-wal"), ByteArray(4))
        writeFile(File(main.path + "-shm"), ByteArray(4))
        val encrypted = ByteArray(32) { 0xCD.toByte() }
        writeFile(recovery.rulesDbTempFile, encrypted)

        assertTrue(recovery.swapInEncryptedRules())

        assertArrayEquals(encrypted, main.readBytes())
        assertFalse(recovery.rulesDbTempFile.exists())
        assertFalse(File(main.path + "-wal").exists())
        assertFalse(File(main.path + "-shm").exists())
    }

    @Test
    fun restorePlaintextFromBackup_noBackup_isANoOp() {
        assertFalse(recovery.restorePlaintextFromBackup())
    }

    @Test
    fun restorePlaintextFromBackup_reinstatesBackupAndDropsTemp() {
        val main = recovery.rulesDbFile
        writeFile(main, ByteArray(16) { 0xCD.toByte() })
        val plaintext = PLAINTEXT_HEADER + ByteArray(8) { 3 }
        writeFile(recovery.rulesDbBackupFile, plaintext)
        writeFile(recovery.rulesDbTempFile, ByteArray(8))

        assertTrue(recovery.restorePlaintextFromBackup())

        assertArrayEquals(plaintext, main.readBytes())
        assertFalse(recovery.hasConversionBackup())
        assertFalse(recovery.rulesDbTempFile.exists())
    }

    @Test
    fun confirmConversionBackup_clearsBackupAndTemp() {
        writeFile(recovery.rulesDbBackupFile, PLAINTEXT_HEADER)
        writeFile(recovery.rulesDbTempFile, ByteArray(8))

        recovery.confirmConversionBackup()

        assertFalse(recovery.hasConversionBackup())
        assertFalse(recovery.rulesDbTempFile.exists())
    }

    @Test
    fun resetAllData_removesConversionArtifacts() {
        writeFile(recovery.rulesDbFile, PLAINTEXT_HEADER)
        writeFile(recovery.rulesDbBackupFile, PLAINTEXT_HEADER)
        writeFile(recovery.rulesDbTempFile, ByteArray(8))

        recovery.resetAllData()

        assertFalse(recovery.rulesDbFile.exists())
        assertFalse(recovery.rulesDbBackupFile.exists())
        assertFalse(recovery.rulesDbTempFile.exists())
    }

    // --- helpers ---

    private fun migration() = PlaintextToEncryptedMigration(RulesStoreKeyManager(context), recovery)

    /** A genuine plaintext SQLite database at the production rules path, as a real install has. */
    private fun seedPlaintextDatabase() {
        val main = recovery.rulesDbFile
        main.parentFile?.mkdirs()
        AndroidSQLiteDriver().open(main.absolutePath).use { conn ->
            conn.prepare("CREATE TABLE probe (id INTEGER PRIMARY KEY, name TEXT)").use { it.step() }
            conn.prepare("INSERT INTO probe (id, name) VALUES (1, 'x')").use { it.step() }
        }
    }

    private fun writeFile(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private class FakeKeyManager : DatabaseKeyManager {
        override fun getOrCreateDek(): ByteArray = ByteArray(32)
        override fun classifyFailure(e: Throwable): KeyFailure = KeyFailure.DatabaseIssue
        override fun resetKeyMaterial() {}
    }

    private companion object {
        /** "SQLite format 3\0". */
        val PLAINTEXT_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
