package com.example.myexodus.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Checksum vectors below were computed with an independent Python implementation
 * (RFC 1071 ones-complement folding) on 2026-08-29 — see the checksum-verify script
 * used during Slice 3 development.
 */
class VpnPacketTest {

    private fun checksumAt(packet: ByteArray, offset: Int): Int =
        VpnPacket.readShort(packet, offset)

    @Test
    fun ipv4HeaderChecksum_matchesCanonicalVector() {
        // Canonical ICMP echo header with the checksum field zeroed -> 0x6c7d (python-verified).
        val header = byteArrayOf(
            0x45, 0x00, 0x00, 0x3C, 0x1C, 0x46, 0x40.toByte(), 0x00,
            0x40, 0x01, 0x00, 0x00, 0xC0.toByte(), 0xA8.toByte(), 0x00, 0x01,
            0x63, 0x3F, 0x8E.toByte(), 0x15,
        )
        assertEquals(0x6C7D, VpnPacket.headerChecksumV4(header, 0, header.size))
    }

    @Test
    fun tcpV4SynAck_checksum_matchesVector() {
        val packet = VpnPacket.buildTcpSegment(
            src = byteArrayOf(1, 2, 3, 4), dst = byteArrayOf(5, 6, 7, 8),
            srcPort = 1234, dstPort = 80,
            seq = 1, ack = 2, syn = true, ackFlag = true,
        )
        // Header checksum self-consistent: the complete header (field included) folds to 0xFFFF.
        assertEquals(0xFFFF, VpnPacket.fold16(VpnPacket.sum16(packet, 0, 20)))
        assertEquals(0x9A9A, checksumAt(packet, 20 + 16))
    }

    @Test
    fun tcpV6PshAck_checksum_matchesVector() {
        val packet = VpnPacket.buildTcpSegment(
            src = ipv6("20010db8000000000000000000000001"), dst = ipv6("fd001414000000000000000000000001"),
            srcPort = 80, dstPort = 4444,
            seq = 100, ack = 200, ackFlag = true, psh = true,
            payload = "TEST".toByteArray(),
        )
        assertEquals(0xB687, checksumAt(packet, 40 + 16))
    }

    @Test
    fun udpV4_checksum_matchesVector() {
        val packet = VpnPacket.buildUdpPacket(
            src = byteArrayOf(8, 8, 8, 8), dst = byteArrayOf(10, 14, 14, 1),
            srcPort = 53, dstPort = 55555,
        )
        assertEquals(0xFE86, checksumAt(packet, 20 + 6))
        // IPv4 header checksum — on-device TUN ingress verification drops a zero field.
        assertEquals(0x12B3, checksumAt(packet, 10))
        assertEquals(0xFFFF, VpnPacket.fold16(VpnPacket.sum16(packet, 0, 20)))
    }

    @Test
    fun udpV6_checksum_matchesVector() {
        val packet = VpnPacket.buildUdpPacket(
            src = ipv6("20010db8000000000000000000000001"), dst = ipv6("fd001414000000000000000000000001"),
            srcPort = 53, dstPort = 55555,
            payload = byteArrayOf(0x12, 0x34),
        )
        assertEquals(0xD59D, checksumAt(packet, 40 + 6))
    }

    @Test
    fun tcpV4_buildParse_roundTrip() {
        val payload = "hello vpn".toByteArray()
        val packet = VpnPacket.buildTcpSegment(
            src = byteArrayOf(10, 14, 14, 1), dst = byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
            srcPort = 12345, dstPort = 443,
            seq = 42, ack = 7, ackFlag = true, psh = true,
            payload = payload,
        )
        val ip = VpnPacket.parseIp(packet)!!
        assertEquals(4, ip.version)
        assertEquals(VpnPacket.PROTO_TCP, ip.protocol)
        assertArrayEquals(byteArrayOf(10, 14, 14, 1), ip.src)
        assertArrayEquals(byteArrayOf(93, 184.toByte(), 216.toByte(), 34), ip.dst)
        val tcp = VpnPacket.parseTcp(ip, packet)!!
        assertEquals(12345, tcp.srcPort)
        assertEquals(443, tcp.dstPort)
        assertEquals(42, tcp.seq)
        assertEquals(7, tcp.ack)
        assertEquals("hello vpn", String(packet, tcp.payloadOffset, tcp.payloadLength))
        assertNull("wrong parse type", VpnPacket.parseUdp(ip, packet))
        // v4-with-payload checksum vector (python-verified 2026-08-29): the on-device
        // relay injects payload segments with this exact math.
        assertEquals(0xECDD, checksumAt(packet, 10))
        assertEquals(0x0752, checksumAt(packet, 20 + 16))
        assertEquals(0xFFFF, VpnPacket.fold16(VpnPacket.sum16(packet, 0, 20)))
    }

    @Test
    fun tcpV6_buildParse_roundTrip() {
        val packet = VpnPacket.buildTcpSegment(
            src = ipv6("fd001414000000000000000000000001"), dst = ipv6("20010db8000000000000000000000001"),
            srcPort = 4444, dstPort = 80,
            seq = 9, ack = 3, syn = true, ackFlag = true,
        )
        val ip = VpnPacket.parseIp(packet)!!
        assertEquals(6, ip.version)
        assertArrayEquals(ipv6("fd001414000000000000000000000001"), ip.src)
        val tcp = VpnPacket.parseTcp(ip, packet)!!
        assertEquals(4444, tcp.srcPort)
        assertNull("v6 fragment/extension headers unsupported", VpnPacket.parseIp(ipv6FragmentPacket()))
    }

    @Test
    fun udpV4_buildParse_roundTrip() {
        val payload = "query-bytes".toByteArray()
        val packet = VpnPacket.buildUdpPacket(
            src = byteArrayOf(10, 14, 14, 1), dst = byteArrayOf(8, 8, 8, 8),
            srcPort = 55555, dstPort = 53,
            payload = payload,
        )
        val ip = VpnPacket.parseIp(packet)!!
        val udp = VpnPacket.parseUdp(ip, packet)!!
        assertEquals(55555, udp.srcPort)
        assertEquals(53, udp.dstPort)
        assertEquals("query-bytes", String(packet, udp.payloadOffset, udp.payloadLength))
    }

    @Test
    fun parseIp_rejectsMalformed() {
        assertNull(VpnPacket.parseIp(byteArrayOf(0x45, 0x00, 0x00))) // truncated
        assertNull(VpnPacket.parseIp(byteArrayOf(0x41, 0x00, 0x00, 0x3C, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))) // version 4, IHL=1
        assertNull(VpnPacket.parseIp(byteArrayOf(0x70, 0x00, 0x00, 0x3C, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))) // version 7
    }

    @Test
    fun parseIpv4_dropsFragments() {
        // First fragment (MF set, offset 0) of a v4 datagram — spike has no reassembly.
        val packet = VpnPacket.buildTcpSegment(
            src = byteArrayOf(1, 1, 1, 1), dst = byteArrayOf(2, 2, 2, 2),
            srcPort = 1, dstPort = 2, seq = 1, ack = 1, ackFlag = true,
        )
        val p = VpnPacket.parseIp(packet)!!
        // Set MF on the flags field (byte 6 bit 5).
        val frag = packet.clone()
        frag[6] = (frag[6].toInt() or 0x20).toByte()
        assertNull(VpnPacket.parseIp(frag))
        assertEquals(4, p.version) // sanity: original parses
    }

    @Test
    fun parseTcp_highDataOffsetByte_isNotSignExtended() {
        // An optioned SYN has data-offset byte >= 0x80 (e.g. 0xa0 = 40-byte header).
        // Without masking, Byte.toInt() sign-extends and parseTcp rejects the segment —
        // the on-device https-gate failure during Slice 3 (all optioned SYNs dropped).
        val payload = "0123456789abcdef0123456789abcdef".toByteArray()
        val packet = VpnPacket.buildTcpSegment(
            src = byteArrayOf(10, 14, 14, 1), dst = byteArrayOf(93, 184.toByte(), 216.toByte(), 34),
            srcPort = 12345, dstPort = 443, seq = 1, ack = 1, ackFlag = true, psh = true,
            payload = payload,
        )
        val ip = VpnPacket.parseIp(packet)!!
        packet[20 + 12] = 0xA0.toByte() // claim a 40-byte TCP header (options present)
        val tcp = VpnPacket.parseTcp(ip, packet)
        assertNotNull(tcp)
        assertEquals(12345, tcp!!.srcPort)
        assertEquals(443, tcp.dstPort)
        assertEquals(20 + 40, tcp.payloadOffset)
        assertEquals(ip.payloadLength - 40, tcp.payloadLength) // 12: claimed header covers payload bytes
    }

    @Test
    fun parseTcp_rejectsShortHeader() {
        val packet = VpnPacket.buildTcpSegment(
            src = byteArrayOf(1, 1, 1, 1), dst = byteArrayOf(2, 2, 2, 2),
            srcPort = 1, dstPort = 2, seq = 1, ack = 1, ackFlag = true,
        )
        val ip = VpnPacket.parseIp(packet)!!
        // Corrupt the data offset nibble to 0 (invalid -> 0 bytes header).
        packet[20 + 12] = 0x00
        assertNull(VpnPacket.parseTcp(ip, packet))
    }

    // --- §6.10 ICMP Port-Unreachable ---

    /**
     * Vector computed with an independent Python implementation of the same byte layout
     * (2026-09-20): inner = buildUdpPacket(203.0.113.7:54321 -> 198.51.100.9:443,
     * "hello-world!"), quoted = its 20-byte header + the 8-byte UDP header.
     */
    @Test
    fun icmpPortUnreachableV4_swapsAddresses_quotesHeaderAndPorts_checksumsMatchVector() {
        val inner = VpnPacket.buildUdpPacket(
            src = byteArrayOf(203.toByte(), 0, 113, 7), dst = byteArrayOf(198.toByte(), 51, 100, 9),
            srcPort = 54321, dstPort = 443, payload = "hello-world!".toByteArray(),
        )
        val ip = VpnPacket.parseIp(inner)!!

        val icmp = VpnPacket.buildIcmpPortUnreachable(ip, inner)!!

        assertEquals("20-byte IP header + ICMP header + 20 + 8 quoted", 56, icmp.size)
        assertEquals("the reply's version/ttl/protocol", 0x45, icmp[0].toInt() and 0xFF)
        assertEquals(1, icmp[9].toInt() and 0xFF)
        // RFC 792: from the datagram's destination, back to its source.
        assertArrayEquals(byteArrayOf(198.toByte(), 51, 100, 9), icmp.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(203.toByte(), 0, 113, 7), icmp.copyOfRange(16, 20))
        assertEquals(VpnPacket.ICMP_TYPE_PORT_UNREACHABLE, icmp[20].toInt() and 0xFF)
        assertEquals(VpnPacket.ICMP_CODE_PORT_UNREACHABLE, icmp[21].toInt() and 0xFF)
        assertEquals("quoted bytes are the inner packet verbatim, ports included", inner.copyOfRange(0, 28).toList(), icmp.copyOfRange(28, 56).toList())
        assertEquals(0xD480, VpnPacket.readShort(icmp, 10)) // python-verified header checksum
        assertEquals(0xF563, VpnPacket.readShort(icmp, 22)) // python-verified ICMP checksum
        assertEquals(0xFFFF, VpnPacket.fold16(VpnPacket.sum16(icmp, 0, 20)))
    }

    /** Same codec over IPv6 — RFC 4443 type 1 code 4, checksum over the pseudo-header. */
    @Test
    fun icmpv6PortUnreachable_checksumsMatchVector() {
        val src = ipv6("20010db8000000000000000000000001")
        val dst = ipv6("fd001414000000000000000000000009")
        val inner = VpnPacket.buildUdpPacket(
            src = src, dst = dst, srcPort = 54321, dstPort = 443, payload = "hello-world!".toByteArray(),
        )
        val ip = VpnPacket.parseIp(inner)!!

        val icmp = VpnPacket.buildIcmpPortUnreachable(ip, inner)!!

        assertEquals("40-byte IP header + ICMP header + 40 + 8 quoted", 96, icmp.size)
        assertEquals(0x60, icmp[0].toInt() and 0xFF)
        assertEquals(58, icmp[6].toInt() and 0xFF)
        assertArrayEquals(dst, icmp.copyOfRange(8, 24))
        assertArrayEquals(src, icmp.copyOfRange(24, 40))
        assertEquals(VpnPacket.ICMPV6_TYPE_PORT_UNREACHABLE, icmp[40].toInt() and 0xFF)
        assertEquals(VpnPacket.ICMPV6_CODE_PORT_UNREACHABLE, icmp[41].toInt() and 0xFF)
        assertEquals(0xE07E, VpnPacket.readShort(icmp, 42)) // python-verified, pseudo-header included
        assertEquals(inner.copyOfRange(0, 48).toList(), icmp.copyOfRange(48, 96).toList())
    }

    /** RFC 792's 8 bytes are a *maximum*: a shorter IP payload is quoted only as far as it goes. */
    @Test
    fun aShortIpPayload_isQuotedOnlyAsFarAsItGoes() {
        val packet = ByteArray(25).also { for (i in it.indices) it[i] = (i + 1).toByte() }
        val ip = VpnPacket.IpHeader(4, 17, byteArrayOf(10, 0, 0, 1), byteArrayOf(10, 0, 0, 2), 20, 5)

        val icmp = VpnPacket.buildIcmpPortUnreachable(ip, packet)!!

        assertEquals("20 + 8 ICMP + (20 + 5 quoted)", 53, icmp.size)
        assertEquals(packet.toList(), icmp.copyOfRange(28, 53).toList())
        assertEquals(0xFFFF, VpnPacket.fold16(VpnPacket.sum16(icmp, 0, 20)))
        assertEquals("odd-length ICMP message still folds with its checksum to zero", 0xFFFF, VpnPacket.fold16(VpnPacket.sum16(icmp, 20, 33)))
    }

    /** INV-14: an inconsistent header aborts construction — never a packet built from bad lengths. */
    @Test
    fun inconsistentHeaders_abortConstruction() {
        val packet = VpnPacket.buildUdpPacket(
            src = byteArrayOf(1, 2, 3, 4), dst = byteArrayOf(5, 6, 7, 8), srcPort = 1, dstPort = 2,
        )
        val good = VpnPacket.parseIp(packet)!!

        assertNull(
            "payload claims more bytes than the buffer holds",
            VpnPacket.buildIcmpPortUnreachable(good.copy(payloadLength = packet.size), packet),
        )
        assertNull(
            "header offset past the buffer",
            VpnPacket.buildIcmpPortUnreachable(good.copy(payloadOffset = packet.size + 1), packet),
        )
        assertNull(
            "v4 header below the RFC 791 minimum",
            VpnPacket.buildIcmpPortUnreachable(good.copy(payloadOffset = 4, payloadLength = 12), packet),
        )
        assertNull(
            "address sizes contradict the version",
            VpnPacket.buildIcmpPortUnreachable(good.copy(src = ByteArray(16), dst = ByteArray(16)), packet),
        )
    }

    private fun ipv6(hex: String): ByteArray {
        val result = ByteArray(16)
        for (i in 0 until 16) result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return result
    }

    /** Fragment header (next header 44) — rejected by the v6 parser. */
    private fun ipv6FragmentPacket(): ByteArray {
        val packet = ByteArray(48)
        packet[0] = 0x60.toByte()
        packet[4] = 8 // payload length: fragment header (8 bytes)
        packet[6] = 44 // next header: IPv6 fragment
        val src = ipv6("fd001414000000000000000000000001")
        val dst = ipv6("20010db8000000000000000000000001")
        src.copyInto(packet, 8)
        dst.copyInto(packet, 24)
        return packet
    }
}
