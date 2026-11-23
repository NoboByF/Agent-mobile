package com.example.tunnel

import android.net.Network
import java.io.ByteArrayOutputStream
import java.io.EOFException
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
 * Pure-Kotlin SSH over WebSocket transport supporting:
 * 1) SSL/TLS WebSocket (Ports 443, 8443, 2083, 2053, 2087, 2096) with custom SNI / Bug Host
 * 2) HTTP WebSocket (Ports 80, 8080, 8880, 2052, 2082, 2086, 2095) with custom Host header
 * 3) Auto-detection of BOTH direct ws-openssh (raw SSH-2.0 stream after HTTP 101)
 *    AND Cloudflare/CDN RFC 6455 masked binary WebSocket framing!
 */
object SshWebSocketTransport {

    private val secureRandom = SecureRandom()

    fun generateWebSocketKey(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun buildUpgradeRequest(
        wsPath: String,
        hostHeader: String,
        wsKey: String = generateWebSocketKey()
    ): ByteArray {
        val cleanPath = wsPath.trim().ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }
        val cleanHost = hostHeader.trim()
        val req = buildString {
            append("GET ").append(cleanPath).append(" HTTP/1.1\r\n")
            append("Host: ").append(cleanHost).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("User-Agent: Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36\r\n")
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
     * Connects TCP (+ optional TLS with explicit SNI), sends HTTP/1.1 WebSocket Upgrade,
     * validates HTTP 101 / 200, auto-detects raw-SSH vs RFC 6455 binary framing,
     * and returns a Socket ready to hand straight to JSch (SshProxyBridge).
     */
    fun connect(
        config: SshWebSocketConfig,
        connectTimeoutMs: Int = 7000,
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
            rawSocket.connect(InetSocketAddress(config.connectHost, config.port), connectTimeoutMs)
            rawSocket.soTimeout = connectTimeoutMs
            rawSocket.tcpNoDelay = true

            if (config.useTls) {
                val effectiveSni = config.sniHost.trim().ifBlank { config.wsHostHeader.trim() }
                val sslFactory = createTunnelSslContext().socketFactory
                val sslSock = sslFactory.createSocket(
                    rawSocket,
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

            val reqBytes = buildUpgradeRequest(
                wsPath = config.wsPath,
                hostHeader = config.wsHostHeader
            )
            val rawOut = activeSocket.getOutputStream()
            rawOut.write(reqBytes)
            rawOut.flush()

            val pushIn = PushbackInputStream(activeSocket.getInputStream(), 16)
            val httpResp = readHttpUpgradeResponse(pushIn)
            if (httpResp.statusCode != 101 && httpResp.statusCode != 200) {
                throw IOException("WS HTTP ${httpResp.statusCode}: ${httpResp.statusLine}")
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
     * Builds an ordered list of smart SSH WebSocket connection strategies for sshOcean & GP SIMs:
     * 1) User's exact configured port + TLS + SNI
     * 2) Alternate SSL/TLS ports (443, 8443, 2083)
     * 3) Wildcard SNI / GP Bug-Host CDN variants when direct host fails
     * 4) Plain HTTP WebSocket ports (80, 8080, 8880)
     */
    fun buildCandidateConfigs(
        host: String,
        port: Int,
        sniHost: String,
        bugHost: String,
        wsPath: String,
        useTls: Boolean,
        resolvedHostIp: String? = null
    ): List<SshWebSocketConfig> {
        val cleanHost = host.trim()
        val cleanSni = sniHost.trim()
        val cleanBug = bugHost.trim()
        val connectAddr = cleanBug.ifBlank { resolvedHostIp?.takeIf { it.isNotBlank() } ?: cleanHost }
        val directAddr = resolvedHostIp?.takeIf { it.isNotBlank() } ?: cleanHost
        val cleanPath = wsPath.trim().ifBlank { "/" }
        val wildcardSniHost = if (cleanSni.isNotBlank() && !cleanSni.equals(cleanHost, ignoreCase = true) && !cleanSni.endsWith(".$cleanHost", ignoreCase = true)) {
            "$cleanSni.$cleanHost"
        } else {
            cleanHost
        }

        val list = mutableListOf<SshWebSocketConfig>()
        val seen = HashSet<String>()

        fun addUnique(cfg: SshWebSocketConfig) {
            val key = "${cfg.connectHost}|${cfg.port}|${cfg.useTls}|${cfg.sniHost}|${cfg.wsHostHeader}"
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
                label = "${if (useTls) "TLS" else "HTTP"}:${port} (${cleanSni.ifBlank { cleanHost }})"
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
                    label = "TLS:${if (useTls) port else 443} (Wildcard $wildcardSniHost)"
                )
            )
        }

        // 3. Direct TLS 443, 8443, 2083 on origin host
        for (tlsPort in listOf(443, 8443, 2083)) {
            addUnique(
                SshWebSocketConfig(
                    connectHost = directAddr,
                    port = tlsPort,
                    wsHostHeader = cleanHost,
                    sniHost = cleanSni.ifBlank { cleanHost },
                    wsPath = cleanPath,
                    useTls = true,
                    label = "TLS:$tlsPort (${cleanSni.ifBlank { cleanHost }})"
                )
            )
        }

        // 4. Plain HTTP WebSocket ports (80, 8080, 8880)
        for (httpPort in listOf(80, 8080, 8880)) {
            addUnique(
                SshWebSocketConfig(
                    connectHost = directAddr,
                    port = httpPort,
                    wsHostHeader = cleanHost,
                    sniHost = "",
                    wsPath = cleanPath,
                    useTls = false,
                    label = "HTTP:$httpPort ($cleanHost)"
                )
            )
        }

        return list
    }

    private fun isIpLiteral(value: String): Boolean {
        return value.all { it.isDigit() || it == '.' } || value.contains(':')
    }

    /**
     * Scoped SSLContext used solely for custom-SNI / Bug-Host SSH WebSocket tunnels
     * where the TLS SNI differs from the origin server certificate CN.
     * SSH-2.0 inside the tunnel still provides end-to-end encryption & key exchange.
     */
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
        frame[0] = 0x82.toByte() // FIN + Binary frame
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

    /**
     * Reads a single RFC 6455 frame from [input]. Returns Pair(opcode, unmaskedPayload), or null on EOF.
     */
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
 * Socket wrapper preserving already-peeked bytes in [PushbackInputStream] for direct Raw-SSH-after-101 servers.
 */
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

/**
 * Socket adapter tunneling an arbitrary byte stream (SSH-2.0) over RFC 6455 Binary WebSocket frames.
 */
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

                    0x08 -> { // Close frame
                        eof = true
                        return -1
                    }

                    0x09 -> { // Ping -> reply Pong (0x8A)
                        synchronized(writeLock) {
                            runCatching {
                                val pong = SshWebSocketTransport.encodeClientBinaryFrame(data)
                                pong[0] = 0x8A.toByte()
                                rawOut.write(pong)
                                rawOut.flush()
                            }
                        }
                    }

                    0x0A -> Unit // Pong -> ignore
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
