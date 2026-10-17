package com.example.myexodus.vpn

import java.nio.ByteBuffer

/**
 * Pure IPv4/IPv6/TCP/UDP packet codec for the DNS-sinkhole spike (plan §7 step 3 / §8.4).
 * No Android imports — checksum math and header building are JVM-unit-tested against
 * independently computed vectors (VpnPacketTest).
 */
object VpnPacket {

    const val MTU = 1400
    const val MAX_TCP_SEGMENT = 1200
    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val PROTO_ICMPV6 = 58

    /** RFC 792 type 3 code 3 / RFC 4443 type 1 code 4 — "port unreachable" for v4/v6. */
    const val ICMP_TYPE_PORT_UNREACHABLE = 3
    const val ICMP_CODE_PORT_UNREACHABLE = 3
    const val ICMPV6_TYPE_PORT_UNREACHABLE = 1
    const val ICMPV6_CODE_PORT_UNREACHABLE = 4

    val EMPTY = ByteArray(0)

    fun readShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    // ---- RFC 1071 ones-complement 16-bit arithmetic ----

    fun sum16(data: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        return sum
    }

    fun fold16(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return s.toInt()
    }

    fun complement(value: Int): Int = value.inv() and 0xFFFF

    /** IPv4 header checksum over [offset, offset+length); the checksum field must be zeroed by the caller. */
    fun headerChecksumV4(header: ByteArray, offset: Int, length: Int): Int =
        complement(fold16(sum16(header, offset, length)))

    private fun v4Pseudo(src4: ByteArray, dst4: ByteArray, protocol: Int, upperLength: Int): ByteArray {
        val p = ByteBuffer.allocate(12)
        p.put(src4); p.put(dst4)
        p.put(0); p.put(protocol.toByte()); p.putShort(upperLength.toShort())
        return p.array()
    }

    private fun v6Pseudo(src16: ByteArray, dst16: ByteArray, protocol: Int, upperLength: Int): ByteArray {
        val p = ByteBuffer.allocate(40)
        p.put(src16); p.put(dst16)
        p.putInt(upperLength)
        p.put(0); p.put(0); p.put(0); p.put(protocol.toByte())
        return p.array()
    }

    /** TCP/UDP checksum over pseudo-header + upper-layer bytes; the upper checksum field must be zeroed. */
    fun upperChecksum(pseudo: ByteArray, upper: ByteArray, upperOffset: Int, upperLength: Int): Int =
        complement(fold16(sum16(pseudo, 0, pseudo.size) + sum16(upper, upperOffset, upperLength)))

    // ---- parsing ----

    data class IpHeader(
        val version: Int,
        val protocol: Int,
        val src: ByteArray,
        val dst: ByteArray,
        val payloadOffset: Int,
        val payloadLength: Int,
    ) { val isV6: Boolean get() = version == 6 }

    data class TcpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val seq: Int,
        val ack: Int,
        val syn: Boolean,
        val ackFlag: Boolean,
        val fin: Boolean,
        val rst: Boolean,
        val window: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    data class UdpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    fun parseIp(packet: ByteArray): IpHeader? {
        if (packet.size < 20) return null
        return when ((packet[0].toInt() ushr 4) and 0xF) {
            4 -> parseIpv4(packet)
            6 -> parseIpv6(packet)
            else -> null
        }
    }

    private fun parseIpv4(packet: ByteArray): IpHeader? {
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20) return null
        val total = readShort(packet, 2) and 0xFFFF
        if (total < 20 || total > packet.size) return null
        // Fragments (MF or non-zero offset) are dropped: no reassembly in the spike.
        if (readShort(packet, 6) and 0x3FFF != 0) return null
        return IpHeader(
            4, packet[9].toInt() and 0xFF,
            packet.copyOfRange(12, 16), packet.copyOfRange(16, 20),
            ihl, total - ihl,
        )
    }

    private fun parseIpv6(packet: ByteArray): IpHeader? {
        if (packet.size < 40) return null
        val payloadLen = readShort(packet, 4) and 0xFFFF
        if (payloadLen == 0 || 40 + payloadLen > packet.size) return null
        val next = packet[6].toInt() and 0xFF
        // Extension headers (fragment, hop-by-hop, …) are not supported — the relay only
        // handles directly-addressed TCP/UDP (ponytail: spike ceiling, note in docs).
        if (next != PROTO_TCP && next != PROTO_UDP) return null
        return IpHeader(
            6, next,
            packet.copyOfRange(8, 24), packet.copyOfRange(24, 40),
            40, payloadLen,
        )
    }

    fun parseTcp(ip: IpHeader, packet: ByteArray): TcpHeader? {
        if (ip.protocol != PROTO_TCP) return null
        val o = ip.payloadOffset
        if (ip.payloadLength < 20 || o + 20 > packet.size) return null
        // Mask before shifting: the data-offset byte has its high bit set whenever the
        // header carries options (e.g. 0xa0 = 40-byte header); sign-extension made it a
        // huge positive value and dropped every optioned SYN on-device.
        val dataOffset = ((packet[o + 12].toInt() and 0xFF) ushr 4) * 4
        if (dataOffset < 20 || o + dataOffset > packet.size) return null
        val flags = packet[o + 13].toInt() and 0xFF
        return TcpHeader(
            srcPort = readShort(packet, o) and 0xFFFF,
            dstPort = readShort(packet, o + 2) and 0xFFFF,
            seq = (readShort(packet, o + 4) shl 16) or readShort(packet, o + 6),
            ack = (readShort(packet, o + 8) shl 16) or readShort(packet, o + 10),
            syn = flags and 0x02 != 0,
            ackFlag = flags and 0x10 != 0,
            fin = flags and 0x01 != 0,
            rst = flags and 0x04 != 0,
            window = readShort(packet, o + 14) and 0xFFFF,
            payloadOffset = o + dataOffset,
            payloadLength = ip.payloadLength - dataOffset,
        )
    }

    fun parseUdp(ip: IpHeader, packet: ByteArray): UdpHeader? {
        if (ip.protocol != PROTO_UDP) return null
        val o = ip.payloadOffset
        if (ip.payloadLength < 8 || o + 8 > packet.size) return null
        val len = readShort(packet, o + 4) and 0xFFFF
        val payloadLen = if (len >= 8) len - 8 else ip.payloadLength - 8
        if (o + 8 + payloadLen > packet.size) return null
        return UdpHeader(
            readShort(packet, o) and 0xFFFF,
            readShort(packet, o + 2) and 0xFFFF,
            o + 8, payloadLen,
        )
    }

    // ---- building ----

    /**
     * Full IP packet carrying one TCP segment. src/dst are the packet addresses as they
     * appear on the wire (the caller passes the already-swapped direction). No options are
     * advertised: the device keeps its default MSS (536), safely below the TUN MTU.
     */
    fun buildTcpSegment(
        src: ByteArray, dst: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Int, ack: Int,
        syn: Boolean = false, ackFlag: Boolean = false, fin: Boolean = false, rst: Boolean = false, psh: Boolean = false,
        payload: ByteArray = EMPTY, payloadOffset: Int = 0, payloadLength: Int = payload.size,
    ): ByteArray {
        val isV6 = src.size == 16
        val tcpLen = 20 + payloadLength
        val base = if (isV6) 40 else 20
        val buf = ByteBuffer.allocate(base + tcpLen)
        if (isV6) {
            buf.put(0x60.toByte()); buf.put(0); buf.put(0); buf.put(0)
            buf.putShort(tcpLen.toShort())
            buf.put(PROTO_TCP.toByte()); buf.put(64)
            buf.put(src); buf.put(dst)
        } else {
            buf.put(0x45.toByte()); buf.put(0)
            buf.putShort((base + tcpLen).toShort())
            buf.putShort(0); buf.putShort(0x4000) // DF, no fragment
            buf.put(64); buf.put(PROTO_TCP.toByte())
            buf.putShort(0) // header checksum placeholder
            buf.put(src); buf.put(dst)
        }
        buf.putShort(srcPort.toShort()); buf.putShort(dstPort.toShort())
        buf.putInt(seq); buf.putInt(ack)
        buf.put(0x50) // data offset (5) + reserved
        var flags = 0
        if (fin) flags = flags or 0x01
        if (syn) flags = flags or 0x02
        if (rst) flags = flags or 0x04
        if (psh) flags = flags or 0x08
        if (ackFlag) flags = flags or 0x10
        buf.put(flags.toByte())
        buf.putShort(65535.toShort()) // window
        buf.putShort(0) // checksum placeholder
        buf.putShort(0) // urgent
        if (payloadLength > 0) buf.put(payload, payloadOffset, payloadLength)

        val array = buf.array()
        if (!isV6) {
            val hc = headerChecksumV4(array, 0, 20)
            array[10] = (hc ushr 8).toByte(); array[11] = hc.toByte()
        }
        val pseudo = if (isV6) v6Pseudo(src, dst, PROTO_TCP, tcpLen) else v4Pseudo(src, dst, PROTO_TCP, tcpLen)
        val c = upperChecksum(pseudo, array, base, tcpLen)
        array[base + 16] = (c ushr 8).toByte(); array[base + 17] = c.toByte()
        return array
    }

    /** Full IP packet carrying one UDP datagram (RFC 768: checksum 0 is reserved — write 0xFFFF instead). */
    fun buildUdpPacket(
        src: ByteArray, dst: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray = EMPTY, payloadOffset: Int = 0, payloadLength: Int = payload.size,
    ): ByteArray {
        val isV6 = src.size == 16
        val udpLen = 8 + payloadLength
        val base = if (isV6) 40 else 20
        val buf = ByteBuffer.allocate(base + udpLen)
        if (isV6) {
            buf.put(0x60.toByte()); buf.put(0); buf.put(0); buf.put(0)
            buf.putShort(udpLen.toShort())
            buf.put(PROTO_UDP.toByte()); buf.put(64)
            buf.put(src); buf.put(dst)
        } else {
            buf.put(0x45.toByte()); buf.put(0)
            buf.putShort((base + udpLen).toShort())
            buf.putShort(0); buf.putShort(0x4000)
            buf.put(64); buf.put(PROTO_UDP.toByte())
            buf.putShort(0)
            buf.put(src); buf.put(dst)
        }
        buf.putShort(srcPort.toShort()); buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0) // checksum placeholder
        if (payloadLength > 0) buf.put(payload, payloadOffset, payloadLength)

        val array = buf.array()
        if (!isV6) {
            // The device kernel verifies the IPv4 header checksum on TUN ingress and
            // silently drops packets with a bad one — a zero field broke all v4 replies
            // on-device while v6 (which has no header checksum) worked.
            val hc = headerChecksumV4(array, 0, 20)
            array[10] = (hc ushr 8).toByte(); array[11] = hc.toByte()
        }
        val pseudo = if (isV6) v6Pseudo(src, dst, PROTO_UDP, udpLen) else v4Pseudo(src, dst, PROTO_UDP, udpLen)
        var c = upperChecksum(pseudo, array, base, udpLen)
        if (c == 0) c = 0xFFFF
        array[base + 6] = (c ushr 8).toByte(); array[base + 7] = c.toByte()
        return array
    }

    /**
     * §6.10's ICMP Port-Unreachable for one datagram: addresses swapped, quoted payload the
     * original IP header plus the first 8 bytes of its payload (RFC 792/4443 — the 8 bytes carry
     * the UDP ports, which is what lets the client match the error to its own socket).
     *
     * INV-14: every length and offset is validated against [packet]; anything inconsistent returns
     * null so the caller falls back to a silent drop rather than writing a malformed packet.
     */
    fun buildIcmpPortUnreachable(ip: IpHeader, packet: ByteArray): ByteArray? {
        val headerLen = ip.payloadOffset
        val expectedAddr = if (ip.isV6) 16 else 4
        if (ip.src.size != expectedAddr || ip.dst.size != expectedAddr) return null
        if (headerLen < (if (ip.isV6) 40 else 20) || headerLen > packet.size) return null
        if (ip.payloadLength < 0 || headerLen + ip.payloadLength > packet.size) return null

        val quotedLen = headerLen + minOf(8, ip.payloadLength)
        val icmpLen = 8 + quotedLen
        val base = if (ip.isV6) 40 else 20
        val buf = ByteBuffer.allocate(base + icmpLen)
        // RFC 792/4443: the error goes back to the datagram's source, from its destination.
        val src = ip.dst
        val dst = ip.src
        if (ip.isV6) {
            buf.put(0x60.toByte()); buf.put(0); buf.put(0); buf.put(0)
            buf.putShort(icmpLen.toShort())
            buf.put(PROTO_ICMPV6.toByte()); buf.put(64)
            buf.put(src); buf.put(dst)
        } else {
            buf.put(0x45.toByte()); buf.put(0)
            buf.putShort((base + icmpLen).toShort())
            buf.putShort(0); buf.putShort(0x4000) // DF, no fragment
            buf.put(64); buf.put(PROTO_ICMP.toByte())
            buf.putShort(0) // header checksum placeholder
            buf.put(src); buf.put(dst)
        }
        buf.put(if (ip.isV6) ICMPV6_TYPE_PORT_UNREACHABLE.toByte() else ICMP_TYPE_PORT_UNREACHABLE.toByte())
        buf.put(if (ip.isV6) ICMPV6_CODE_PORT_UNREACHABLE.toByte() else ICMP_CODE_PORT_UNREACHABLE.toByte())
        buf.putShort(0) // checksum placeholder
        buf.putInt(0) // unused
        buf.put(packet, 0, quotedLen)

        val array = buf.array()
        if (!ip.isV6) {
            val hc = headerChecksumV4(array, 0, 20)
            array[10] = (hc ushr 8).toByte(); array[11] = hc.toByte()
        }
        // ICMPv4 checksums over its own message; ICMPv6 appends the pseudo-header.
        val c = if (ip.isV6) {
            upperChecksum(v6Pseudo(src, dst, PROTO_ICMPV6, icmpLen), array, base, icmpLen)
        } else {
            complement(fold16(sum16(array, base, icmpLen)))
        }
        array[base + 2] = (c ushr 8).toByte(); array[base + 3] = c.toByte()
        return array
    }
}
