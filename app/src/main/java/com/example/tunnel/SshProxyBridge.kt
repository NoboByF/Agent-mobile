package com.example.tunnel

import android.net.Network
import com.example.data.HttpCustomTunnelProfile
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Full SSH-2.0 client (via JSch) + Local Dual-Protocol Proxy (HTTP/HTTPS CONNECT + SOCKS5)
 * forwarding all device/browser TCP connections through SSH direct-tcpip channels.
 *
 * Works seamlessly over either:
 * 1) SlowDNS (dnstt -> KCP -> Noise_NK -> SMUX -> SmuxSocket)
 * 2) Direct protected TCP socket when SSH port is directly reachable.
 */
class SshProxyBridge(
    private val profile: HttpCustomTunnelProfile,
    private val customSocketFactory: (() -> Socket)? = null,
    private val underlyingNetwork: Network? = null,
    private val protectTcp: ((Socket) -> Boolean)? = null,
    private val onTraffic: ((txDelta: Long, rxDelta: Long) -> Unit)? = null,
    private val onLog: ((String) -> Unit)? = null
) {
    private val running = AtomicBoolean(false)
    private var sshSession: Session? = null
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clientPool: ExecutorService = Executors.newCachedThreadPool()
    private val activeConnections = AtomicInteger(0)

    @Volatile
    var localProxyPort: Int = -1
        private set

    @Volatile
    var serverVersionBanner: String = ""
        private set

    fun isConnected(): Boolean = running.get() && (sshSession?.isConnected == true) && localProxyPort > 0

    fun activeStreamCount(): Int = activeConnections.get()

    /**
     * Authenticates with the remote SSH server and binds 127.0.0.1:<ephemeralPort>.
     * Throws a descriptive exception on authentication or handshake failure.
     */
    fun connectAndStartProxy(connectTimeoutMs: Int = 22_000): Int {
        val jsch = JSch()
        val session = jsch.getSession(
            profile.username.ifBlank { "root" },
            profile.host,
            profile.port
        )
        session.setPassword(profile.password)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        session.setConfig("CheckCiphers", "aes128-ctr,aes192-ctr,aes256-ctr,chacha20-poly1305@openssh.com")
        session.setServerAliveInterval(20_000)
        session.setServerAliveCountMax(4)

        session.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                val custom = customSocketFactory?.invoke()
                if (custom != null) return custom
                val s = Socket()
                protectTcp?.invoke(s)
                if (underlyingNetwork != null) {
                    runCatching { underlyingNetwork.bindSocket(s) }
                }
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                s.tcpNoDelay = true
                return s
            }

            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })

        session.connect(connectTimeoutMs)
        sshSession = session
        serverVersionBanner = runCatching { session.serverVersion }.getOrDefault("SSH-2.0")

        val srv = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = srv
        localProxyPort = srv.localPort
        running.set(true)

        acceptThread = Thread({ acceptLoop(srv) }, "ssh-local-proxy-$localProxyPort").apply {
            isDaemon = true
            start()
        }
        onLog?.invoke("SSH authenticated ($serverVersionBanner) -> Local Proxy 127.0.0.1:$localProxyPort")
        return localProxyPort
    }

    private fun acceptLoop(srv: ServerSocket) {
        while (running.get() && !srv.isClosed) {
            val client = try {
                srv.accept()
            } catch (_: Exception) {
                break
            }
            clientPool.execute { handleProxyClient(client) }
        }
    }

    private fun handleProxyClient(client: Socket) {
        activeConnections.incrementAndGet()
        try {
            client.soTimeout = 30_000
            client.tcpNoDelay = true
            val bis = BufferedInputStream(client.getInputStream(), 8192)
            val out = client.getOutputStream()

            bis.mark(4)
            val firstByte = bis.read()
            if (firstByte < 0) return
            bis.reset()

            if (firstByte == 0x05) {
                handleSocks5(bis, out)
            } else {
                handleHttpProxy(bis, out)
            }
        } catch (_: Exception) {
            // Client closed connection or target unreachable
        } finally {
            runCatching { client.close() }
            activeConnections.decrementAndGet()
        }
    }

    private fun handleSocks5(input: BufferedInputStream, output: OutputStream) {
        val dis = DataInputStream(input)
        val ver = dis.readUnsignedByte()
        if (ver != 5) return
        val nMethods = dis.readUnsignedByte()
        val methods = ByteArray(nMethods)
        dis.readFully(methods)
        // Reply: version 5, no authentication required (0x00)
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        val reqVer = dis.readUnsignedByte()
        val cmd = dis.readUnsignedByte()
        dis.readUnsignedByte() // RSV
        val atyp = dis.readUnsignedByte()
        if (reqVer != 5 || cmd != 1) {
            output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }

        val targetHost = when (atyp) {
            1 -> {
                val ipv4 = ByteArray(4)
                dis.readFully(ipv4)
                InetAddress.getByAddress(ipv4).hostAddress.orEmpty()
            }

            3 -> {
                val len = dis.readUnsignedByte()
                val domain = ByteArray(len)
                dis.readFully(domain)
                String(domain, Charsets.US_ASCII)
            }

            4 -> {
                val ipv6 = ByteArray(16)
                dis.readFully(ipv6)
                InetAddress.getByAddress(ipv6).hostAddress.orEmpty()
            }

            else -> return
        }
        val targetPort = dis.readUnsignedShort()
        val channel = openSshDirectChannel(targetHost, targetPort) ?: run {
            output.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            return
        }

        try {
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
            relayBidirectional(input, output, channel)
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    private fun handleHttpProxy(input: BufferedInputStream, output: OutputStream) {
        val requestLine = readAsciiLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase()
        val target = parts[1]
        val httpVer = parts.getOrElse(2) { "HTTP/1.1" }

        val headers = mutableListOf<String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            headers.add(line)
        }

        if (method == "CONNECT") {
            val (host, port) = parseHostPort(target, defaultPort = 443) ?: return
            val channel = openSshDirectChannel(host, port)
            if (channel == null) {
                output.write("$httpVer 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                return
            }
            try {
                output.write("$httpVer 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                relayBidirectional(input, output, channel)
            } finally {
                runCatching { channel.disconnect() }
            }
        } else {
            // Plain HTTP proxy request (e.g., GET http://connectivitycheck.gstatic.com/generate_204 HTTP/1.1)
            val (host, port, originPath) = parseHttpProxyUrl(target, headers) ?: return
            val channel = openSshDirectChannel(host, port)
            if (channel == null) {
                output.write("$httpVer 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
                return
            }
            try {
                val sshOut = channel.outputStream
                val reqBuilder = StringBuilder()
                reqBuilder.append("$method $originPath $httpVer\r\n")
                var hasHostHeader = false
                for (h in headers) {
                    val lower = h.lowercase()
                    if (lower.startsWith("proxy-connection:") || lower.startsWith("proxy-authorization:")) {
                        continue
                    }
                    if (lower.startsWith("host:")) hasHostHeader = true
                    reqBuilder.append(h).append("\r\n")
                }
                if (!hasHostHeader) {
                    val hostVal = if (port == 80) host else "$host:$port"
                    reqBuilder.append("Host: $hostVal\r\n")
                }
                reqBuilder.append("\r\n")
                val headerBytes = reqBuilder.toString().toByteArray(Charsets.US_ASCII)
                sshOut.write(headerBytes)
                sshOut.flush()
                onTraffic?.invoke(headerBytes.size.toLong(), 0L)
                relayBidirectional(input, output, channel)
            } finally {
                runCatching { channel.disconnect() }
            }
        }
    }

    private fun openSshDirectChannel(host: String, port: Int): ChannelDirectTCPIP? {
        val session = sshSession ?: return null
        if (!session.isConnected) return null
        return runCatching {
            val ch = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
            ch.setHost(host)
            ch.setPort(port)
            ch.setOrgIPAddress("127.0.0.1")
            ch.setOrgPort(0)
            ch.connect(15_000)
            ch
        }.getOrNull()
    }

    private fun relayBidirectional(
        clientIn: InputStream,
        clientOut: OutputStream,
        channel: ChannelDirectTCPIP
    ) {
        val sshIn = channel.inputStream
        val sshOut = channel.outputStream

        val uploadTask = clientPool.submit {
            val buf = ByteArray(8192)
            try {
                while (running.get() && !channel.isClosed) {
                    val n = clientIn.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        sshOut.write(buf, 0, n)
                        sshOut.flush()
                        onTraffic?.invoke(n.toLong(), 0L)
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { sshOut.close() }
            }
        }

        val buf = ByteArray(8192)
        try {
            while (running.get()) {
                val n = sshIn.read(buf)
                if (n < 0) break
                if (n > 0) {
                    clientOut.write(buf, 0, n)
                    clientOut.flush()
                    onTraffic?.invoke(0L, n.toLong())
                }
            }
        } catch (_: Exception) {
        } finally {
            uploadTask.cancel(true)
        }
    }

    fun close() {
        running.set(false)
        localProxyPort = -1
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { sshSession?.disconnect() }
        sshSession = null
        acceptThread?.interrupt()
        runCatching { clientPool.shutdownNow() }
    }

    companion object {
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

        internal fun parseHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
            val clean = authority.trim()
            if (clean.isEmpty()) return null
            return if (clean.startsWith("[")) {
                val endBracket = clean.indexOf(']')
                if (endBracket < 0) return null
                val host = clean.substring(1, endBracket)
                val port = clean.substring(endBracket + 1).removePrefix(":").toIntOrNull() ?: defaultPort
                host to port
            } else {
                val colon = clean.lastIndexOf(':')
                if (colon > 0) {
                    val host = clean.substring(0, colon)
                    val port = clean.substring(colon + 1).toIntOrNull() ?: defaultPort
                    host to port
                } else {
                    clean to defaultPort
                }
            }
        }

        internal fun parseHttpProxyUrl(target: String, headers: List<String>): Triple<String, Int, String>? {
            if (target.startsWith("http://", ignoreCase = true)) {
                val rest = target.substring(7)
                val slashIdx = rest.indexOf('/')
                val authority = if (slashIdx >= 0) rest.substring(0, slashIdx) else rest
                val path = if (slashIdx >= 0) rest.substring(slashIdx) else "/"
                val (host, port) = parseHostPort(authority, 80) ?: return null
                return Triple(host, port, path)
            }
            val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                ?.substringAfter(":")?.trim() ?: return null
            val (host, port) = parseHostPort(hostHeader, 80) ?: return null
            val path = if (target.startsWith("/")) target else "/$target"
            return Triple(host, port, path)
        }
    }
}
