package com.example.myexodus.domainusage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-13 (§12.1): the S1.4 wall-clock grep check fails when a forbidden call is added to a test
 * file inside `domainusage`, and the S1.6 checksum check fails on a modified PSL asset.
 * Runs the real script — the check under test is the check that ships.
 */
class DomainUsageCiChecksTest {

    /** Gradle runs JVM unit tests with `user.dir` at the `app` module. */
    private val moduleDir = File(requireNotNull(System.getProperty("user.dir")))
    private val script = requireNotNull(moduleDir.parentFile).resolve("scripts/domainusage-ci-checks.sh")
    private val assets = moduleDir.resolve("src/main/assets")

    private fun run(
        scanRoot: File? = null,
        pslAsset: File? = null,
        pslProvenance: File? = null,
    ): Pair<Int, String> {
        val process = ProcessBuilder("bash", script.absolutePath).apply {
            environment()["DOMAINUSAGE_SCAN_ROOT"] = (scanRoot ?: cleanScanRoot()).absolutePath
            pslAsset?.let { environment()["PSL_ASSET_PATH"] = it.absolutePath }
            pslProvenance?.let { environment()["PSL_PROVENANCE_PATH"] = it.absolutePath }
        }.redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    /**
     * A scratch tree laid out like the real one, so path-based filtering is exercised. The
     * forbidden tokens below are assembled from fragments: with this file itself inside
     * `domainusage`, a literal call would trip the very check it tests.
     */
    private fun cleanScanRoot(): File {
        val root = File.createTempFile("domainusage-scan", "").apply { delete(); mkdirs() }
        root.resolve("main/java/com/example/myexodus/domainusage").mkdirs()
        root.resolve("main/java/com/example/myexodus/domainusage/SystemWallClock.kt").writeText(
            "fun allowedHere(): Long = System." + "currentTime" + "Millis()\n",
        )
        writeVpnService(root)
        return root
    }

    /** The INV-9 check requires the real builder call to be present, so the fixture carries it. */
    private fun writeVpnService(root: File, body: String = VPN_SERVICE_BODY) {
        root.resolve("main/java/com/example/myexodus/vpn/DnsSinkholeVpnService.kt").apply {
            requireNotNull(parentFile).mkdirs()
            writeText(body)
        }
    }

    @Test
    fun cleanTree_passes() {
        val (exit, output) = run()
        assertEquals(output, 0, exit)
    }

    @Test
    fun wallClockCallInDomainusageTestFile_fails() {
        val root = cleanScanRoot()
        val violation = root.resolve("test/java/com/example/myexodus/domainusage/LedgerTest.kt")
        requireNotNull(violation.parentFile).mkdirs()
        violation.writeText(
            "val t = java.time." + "Instant" + ".now()\n" +
                "val u = java.time." + "LocalDateTime" + ".now()\n",
        )

        val (exit, output) = run(scanRoot = root)

        assertNotEquals(output, 0, exit)
        assertTrue(output, output.contains("FAIL [S1.4]"))
        assertTrue(output, output.contains("LedgerTest.kt"))
    }

    @Test
    fun wallClockCallOutsideDomainusage_isIgnored() {
        val root = cleanScanRoot()
        root.resolve("main/java/com/example/myexodus/blocking/Other.kt").apply {
            requireNotNull(parentFile).mkdirs()
            writeText("val t = System." + "currentTime" + "Millis()\n")
        }

        val (exit, output) = run(scanRoot = root)
        assertEquals(output, 0, exit)
    }

    @Test
    fun roomDaoReachedFromAPacketPathFile_fails() {
        val root = cleanScanRoot()
        root.resolve("main/java/com/example/myexodus/domainusage/InspectingTcpTap.kt").writeText(
            "fun f(h: H) { h." + "ruleUsage" + "Dao().loadDay(\"d\") }\n",
        )

        val (exit, output) = run(scanRoot = root)

        assertNotEquals(output, 0, exit)
        assertTrue(output, output.contains("FAIL [INV-1]"))
        assertTrue(output, output.contains("InspectingTcpTap.kt"))
    }

    @Test
    fun thePersistencePathItselfMayNameTheDao() {
        val root = cleanScanRoot()
        root.resolve("main/java/com/example/myexodus/domainusage/UsagePersistence.kt").writeText(
            "suspend fun f(dao: " + "RuleUsage" + "Dao) { dao.loadDay(\"d\") }\n",
        )

        val (exit, output) = run(scanRoot = root)

        assertEquals(output, 0, exit)
    }

    @Test
    fun interpolatedLogInsideDomainusageMain_fails() {
        val root = cleanScanRoot()
        root.resolve("main/java/com/example/myexodus/domainusage/Ledger.kt").writeText(
            "fun log(host: String) { Log.i(\"T\", \"resolved \" + \"\$host\") }\n",
        )

        val (exit, output) = run(scanRoot = root)

        assertNotEquals(output, 0, exit)
        assertTrue(output, output.contains("FAIL [INV-7]"))
        assertTrue(output, output.contains("Ledger.kt"))
    }

    @Test
    fun ungatedSensitiveLogInTheVpnService_fails() {
        val root = cleanScanRoot()
        writeVpnService(
            root,
            VPN_SERVICE_BODY + "fun log(q: Q) { Log.i(\"T\", \"nxdomain \" + q.qname) }\n",
        )

        val (exit, output) = run(scanRoot = root)

        assertNotEquals(output, 0, exit)
        assertTrue(output, output.contains("FAIL [INV-7]"))
        assertTrue(output, output.contains("DnsSinkholeVpnService.kt"))
    }

    @Test
    fun aDebugGuardedSensitiveLogInTheVpnService_passes() {
        val root = cleanScanRoot()
        writeVpnService(
            root,
            VPN_SERVICE_BODY +
                "fun log(q: Q) { if (BuildConfig.DEBUG) Log.i(\"T\", \"nxdomain \" + q.qname) }\n",
        )

        val (exit, output) = run(scanRoot = root)

        assertEquals(output, 0, exit)
    }

    @Test
    fun aCallToTheVpnBypassInAnySourceFile_fails() {
        val root = cleanScanRoot()
        root.resolve("main/java/com/example/myexodus/blocking/Other.kt").apply {
            requireNotNull(parentFile).mkdirs()
            // Assembled, not literal: this test file is itself inside the real scan root, so the
            // literal token would fail the production check the moment CI ran it for real.
            writeText("val b = builder()." + "allow" + "Bypass()\n")
        }

        val (exit, output) = run(scanRoot = root)

        assertNotEquals(output, 0, exit)
        assertTrue(output, output.contains("FAIL [INV-10]"))
        assertTrue(output, output.contains("Other.kt"))
    }

    @Test
    fun vpnServiceWithoutTheDisallowedApplicationCall_fails() {
        val root = cleanScanRoot()
        writeVpnService(root, body = "class DnsSinkholeVpnService { fun builder() = Unit }\n")

        val (exit, output) = run(scanRoot = root)

        assertNotEquals(output, 0, exit)
        assertTrue(output, output.contains("FAIL [INV-9]"))
    }

    @Test
    fun vpnServiceMissingEntirely_fails() {
        val root = cleanScanRoot()
        require(root.resolve("main/java/com/example/myexodus/vpn/DnsSinkholeVpnService.kt").delete())

        val (exit, output) = run(scanRoot = root)

        assertNotEquals(output, 0, exit)
        assertTrue(output, output.contains("FAIL [INV-9]"))
    }

    @Test
    fun modifiedPslAsset_failsChecksum() {
        val modified = File.createTempFile("public_suffix_list", ".dat").apply {
            deleteOnExit()
            writeBytes(assets.resolve("public_suffix_list.dat").readBytes())
            appendBytes("evil.example\n".toByteArray())
        }

        val (exit, output) = run(
            pslAsset = modified,
            pslProvenance = assets.resolve("public_suffix_list.provenance"),
        )

        assertNotEquals(output, 0, exit)
        assertTrue(output, output.contains("FAIL [S1.6]"))
    }

    private companion object {
        /** Matches the shape the INV-9 check looks for in the real service. */
        const val VPN_SERVICE_BODY =
            "class DnsSinkholeVpnService { fun builder() = Unit.apply { addDisallowedApplication(\"pkg\") } }\n"
    }
}
