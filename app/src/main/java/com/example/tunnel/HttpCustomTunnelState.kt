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
    val activeDohUrl: String? = null,
    val dnsRttMs: Long? = null,
    val sshBanner: String? = null,
    val resolvedIp: String? = null,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val txSpeedBps: Long = 0L,
    val rxSpeedBps: Long = 0L,
    val dnsPacketsHandled: Int = 0,
    val reconnectCount: Int = 0,
    val pingHistoryMs: List<Long> = emptyList(),
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
    val dohUrl: String? = null,
    val error: String? = null
)

data class ProbeSummary(
    val dnsReachable: Boolean,
    val workingDnsServer: String?,
    val workingDnsTransport: String?,
    val workingDohUrl: String?,
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
        "1.0.0.1",
        "8.8.8.8",
        "8.8.4.4",
        "9.9.9.9",
        "208.67.222.222",
        GpNetworkInspector.ADGUARD_DNS_IP
    )

    private val _state = MutableStateFlow(TunnelConnectionState())
    val state: StateFlow<TunnelConnectionState> = _state.asStateFlow()

    private val _profiles = MutableStateFlow(HttpCustomTunnelProfile.DEFAULT_PROFILES)
    val profiles: StateFlow<List<HttpCustomTunnelProfile>> = _profiles.asStateFlow()

    private val _selectedProfileId = MutableStateFlow(HttpCustomTunnelProfile.DEFAULT_PROFILES.first().id)
    val selectedProfileId: StateFlow<String> = _selectedProfileId.asStateFlow()

    private val _tuning = MutableStateFlow(TunnelTuningConfig())
    val tuning: StateFlow<TunnelTuningConfig> = _tuning.asStateFlow()

    private val _carrierSnapshot = MutableStateFlow(GpCarrierSnapshot())
    val carrierSnapshot: StateFlow<GpCarrierSnapshot> = _carrierSnapshot.asStateFlow()

    private val _benchmarkResults = MutableStateFlow<List<DnsBenchmarkEntry>>(emptyList())
    val benchmarkResults: StateFlow<List<DnsBenchmarkEntry>> = _benchmarkResults.asStateFlow()

    private val _isBenchmarkRunning = MutableStateFlow(false)
    val isBenchmarkRunning: StateFlow<Boolean> = _isBenchmarkRunning.asStateFlow()

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
        appendLog("DNS resolver set to: $dnsServer")
    }

    fun updateTuning(transform: (TunnelTuningConfig) -> TunnelTuningConfig) {
        _tuning.update(transform)
        val cfg = _tuning.value
        appendLog(
            "GP Pro Tuning -> ${cfg.transportMode.shortLabel}, MTU=${cfg.mtuSize}, EDNS=${cfg.ednsPayloadSize}, AdBlock=${cfg.adBlockDnsEnabled}"
        )
    }

    fun refreshCarrierSnapshot(context: Context): GpCarrierSnapshot {
        val snap = GpNetworkInspector.inspectCurrentNetwork(context)
        _carrierSnapshot.value = snap
        return snap
    }

    fun updateState(transform: (TunnelConnectionState) -> TunnelConnectionState) {
        _state.update(transform)
    }

    fun recordPingSample(rttMs: Long) {
        _state.update { current ->
            current.copy(
                dnsRttMs = rttMs,
                pingHistoryMs = (current.pingHistoryMs + rttMs).takeLast(24)
            )
        }
    }

    fun appendLog(message: String) {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "[$stamp] $message"
        _state.update { current ->
            current.copy(logs = (current.logs + line).takeLast(120))
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

    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 1024L -> "$bytesPerSec B/s"
            bytesPerSec < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
            else -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
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
     * Prioritizes AdGuard when AdBlock is enabled, Carrier DNS in Auto mode, or explicit IP first.
     */
    fun buildCandidateDnsList(
        configuredDns: String,
        carrierDnsList: List<String>,
        adBlockEnabled: Boolean = false
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

        if (adBlockEnabled) {
            addIfNew(GpNetworkInspector.ADGUARD_DNS_IP)
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
        ednsPayloadSize: Int,
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
                includeEdns0 = true,
                ednsUdpPayloadSize = ednsPayloadSize
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

    private fun probeSingleDoh(
        dohUrl: String,
        labelIp: String,
        targetDomain: String,
        underlyingNetwork: Network?,
        protectTcp: ((Socket) -> Boolean)?
    ): DnsCandidateProbeResult {
        return runCatching {
            val txId = ((System.nanoTime() xor dohUrl.hashCode().toLong()) and 0xFFFFL).toInt()
            val query = DnsPacketCodec.buildDnsQueryPacket(
                domain = targetDomain,
                queryType = DnsPacketCodec.TYPE_A,
                transactionId = txId,
                includeEdns0 = false
            )
            val client = GpNetworkInspector.createProtectedDohClient(
                underlyingNetwork = underlyingNetwork,
                protectTcp = protectTcp,
                timeoutMs = 3000L
            )
            val reply = GpNetworkInspector.sendDohQuery(client, dohUrl, query)
            if (reply != null) {
                val (wireBytes, rtt) = reply
                val parsed = DnsPacketCodec.parseDnsResponse(wireBytes, wireBytes.size)
                if (parsed != null) {
                    DnsCandidateProbeResult(
                        serverIp = labelIp,
                        isCarrierDns = false,
                        transport = "DoH:443",
                        reachable = true,
                        rttMs = rtt,
                        responseCode = parsed.responseCode,
                        resolvedIps = parsed.resolvedIps,
                        dohUrl = dohUrl
                    )
                } else {
                    DnsCandidateProbeResult(
                        serverIp = labelIp,
                        isCarrierDns = false,
                        transport = "DoH:443",
                        reachable = false,
                        rttMs = null,
                        dohUrl = dohUrl,
                        error = "Invalid DoH response"
                    )
                }
            } else {
                DnsCandidateProbeResult(
                    serverIp = labelIp,
                    isCarrierDns = false,
                    transport = "DoH:443",
                    reachable = false,
                    rttMs = null,
                    dohUrl = dohUrl,
                    error = "DoH unreachable"
                )
            }
        }.getOrElse { err ->
            DnsCandidateProbeResult(
                serverIp = labelIp,
                isCarrierDns = false,
                transport = "DoH:443",
                reachable = false,
                rttMs = null,
                dohUrl = dohUrl,
                error = err.localizedMessage ?: err::class.java.simpleName
            )
        }
    }

    /**
     * Benchmarks Carrier DNS, Public UDP:53 resolvers, and Encrypted DoH:443 in parallel so
     * GP users can see exact latency rankings and pick the fastest resolver in 1 tap.
     */
    suspend fun runDnsBenchmark(
        context: Context,
        profile: HttpCustomTunnelProfile
    ): List<DnsBenchmarkEntry> = withContext(Dispatchers.IO) {
        _isBenchmarkRunning.value = true
        try {
            val snap = refreshCarrierSnapshot(context)
            val (activeNet, carrierDns) = discoverUnderlyingNetworkAndDns(context)
            val ednsSize = _tuning.value.ednsPayloadSize
            val targetDomain = profile.nameserver.ifBlank { profile.host }

            val udpTargets = buildList {
                carrierDns.forEachIndexed { idx, ip ->
                    val prefix = if (snap.isGrameenphone) "GP Carrier DNS #${idx + 1}" else "Carrier DNS #${idx + 1}"
                    add(Triple(prefix, ip, true))
                }
                add(Triple("Cloudflare Primary", "1.1.1.1", false))
                add(Triple("Cloudflare Alt", "1.0.0.1", false))
                add(Triple("Google DNS", "8.8.8.8", false))
                add(Triple("Quad9 Secure", "9.9.9.9", false))
                add(Triple("AdGuard AdBlock", GpNetworkInspector.ADGUARD_DNS_IP, false))
            }.distinctBy { it.second }

            val entries = coroutineScope {
                val udpJobs = udpTargets.map { (label, ip, isCarrier) ->
                    async(Dispatchers.IO) {
                        val res = probeSingleUdpDns(
                            dnsIp = ip,
                            isCarrier = isCarrier,
                            targetDomain = targetDomain,
                            queryType = DnsPacketCodec.TYPE_TXT,
                            ednsPayloadSize = ednsSize,
                            timeoutMs = 2400,
                            underlyingNetwork = activeNet,
                            protectUdp = null
                        )
                        DnsBenchmarkEntry(
                            label = label,
                            serverIp = ip,
                            transport = res.transport,
                            isCarrier = isCarrier,
                            reachable = res.reachable,
                            rttMs = res.rttMs,
                            detail = if (res.reachable) "SlowDNS EDNS0 OK" else (res.error ?: "Timeout")
                        )
                    }
                }
                val dohJob = async(Dispatchers.IO) {
                    val dohRes = probeSingleDoh(
                        dohUrl = GpNetworkInspector.CLOUDFLARE_DOH_URL,
                        labelIp = "1.1.1.1",
                        targetDomain = profile.host,
                        underlyingNetwork = activeNet,
                        protectTcp = null
                    )
                    DnsBenchmarkEntry(
                        label = "Cloudflare DoH (HTTPS)",
                        serverIp = "1.1.1.1",
                        transport = "DoH:443",
                        isCarrier = false,
                        reachable = dohRes.reachable,
                        rttMs = dohRes.rttMs,
                        detail = if (dohRes.reachable) "Encrypted Port 443 OK" else (dohRes.error ?: "Timeout")
                    )
                }
                udpJobs.awaitAll() + dohJob.await()
            }

            val sorted = entries.sortedWith(
                compareByDescending<DnsBenchmarkEntry> { it.reachable }
                    .thenBy { it.rttMs ?: Long.MAX_VALUE }
            )
            _benchmarkResults.value = sorted
            val fastest = sorted.firstOrNull { it.reachable }
            if (fastest != null) {
                appendLog("DNS Benchmark fastest: ${fastest.label} (${fastest.serverIp} ${fastest.transport}) -> ${fastest.rttMs} ms")
            } else {
                appendLog("DNS Benchmark: No resolvers responded")
            }
            sorted
        } finally {
            _isBenchmarkRunning.value = false
        }
    }

    suspend fun probeNetworkReachability(
        profile: HttpCustomTunnelProfile,
        context: Context? = null,
        tuningConfig: TunnelTuningConfig = _tuning.value,
        underlyingNetworkOverride: Network? = null,
        carrierDnsOverride: List<String>? = null,
        protectUdp: ((DatagramSocket) -> Boolean)? = null,
        protectTcp: ((Socket) -> Boolean)? = null
    ): ProbeSummary = withContext(Dispatchers.IO) {
        val (discoveredNet, discoveredDns) = discoverUnderlyingNetworkAndDns(context)
        val activeNet = underlyingNetworkOverride ?: discoveredNet
        val carrierDnsList = carrierDnsOverride ?: discoveredDns
        val candidates = buildCandidateDnsList(
            configuredDns = profile.dnsServer,
            carrierDnsList = carrierDnsList,
            adBlockEnabled = tuningConfig.adBlockDnsEnabled
        )
        val targetDomain = profile.nameserver.ifBlank { profile.host }
        val allResults = mutableListOf<DnsCandidateProbeResult>()
        var bestDns: DnsCandidateProbeResult? = null

        // Mode 1: Explicit DoH HTTPS:443 mode
        if (tuningConfig.transportMode == TunnelTransportMode.DOH_HTTPS) {
            val primaryDoh = GpNetworkInspector.selectDohEndpoint(profile.dnsServer, tuningConfig.adBlockDnsEnabled)
            val dohUrls = listOf(
                primaryDoh to if (primaryDoh == GpNetworkInspector.GOOGLE_DOH_URL) "8.8.8.8"
                else if (primaryDoh == GpNetworkInspector.ADGUARD_DOH_URL) GpNetworkInspector.ADGUARD_DNS_IP
                else "1.1.1.1",
                GpNetworkInspector.CLOUDFLARE_DOH_URL to "1.1.1.1",
                GpNetworkInspector.GOOGLE_DOH_URL to "8.8.8.8"
            ).distinctBy { it.first }

            val dohResults = coroutineScope {
                dohUrls.map { (url, ip) ->
                    async(Dispatchers.IO) {
                        probeSingleDoh(url, ip, profile.host, activeNet, protectTcp)
                    }
                }.awaitAll()
            }
            allResults.addAll(dohResults)
            bestDns = dohResults.filter { it.reachable }.minByOrNull { it.rttMs ?: Long.MAX_VALUE }
        }

        // Mode 2: UDP:53 Probing (Auto Smart or Carrier UDP)
        if (bestDns == null && tuningConfig.transportMode != TunnelTransportMode.TCP_53) {
            val udpCandidates = if (tuningConfig.transportMode == TunnelTransportMode.CARRIER_UDP && carrierDnsList.isNotEmpty()) {
                carrierDnsList.map { it to true }
            } else {
                candidates
            }
            val udpResults = coroutineScope {
                udpCandidates.map { (ip, isCarrier) ->
                    async(Dispatchers.IO) {
                        val primary = probeSingleUdpDns(
                            dnsIp = ip,
                            isCarrier = isCarrier,
                            targetDomain = targetDomain,
                            queryType = DnsPacketCodec.TYPE_TXT,
                            ednsPayloadSize = tuningConfig.ednsPayloadSize,
                            timeoutMs = 2500,
                            underlyingNetwork = activeNet,
                            protectUdp = protectUdp
                        )
                        if (primary.reachable) {
                            if (primary.resolvedIps.isEmpty()) {
                                val aProbe = probeSingleUdpDns(
                                    dnsIp = ip,
                                    isCarrier = isCarrier,
                                    targetDomain = profile.host,
                                    queryType = DnsPacketCodec.TYPE_A,
                                    ednsPayloadSize = tuningConfig.ednsPayloadSize,
                                    timeoutMs = 1500,
                                    underlyingNetwork = activeNet,
                                    protectUdp = protectUdp
                                )
                                primary.copy(resolvedIps = aProbe.resolvedIps)
                            } else {
                                primary
                            }
                        } else {
                            probeSingleUdpDns(
                                dnsIp = ip,
                                isCarrier = isCarrier,
                                targetDomain = profile.host,
                                queryType = DnsPacketCodec.TYPE_A,
                                ednsPayloadSize = tuningConfig.ednsPayloadSize,
                                timeoutMs = 1900,
                                underlyingNetwork = activeNet,
                                protectUdp = protectUdp
                            )
                        }
                    }
                }.awaitAll()
            }
            allResults.addAll(udpResults)
            bestDns = udpResults
                .filter { it.reachable }
                .minByOrNull { (it.rttMs ?: Long.MAX_VALUE) + if (it.isCarrierDns) 0L else 15L }
        }

        // Mode 3: TCP:53 Probing (explicit TCP_53 or fallback when UDP:53 fails in Auto Smart)
        if (bestDns == null) {
            val tcpResults = coroutineScope {
                candidates.take(4).map { (ip, isCarrier) ->
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

        // Mode 4: Automatic DoH HTTPS:443 Fallback in Auto Smart when both UDP:53 & TCP:53 are blocked
        if (bestDns == null && tuningConfig.transportMode == TunnelTransportMode.AUTO_SMART) {
            val fallbackDoh = coroutineScope {
                listOf(
                    GpNetworkInspector.CLOUDFLARE_DOH_URL to "1.1.1.1",
                    GpNetworkInspector.GOOGLE_DOH_URL to "8.8.8.8"
                ).map { (url, ip) ->
                    async(Dispatchers.IO) {
                        probeSingleDoh(url, ip, profile.host, activeNet, protectTcp)
                    }
                }.awaitAll()
            }
            allResults.addAll(fallbackDoh)
            bestDns = fallbackDoh.filter { it.reachable }.minByOrNull { it.rttMs ?: Long.MAX_VALUE }
        }

        // 5. Direct TCP + SSH-2.0 handshake to host:port
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
                tcp.connect(InetSocketAddress(connectTarget, profile.port), 3200)
                tcp.soTimeout = 2800
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
            buildList {
                add("DNS ($testedIps + DoH:443): কোনো রেসপন্স নেই (মোবাইল ডাটা/সিগন্যাল চেক করুন)")
                if (!sshError.isNullOrBlank()) add(sshError!!)
            }.joinToString(" | ")
        } else {
            null
        }

        ProbeSummary(
            dnsReachable = bestDns != null,
            workingDnsServer = bestDns?.serverIp,
            workingDnsTransport = bestDns?.transport,
            workingDohUrl = bestDns?.dohUrl,
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
