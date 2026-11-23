package com.example.tunnel

import android.net.Network
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Exact wire-level dnstt (SlowDNS) DNS transport encoder/decoder & adaptive poller.
 *
 * Compatible with David Fifield's dnstt-server:
 * - Upstream QNAME: Base32-lowercase-unpadded([8B ClientID][0xe0 + padLen][pad][len][kcpPacket]).<domain>
 * - Poll QNAME:     Base32-lowercase-unpadded([8B ClientID][0xe8][8 random bytes]).<domain>
 * - Downstream TXT: Concatenated RFC-1035 TXT character strings containing 0..N packets,
 *                   each prefixed with a 2-byte big-endian uint16 length.
 */
object DnsttCodec {

    private const val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    const val CLIENT_ID_LEN = 8
    const val NUM_PADDING_DATA = 3
    const val NUM_PADDING_POLL = 8
    const val PADDING_PREFIX_BASE = 0xE0 // 224
    const val MAX_DATA_CHUNK_LEN = 0xDF  // 223

    private val secureRandom = SecureRandom()

    fun generateClientId(): ByteArray = ByteArray(CLIENT_ID_LEN).also { secureRandom.nextBytes(it) }

    /**
     * Computes maximum safe raw KCP packet length that fits within RFC-1035 255-octet QNAME limit
     * for the given tunnel nameserver domain (e.g., "nsts3.me-link.uk" -> ~128 bytes).
     */
    fun computeMaxKcpMtu(tunnelDomain: String, qnameLimit: Int = 255): Int {
        val domainLabels = tunnelDomain.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
        var domainWireLen = 1 // terminating root zero byte
        for (lbl in domainLabels) {
            domainWireLen += 1 + lbl.length
        }
        val availableOctets = (qnameLimit.coerceIn(120, 255) - domainWireLen).coerceAtLeast(32)
        // Each full 63-char label consumes 64 octets (1 length byte + 63 chars).
        val fullLabels = availableOctets / 64
        val remOctets = availableOctets % 64
        val remChars = if (remOctets > 1) remOctets - 1 else 0
        val maxBase32Chars = fullLabels * 63 + remChars
        val maxRawBytes = (maxBase32Chars * 5) / 8
        // Overhead: ClientID (8) + padPrefix (1) + NUM_PADDING_DATA (3) + dataLenPrefix (1) = 13 bytes
        val overhead = CLIENT_ID_LEN + 1 + NUM_PADDING_DATA + 1
        return (maxRawBytes - overhead).coerceIn(48, MAX_DATA_CHUNK_LEN)
    }

    /**
     * Encodes an upstream KCP packet (or empty poll packet) into DNS QNAME labels + tunnel domain.
     */
    fun encodeQueryDomain(
        clientId: ByteArray,
        payload: ByteArray,
        tunnelDomain: String,
        random: SecureRandom = secureRandom
    ): String {
        require(clientId.size == CLIENT_ID_LEN) { "ClientID must be $CLIENT_ID_LEN bytes" }
        require(payload.size <= MAX_DATA_CHUNK_LEN) { "Payload exceeds dnstt 223-byte limit" }

        val buf = ByteArrayOutputStream(CLIENT_ID_LEN + 16 + payload.size)
        buf.write(clientId)
        if (payload.isEmpty()) {
            buf.write(PADDING_PREFIX_BASE + NUM_PADDING_POLL)
            val pad = ByteArray(NUM_PADDING_POLL)
            random.nextBytes(pad)
            buf.write(pad)
        } else {
            buf.write(PADDING_PREFIX_BASE + NUM_PADDING_DATA)
            val pad = ByteArray(NUM_PADDING_DATA)
            random.nextBytes(pad)
            buf.write(pad)
            buf.write(payload.size and 0xFF)
            buf.write(payload)
        }

        val b32 = encodeBase32Lower(buf.toByteArray())
        val sb = StringBuilder(b32.length + tunnelDomain.length + 8)
        var i = 0
        while (i < b32.length) {
            if (sb.isNotEmpty()) sb.append('.')
            val end = minOf(i + 63, b32.length)
            sb.append(b32, i, end)
            i = end
        }
        val cleanDomain = tunnelDomain.trim().trimStart('.').trimEnd('.')
        if (cleanDomain.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(cleanDomain)
        }
        return sb.toString()
    }

    /**
     * RFC 4648 standard Base32 lowercase encoder without '=' padding.
     */
    fun encodeBase32Lower(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val idx = (buffer ushr bitsLeft) and 0x1F
                out.append(BASE32_ALPHABET[idx])
            }
        }
        if (bitsLeft > 0) {
            val idx = (buffer shl (5 - bitsLeft)) and 0x1F
            out.append(BASE32_ALPHABET[idx])
        }
        return out.toString()
    }

    /**
     * RFC 4648 Base32 decoder (case-insensitive, padding optional).
     */
    fun decodeBase32(encoded: String): ByteArray {
        val out = ByteArrayOutputStream((encoded.length * 5) / 8 + 1)
        var buffer = 0
        var bitsLeft = 0
        for (rawCh in encoded) {
            if (rawCh == '=' || rawCh == '.' || rawCh.isWhitespace()) continue
            val ch = rawCh.lowercaseChar()
            val value = when (ch) {
                in 'a'..'z' -> ch - 'a'
                in '2'..'7' -> (ch - '2') + 26
                else -> throw IllegalArgumentException("Invalid Base32 char: $rawCh")
            }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer ushr bitsLeft) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /**
     * Extracts raw concatenated TXT RDATA bytes from a DNS wire response packet.
     * Returns null if RCODE != 0 or no valid TXT answer record is present.
     */
    fun extractTxtPayloadFromDnsResponse(packet: ByteArray, length: Int = packet.size): ByteArray? {
        if (length < 12 || length > packet.size) return null
        val flags = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        val rcode = flags and 0x0F
        if (!isResponse || rcode != 0) return null

        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        val anCount = ((packet[6].toInt() and 0xFF) shl 8) or (packet[7].toInt() and 0xFF)
        if (anCount <= 0) return ByteArray(0)

        var offset = 12
        repeat(qdCount) {
            offset = skipDnsName(packet, offset, length)
            if (offset < 0 || offset + 4 > length) return null
            offset += 4
        }

        val combined = ByteArrayOutputStream()
        var foundTxt = false
        repeat(anCount) {
            offset = skipDnsName(packet, offset, length)
            if (offset < 0 || offset + 10 > length) return if (foundTxt) combined.toByteArray() else null
            val rrType = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
            val rdLen = ((packet[offset + 8].toInt() and 0xFF) shl 8) or (packet[offset + 9].toInt() and 0xFF)
            offset += 10
            if (offset + rdLen > length) return if (foundTxt) combined.toByteArray() else null
            if (rrType == DnsPacketCodec.TYPE_TXT) {
                foundTxt = true
                // Decode RFC-1035 TXT character-strings: [1-byte length][data]...
                var r = offset
                val rEnd = offset + rdLen
                while (r < rEnd) {
                    val strLen = packet[r].toInt() and 0xFF
                    r++
                    if (r + strLen > rEnd) break
                    combined.write(packet, r, strLen)
                    r += strLen
                }
            }
            offset += rdLen
        }
        return if (foundTxt) combined.toByteArray() else null
    }

    /**
     * Unpacks 0..N downstream packets from dnstt TXT payload.
     * Each downstream packet is prefixed with a 2-byte big-endian uint16 length.
     */
    fun unpackDownstreamPackets(txtPayload: ByteArray): List<ByteArray> {
        if (txtPayload.isEmpty()) return emptyList()
        val result = mutableListOf<ByteArray>()
        var idx = 0
        while (idx + 2 <= txtPayload.size) {
            val pktLen = ((txtPayload[idx].toInt() and 0xFF) shl 8) or (txtPayload[idx + 1].toInt() and 0xFF)
            idx += 2
            if (pktLen == 0) continue
            if (idx + pktLen > txtPayload.size) break
            result.add(txtPayload.copyOfRange(idx, idx + pktLen))
            idx += pktLen
        }
        return result
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
}

data class DnsttProbeResult(
    val reachable: Boolean,
    val rttMs: Long? = null,
    val workingResolverIp: String? = null,
    val workingTransport: String? = null,
    val includeEdns0: Boolean = true,
    val ednsSize: Int = 512,
    val diagnostics: List<String> = emptyList()
)

/**
 * Full-duplex asynchronous DNS packet transport for dnstt over UDP:53, TCP:53, or DoH:443.
 * Supports concurrent worker threads so SlowDNS throughput stays responsive even on high-latency 4G links.
 */
class DnsttPacketTransport(
    private var resolverIp: String,
    private var transportType: String,
    private val tunnelDomain: String,
    private var ednsPayloadSize: Int,
    private val dohUrl: String? = null,
    private val dohClient: OkHttpClient? = null,
    private val underlyingNetwork: Network? = null,
    private val protectUdp: ((DatagramSocket) -> Boolean)? = null,
    private val protectTcp: ((Socket) -> Boolean)? = null,
    private val onBytesTransferred: ((txDelta: Long, rxDelta: Long, rttMs: Long?) -> Unit)? = null,
    private val onLog: ((String) -> Unit)? = null
) {
    val clientId: ByteArray = DnsttCodec.generateClientId()
    val maxKcpMtu: Int = DnsttCodec.computeMaxKcpMtu(tunnelDomain)

    private val running = AtomicBoolean(false)
    private val sendQueue = LinkedBlockingQueue<ByteArray>(512)
    private val recvQueue = LinkedBlockingQueue<ByteArray>(1024)
    private val workers = mutableListOf<Thread>()
    private val lastValidResponseAtMs = AtomicLong(0L)
    private val totalResponsesReceived = AtomicLong(0L)

    @Volatile
    private var includeEdns0InLoop: Boolean = true

    @Volatile
    private var resolverAddr: InetAddress? = null

    fun lastResponseTimestampMs(): Long = lastValidResponseAtMs.get()
    fun validResponseCount(): Long = totalResponsesReceived.get()

    fun start(workerCount: Int = 3) {
        if (!running.compareAndSet(false, true)) return
        resolverAddr = runCatching { InetAddress.getByName(resolverIp) }.getOrNull()
        val count = workerCount.coerceIn(1, 6)
        repeat(count) { idx ->
            val t = Thread({ workerLoop(idx) }, "dnstt-dns-worker-$idx").apply {
                isDaemon = true
                start()
            }
            workers.add(t)
        }
    }

    fun enqueueSend(kcpPacket: ByteArray) {
        if (!running.get()) return
        if (kcpPacket.size > maxKcpMtu) return
        if (!sendQueue.offer(kcpPacket)) {
            sendQueue.poll()
            sendQueue.offer(kcpPacket)
        }
    }

    fun pollReceived(timeoutMs: Long): ByteArray? {
        return runCatching {
            recvQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    /**
     * Probes SlowDNS (dnstt-server) across multiple wire strategies:
     * 1) Active resolver (e.g. GP 192.0.0.1 / IPv6 DNS) with EDNS0=512
     * 2) Active resolver WITHOUT EDNS0 OPT (for strict carrier resolvers that drop OPT on TXT queries)
     * 3) Direct UDP:53 to the SlowDNS server IP itself (HTTP Custom "slowDnsHandling: direct")
     * 4) TCP:53 fallback
     * Returns detailed wire RCODE/timeout diagnostics for every tested path.
     */
    fun verifyDnsttServerReachable(
        timeoutMs: Int = 2500,
        candidateResolverIps: List<String> = listOf(resolverIp),
        directServerIp: String? = null
    ): DnsttProbeResult {
        val diagNotes = mutableListOf<String>()
        val qname = DnsttCodec.encodeQueryDomain(clientId, ByteArray(0), tunnelDomain)

        data class ProbeAttempt(
            val label: String,
            val targetIp: String,
            val mode: String,
            val useEdns: Boolean,
            val ednsBytes: Int
        )

        val attempts = buildList {
            for (ip in candidateResolverIps.distinct()) {
                if (transportType == "DoH:443") {
                    add(ProbeAttempt("DoH($ip)", ip, "DoH:443", true, 512))
                } else {
                    add(ProbeAttempt("Carrier($ip,EDNS512)", ip, "UDP:53", true, 512))
                    add(ProbeAttempt("Carrier($ip,NoEDNS)", ip, "UDP:53", false, 512))
                }
            }
            if (!directServerIp.isNullOrBlank() && directServerIp !in candidateResolverIps) {
                add(ProbeAttempt("Direct($directServerIp:53)", directServerIp, "UDP:53", true, 512))
                add(ProbeAttempt("Direct($directServerIp:53,NoEDNS)", directServerIp, "UDP:53", false, 512))
            }
            if (transportType != "DoH:443") {
                add(ProbeAttempt("TCP:53(${candidateResolverIps.firstOrNull() ?: resolverIp})", candidateResolverIps.firstOrNull() ?: resolverIp, "TCP:53", true, 512))
            }
        }

        for (att in attempts) {
            val addr = runCatching { InetAddress.getByName(att.targetIp) }.getOrNull()
            if (addr == null) {
                diagNotes.add("${att.label}: invalid IP")
                continue
            }
            val txId = ((System.nanoTime() xor att.hashCode().toLong()) and 0xFFFFL).toInt()
            val query = DnsPacketCodec.buildDnsQueryPacket(
                domain = qname,
                queryType = DnsPacketCodec.TYPE_TXT,
                transactionId = txId,
                includeEdns0 = att.useEdns,
                ednsUdpPayloadSize = att.ednsBytes
            )
            val t0 = System.currentTimeMillis()
            val prevTransport = transportType
            transportType = att.mode
            val reply = exchangeDnsWire(query, addr, timeoutMs)
            transportType = prevTransport

            if (reply == null) {
                diagNotes.add("${att.label} -> Timeout(${timeoutMs}ms)")
                continue
            }

            val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            val info = DnsPacketCodec.parseDnsResponse(reply, reply.size)
            val txt = DnsttCodec.extractTxtPayloadFromDnsResponse(reply, reply.size)
            if (txt != null) {
                val unpacked = DnsttCodec.unpackDownstreamPackets(txt)
                if (info?.answerCount ?: 0 > 0) {
                    diagNotes.add("${att.label} -> TXT OK (${txt.size}B, ${rtt}ms)")
                    resolverIp = att.targetIp
                    resolverAddr = addr
                    transportType = att.mode
                    includeEdns0InLoop = att.useEdns
                    ednsPayloadSize = att.ednsBytes
                    lastValidResponseAtMs.set(System.currentTimeMillis())
                    totalResponsesReceived.incrementAndGet()
                    unpacked.forEach { recvQueue.offer(it) }
                    onBytesTransferred?.invoke(query.size.toLong(), reply.size.toLong(), rtt)
                    return DnsttProbeResult(
                        reachable = true,
                        rttMs = rtt,
                        workingResolverIp = att.targetIp,
                        workingTransport = att.mode,
                        includeEdns0 = att.useEdns,
                        ednsSize = att.ednsBytes,
                        diagnostics = diagNotes
                    )
                } else {
                    diagNotes.add("${att.label} -> NOERROR AN=0 (${rtt}ms)")
                }
            } else {
                val rcodeName = when (info?.responseCode) {
                    1 -> "FORMERR"
                    2 -> "SERVFAIL"
                    3 -> "NXDOMAIN"
                    5 -> "REFUSED"
                    else -> "RCODE=${info?.responseCode}"
                }
                diagNotes.add("${att.label} -> $rcodeName AN=${info?.answerCount ?: 0} (${rtt}ms)")
            }
        }

        return DnsttProbeResult(
            reachable = false,
            diagnostics = diagNotes
        )
    }

    private fun workerLoop(workerIndex: Int) {
        val addr = resolverAddr ?: runCatching { InetAddress.getByName(resolverIp) }.getOrNull() ?: return
        var pollBackoffMs = if (workerIndex == 0) 90L else 260L
        val maxBackoffMs = if (workerIndex == 0) 900L else 1800L
        var txSeq = (workerIndex * 1000 + (System.nanoTime() and 0xFFF).toInt()) and 0xFFFF

        while (running.get()) {
            val outbound = runCatching {
                sendQueue.poll(pollBackoffMs, TimeUnit.MILLISECONDS)
            }.getOrNull()
            if (!running.get()) break

            val payload = outbound ?: ByteArray(0)
            // Only worker 0..1 send empty background polls when queue is idle to avoid flooding GP resolver
            if (payload.isEmpty() && workerIndex > 1) {
                continue
            }

            txSeq = (txSeq + 1) and 0xFFFF
            val qname = DnsttCodec.encodeQueryDomain(clientId, payload, tunnelDomain)
            val query = DnsPacketCodec.buildDnsQueryPacket(
                domain = qname,
                queryType = DnsPacketCodec.TYPE_TXT,
                transactionId = txSeq,
                includeEdns0 = includeEdns0InLoop,
                ednsUdpPayloadSize = ednsPayloadSize
            )

            val t0 = System.currentTimeMillis()
            val reply = exchangeDnsWire(query, addr, timeoutMs = 3200)
            if (reply != null) {
                val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                val txt = DnsttCodec.extractTxtPayloadFromDnsResponse(reply, reply.size)
                onBytesTransferred?.invoke(query.size.toLong(), reply.size.toLong(), rtt)
                if (txt != null) {
                    lastValidResponseAtMs.set(System.currentTimeMillis())
                    totalResponsesReceived.incrementAndGet()
                    val packets = DnsttCodec.unpackDownstreamPackets(txt)
                    if (packets.isNotEmpty()) {
                        for (pkt in packets) {
                            recvQueue.offer(pkt)
                        }
                        // Data is flowing! Poll aggressively for more downstream chunks
                        pollBackoffMs = 35L
                    } else {
                        pollBackoffMs = (pollBackoffMs + 90L).coerceAtMost(maxBackoffMs)
                    }
                } else {
                    pollBackoffMs = (pollBackoffMs + 150L).coerceAtMost(maxBackoffMs)
                }
            } else {
                onBytesTransferred?.invoke(query.size.toLong(), 0L, null)
                pollBackoffMs = (pollBackoffMs + 220L).coerceAtMost(maxBackoffMs)
            }
        }
    }

    private fun exchangeDnsWire(query: ByteArray, addr: InetAddress, timeoutMs: Int): ByteArray? {
        return when (transportType) {
            "DoH:443" -> {
                val client = dohClient ?: return null
                val url = dohUrl ?: GpNetworkInspector.CLOUDFLARE_DOH_URL
                GpNetworkInspector.sendDohQuery(client, url, query)?.first
            }

            "TCP:53" -> exchangeTcpDns(query, addr, timeoutMs)

            else -> exchangeUdpDns(query, addr, timeoutMs)
        }
    }

    private fun exchangeUdpDns(query: ByteArray, addr: InetAddress, timeoutMs: Int): ByteArray? {
        return runCatching {
            DatagramSocket().use { sock ->
                protectUdp?.invoke(sock)
                runCatching { underlyingNetwork?.bindSocket(sock) }
                sock.soTimeout = timeoutMs
                sock.send(DatagramPacket(query, query.size, addr, 53))
                val respBuf = ByteArray(2048)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                sock.receive(respPkt)
                respBuf.copyOfRange(0, respPkt.length)
            }
        }.getOrNull()
    }

    private fun exchangeTcpDns(query: ByteArray, addr: InetAddress, timeoutMs: Int): ByteArray? {
        return runCatching {
            Socket().use { tcp ->
                protectTcp?.invoke(tcp)
                runCatching { underlyingNetwork?.bindSocket(tcp) }
                tcp.connect(InetSocketAddress(addr, 53), timeoutMs)
                tcp.soTimeout = timeoutMs
                val dos = DataOutputStream(tcp.getOutputStream())
                dos.writeShort(query.size)
                dos.write(query)
                dos.flush()
                val dis = DataInputStream(tcp.getInputStream())
                val len = dis.readUnsignedShort()
                if (len in 12..4096) {
                    ByteArray(len).also { dis.readFully(it) }
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        workers.forEach { runCatching { it.interrupt() } }
        workers.clear()
        sendQueue.clear()
        recvQueue.clear()
        onLog?.invoke("dnstt DNS transport closed")
    }
}
