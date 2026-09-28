package com.example.myexodus.domainusage

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U-8: fixtures under `src/test/resources/tls/`, split at every byte boundary and across two TLS
 * records, ECH, missing extensions, wrong content type, oversize, malformed structures, and fuzz.
 *
 * Fixture A is a real ClientHello (`python3 -c` with `ssl.MemoryBIO`,
 * `wrap_bio(server_hostname="example.com")`). Fixtures B and C are real Chrome 153.0.8010.47
 * captures from the reference X6532 (`scripts/domainusage-tls-hello-capture.py`; see
 * `src/test/resources/tls/FIXTURES.provenance`). Every one of the 20 captures in that session
 * carried `0xFE0D`, so a real Chrome hello *without* an ECH extension no longer exists to
 * capture: Chrome sends GREASE ECH to hosts that publish no ECH config, and RFC 9849 §6.2 makes
 * GREASE indistinguishable from a real config by design — hence `Result.Ech` for B and C, with
 * their plaintext SNI (a cover name, per spec) deliberately ignored. Fixture D is still synthetic
 * pending the Android 17 capture; the S4 section of `app/docs/domainusage-implementation-log.md`
 * carries the history.
 */
class TlsClientHelloParserTest {

    private val counters = EngineCounters()

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/tls/$name")!!.use { it.readBytes() }

    private val a = fixture("fixture_a_example_com.bin")
    private val b = fixture("fixture_b_chrome_pq.bin")
    private val c = fixture("fixture_c_chrome_ech.bin")
    private val d = fixture("fixture_d_android17_ech.bin")

    private fun feed(bytes: ByteArray, len: Int = bytes.size): TlsClientHelloParser.Result =
        TlsClientHelloParser.feed(bytes, 0, len)

    // --- fixtures ---

    @Test
    fun fixtureAIsARealClientHelloForExampleCom() {
        assertEquals(TlsClientHelloParser.Result.Sni("example.com"), feed(a))
    }

    @Test
    fun fixtureBCarriesAPostQuantumKeyShareUnderAnIgnoredGreaseEchSni() {
        assertEquals(TlsClientHelloParser.Result.Ech, feed(b))
        assertTrue(
            "fixture still carries the X25519MLKEM768 key-share group",
            (0 until b.size - 1).any { b[it] == 0x11.toByte() && b[it + 1] == 0xEC.toByte() },
        )
        // The plaintext SNI is present (spec requires a public name) and must not be used: the
        // capture's inner name is what ECH hides, so attributing on the outer one is a guess.
        assertTrue(
            "capture still carries its plaintext SNI for the ignore-path to be meaningful",
            String(b, Charsets.ISO_8859_1).contains("du-b.192.0.2.99.nip.io"),
        )
    }

    @Test
    fun echFixturesReportEchAndIgnoreTheirPlaintextSni() {
        assertEquals(TlsClientHelloParser.Result.Ech, feed(c))
        assertEquals(TlsClientHelloParser.Result.Ech, feed(d))
    }

    @Test
    fun splitAtEveryByteBoundaryNeedsMoreUntilTheLastByteThenDecides() {
        val junk = ByteArray(64) { 0xAA.toByte() }
        for ((fixture, expected) in listOf(
            a to TlsClientHelloParser.Result.Sni("example.com"),
            b to TlsClientHelloParser.Result.Ech,
            c to TlsClientHelloParser.Result.Ech,
            d to TlsClientHelloParser.Result.Ech,
        )) {
            val padded = fixture + junk
            for (len in 1 until fixture.size) {
                assertEquals(
                    "fixture ${fixture.size}B split at byte $len must not decide yet (and must not read the junk)",
                    TlsClientHelloParser.Result.NeedMore,
                    feed(padded, len),
                )
            }
            assertEquals(expected, feed(padded, fixture.size))
        }
    }

    @Test
    fun aClientHelloSplitAcrossTwoTlsRecordsParsesIdentically() {
        for ((fixture, expected) in listOf(
            a to TlsClientHelloParser.Result.Sni("example.com"),
            b to TlsClientHelloParser.Result.Ech,
            c to TlsClientHelloParser.Result.Ech,
        )) {
            val handshakeSize = fixture.size - 5
            for (splitAt in listOf(1, 2, 3, 4, 5, 40, 600, handshakeSize - 1)) {
                val reframed = reframeAcrossTwoRecords(fixture, splitAt)
                for (len in 1 until reframed.size) {
                    assertEquals(
                        "splitAt=$splitAt len=$len",
                        TlsClientHelloParser.Result.NeedMore,
                        feed(reframed, len),
                    )
                }
                assertEquals("splitAt=$splitAt", expected, feed(reframed))
            }
        }
    }

    /** Re-frames a single-record ClientHello as two consecutive handshake records. */
    private fun reframeAcrossTwoRecords(record: ByteArray, splitAt: Int): ByteArray {
        val handshake = record.copyOfRange(5, record.size)
        fun framed(payload: ByteArray): ByteArray =
            byteArrayOf(0x16, 0x03, 0x01, (payload.size shr 8).toByte(), payload.size.toByte()) + payload
        return framed(handshake.copyOfRange(0, splitAt)) + framed(handshake.copyOfRange(splitAt, handshake.size))
    }

    // --- structure ---

    @Test
    fun aMissingExtensionsFieldIsNoSni() {
        assertEquals(TlsClientHelloParser.Result.NoSni, feed(noExtensionsClientHello()))
    }

    @Test
    fun anSniExtensionAfterOtherExtensionsIsStillFound() {
        val hello = clientHello(
            extensions = ext(0x001c, byteArrayOf(0x40, 0x01)) +
                ext(0x000d, byteArrayOf(0, 2, 0x04, 0x03)) +
                sniExtension("late.example") +
                ext(0x0015, ByteArray(8)),
        )
        assertEquals(TlsClientHelloParser.Result.Sni("late.example"), feed(hello))
    }

    @Test
    fun aNonHostNameEntryFirstStillFindsTheHostName() {
        val names = serverNameList(byteArrayOf(0x01, 0x00, 0x03, 1, 2, 3) + serverNameEntry("real.example"))
        val hello = clientHello(extensions = ext(0x0000, names))
        assertEquals(TlsClientHelloParser.Result.Sni("real.example"), feed(hello))
    }

    @Test
    fun aWrongContentTypeOrRecordVersionIsNotTls() {
        assertEquals(TlsClientHelloParser.Result.NotTls, feed(a.copyOf().also { it[0] = 0x17 }))
        assertEquals(TlsClientHelloParser.Result.NotTls, feed(a.copyOf().also { it[1] = 0x02 }))
    }

    @Test
    fun aNonClientHelloHandshakeIsNoSni() {
        assertEquals(TlsClientHelloParser.Result.NoSni, feed(a.copyOf().also { it[5] = 0x02 }))
    }

    @Test
    fun anOversizeClientHelloIsTooLarge() {
        // Declared handshake length alone exceeds INSPECT_MAX_BYTES — decided from the header.
        val oversize = byteArrayOf(0x16, 0x03, 0x01, 0x40, 0x00, 0x01) +
            byteArrayOf(0x00, 0x40, 0x00) + ByteArray(16)
        assertEquals(TlsClientHelloParser.Result.TooLarge, feed(oversize))
    }

    @Test
    fun aRecordLongerThanTheProtocolAllowsIsMalformed() {
        val badRecord = byteArrayOf(0x16, 0x03, 0x01, 0x40, 0x01) + ByteArray(32)
        assertEquals(TlsClientHelloParser.Result.Malformed, feed(badRecord))
    }

    @Test
    fun lengthsThatExceedTheirEnclosingStructureAreMalformed() {
        // session_id length runs past the end of the ClientHello body (record 5 + handshake 4 +
        // legacy_version 2 + random 32 puts the session_id length byte at offset 43)
        val badSession = clientHello().copyOf().also { it[43] = 0xFF.toByte() }
        assertEquals(TlsClientHelloParser.Result.Malformed, feed(badSession))

        // extension block length runs past the end of the ClientHello body
        val badExtBlock = clientHello(extensions = ext(0x0015, ByteArray(4)), extensionsLenBias = 8)
        assertEquals(TlsClientHelloParser.Result.Malformed, feed(badExtBlock))

        // an extension's length runs past the end of the extension block
        val badExt = clientHello(extensions = byteArrayOf(0x00, 0x15, 0x00, 0x20, 1, 2, 3))
        assertEquals(TlsClientHelloParser.Result.Malformed, feed(badExt))

        // a trailing partial extension header inside the declared block
        val partialHeader = clientHello(extensions = ext(0x0015, ByteArray(4)) + byteArrayOf(0x00, 0x1c))
        assertEquals(TlsClientHelloParser.Result.Malformed, feed(partialHeader))

        // odd cipher-suite list length
        val oddCiphers = clientHello(extensions = ext(0x0015, ByteArray(4)), cipherSuites = byteArrayOf(0x13, 0x01, 0x13))
        assertEquals(TlsClientHelloParser.Result.Malformed, feed(oddCiphers))
    }

    @Test
    fun aHandshakeStreamInterruptedByANonHandshakeRecordIsMalformed() {
        // A later non-handshake record is irrelevant while the ClientHello completes inside its
        // own record — the interruption only matters when the parse needs the next record's bytes.
        val twoRecords = a + byteArrayOf(0x15, 0x03, 0x03, 0x00, 0x02, 0x01, 0x00)
        assertEquals(TlsClientHelloParser.Result.Sni("example.com"), feed(twoRecords))

        val split = reframeAcrossTwoRecords(a, 100)
        split[5 + 100] = 0x15 // record 2's content type, at the byte after record 1
        assertEquals(TlsClientHelloParser.Result.Malformed, feed(split))
    }

    // --- hostname validation ---

    @Test
    fun hostnamesAreLowercasedAndInvalidOnesAreNoSni() {
        assertEquals(TlsClientHelloParser.Result.Sni("mixed.example"), feed(clientHello(sni = "MiXeD.Example")))
        assertEquals(TlsClientHelloParser.Result.Sni("_dmarc.example"), feed(clientHello(sni = "_dmarc.example")))

        for (invalid in listOf(
            "",                        // empty name
            "has space.example",       // forbidden character
            "slash/name.example",      // forbidden character
            "trailing.dot.",           // empty final label
            ".leading.dot",            // empty first label
            "a".repeat(64) + ".example", // label over 63 bytes
            ("a".repeat(63) + ".").repeat(4) + "example", // over 253 bytes
        )) {
            assertEquals("host=$invalid", TlsClientHelloParser.Result.NoSni, feed(clientHello(sni = invalid)))
        }

        val nonAscii = clientHello(extensions = sniExtensionBytes("example".toByteArray() + 0x80.toByte()))
        assertEquals(TlsClientHelloParser.Result.NoSni, feed(nonAscii))
    }

    // --- counters (§8 S4.6) ---

    @Test
    fun countIntoMapsEveryTerminalResultAndNeedMoreCountsNothing() {
        TlsClientHelloParser.countInto(TlsClientHelloParser.Result.NeedMore, counters)
        assertEquals(0L, counters.snapshot().values.sum())

        TlsClientHelloParser.countInto(TlsClientHelloParser.Result.Sni("h"), counters)
        TlsClientHelloParser.countInto(TlsClientHelloParser.Result.Ech, counters)
        TlsClientHelloParser.countInto(TlsClientHelloParser.Result.NoSni, counters)
        TlsClientHelloParser.countInto(TlsClientHelloParser.Result.NotTls, counters)
        TlsClientHelloParser.countInto(TlsClientHelloParser.Result.Malformed, counters)
        TlsClientHelloParser.countInto(TlsClientHelloParser.Result.TooLarge, counters)

        assertEquals(1L, counters.get(Counter.sni_ok))
        assertEquals(1L, counters.get(Counter.sni_ech))
        assertEquals(1L, counters.get(Counter.sni_none))
        assertEquals(1L, counters.get(Counter.sni_notls))
        assertEquals(1L, counters.get(Counter.sni_malformed))
        assertEquals(1L, counters.get(Counter.sni_toolarge))
        assertEquals(6L, counters.snapshot().values.sum())
    }

    // --- fuzz ---

    @Test
    fun fuzzMutationsNeverThrowAndNeverReadPastLen() {
        val random = Random(20260920)
        val bases = listOf(a, b, c, d)
        repeat(100_000) { i ->
            val base = bases[i % bases.size]
            val mutated = when (random.nextInt(4)) {
                0 -> base.copyOf().also { it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                1 -> base.copyOf(random.nextInt(base.size + 1))
                2 -> base + ByteArray(random.nextInt(16)) { random.nextInt(256).toByte() }
                else -> base.copyOf(base.size + 8).also {
                    for (j in base.size until it.size) it[j] = random.nextInt(256).toByte()
                }
            }
            val padded = mutated + ByteArray(32) { 0x5A }
            val result = TlsClientHelloParser.feed(padded, 0, mutated.size)
            val unpadded = TlsClientHelloParser.feed(mutated, 0, mutated.size)
            assertEquals("byte $i: the bytes past len changed the result", unpadded, result)
        }
    }

    @Test
    fun fuzzStructuralMutationsAtEveryFieldBoundaryNeverThrow() {
        val random = Random(4242)
        for (offset in 0 until minOf(64, a.size)) {
            for (value in intArrayOf(0x00, 0x01, 0x40, 0x7F, 0xC0, 0xFF)) {
                val mutated = a.copyOf()
                mutated[offset] = value.toByte()
                TlsClientHelloParser.feed(mutated, 0, mutated.size)
            }
        }
        repeat(5_000) {
            val mutated = a.copyOf()
            repeat(3) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
            TlsClientHelloParser.feed(mutated, 0, mutated.size)
        }
    }

    // --- hand-built ClientHellos ---

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun ext(type: Int, body: ByteArray) = u16(type) + u16(body.size) + body

    private fun sniExtension(host: String) = ext(0x0000, serverNameList(serverNameEntry(host)))

    private fun serverNameEntry(host: String) = serverNameEntryBytes(host.toByteArray(Charsets.US_ASCII))

    private fun serverNameEntryBytes(name: ByteArray) = byteArrayOf(0x00) + u16(name.size) + name

    private fun serverNameList(entries: ByteArray) = u16(entries.size) + entries

    private fun sniExtensionBytes(name: ByteArray) = ext(0x0000, serverNameList(serverNameEntryBytes(name)))

    private fun noExtensionsClientHello(): ByteArray =
        clientHello(extensions = null)

    private fun clientHello(
        sni: String = "example.com",
        extensions: ByteArray? = sniExtension(sni),
        sessionIdLen: Int = 0,
        cipherSuites: ByteArray = byteArrayOf(0x13, 0x01),
        extensionsLenBias: Int = 0,
    ): ByteArray {
        val body = u16(0x0303) + ByteArray(32) + byteArrayOf(sessionIdLen.toByte()) + ByteArray(sessionIdLen) +
            u16(cipherSuites.size) + cipherSuites +
            byteArrayOf(1, 0)
        val withExtensions = if (extensions == null) body
        else body + u16(extensions.size + extensionsLenBias) + extensions
        val handshake = byteArrayOf(0x01) +
            byteArrayOf((withExtensions.size shr 16).toByte(), (withExtensions.size shr 8).toByte(), withExtensions.size.toByte()) +
            withExtensions
        return byteArrayOf(0x16, 0x03, 0x01, (handshake.size shr 8).toByte(), handshake.size.toByte()) + handshake
    }
}
