package com.example.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

data class GpCarrierSnapshot(
    val operatorName: String = "Unknown Network",
    val operatorCode: String = "",
    val isGrameenphone: Boolean = false,
    val transportLabel: String = "Offline",
    val localIpv4: String? = null,
    val interfaceName: String? = null,
    val carrierDnsServers: List<String> = emptyList(),
    val downstreamKbps: Int = 0,
    val upstreamKbps: Int = 0,
    val isMetered: Boolean = true
)

enum class TunnelTransportMode(val label: String, val shortLabel: String) {
    AUTO_SMART("Auto Smart (Carrier + UDP + TCP + DoH)", "Auto Smart"),
    CARRIER_UDP("Carrier UDP:53", "Carrier UDP"),
    TCP_53("DNS-over-TCP:53", "TCP:53"),
    DOH_HTTPS("DNS-over-HTTPS (443)", "DoH 443")
}

data class TunnelTuningConfig(
    val transportMode: TunnelTransportMode = TunnelTransportMode.AUTO_SMART,
    val mtuSize: Int = 1400, // 1400 avoids GP 4G LTE fragmentation
    val ednsPayloadSize: Int = 1232, // RFC-6891 optimal SlowDNS payload size
    val keepaliveIntervalSec: Int = 15,
    val autoReconnectWatchdog: Boolean = true,
    val adBlockDnsEnabled: Boolean = false
)

data class DnsBenchmarkEntry(
    val label: String,
    val serverIp: String,
    val transport: String,
    val isCarrier: Boolean,
    val reachable: Boolean,
    val rttMs: Long?,
    val detail: String
)

/**
 * Inspects real Android SIM/Carrier network telemetry (Grameenphone MCC+MNC 47001 detection,
 * LinkProperties local IPv4, Carrier DNS) and provides RFC-8484 DNS-over-HTTPS (DoH) transport
 * with VpnService.protect() socket support.
 */
object GpNetworkInspector {

    const val GP_MCC_MNC = "47001"
    const val ADGUARD_DNS_IP = "94.140.14.14"
    const val CLOUDFLARE_DOH_URL = "https://1.1.1.1/dns-query"
    const val GOOGLE_DOH_URL = "https://8.8.8.8/dns-query"
    const val ADGUARD_DOH_URL = "https://94.140.14.14/dns-query"

    private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()

    fun isGrameenphoneOperator(operatorCode: String, operatorName: String): Boolean {
        val code = operatorCode.trim()
        val lower = operatorName.trim().lowercase()
        return code == GP_MCC_MNC ||
            lower.contains("grameen") ||
            lower == "gp" ||
            lower.startsWith("gp ") ||
            lower.contains("gp-bd")
    }

    fun resolveOperatorDisplayName(operatorCode: String, rawName: String): String {
        val trimmed = rawName.trim()
        return when (operatorCode.trim()) {
            "47001" -> "Grameenphone (GP)"
            "47002" -> "Robi"
            "47003" -> "Banglalink"
            "47004" -> "Teletalk"
            "47007" -> "Airtel"
            else -> trimmed.ifBlank { "Mobile / Wi-Fi" }
        }
    }

    fun inspectCurrentNetwork(context: Context): GpCarrierSnapshot {
        val tm = runCatching {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull()

        val rawOpCode = runCatching {
            tm?.networkOperator?.takeIf { it.isNotBlank() } ?: tm?.simOperator.orEmpty()
        }.getOrDefault("")

        val rawOpName = runCatching {
            tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: tm?.simOperatorName.orEmpty()
        }.getOrDefault("")

        val isGp = isGrameenphoneOperator(rawOpCode, rawOpName)
        val displayOp = resolveOperatorDisplayName(rawOpCode, rawOpName)

        val cm = runCatching {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull() ?: return GpCarrierSnapshot(
            operatorName = displayOp,
            operatorCode = rawOpCode,
            isGrameenphone = isGp
        )

        val (physicalNet, carrierDns) = HttpCustomTunnelStateHolder.discoverUnderlyingNetworkAndDns(context)
        val caps = physicalNet?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val linkProps = physicalNet?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }

        val transportLabel = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4G / Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }

        val localIpv4 = linkProps?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress

        return GpCarrierSnapshot(
            operatorName = displayOp,
            operatorCode = rawOpCode,
            isGrameenphone = isGp,
            transportLabel = transportLabel,
            localIpv4 = localIpv4,
            interfaceName = linkProps?.interfaceName,
            carrierDnsServers = carrierDns,
            downstreamKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
            upstreamKbps = caps?.linkUpstreamBandwidthKbps ?: 0,
            isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        )
    }

    fun selectDohEndpoint(dnsServer: String, adBlockEnabled: Boolean): String {
        if (adBlockEnabled) return ADGUARD_DOH_URL
        return when (dnsServer.trim()) {
            "8.8.8.8", "8.8.4.4" -> GOOGLE_DOH_URL
            ADGUARD_DNS_IP -> ADGUARD_DOH_URL
            else -> CLOUDFLARE_DOH_URL
        }
    }

    /**
     * Builds an OkHttpClient whose sockets are protected via VpnService.protect() and optionally
     * bound to the physical underlying network so DoH traffic never loops inside the VPN TUN.
     */
    fun createProtectedDohClient(
        underlyingNetwork: Network? = null,
        protectTcp: ((Socket) -> Boolean)? = null,
        timeoutMs: Long = 3500L
    ): OkHttpClient {
        val protectedFactory = object : SocketFactory() {
            private fun prepare(socket: Socket): Socket {
                protectTcp?.invoke(socket)
                if (underlyingNetwork != null) {
                    runCatching { underlyingNetwork.bindSocket(socket) }
                }
                return socket
            }

            override fun createSocket(): Socket = prepare(Socket())

            override fun createSocket(host: String, port: Int): Socket =
                prepare(Socket()).apply {
                    connect(java.net.InetSocketAddress(host, port), timeoutMs.toInt())
                }

            override fun createSocket(
                host: String,
                port: Int,
                localHost: InetAddress,
                localPort: Int
            ): Socket = prepare(Socket()).apply {
                bind(java.net.InetSocketAddress(localHost, localPort))
                connect(java.net.InetSocketAddress(host, port), timeoutMs.toInt())
            }

            override fun createSocket(host: InetAddress, port: Int): Socket =
                prepare(Socket()).apply {
                    connect(java.net.InetSocketAddress(host, port), timeoutMs.toInt())
                }

            override fun createSocket(
                address: InetAddress,
                port: Int,
                localAddress: InetAddress,
                localPort: Int
            ): Socket = prepare(Socket()).apply {
                bind(java.net.InetSocketAddress(localAddress, localPort))
                connect(java.net.InetSocketAddress(address, port), timeoutMs.toInt())
            }
        }

        return OkHttpClient.Builder()
            .socketFactory(protectedFactory)
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Sends a real RFC-8484 DNS-over-HTTPS POST (application/dns-message) and returns raw wire response bytes.
     */
    fun sendDohQuery(
        client: OkHttpClient,
        dohUrl: String,
        dnsWireQuery: ByteArray
    ): Pair<ByteArray, Long>? = runCatching {
        val request = Request.Builder()
            .url(dohUrl)
            .header("Accept", "application/dns-message")
            .post(dnsWireQuery.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
            .build()
        val t0 = System.currentTimeMillis()
        client.newCall(request).execute().use { resp ->
            val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.bytes() ?: return@use null
            if (body.size < 12) return@use null
            body to rtt
        }
    }.getOrNull()
}
