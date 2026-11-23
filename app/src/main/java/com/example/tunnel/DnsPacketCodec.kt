package com.example.tunnel

import java.io.ByteArrayOutputStream
import java.net.InetAddress

data class DnsResponseInfo(
    val transactionId: Int,
    val responseCode: Int,
    val questionCount: Int,
    val answerCount: Int,
    val resolvedIps: List<String>
)

data class TunUdpPacket(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray
)

/**
 * Pure Kotlin RFC-1035 DNS wire-format codec and IPv4/UDP TUN packet processor.
 */
object DnsPacketCodec {

    const val TYPE_A = 1
    const val TYPE_NS = 2
    const val TYPE_TXT = 16

    fun buildDnsQueryPacket(
        domain: String,
        queryType: Int = TYPE_A,
        transactionId: Int = 0x1A2B
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // Header: ID (2), Flags (2: standard recursive query 0x0100), QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
        out.write((transactionId ushr 8) and 0xFF)
        out.write(transactionId and 0xFF)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        // QNAME labels
        val labels = domain.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            val len = bytes.size.coerceAtMost(63)
            out.write(len)
            out.write(bytes, 0, len)
        }
        out.write(0x00) // root terminator

        // QTYPE (2) and QCLASS=IN (1)
        out.write((queryType ushr 8) and 0xFF)
        out.write(queryType and 0xFF)
        out.write(0x00)
        out.write(0x01)
        return out.toByteArray()
    }

    fun parseDnsResponse(packet: ByteArray, length: Int = packet.size): DnsResponseInfo? {
        if (length < 12 || length > packet.size) return null
        val txId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val flags = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val rcode = flags and 0x0F
        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val anCount = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)

        var offset = 12
        repeat(qdCount) {
            offset = skipDnsName(packet, offset, length)
            if (offset < 0 || offset + 4 > length) {
                return DnsResponseInfo(txId, rcode, qdCount, anCount, emptyList())
            }
            offset += 4
        }

        val resolvedIps = mutableListOf<String>()
        repeat(anCount) {
            offset = skipDnsName(packet, offset, length)
            if (offset < 0 || offset + 10 > length) {
                return DnsResponseInfo(txId, rcode, qdCount, anCount, resolvedIps)
            }
            val rrType = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
            val rdLen = ((packet[offset + 8].toInt() and 0xFF) shl 8) or (packet[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdLen > length) {
                return DnsResponseInfo(txId, rcode, qdCount, anCount, resolvedIps)
            }
            if (rrType == TYPE_A && rdLen == 4) {
                val ip = runCatching {
                    InetAddress.getByAddress(packet.copyOfRange(offset, offset + 4)).hostAddress
                }.getOrNull()
                if (!ip.isNullOrBlank()) resolvedIps.add(ip)
            }
            offset += rdLen
        }

        return DnsResponseInfo(
            transactionId = txId,
            responseCode = rcode,
            questionCount = qdCount,
            answerCount = anCount,
            resolvedIps = resolvedIps
        )
    }

    private fun skipDnsName(packet: ByteArray, start: Int, length: Int): Int {
        var idx = start
        var steps = 0
        while (idx < length && steps < 128) {
            val len = packet[idx].toInt() and 0xFF
            if (len == 0) return idx + 1
            if ((len and 0xC0) == 0xC0) {
                return if (idx + 2 <= length) idx + 2 else -1
            }
            idx += 1 + len
            steps++
        }
        return -1
    }

    fun parseTunUdpPacket(packet: ByteArray, length: Int): TunUdpPacket? {
        if (length < 28 || length > packet.size) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null // UDP only

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        if (udpLen < 8) return null
        val payloadEnd = minOf(length, ihl + udpLen)
        if (payloadEnd < ihl + 8) return null
        val payload = packet.copyOfRange(ihl + 8, payloadEnd)

        return TunUdpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            payload = payload
        )
    }

    fun buildTunUdpResponsePacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpLen = 8 + payload.size
        val totalLen = ipHeaderLen + udpLen
        val packet = ByteArray(totalLen)

        // IPv4 Header
        packet[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x01.toByte()
        packet[6] = 0x40.toByte() // Don't fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 17.toByte()   // UDP
        // Checksum [10..11] filled below
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        val ipChecksum = computeChecksum(packet, 0, ipHeaderLen)
        packet[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header
        val u = ipHeaderLen
        packet[u] = ((srcPort ushr 8) and 0xFF).toByte()
        packet[u + 1] = (srcPort and 0xFF).toByte()
        packet[u + 2] = ((dstPort ushr 8) and 0xFF).toByte()
        packet[u + 3] = (dstPort and 0xFF).toByte()
        packet[u + 4] = ((udpLen ushr 8) and 0xFF).toByte()
        packet[u + 5] = (udpLen and 0xFF).toByte()
        // Payload
        System.arraycopy(payload, 0, packet, u + 8, payload.size)

        val udpChecksum = computeUdpChecksum(srcIp, dstIp, packet, u, udpLen)
        packet[u + 6] = ((udpChecksum ushr 8) and 0xFF).toByte()
        packet[u + 7] = (udpChecksum and 0xFF).toByte()

        return packet
    }

    fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun computeUdpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        packet: ByteArray,
        udpOffset: Int,
        udpLength: Int
    ): Int {
        var sum = 0L
        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 17L // UDP protocol
        sum += udpLength.toLong()

        var i = udpOffset
        val end = udpOffset + udpLength
        while (i + 1 < end) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        val result = (sum.inv() and 0xFFFFL).toInt()
        return if (result == 0) 0xFFFF else result
    }
}
