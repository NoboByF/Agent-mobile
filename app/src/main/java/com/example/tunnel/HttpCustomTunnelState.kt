package com.example.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.data.HttpCustomTunnelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
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
    val activeDnsServer: String? = null,
    val activeDnsTransport: String? = null,
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

data class DnsCandidateProbeResult(
    val serverIp: String,
    val isCarrierDns: Boolean,
    val transport: String,
    val reachable: Boolean,
    val rttMs: Long?,
    val responseCode: Int? = null,
    val resolvedIps: List<String> = emptyList(),
    val error: String? = null
)

data class ProbeSummary(
    val dnsReachable: Boolean,
    val workingDnsServer: String?,
    val workingDnsTransport: String?,
    val workingDnsIsCarrier: Boolean,
    val dnsRttMs: Long?,
    val resolvedIps: List<String>,
    val sshReachable: Boolean,
    val sshBanner: String?,
    val sshRttMs: Long?,
    val testedResolvers: List<DnsCandidateProbeResult>,
    val errorSummary: String?
)

object HttpCustomTunnelStateHolder {
    const val VIRTUAL_TUN_CLIENT_IP = "10.88.0.2"
    const val VIRTUAL_TUN_DNS_IP = "10.88.0.1"

    private val PUBLIC_FALLBACK_DNS = listOf(
        "1.1.1.1",
        "8.8.8.8",
        "8.8.4.4",
        "9.9.9.9",
        "208.67.222.222"
    )

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

    fun updateProfile(updated: HttpCustomTunnelProfile) {
        _profiles.update { list ->
            list.map { if (it.id == updated.id) updated else it }
        }
        appendLog("Updated profile: ${updated.name} (DNS=${updated.dnsServer})")
    }

    fun updateProfileDns(profileId: String, dnsServer: String) {
        _profiles.update { list ->
            list.map { if (it.id == profileId) it.copy(dnsServer = dnsServer) else it }
        }
        appendLog("DNS resolver mode set to: $dnsServer")
    }

    fun updateState(transform: (TunnelConnectionState) -> TunnelConnectionState) {
        _state.update(transform)
    }

    fun appendLog(message: String) {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "[$stamp] $message"
        _state.update { current ->
            current.copy(logs = (current.logs + line).takeLast(100))
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

    /**
     * Finds the active physical (non-VPN) network and its carrier/router IPv4 DNS servers.
     */
    fun discoverUnderlyingNetworkAndDns(context: Context?): Pair<Network?, List<String>> {
        if (context == null) return null to emptyList()
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching null to emptyList()

            val nonVpnNetworks = cm.allNetworks.filter { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }

            val preferredNetwork = cm.activeNetwork?.takeIf { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } ?: nonVpnNetworks.firstOrNull { net ->
                cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            } ?: nonVpnNetworks.firstOrNull()

            val dnsIps = LinkedHashSet<String>()
            val orderedNetworks = buildList {
                if (preferredNetwork != null) add(preferredNetwork)
                addAll(nonVpnNetworks)
            }
            for (net in orderedNetworks) {
                val linkProps = cm.getLinkProperties(net) ?: continue
                for (addr in linkProps.dnsServers) {
                    if (addr is Inet4Address) {
                        val ip = addr.hostAddress.orEmpty()
                        if (ip.isNotBlank() &&
                            ip != VIRTUAL_TUN_DNS_IP &&
                            ip != VIRTUAL_TUN_CLIENT_IP &&
                            !ip.startsWith("127.")
                        ) {
                            dnsIps.add(ip)
                        }
                    }
                }
            }
            preferredNetwork to dnsIps.toList()
        }.getOrDefault(null to emptyList())
    }

    /**
     * Builds deduplicated ordered candidate DNS servers.
     * In "auto" mode, carrier/system DNS servers are prioritized first because mobile operators
     * (GP/Robi/Airtel) frequently drop direct UDP:53 to 8.8.8.8 while permitting carrier DNS.
     */
    fun buildCandidateDnsList(
        configuredDns: String,
        carrierDnsList: List<String>
    ): List<Pair<String, Boolean>> {
        val cleanConfigured = configuredDns.trim()
        val isAuto = cleanConfigured.isBlank() || cleanConfigured.equals("auto", ignoreCase = true)
        val carrierSet = carrierDnsList.toSet()
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<Pair<String, Boolean>>()

        fun addIfNew(ip: String) {
            val trimmed = ip.trim()
            if (trimmed.isBlank() || trimmed.equals("auto", ignoreCase = true)) return
            if (trimmed == VIRTUAL_TUN_DNS_IP || trimmed == VIRTUAL_TUN_CLIENT_IP) return
            if (seen.add(trimmed)) {
                result.add(trimmed to (trimmed in carrierSet))
            }
        }

        if (isAuto) {
            carrierDnsList.forEach(::addIfNew)
            PUBLIC_FALLBACK_DNS.forEach(::addIfNew)
        } else {
            addIfNew(cleanConfigured)
            carrierDnsList.forEach(::addIfNew)
            PUBLIC_FALLBACK_DNS.forEach(::addIfNew)
        }
        return result
    }

    private fun probeSingleUdpDns(
        dnsIp: String,
        isCarrier: Boolean,
        targetDomain: String,
        queryType: Int,
        timeoutMs: Int,
        underlyingNetwork: Network?,
        protectUdp: ((DatagramSocket) -> Boolean)?
    ): DnsCandidateProbeResult {
        return runCatching {
            val txId = ((System.nanoTime() xor dnsIp.hashCode().toLong()) and 0xFFFFL).toInt()
            val queryPacket = DnsPacketCodec.buildDnsQueryPacket(
                domain = targetDomain,
                queryType = queryType,
                transactionId = txId,
                includeEdns0 = true
            )
            DatagramSocket().use { sock ->
                protectUdp?.invoke(sock)
                if (underlyingNetwork != null) {
                    runCatching { underlyingNetwork.bindSocket(sock) }
                }
                sock.soTimeout = timeoutMs
                val addr = InetAddress.getByName(dnsIp)
                val t0 = System.currentTimeMillis()
                sock.send(DatagramPacket(queryPacket, queryPacket.size, addr, 53))
                val respBuf = ByteArray(2048)
                val respPacket = DatagramPacket(respBuf, respBuf.size)
                sock.receive(respPacket)
                val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                val parsed = DnsPacketCodec.parseDnsResponse(respBuf, respPacket.length)
                if (parsed != null && parsed.transactionId == txId) {
                    DnsCandidateProbeResult(
                        serverIp = dnsIp,
                        isCarrierDns = isCarrier,
                        transport = "UDP:53",
                        reachable = true,
                        rttMs = rtt,
                        responseCode = parsed.responseCode,
                        resolvedIps = parsed.resolvedIps
                    )
                } else {
                    DnsCandidateProbeResult(
                        serverIp = dnsIp,
                        isCarrierDns = isCarrier,
                        transport = "UDP:53",
                        reachable = false,
                        rttMs = null,
                        error = "Invalid DNS reply"
                    )
                }
            }
        }.getOrElse { err ->
            DnsCandidateProbeResult(
                serverIp = dnsIp,
                isCarrierDns = isCarrier,
                transport = "UDP:53",
                reachable = false,
                rttMs = null,
                error = err.localizedMessage ?: err::class.java.simpleName
            )
        }
    }

    private fun probeSingleTcpDns(
        dnsIp: String,
        isCarrier: Boolean,
        targetDomain: String,
        timeoutMs: Int,
        underlyingNetwork: Network?,
        protectTcp: ((Socket) -> Boolean)?
    ): DnsCandidateProbeResult {
        return runCatching {
            val txId = ((System.nanoTime() xor (dnsIp.hashCode().toLong() shl 1)) and 0xFFFFL).toInt()
            val queryPacket = DnsPacketCodec.buildDnsQueryPacket(
                domain = targetDomain,
                queryType = DnsPacketCodec.TYPE_A,
                transactionId = txId,
                includeEdns0 = false
            )
            Socket().use { tcp ->
                protectTcp?.invoke(tcp)
                if (underlyingNetwork != null) {
                    runCatching { underlyingNetwork.bindSocket(tcp) }
                }
                val t0 = System.currentTimeMillis()
                tcp.connect(InetSocketAddress(InetAddress.getByName(dnsIp), 53), timeoutMs)
                tcp.soTimeout = timeoutMs
                val dos = DataOutputStream(tcp.getOutputStream())
                dos.writeShort(queryPacket.size)
                dos.write(queryPacket)
                dos.flush()

                val dis = DataInputStream(tcp.getInputStream())
                val respLen = dis.readUnsignedShort()
                if (respLen in 12..4096) {
                    val respBuf = ByteArray(respLen)
                    dis.readFully(respBuf)
                    val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    val parsed = DnsPacketCodec.parseDnsResponse(respBuf, respLen)
                    if (parsed != null && parsed.transactionId == txId) {
                        DnsCandidateProbeResult(
                            serverIp = dnsIp,
                            isCarrierDns = isCarrier,
                            transport = "TCP:53",
                            reachable = true,
                            rttMs = rtt,
                            responseCode = parsed.responseCode,
                            resolvedIps = parsed.resolvedIps
                        )
                    } else {
                        DnsCandidateProbeResult(
                            serverIp = dnsIp,
                            isCarrierDns = isCarrier,
                            transport = "TCP:53",
                            reachable = false,
                            rttMs = null,
                            error = "Invalid TCP DNS reply"
                        )
                    }
                } else {
                    DnsCandidateProbeResult(
                        serverIp = dnsIp,
                        isCarrierDns = isCarrier,
                        transport = "TCP:53",
                        reachable = false,
                        rttMs = null,
                        error = "Unexpected length $respLen"
                    )
                }
            }
        }.getOrElse { err ->
            DnsCandidateProbeResult(
                serverIp = dnsIp,
                isCarrierDns = isCarrier,
                transport = "TCP:53",
                reachable = false,
                rttMs = null,
                error = err.localizedMessage ?: err::class.java.simpleName
            )
        }
    }

    suspend fun probeNetworkReachability(
        profile: HttpCustomTunnelProfile,
        context: Context? = null,
        underlyingNetworkOverride: Network? = null,
        carrierDnsOverride: List<String>? = null,
        protectUdp: ((DatagramSocket) -> Boolean)? = null,
        protectTcp: ((Socket) -> Boolean)? = null
    ): ProbeSummary = withContext(Dispatchers.IO) {
        val (discoveredNet, discoveredDns) = discoverUnderlyingNetworkAndDns(context)
        val activeNet = underlyingNetworkOverride ?: discoveredNet
        val carrierDnsList = carrierDnsOverride ?: discoveredDns
        val candidates = buildCandidateDnsList(profile.dnsServer, carrierDnsList)
        val targetDomain = profile.nameserver.ifBlank { profile.host }

        // 1. Probe all candidate DNS servers concurrently over UDP:53 (SlowDNS TXT+EDNS0 & A)
        val udpResults = coroutineScope {
            candidates.map { (ip, isCarrier) ->
                async(Dispatchers.IO) {
                    val primary = probeSingleUdpDns(
                        dnsIp = ip,
                        isCarrier = isCarrier,
                        targetDomain = targetDomain,
                        queryType = DnsPacketCodec.TYPE_TXT,
                        timeoutMs = 2600,
                        underlyingNetwork = activeNet,
                        protectUdp = protectUdp
                    )
                    if (primary.reachable) {
                        // Also try resolving A record quickly for SSH host if possible
                        if (primary.resolvedIps.isEmpty()) {
                            val aProbe = probeSingleUdpDns(
                                dnsIp = ip,
                                isCarrier = isCarrier,
                                targetDomain = profile.host,
                                queryType = DnsPacketCodec.TYPE_A,
                                timeoutMs = 1500,
                                underlyingNetwork = activeNet,
                                protectUdp = protectUdp
                            )
                            primary.copy(resolvedIps = aProbe.resolvedIps)
                        } else {
                            primary
                        }
                    } else {
                        // Fallback check with standard A query on same resolver (some carriers filter TXT)
                        probeSingleUdpDns(
                            dnsIp = ip,
                            isCarrier = isCarrier,
                            targetDomain = profile.host,
                            queryType = DnsPacketCodec.TYPE_A,
                            timeoutMs = 2000,
                            underlyingNetwork = activeNet,
                            protectUdp = protectUdp
                        )
                    }
                }
            }.awaitAll()
        }

        // 2. If all UDP:53 probes failed, try DNS-over-TCP (port 53) on top 3 resolvers
        val allResults = mutableListOf<DnsCandidateProbeResult>()
        allResults.addAll(udpResults)
        var bestDns = udpResults
            .filter { it.reachable }
            .minByOrNull { (it.rttMs ?: Long.MAX_VALUE) + if (it.isCarrierDns) 0L else 15L }

        if (bestDns == null) {
            val tcpResults = coroutineScope {
                candidates.take(3).map { (ip, isCarrier) ->
                    async(Dispatchers.IO) {
                        probeSingleTcpDns(
                            dnsIp = ip,
                            isCarrier = isCarrier,
                            targetDomain = profile.host,
                            timeoutMs = 2600,
                            underlyingNetwork = activeNet,
                            protectTcp = protectTcp
                        )
                    }
                }.awaitAll()
            }
            allResults.addAll(tcpResults)
            bestDns = tcpResults.filter { it.reachable }.minByOrNull { it.rttMs ?: Long.MAX_VALUE }
        }

        // 3. Also test direct TCP + SSH-2.0 handshake to host:port
        var sshReachable = false
        var sshBanner: String? = null
        var sshRttMs: Long? = null
        var sshError: String? = null

        val resolvedIps = bestDns?.resolvedIps.orEmpty().ifEmpty {
            runCatching {
                val addrs = if (activeNet != null) {
                    activeNet.getAllByName(profile.host)
                } else {
                    InetAddress.getAllByName(profile.host)
                }
                addrs.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
            }.getOrDefault(emptyList())
        }

        val connectTarget = resolvedIps.firstOrNull() ?: profile.host
        runCatching {
            Socket().use { tcp ->
                protectTcp?.invoke(tcp)
                if (activeNet != null) {
                    runCatching { activeNet.bindSocket(tcp) }
                }
                val t0 = System.currentTimeMillis()
                tcp.connect(InetSocketAddress(connectTarget, profile.port), 3500)
                tcp.soTimeout = 3000
                val reader = tcp.getInputStream().bufferedReader(Charsets.UTF_8)
                val bannerLine = reader.readLine()?.trim().orEmpty()
                if (bannerLine.startsWith("SSH-", ignoreCase = true)) {
                    tcp.getOutputStream().write("SSH-2.0-LivePilot_1.0\r\n".toByteArray(Charsets.US_ASCII))
                    tcp.getOutputStream().flush()
                    sshBanner = bannerLine
                } else {
                    sshBanner = bannerLine.ifBlank { "TCP:${profile.port} Open" }
                }
                sshReachable = true
                sshRttMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
            }
        }.onFailure { err ->
            sshError = "SSH(${profile.host}:${profile.port}): ${err.localizedMessage ?: err::class.java.simpleName}"
        }

        val errorSummary = if (bestDns == null && !sshReachable) {
            val testedIps = candidates.take(4).joinToString(", ") { it.first }
            val detail = buildList {
                add("DNS ($testedIps): কোনো রেসপন্স নেই (ক্যারিয়ার UDP/TCP:53 ব্লক বা নেটওয়ার্ক অফ)")
                if (!sshError.isNullOrBlank()) add(sshError!!)
            }.joinToString(" | ")
            detail
        } else {
            null
        }

        ProbeSummary(
            dnsReachable = bestDns != null,
            workingDnsServer = bestDns?.serverIp,
            workingDnsTransport = bestDns?.transport,
            workingDnsIsCarrier = bestDns?.isCarrierDns == true,
            dnsRttMs = bestDns?.rttMs,
            resolvedIps = resolvedIps,
            sshReachable = sshReachable,
            sshBanner = sshBanner,
            sshRttMs = sshRttMs,
            testedResolvers = allResults,
            errorSummary = errorSummary
        )
    }
}
