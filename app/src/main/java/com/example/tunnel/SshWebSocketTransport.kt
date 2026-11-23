package com.example.tunnel

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class SshWebSocketConfig(
    val connectHost: String,
    val port: Int,
    val wsHostHeader: String,
    val sniHost: String = "",
    val wsPath: String = "/",
    val useTls: Boolean = true,
    val customPayload: String = "",
    val splitPayload: Boolean = false,
    val fragmentTlsClientHello: Boolean = false,
    val includeWebSocketKey: Boolean = true,
    val label: String = ""
)

data class SshWebSocketConnectResult(
    val socket: Socket,
    val statusCode: Int,
    val statusLine: String,
    val isRfc6455Framed: Boolean,
    val rttMs: Long,
    val summaryLabel: String
)

/**
 * Pure-Kotlin Advanced SSH-over-WebSocket & HTTP Custom Payload Injector supporting:
 * 1) SSL/TLS WebSocket (443, 8443, 2083, 2053, 2087, 2096) with custom SNI / Wildcard & TLS ClientHello DPI Fragmentation
 * 2) HTTP WebSocket (80, 8080, 8880, 2052, 2082, 2086, 2095) with Split-Packet DPI Bypass ([split])
 * 3) Cloudflare Anycast Edge IP Fronting (104.16.x.x, 104.18.x.x, 162.159.x.x, 172.64.x.x -> Host: nl1.wssht.to)
 * 4) Full HTTP Custom Payload macro rendering ([crlf], [lf], [cr], [split], [delay_split], [host], [host_port], [sni], [ua], [protocol])
 * 5) Auto-detection of BOTH direct ws-openssh (raw SSH-2.0 after 101) AND Cloudflare RFC 6455 Binary WebSocket frames!
 */
object SshWebSocketTransport {

    private val secureRandom = SecureRandom()
    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36"

    const val PRESET_SSHOCEAN_STANDARD =
        "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]"

    const val PRESET_GP_SPLIT_DPI =
        "GET http://[sni]/ HTTP/1.1[crlf]Host: [sni][crlf][crlf][split]GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]"

    const val PRESET_BACK_INJECT =
        "CONNECT [host_port] HTTP/1.1[crlf][crlf][split]GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf][crlf]"

    fun generateWebSocketKey(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Renders an HTTP Custom payload template into 1 or more split chunks ready for sequential TCP write/flush.
     * Supports [crlf], [lf], [cr], [split], [delay_split], [host], [host_port], [sni], [ua], [protocol].
     */
    fun renderPayloadChunks(
        template: String,
        hostHeader: String,
        port: Int,
        sniHost: String,
        wsPath: String = "/",
        wsKey: String? = null
    ): List<ByteArray> {
        val cleanHost = hostHeader.trim()
        val cleanSni = sniHost.trim().ifBlank { cleanHost }
        val cleanPath = wsPath.trim().ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }

        var expanded = template
            .replace("[host_port]", "$cleanHost:$port", ignoreCase = true)
            .replace("[host]", cleanHost, ignoreCase = true)
            .replace("[sni]", cleanSni, ignoreCase = true)
            .replace("[bug_host]", cleanSni, ignoreCase = true)
            .replace("[ua]", DEFAULT_USER_AGENT, ignoreCase = true)
            .replace("[protocol]", "HTTP/1.1", ignoreCase = true)
            .replace("[crlf]", "\r\n", ignoreCase = true)
            .replace("[lf]", "\n", ignoreCase = true)
            .replace("[cr]", "\r", ignoreCase = true)

        if (cleanPath != "/" && expanded.startsWith("GET / ")) {
            expanded = expanded.replaceFirst("GET / ", "GET $cleanPath ")
        }

        // Inject RFC 6455 WebSocket headers if requested and not already present in custom payload
        if (!wsKey.isNullOrBlank() &&
            !expanded.contains("Sec-WebSocket-Key", ignoreCase = true) &&
            expanded.contains("Upgrade: websocket", ignoreCase = true)
        ) {
            val endMarkerIdx = expanded.lastIndexOf("\r\n\r\n")
            if (endMarkerIdx >= 0) {
                expanded = expanded.substring(0, endMarkerIdx) +
                    "\r\nSec-WebSocket-Key: $wsKey\r\nSec-WebSocket-Version: 13\r\n\r\n" +
                    expanded.substring(endMarkerIdx + 4)
            }
        }

        val splitRegex = Regex("\\[(?:delay_)?split]", RegexOption.IGNORE_CASE)
        return expanded.split(splitRegex)
            .filter { it.isNotEmpty() }
            .map { it.toByteArray(Charsets.US_ASCII) }
    }

    fun buildUpgradeRequest(
        wsPath: String,
        hostHeader: String,
        wsKey: String = generateWebSocketKey(),
        includeWebSocketKey: Boolean = true
    ): ByteArray {
        val cleanPath = wsPath.trim().ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }
        val cleanHost = hostHeader.trim()
        val req = buildString {
            append("GET ").append(cleanPath).append(" HTTP/1.1\r\n")
            append("Host: ").append(cleanHost).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            if (includeWebSocketKey) {
                append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
            }
            append("User-Agent: ").append(DEFAULT_USER_AGENT).append("\r\n")
            append("\r\n")
        }
        return req.toByteArray(Charsets.US_ASCII)
    }

    data class HttpUpgradeResponse(
        val statusCode: Int,
        val statusLine: String,
        val headers: Map<String, String>
    )

    fun readHttpUpgradeResponse(input: InputStream): HttpUpgradeResponse {
        val statusLine = readAsciiLine(input)
            ?: throw IOException("Server closed connection before HTTP response")
        val parts = statusLine.split(' ')
        val statusCode = parts.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Malformed HTTP status line: $statusLine")
        val headers = LinkedHashMap<String, String>()
        var lineCount = 0
        while (lineCount < 64) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                headers[key] = value
            }
            lineCount++
        }
        return HttpUpgradeResponse(statusCode, statusLine, headers)
    }

    /**
     * Connects TCP (+ optional TLS with explicit SNI & optional ClientHello DPI fragmentation),
     * sends HTTP/1.1 WebSocket Upgrade or Custom Split Payload, validates HTTP 101 / 200,
     * auto-detects Raw-SSH vs RFC 6455 Binary WebSocket framing, and returns a Socket ready for JSch.
     */
    fun connect(
        config: SshWebSocketConfig,
        connectTimeoutMs: Int = 5500,
        underlyingNetwork: Network? = null,
        protectTcp: ((Socket) -> Boolean)? = null
    ): SshWebSocketConnectResult {
        val t0 = System.currentTimeMillis()
        val rawSocket = Socket()
        var activeSocket: Socket = rawSocket
        try {
            protectTcp?.invoke(rawSocket)
            if (underlyingNetwork != null) {
                runCatching { underlyingNetwork.bindSocket(rawSocket) }
            }
            val targetEndpoint = if (!isIpLiteral(config.connectHost) && underlyingNetwork != null) {
                val resolved = runCatching { underlyingNetwork.getByName(config.connectHost) }.getOrNull()
                if (resolved != null) InetSocketAddress(resolved, config.port)
                else InetSocketAddress(config.connectHost, config.port)
            } else {
                InetSocketAddress(config.connectHost, config.port)
            }
            rawSocket.connect(targetEndpoint, connectTimeoutMs)
            rawSocket.soTimeout = connectTimeoutMs
            rawSocket.tcpNoDelay = true

            if (config.useTls) {
                val effectiveSni = config.sniHost.trim().ifBlank { config.wsHostHeader.trim() }
                val baseForTls: Socket = if (config.fragmentTlsClientHello) {
                    TlsFragmentingSocketWrapper(rawSocket)
                } else {
                    rawSocket
                }
                val sslFactory = createTunnelSslContext().socketFactory
                val sslSock = sslFactory.createSocket(
                    baseForTls,
                    effectiveSni,
                    config.port,
                    true
                ) as SSLSocket
                sslSock.soTimeout = connectTimeoutMs
                val params = sslSock.sslParameters
                if (effectiveSni.isNotBlank() && !isIpLiteral(effectiveSni)) {
                    runCatching {
                        params.serverNames = listOf(SNIHostName(effectiveSni))
                    }
                }
                sslSock.sslParameters = params
                sslSock.startHandshake()
                activeSocket = sslSock
            }

            val wsKey = if (config.includeWebSocketKey) generateWebSocketKey() else null
            val chunks: List<ByteArray> = when {
                config.customPayload.isNotBlank() -> renderPayloadChunks(
                    template = config.customPayload,
                    hostHeader = config.wsHostHeader,
                    port = config.port,
                    sniHost = config.sniHost.ifBlank { config.wsHostHeader },
                    wsPath = config.wsPath,
                    wsKey = wsKey
                )

                config.splitPayload -> {
                    val full = buildUpgradeRequest(
                        wsPath = config.wsPath,
                        hostHeader = config.wsHostHeader,
                        wsKey = wsKey ?: generateWebSocketKey(),
                        includeWebSocketKey = config.includeWebSocketKey
                    )
                    // Split right after the request line to defeat single-packet DPI classifiers
                    val splitAt = (full.size / 3).coerceIn(8, (full.size - 4).coerceAtLeast(8))
                    listOf(full.copyOfRange(0, splitAt), full.copyOfRange(splitAt, full.size))
                }

                else -> listOf(
                    buildUpgradeRequest(
                        wsPath = config.wsPath,
                        hostHeader = config.wsHostHeader,
                        wsKey = wsKey ?: generateWebSocketKey(),
                        includeWebSocketKey = config.includeWebSocketKey
                    )
                )
            }

            val rawOut = activeSocket.getOutputStream()
            chunks.forEachIndexed { idx, chunk ->
                rawOut.write(chunk)
                rawOut.flush()
                if (idx < chunks.lastIndex) {
                    runCatching { Thread.sleep(25L) }
                }
            }

            val pushIn = PushbackInputStream(activeSocket.getInputStream(), 16)
            var httpResp = readHttpUpgradeResponse(pushIn)

            // Some split-inject payloads emit an initial 200/400 response before the 101 Switching Protocols reply
            if (chunks.size > 1 && httpResp.statusCode != 101 && httpResp.statusCode in 200..499) {
                val prevTo = activeSocket.soTimeout
                runCatching {
                    activeSocket.soTimeout = 1000
                    val next = readHttpUpgradeResponse(pushIn)
                    if (next.statusCode == 101 || next.statusCode == 200) {
                        httpResp = next
                    }
                }
                runCatching { activeSocket.soTimeout = prevTo }
            }

            if (httpResp.statusCode != 101 && httpResp.statusCode != 200) {
                val srv = httpResp.headers["server"]?.let { " [$it]" }.orEmpty()
                throw IOException("WS HTTP ${httpResp.statusCode}$srv: ${httpResp.statusLine}")
            }

            // Auto-detect raw SSH-2.0 stream vs RFC 6455 WebSocket binary frames
            val isCloudflareHeader = httpResp.headers["server"]?.contains("cloudflare", ignoreCase = true) == true
            val hasWsAccept = !httpResp.headers["sec-websocket-accept"].isNullOrBlank()
            var useRfc6455 = isCloudflareHeader && hasWsAccept

            val prevTimeout = activeSocket.soTimeout
            try {
                activeSocket.soTimeout = 1400
                val firstByte = pushIn.read()
                if (firstByte >= 0) {
                    pushIn.unread(firstByte)
                    useRfc6455 = when (firstByte) {
                        'S'.code -> false // Direct "SSH-2.0-..." raw stream (ws-openssh / sshOcean)
                        0x82, 0x81, 0x02, 0x01 -> true // RFC 6455 Binary/Text WebSocket frame
                        else -> useRfc6455
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Server waits for client SSH banner first; keep header-based heuristic
            } finally {
                runCatching { activeSocket.soTimeout = prevTimeout.coerceAtLeast(25_000) }
            }

            val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            val wrappedSocket: Socket = if (useRfc6455) {
                Rfc6455BinaryWebSocketAdapter(activeSocket, pushIn, rawOut)
            } else {
                PushbackSocketAdapter(activeSocket, pushIn, rawOut)
            }
            val protoTag = if (config.useTls) "WSS/TLS:${config.port}" else "WS/HTTP:${config.port}"
            val frameTag = if (useRfc6455) "RFC6455" else "RawSSH"
            return SshWebSocketConnectResult(
                socket = wrappedSocket,
                statusCode = httpResp.statusCode,
                statusLine = httpResp.statusLine,
                isRfc6455Framed = useRfc6455,
                rttMs = rtt,
                summaryLabel = "$protoTag ($frameTag, HTTP ${httpResp.statusCode})"
            )
        } catch (e: Exception) {
            runCatching { activeSocket.close() }
            runCatching { rawSocket.close() }
            throw e
        }
    }

    /**
     * Builds an aggressive, GP-optimized SSH WebSocket strategy list:
     * 1) User's exact configured port + TLS + SNI + custom payload/fragment flags
     * 2) Cloudflare Anycast Edge IP fronting on HTTP:80 & TLS:443 (104.16.132.229, 104.18.2.2, 162.159.135.42)
     * 3) Wildcard SNI / GP Bug-Host CDN variants (www.grameenphone.com.<host>, cloudflare.com.<host>)
     * 4) TLS ClientHello DPI-fragmented Port 443 / 8443 / 2083
     * 5) Plain & Split-Packet HTTP WebSocket ports (80, 8080, 8880)
     */
    fun buildCandidateConfigs(
        host: String,
        port: Int,
        sniHost: String,
        bugHost: String,
        wsPath: String,
        useTls: Boolean,
        resolvedHostIp: String? = null,
        customPayload: String = "",
        enableSplitPayload: Boolean = false,
        enableTlsFragment: Boolean = false
    ): List<SshWebSocketConfig> {
        val cleanHost = host.trim()
        val cleanSni = sniHost.trim()
        val cleanBug = bugHost.trim()
        val connectAddr = cleanBug.ifBlank { resolvedHostIp?.takeIf { it.isNotBlank() } ?: cleanHost }
        val directAddr = resolvedHostIp?.takeIf { it.isNotBlank() } ?: cleanHost
        val cleanPath = wsPath.trim().ifBlank { "/" }
        val wildcardSniHost = if (cleanSni.isNotBlank() &&
            !cleanSni.equals(cleanHost, ignoreCase = true) &&
            !cleanSni.endsWith(".$cleanHost", ignoreCase = true)
        ) {
            "$cleanSni.$cleanHost"
        } else {
            cleanHost
        }

        val list = mutableListOf<SshWebSocketConfig>()
        val seen = HashSet<String>()

        fun addUnique(cfg: SshWebSocketConfig) {
            val key = "${cfg.connectHost}|${cfg.port}|${cfg.useTls}|${cfg.sniHost}|${cfg.wsHostHeader}|${cfg.splitPayload}|${cfg.fragmentTlsClientHello}"
            if (seen.add(key)) list.add(cfg)
        }

        // 1. Primary configured mode
        addUnique(
            SshWebSocketConfig(
                connectHost = connectAddr,
                port = port,
                wsHostHeader = cleanHost,
                sniHost = cleanSni.ifBlank { cleanHost },
                wsPath = cleanPath,
                useTls = useTls,
                customPayload = customPayload,
                splitPayload = enableSplitPayload,
                fragmentTlsClientHello = enableTlsFragment,
                label = "${if (useTls) "TLS" else "HTTP"}:$port (${cleanBug.ifBlank { cleanSni.ifBlank { cleanHost } }})"
            )
        )

        // 2. Wildcard SNI header variant (e.g. www.grameenphone.com.nl1.wssht.to)
        if (wildcardSniHost != cleanHost) {
            addUnique(
                SshWebSocketConfig(
                    connectHost = directAddr,
                    port = if (useTls) port else 443,
                    wsHostHeader = wildcardSniHost,
                    sniHost = wildcardSniHost,
                    wsPath = cleanPath,
                    useTls = true,
                    customPayload = customPayload,
                    fragmentTlsClientHello = true,
                    label = "TLS+Frag:${if (useTls) port else 443} ($wildcardSniHost)"
                )
            )
        }

        // 3. Direct TLS 443 with ClientHello DPI Fragmentation (bypasses GP stateless SNI DPI)
        addUnique(
            SshWebSocketConfig(
                connectHost = directAddr,
                port = 443,
                wsHostHeader = cleanHost,
                sniHost = cleanHost,
                wsPath = cleanPath,
                useTls = true,
                customPayload = customPayload,
                fragmentTlsClientHello = true,
                label = "TLS+DPI-Frag:443 ($cleanHost)"
            )
        )

        // 4. Cloudflare Anycast Edge IP Fronting on HTTP:80 (with Split DPI Bypass!) & TLS:443
        for (edgeIp in listOf("104.16.132.229", "104.18.2.2", "162.159.135.42")) {
            addUnique(
                SshWebSocketConfig(
                    connectHost = edgeIp,
                    port = 80,
                    wsHostHeader = cleanHost,
                    sniHost = cleanSni.ifBlank { cleanHost },
                    wsPath = cleanPath,
                    useTls = false,
                    customPayload = customPayload,
                    splitPayload = true,
                    label = "CF-Edge($edgeIp):80 Split"
                )
            )
            addUnique(
                SshWebSocketConfig(
                    connectHost = edgeIp,
                    port = 443,
                    wsHostHeader = cleanHost,
                    sniHost = cleanHost,
                    wsPath = cleanPath,
                    useTls = true,
                    customPayload = customPayload,
                    fragmentTlsClientHello = true,
                    label = "CF-Edge($edgeIp):443 TLS"
                )
            )
        }

        // 5. Alternate Cloudflare SSL/TLS ports (8443, 2083)
        for (tlsPort in listOf(8443, 2083)) {
            addUnique(
                SshWebSocketConfig(
                    connectHost = directAddr,
                    port = tlsPort,
                    wsHostHeader = cleanHost,
                    sniHost = cleanHost,
                    wsPath = cleanPath,
                    useTls = true,
                    customPayload = customPayload,
                    fragmentTlsClientHello = enableTlsFragment,
                    label = "TLS:$tlsPort ($cleanHost)"
                )
            )
        }

        // 6. Plain & Split HTTP WebSocket ports (80, 8080, 2052, 8880)
        for (httpPort in listOf(80, 8080, 2052, 8880)) {
            addUnique(
                SshWebSocketConfig(
                    connectHost = directAddr,
                    port = httpPort,
                    wsHostHeader = cleanHost,
                    sniHost = "",
                    wsPath = cleanPath,
                    useTls = false,
                    customPayload = customPayload,
                    splitPayload = httpPort == 80 || httpPort == 2052,
                    label = "HTTP:$httpPort ($cleanHost)"
                )
            )
        }

        // 7. GP Zero-Rated Split-Inject Payload over Cloudflare Edge IP (bypasses GP HTTP Host DPI)
        if (customPayload.isBlank()) {
            addUnique(
                SshWebSocketConfig(
                    connectHost = "162.159.135.42",
                    port = 80,
                    wsHostHeader = cleanHost,
                    sniHost = "www.grameenphone.com",
                    wsPath = cleanPath,
                    useTls = false,
                    customPayload = PRESET_GP_SPLIT_DPI,
                    splitPayload = true,
                    label = "CF-Edge(162.159):80 GP-Split"
                )
            )
        }

        return list
    }

    private fun isIpLiteral(value: String): Boolean {
        return value.all { it.isDigit() || it == '.' } || value.contains(':')
    }

    private fun createTunnelSslContext(): SSLContext {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), secureRandom)
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        var prev = -1
        var count = 0
        while (count < 8192) {
            val b = input.read()
            if (b < 0) {
                return if (baos.size() == 0) null else baos.toString("US-ASCII")
            }
            if (b == '\n'.code) {
                val bytes = baos.toByteArray()
                val end = if (prev == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, Charsets.US_ASCII)
            }
            baos.write(b)
            prev = b
            count++
        }
        return baos.toString("US-ASCII")
    }

    /**
     * Encodes a client->server RFC 6455 masked binary WebSocket frame (FIN=1, opcode=0x02, MASK=1).
     */
    fun encodeClientBinaryFrame(payload: ByteArray, offset: Int = 0, length: Int = payload.size): ByteArray {
        val mask = ByteArray(4).also { secureRandom.nextBytes(it) }
        val headerSize = when {
            length < 126 -> 2 + 4
            length <= 0xFFFF -> 4 + 4
            else -> 10 + 4
        }
        val frame = ByteArray(headerSize + length)
        frame[0] = 0x82.toByte()
        var pos = 2
        when {
            length < 126 -> {
                frame[1] = (0x80 or length).toByte()
            }

            length <= 0xFFFF -> {
                frame[1] = (0x80 or 126).toByte()
                frame[2] = ((length ushr 8) and 0xFF).toByte()
                frame[3] = (length and 0xFF).toByte()
                pos = 4
            }

            else -> {
                frame[1] = (0x80 or 127).toByte()
                val len64 = length.toLong()
                for (i in 0 until 8) {
                    frame[2 + i] = ((len64 ushr (56 - i * 8)) and 0xFFL).toByte()
                }
                pos = 10
            }
        }
        System.arraycopy(mask, 0, frame, pos, 4)
        pos += 4
        for (i in 0 until length) {
            frame[pos + i] = (payload[offset + i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        return frame
    }

    fun readWebSocketFrame(input: InputStream): Pair<Int, ByteArray>? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) throw EOFException("Truncated WS frame header")

        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var payloadLen = (b1 and 0x7F).toLong()
        if (payloadLen == 126L) {
            val h = input.read()
            val l = input.read()
            if (h < 0 || l < 0) throw EOFException("Truncated WS 16-bit length")
            payloadLen = ((h shl 8) or l).toLong()
        } else if (payloadLen == 127L) {
            var v = 0L
            repeat(8) {
                val nx = input.read()
                if (nx < 0) throw EOFException("Truncated WS 64-bit length")
                v = (v shl 8) or (nx.toLong() and 0xFFL)
            }
            payloadLen = v
        }
        if (payloadLen < 0 || payloadLen > 8 * 1024 * 1024) {
            throw IOException("Invalid WS frame size: $payloadLen")
        }
        val maskKey = if (masked) {
            ByteArray(4).also { readFully(input, it) }
        } else {
            null
        }
        val data = ByteArray(payloadLen.toInt())
        if (data.isNotEmpty()) {
            readFully(input, data)
            if (maskKey != null) {
                for (i in data.indices) {
                    data[i] = (data[i].toInt() xor maskKey[i and 3].toInt()).toByte()
                }
            }
        }
        return opcode to data
    }

    private fun readFully(input: InputStream, dest: ByteArray) {
        var off = 0
        while (off < dest.size) {
            val n = input.read(dest, off, dest.size - off)
            if (n < 0) throw EOFException("Unexpected EOF reading WS payload")
            off += n
        }
    }
}

/**
 * Splits the very first TLS ClientHello record across 2 TCP segments (first 5B record header +
 * small handshake prefix, then remainder) to evade stateless carrier SNI DPI filters on port 443.
 */
private class TlsFragmentingSocketWrapper(
    private val delegate: Socket
) : Socket() {
    private val wrappedOut: OutputStream = object : FilterOutputStream(delegate.getOutputStream()) {
        private var fragmentedFirstWrite = false

        override fun write(b: ByteArray, off: Int, len: Int) {
            // TLS Handshake record starts with 0x16 0x03 ...
            if (!fragmentedFirstWrite && len > 40 && (b[off].toInt() and 0xFF) == 0x16) {
                fragmentedFirstWrite = true
                val part1 = 24
                out.write(b, off, part1)
                out.flush()
                runCatching { Thread.sleep(12L) }
                out.write(b, off + part1, len - part1)
                out.flush()
            } else {
                out.write(b, off, len)
            }
        }
    }

    override fun getInputStream(): InputStream = delegate.getInputStream()
    override fun getOutputStream(): OutputStream = wrappedOut
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isClosed(): Boolean = delegate.isClosed
    override fun getInetAddress() = delegate.inetAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun setSoTimeout(timeout: Int) {
        runCatching { delegate.soTimeout = timeout }
    }
    override fun getSoTimeout(): Int = runCatching { delegate.soTimeout }.getOrDefault(0)
    override fun setTcpNoDelay(on: Boolean) {
        runCatching { delegate.tcpNoDelay = on }
    }
    override fun close() {
        runCatching { delegate.close() }
    }
}

private class PushbackSocketAdapter(
    private val delegate: Socket,
    private val pushIn: InputStream,
    private val rawOut: OutputStream
) : Socket() {
    override fun getInputStream(): InputStream = pushIn
    override fun getOutputStream(): OutputStream = rawOut
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isClosed(): Boolean = delegate.isClosed
    override fun setSoTimeout(timeout: Int) {
        runCatching { delegate.soTimeout = timeout }
    }
    override fun getSoTimeout(): Int = runCatching { delegate.soTimeout }.getOrDefault(0)
    override fun setTcpNoDelay(on: Boolean) {
        runCatching { delegate.tcpNoDelay = on }
    }
    override fun close() {
        runCatching { delegate.close() }
    }
}

private class Rfc6455BinaryWebSocketAdapter(
    private val delegate: Socket,
    private val rawIn: InputStream,
    private val rawOut: OutputStream
) : Socket() {

    private val writeLock = Any()

    private val wsInputStream = object : InputStream() {
        private var currentChunk: ByteArray = ByteArray(0)
        private var chunkPos: Int = 0
        private var eof: Boolean = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (len == 0) return 0
            while (chunkPos >= currentChunk.size) {
                val frame = try {
                    SshWebSocketTransport.readWebSocketFrame(rawIn)
                } catch (_: EOFException) {
                    eof = true
                    return -1
                } ?: run {
                    eof = true
                    return -1
                }
                val (opcode, data) = frame
                when (opcode) {
                    0x00, 0x01, 0x02 -> {
                        if (data.isNotEmpty()) {
                            currentChunk = data
                            chunkPos = 0
                        }
                    }

                    0x08 -> {
                        eof = true
                        return -1
                    }

                    0x09 -> {
                        synchronized(writeLock) {
                            runCatching {
                                val pong = SshWebSocketTransport.encodeClientBinaryFrame(data)
                                pong[0] = 0x8A.toByte()
                                rawOut.write(pong)
                                rawOut.flush()
                            }
                        }
                    }

                    0x0A -> Unit
                }
            }
            val toCopy = minOf(len, currentChunk.size - chunkPos)
            System.arraycopy(currentChunk, chunkPos, b, off, toCopy)
            chunkPos += toCopy
            return toCopy
        }

        override fun close() {
            this@Rfc6455BinaryWebSocketAdapter.close()
        }
    }

    private val wsOutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val frame = SshWebSocketTransport.encodeClientBinaryFrame(b, off, len)
            synchronized(writeLock) {
                rawOut.write(frame)
                rawOut.flush()
            }
        }

        override fun flush() {
            synchronized(writeLock) { rawOut.flush() }
        }

        override fun close() {
            this@Rfc6455BinaryWebSocketAdapter.close()
        }
    }

    override fun getInputStream(): InputStream = wsInputStream
    override fun getOutputStream(): OutputStream = wsOutputStream
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isClosed(): Boolean = delegate.isClosed
    override fun setSoTimeout(timeout: Int) {
        runCatching { delegate.soTimeout = timeout }
    }
    override fun getSoTimeout(): Int = runCatching { delegate.soTimeout }.getOrDefault(0)
    override fun setTcpNoDelay(on: Boolean) {
        runCatching { delegate.tcpNoDelay = on }
    }
    override fun close() {
        runCatching { delegate.close() }
    }
}
