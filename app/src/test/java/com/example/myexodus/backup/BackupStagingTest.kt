package com.example.myexodus.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** §6.4 item 14: the startup sweep and restore pruning after a process death. */
class BackupStagingTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun sweepKeepsTheActiveOperationAndEveryRestoreDirectory() {
        val staging = BackupStaging(temp.root)
        staging.objectFile("active").writeText("x")
        staging.compressedFile("active").writeText("x")
        staging.objectFile("stale").writeText("x")
        staging.compressedFile("stale").writeText("x")
        staging.restoreObjectFile("r1").parentFile!!.mkdirs()
        staging.restoreObjectFile("r1").writeText("x")

        staging.sweep(keepBackupId = "active")

        assertEquals(
            setOf("object-active.bin", "restore-r1"),
            temp.root.listFiles()!!.map { it.name }.toSet(),
        )
    }

    @Test
    fun sweepWithNoActiveOperationRemovesEveryUploadArtifact() {
        val staging = BackupStaging(temp.root)
        staging.objectFile("stale").writeText("x")
        staging.compressedFile("stale").writeText("x")

        staging.sweep(keepBackupId = null)

        assertEquals(emptyList<String>(), temp.root.listFiles()!!.map { it.name })
    }

    @Test
    fun pruneRestoreKeepsOnlyTheNamedRestore() {
        val staging = BackupStaging(temp.root)
        staging.restoreObjectFile("keep").parentFile!!.mkdirs()
        staging.restoreObjectFile("keep").writeText("x")
        staging.restoreObjectFile("gone").parentFile!!.mkdirs()
        staging.restoreObjectFile("gone").writeText("x")
        staging.objectFile("unrelated").writeText("x")

        staging.pruneRestore(keepBackupId = "keep")

        assertTrue(staging.restoreDir("keep").exists())
        assertFalse(staging.restoreDir("gone").exists())
        assertTrue(staging.objectFile("unrelated").exists())
    }

    @Test
    fun pruneRestoreWithoutAKeepSetRemovesEveryRestoreDirectory() {
        val staging = BackupStaging(temp.root)
        staging.restoreObjectFile("a").parentFile!!.mkdirs()
        staging.restoreObjectFile("a").writeText("x")
        staging.restoreObjectFile("b").parentFile!!.mkdirs()
        staging.restoreObjectFile("b").writeText("x")

        staging.pruneRestore(keepBackupId = null)

        assertEquals(emptyList<String>(), temp.root.listFiles()!!.map { it.name })
    }

    @Test
    fun aFirstRunWithNoStagingDirectoryStillWritesAndSeals() {
        val root = java.io.File(temp.root, "absent")
        val staging = BackupStaging(root)

        staging.compressedFile("b1").writeText("x")
        staging.objectFile("b1").writeText("y")

        assertTrue(root.isDirectory)
        assertEquals("x", staging.compressedFile("b1").readText())
    }

    @Test
    fun wipeRemovesEveryArtifactAndTheRoot() {
        val staging = BackupStaging(temp.root)
        staging.objectFile("active").writeText("x")
        staging.restoreObjectFile("r1").parentFile!!.mkdirs()
        staging.restoreObjectFile("r1").writeText("x")

        staging.wipe()

        assertFalse(temp.root.exists())
    }
}
