package com.example.tunnel

import com.example.data.HttpCustomTunnelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TunnelConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class TunnelConnectionState(
    val phase: TunnelConnectionPhase = TunnelConnectionPhase.DISCONNECTED,
    val activeProfileId: String? = null,
    val activeProfileName: String? = null,
    val statusDetail: String = "সংযোগ বিচ্ছিন্ন (Disconnected)",
    val dnsRttMs: Long? = null,
    val sshBanner: String? = null,
    val resolvedIp: String? = null,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val dnsPacketsHandled: Int = 0,
    val connectedSinceMs: Long? = null,
    val lastError: String? = null,
    val logs: List<String> = emptyList()
)

data class ProbeSummary(
    val dnsReachable: Boolean,
    val dnsRttMs: Long?,
    val resolvedIps: List<String>,
    val sshReachable: Boolean,
    val sshBanner: String?,
    val sshRttMs: Long?,
    val errorSummary: String?
)

object HttpCustomTunnelStateHolder {
    private val _state = MutableStateFlow(TunnelConnectionState())
    val state: StateFlow<TunnelConnectionState> = _state.asStateFlow()

    private val _profiles = MutableStateFlow(HttpCustomTunnelProfile.DEFAULT_PROFILES)
    val profiles: StateFlow<List<HttpCustomTunnelProfile>> = _profiles.asStateFlow()

    private val _selectedProfileId = MutableStateFlow(HttpCustomTunnelProfile.DEFAULT_PROFILES.first().id)
    val selectedProfileId: StateFlow<String> = _selectedProfileId.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun selectProfile(profileId: String) {
        _selectedProfileId.value = profileId
    }

    fun addOrSelectProfile(profile: HttpCustomTunnelProfile) {
        _profiles.update { list ->
            val filtered = list.filterNot { it.name == profile.name && it.host == profile.host }
            listOf(profile) + filtered
        }
        _selectedProfileId.value = profile.id
        appendLog("Imported profile: ${profile.name} (${profile.host}:${profile.port})")
    }

    fun updateState(transform: (TunnelConnectionState) -> TunnelConnectionState) {
        _state.update(transform)
    }

    fun appendLog(message: String) {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "[$stamp] $message"
        _state.update { current ->
            current.copy(logs = (current.logs + line).takeLast(80))
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes B"
            bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    suspend fun probeNetworkReachability(
        profile: HttpCustomTunnelProfile,
        protectUdp: ((DatagramSocket) -> Boolean)? = null,
        protectTcp: ((Socket) -> Boolean)? = null
    ): ProbeSummary = withContext(Dispatchers.IO) {
        var dnsReachable = false
        var dnsRttMs: Long? = null
        var resolvedIps: List<String> = emptyList()
        var sshReachable = false
        var sshBanner: String? = null
        var sshRttMs: Long? = null
        val errors = mutableListOf<String>()

        // 1. Real UDP DNS query over port 53 to configured DNS server (default 8.8.8.8)
        val dnsHost = profile.dnsServer.ifBlank { "8.8.8.8" }
        val targetDomain = profile.nameserver.ifBlank { profile.host }
        runCatching {
            val queryPacket = DnsPacketCodec.buildDnsQueryPacket(
                domain = targetDomain,
                queryType = DnsPacketCodec.TYPE_A,
                transactionId = (System.currentTimeMillis() and 0xFFFF).toInt()
            )
            DatagramSocket().use { sock ->
                protectUdp?.invoke(sock)
                sock.soTimeout = 4500
                val addr = InetAddress.getByName(dnsHost)
                val t0 = System.currentTimeMillis()
                sock.send(DatagramPacket(queryPacket, queryPacket.size, addr, 53))
                val respBuf = ByteArray(1500)
                val respPacket = DatagramPacket(respBuf, respBuf.size)
                sock.receive(respPacket)
                dnsRttMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                val parsed = DnsPacketCodec.parseDnsResponse(respBuf, respPacket.length)
                dnsReachable = parsed != null
                resolvedIps = parsed?.resolvedIps.orEmpty()
            }
        }.onFailure { err ->
            errors.add("DNS($dnsHost:53): ${err.localizedMessage ?: err::class.java.simpleName}")
        }

        // 2. Real TCP + SSH-2.0 banner handshake to host:port
        val connectHost = resolvedIps.firstOrNull() ?: profile.host
        runCatching {
            Socket().use { tcp ->
                protectTcp?.invoke(tcp)
                val t0 = System.currentTimeMillis()
                tcp.connect(InetSocketAddress(connectHost, profile.port), 5000)
                tcp.soTimeout = 4000
                val reader = tcp.getInputStream().bufferedReader(Charsets.UTF_8)
                val bannerLine = reader.readLine()?.trim().orEmpty()
                if (bannerLine.startsWith("SSH-", ignoreCase = true)) {
                    tcp.getOutputStream().write("SSH-2.0-LivePilot_1.0\r\n".toByteArray(Charsets.US_ASCII))
                    tcp.getOutputStream().flush()
                    sshBanner = bannerLine
                    sshReachable = true
                } else {
                    sshBanner = bannerLine.ifBlank { "TCP:${profile.port} Open" }
                    sshReachable = true
                }
                sshRttMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            }
        }.onFailure { err ->
            errors.add("SSH(${profile.host}:${profile.port}): ${err.localizedMessage ?: err::class.java.simpleName}")
        }

        ProbeSummary(
            dnsReachable = dnsReachable,
            dnsRttMs = dnsRttMs,
            resolvedIps = resolvedIps,
            sshReachable = sshReachable,
            sshBanner = sshBanner,
            sshRttMs = sshRttMs,
            errorSummary = errors.takeIf { it.isNotEmpty() }?.joinToString(" | ")
        )
    }
}
