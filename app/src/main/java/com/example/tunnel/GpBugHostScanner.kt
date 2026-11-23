package com.example.tunnel

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate

data class GpBugHostScanEntry(
    val label: String,
    val connectTarget: String,
    val port: Int,
    val useTls: Boolean,
    val sniHost: String,
    val wsHostHeader: String,
    val reachable: Boolean,
    val wsUpgrade101: Boolean,
    val httpStatusCode: Int?,
    val rttMs: Long?,
    val serverHeader: String? = null,
    val detail: String
)

/**
 * Real Grameenphone (GP) Bug-Host, Cloudflare Anycast Edge IP, and Live HTTP 101 WebSocket Scanner.
 * Probes GP zero-rated domains and Cloudflare Edge IPs directly against the active SSH-WS host
 * (e.g. nl1.wssht.to) to discover which IP + Port + SNI passes GP's firewall and returns HTTP 101!
 */
object GpBugHostScanner {

    val CLOUDFLARE_EDGE_IPS = listOf(
        "104.16.132.229",
        "104.16.133.229",
        "104.18.2.2",
        "104.18.3.2",
        "104.18.27.160",
        "162.159.135.42",
        "172.64.155.1"
    )

    data class ScanTarget(
        val label: String,
        val connectTarget: String,
        val port: Int,
        val useTls: Boolean,
        val sniHost: String,
        val wsHostHeader: String
    )

    fun buildGpScanTargets(sshWsHost: String): List<ScanTarget> {
        val targetWs = sshWsHost.trim().ifBlank { "nl1.wssht.to" }
        return listOf(
            // Direct SSH WebSocket server ports (TLS & HTTP)
            ScanTarget("Direct TLS:443", targetWs, 443, true, targetWs, targetWs),
            ScanTarget("Direct HTTP:80", targetWs, 80, false, "", targetWs),
            ScanTarget("Direct HTTP:8080", targetWs, 8080, false, "", targetWs),
            ScanTarget("Direct TLS:8443", targetWs, 8443, true, targetWs, targetWs),
            ScanTarget("Direct HTTP:2052", targetWs, 2052, false, "", targetWs),
            ScanTarget("Direct TLS:2083", targetWs, 2083, true, targetWs, targetWs),

            // Cloudflare CDN Anycast Edge endpoints for the configured SSH-WS host
            ScanTarget("CF Edge 104.16 (HTTP:80)", "104.16.132.229", 80, false, "", targetWs),
            ScanTarget("CF Edge 104.18 (HTTP:80)", "104.18.2.2", 80, false, "", targetWs),
            ScanTarget("CF Edge 162.159 (HTTP:80)", "162.159.135.42", 80, false, "", targetWs),
            ScanTarget("CF Edge 172.64 (HTTP:80)", "172.64.155.1", 80, false, "", targetWs),
            ScanTarget("CF Edge 104.16 (TLS:443)", "104.16.132.229", 443, true, targetWs, targetWs),
            ScanTarget("CF Edge 104.18 (TLS:443)", "104.18.2.2", 443, true, targetWs, targetWs),
            ScanTarget("CF Edge 162.159 (TLS:443)", "162.159.135.42", 443, true, targetWs, targetWs),
            ScanTarget("CF Edge 172.64 (TLS:443)", "172.64.155.1", 443, true, targetWs, targetWs),
            ScanTarget("Direct HTTP:8880", targetWs, 8880, false, "", targetWs),
            ScanTarget("Direct TLS:2096", targetWs, 2096, true, targetWs, targetWs)
        )
    }

    suspend fun runScan(
        sshWsHost: String,
        underlyingNetwork: Network? = null,
        protectTcp: ((Socket) -> Boolean)? = null
    ): List<GpBugHostScanEntry> = withContext(Dispatchers.IO) {
        val targets = buildGpScanTargets(sshWsHost)
        val results = coroutineScope {
            targets.map { target ->
                async(Dispatchers.IO) {
                    probeSingleTarget(target, underlyingNetwork, protectTcp)
                }
            }.awaitAll()
        }
        results.sortedWith(
            compareByDescending<GpBugHostScanEntry> { it.wsUpgrade101 }
                .thenByDescending { it.reachable }
                .thenBy { it.rttMs ?: Long.MAX_VALUE }
        )
    }

    private fun probeSingleTarget(
        target: ScanTarget,
        underlyingNetwork: Network?,
        protectTcp: ((Socket) -> Boolean)?
    ): GpBugHostScanEntry {
        val t0 = System.currentTimeMillis()
        val rawSocket = Socket()
        var activeSocket: Socket = rawSocket
        return try {
            protectTcp?.invoke(rawSocket)
            if (underlyingNetwork != null) {
                runCatching { underlyingNetwork.bindSocket(rawSocket) }
            }
            rawSocket.connect(InetSocketAddress(target.connectTarget, target.port), 3500)
            rawSocket.soTimeout = 3500
            rawSocket.tcpNoDelay = true

            if (target.useTls) {
                val ctx = createPermissiveSslContext()
                val sslSock = ctx.socketFactory.createSocket(
                    rawSocket,
                    target.sniHost.ifBlank { target.connectTarget },
                    target.port,
                    true
                ) as SSLSocket
                sslSock.soTimeout = 3500
                val sni = target.sniHost.trim()
                if (sni.isNotBlank() && !sni.all { it.isDigit() || it == '.' } && !sni.contains(':')) {
                    runCatching {
                        val params = sslSock.sslParameters
                        params.serverNames = listOf(SNIHostName(sni))
                        sslSock.sslParameters = params
                    }
                }
                sslSock.startHandshake()
                activeSocket = sslSock
            }

            val req = SshWebSocketTransport.buildUpgradeRequest(
                wsPath = "/",
                hostHeader = target.wsHostHeader
            )
            val out = activeSocket.getOutputStream()
            out.write(req)
            out.flush()

            val resp = SshWebSocketTransport.readHttpUpgradeResponse(activeSocket.getInputStream())
            val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            val serverHdr = resp.headers["server"]
            val upgradeHdr = resp.headers["upgrade"]
            val is101 = resp.statusCode == 101 &&
                (upgradeHdr.isNullOrBlank() || upgradeHdr.contains("websocket", ignoreCase = true))
            val detail = buildString {
                append("HTTP ${resp.statusCode}")
                if (is101) append(" • WS 101 READY")
                else if (resp.statusCode in 301..302) append(" • Captive Portal Redirect")
                if (!serverHdr.isNullOrBlank()) append(" [$serverHdr]")
            }
            GpBugHostScanEntry(
                label = target.label,
                connectTarget = target.connectTarget,
                port = target.port,
                useTls = target.useTls,
                sniHost = target.sniHost,
                wsHostHeader = target.wsHostHeader,
                reachable = true,
                wsUpgrade101 = is101,
                httpStatusCode = resp.statusCode,
                rttMs = rtt,
                serverHeader = serverHdr,
                detail = detail
            )
        } catch (e: Exception) {
            val err = e.localizedMessage?.take(42) ?: e::class.java.simpleName
            GpBugHostScanEntry(
                label = target.label,
                connectTarget = target.connectTarget,
                port = target.port,
                useTls = target.useTls,
                sniHost = target.sniHost,
                wsHostHeader = target.wsHostHeader,
                reachable = false,
                wsUpgrade101 = false,
                httpStatusCode = null,
                rttMs = null,
                detail = err
            )
        } finally {
            runCatching { activeSocket.close() }
            runCatching { rawSocket.close() }
        }
    }

    private fun createPermissiveSslContext(): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tm), SecureRandom())
        }
    }
}
