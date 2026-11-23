package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.HttpCustomTunnelProfile
import com.example.tunnel.DnsPacketCodec
import com.example.tunnel.GpNetworkInspector
import com.example.tunnel.HttpCustomTunnelStateHolder
import com.example.tunnel.TunnelConnectionPhase
import com.example.tunnel.TunnelTuningConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class HttpCustomVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var connectJob: Job? = null
    private var packetLoopJob: Job? = null
    private var keepaliveJob: Job? = null
    private var speedMeterJob: Job? = null
    private var watchdogReconnectJob: Job? = null

    private var activeProfileRef: HttpCustomTunnelProfile? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastBoundNetworkHandle: Long? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnectAndStop("ব্যবহারকারী সংযোগ বিচ্ছিন্ন করেছেন")
                return START_NOT_STICKY
            }

            ACTION_CONNECT, null -> {
                val profile = extractProfile(intent)
                activeProfileRef = profile
                startForegroundWithStatus("কানেক্ট হচ্ছে: ${profile.host}...")
                startTunnelConnection(profile, isAutoReconnect = false)
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onRevoke() {
        disconnectAndStop("সিস্টেম থেকে VPN অনুমতি বাতিল করা হয়েছে")
        super.onRevoke()
    }

    override fun onDestroy() {
        unregisterNetworkWatchdog()
        cleanupResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun extractProfile(intent: Intent?): HttpCustomTunnelProfile {
        val id = intent?.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val existing = HttpCustomTunnelStateHolder.profiles.value.firstOrNull { it.id == id }
        if (existing != null) return existing

        val host = intent?.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() } ?: "nsts3.me-link.uk"
        val port = intent?.getIntExtra(EXTRA_PORT, 22)?.takeIf { it in 1..65535 } ?: 22
        val name = intent?.getStringExtra(EXTRA_PROFILE_NAME)?.takeIf { it.isNotBlank() }
            ?: "HTTP Custom ($host)"
        val ns = intent?.getStringExtra(EXTRA_NAMESERVER)?.takeIf { it.isNotBlank() } ?: host
        val dns = intent?.getStringExtra(EXTRA_DNS_SERVER)?.takeIf { it.isNotBlank() } ?: "auto"

        return HttpCustomTunnelProfile(
            id = id.ifBlank { "hc_active" },
            name = name,
            operatorName = "SlowDNS + SSH",
            host = host,
            port = port,
            username = intent?.getStringExtra(EXTRA_USERNAME).orEmpty(),
            password = "",
            protocol = "SlowDns + SSH",
            nameserver = ns,
            pubKey = intent?.getStringExtra(EXTRA_PUBKEY).orEmpty(),
            bannerNote = "",
            rawJson = "",
            dnsServer = dns
        )
    }

    private fun startTunnelConnection(profile: HttpCustomTunnelProfile, isAutoReconnect: Boolean) {
        connectJob?.cancel()
        packetLoopJob?.cancel()
        keepaliveJob?.cancel()
        speedMeterJob?.cancel()
        runCatching { tunInterface?.close() }
        tunInterface = null

        val tuning = HttpCustomTunnelStateHolder.tuning.value
        val carrierSnap = HttpCustomTunnelStateHolder.refreshCarrierSnapshot(this)
        val opTag = if (carrierSnap.isGrameenphone) "Grameenphone (GP)" else carrierSnap.operatorName

        HttpCustomTunnelStateHolder.updateState { current ->
            current.copy(
                phase = TunnelConnectionPhase.CONNECTING,
                activeProfileId = profile.id,
                activeProfileName = profile.name,
                statusDetail = if (isAutoReconnect) {
                    "$opTag নেটওয়ার্ক পরিবর্তন — অটো রিকানেক্ট হচ্ছে..."
                } else {
                    "$opTag • ${tuning.transportMode.shortLabel} যাচাই হচ্ছে..."
                },
                activeDnsServer = null,
                activeDnsTransport = null,
                activeDohUrl = null,
                dnsRttMs = null,
                sshBanner = null,
                resolvedIp = null,
                lastError = null,
                txSpeedBps = 0L,
                rxSpeedBps = 0L,
                txBytes = if (isAutoReconnect) current.txBytes else 0L,
                rxBytes = if (isAutoReconnect) current.rxBytes else 0L,
                dnsPacketsHandled = if (isAutoReconnect) current.dnsPacketsHandled else 0,
                reconnectCount = if (isAutoReconnect) current.reconnectCount + 1 else 0,
                pingHistoryMs = if (isAutoReconnect) current.pingHistoryMs else emptyList(),
                connectedSinceMs = if (isAutoReconnect) current.connectedSinceMs else null
            )
        }
        HttpCustomTunnelStateHolder.appendLog(
            "${if (isAutoReconnect) "Auto-reconnecting" else "Connecting"} [$opTag / ${carrierSnap.transportLabel}]: ${profile.name} (${tuning.transportMode.shortLabel}, MTU=${tuning.mtuSize})"
        )

        connectJob = serviceScope.launch {
            val (underlyingNetwork, carrierDnsList) =
                HttpCustomTunnelStateHolder.discoverUnderlyingNetworkAndDns(this@HttpCustomVpnService)
            lastBoundNetworkHandle = underlyingNetwork?.networkHandle

            if (carrierDnsList.isNotEmpty()) {
                HttpCustomTunnelStateHolder.appendLog("$opTag DNS detected: ${carrierDnsList.joinToString(", ")}")
            } else {
                HttpCustomTunnelStateHolder.appendLog("No carrier DNS reported; probing public + DoH:443...")
            }

            val probe = HttpCustomTunnelStateHolder.probeNetworkReachability(
                profile = profile,
                context = this@HttpCustomVpnService,
                tuningConfig = tuning,
                underlyingNetworkOverride = underlyingNetwork,
                carrierDnsOverride = carrierDnsList,
                protectUdp = { sock -> protect(sock) },
                protectTcp = { tcp -> protect(tcp) }
            )

            probe.testedResolvers.forEach { res ->
                val tag = if (res.isCarrierDns) "GP/Carrier" else "Public"
                if (res.reachable) {
                    HttpCustomTunnelStateHolder.appendLog("OK [$tag] ${res.serverIp} (${res.transport}) ${res.rttMs} ms")
                } else {
                    HttpCustomTunnelStateHolder.appendLog("Timeout [$tag] ${res.serverIp} (${res.transport})")
                }
            }

            if (probe.sshReachable) {
                HttpCustomTunnelStateHolder.appendLog("OK SSH ${profile.host}:${profile.port} (${probe.sshRttMs} ms) [${probe.sshBanner}]")
            }

            if (!probe.dnsReachable && !probe.sshReachable) {
                val errText = probe.errorSummary
                    ?: "কোনো DNS বা DoH:443 রেসপন্স পাওয়া যায়নি। সিমের 4G ডাটা চালু আছে কিনা দেখুন।"
                HttpCustomTunnelStateHolder.updateState { current ->
                    current.copy(
                        phase = TunnelConnectionPhase.ERROR,
                        statusDetail = "সংযোগ ব্যর্থ হয়েছে",
                        dnsRttMs = null,
                        lastError = errText,
                        connectedSinceMs = null
                    )
                }
                HttpCustomTunnelStateHolder.appendLog("Connection failed: $errText")
                unregisterNetworkWatchdog()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            val upstreamDnsIp = probe.workingDnsServer
                ?: carrierDnsList.firstOrNull()
                ?: "1.1.1.1"
            val transport = probe.workingDnsTransport ?: "UDP:53"
            val activeDohUrl = probe.workingDohUrl
                ?: if (transport == "DoH:443") {
                    GpNetworkInspector.selectDohEndpoint(profile.dnsServer, tuning.adBlockDnsEnabled)
                } else {
                    null
                }

            val builder = Builder()
                .setSession(profile.name)
                .setMtu(tuning.mtuSize.coerceIn(1280, 1500))
                .addAddress(HttpCustomTunnelStateHolder.VIRTUAL_TUN_CLIENT_IP, 32)
                .addDnsServer(HttpCustomTunnelStateHolder.VIRTUAL_TUN_DNS_IP)
                .addRoute(HttpCustomTunnelStateHolder.VIRTUAL_TUN_DNS_IP, 32)

            if (underlyingNetwork != null) {
                runCatching { builder.setUnderlyingNetworks(arrayOf(underlyingNetwork)) }
            }

            val established = runCatching { builder.establish() }.getOrNull()
            if (established == null) {
                val permErr = "Android VPN ইন্টারফেস তৈরি করা যায়নি। অনুগ্রহ করে VPN অনুমতি দিন।"
                HttpCustomTunnelStateHolder.updateState { current ->
                    current.copy(
                        phase = TunnelConnectionPhase.ERROR,
                        statusDetail = "VPN অনুমতি প্রয়োজন",
                        lastError = permErr,
                        connectedSinceMs = null
                    )
                }
                HttpCustomTunnelStateHolder.appendLog("TUN establish returned null")
                unregisterNetworkWatchdog()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            tunInterface = established
            val dnsLabel = when {
                activeDohUrl != null -> "DoH 443 ($upstreamDnsIp)"
                probe.workingDnsIsCarrier && carrierSnap.isGrameenphone -> "GP Carrier ($upstreamDnsIp)"
                probe.workingDnsIsCarrier -> "Carrier DNS ($upstreamDnsIp)"
                tuning.adBlockDnsEnabled && upstreamDnsIp == GpNetworkInspector.ADGUARD_DNS_IP -> "AdGuard ($upstreamDnsIp)"
                else -> upstreamDnsIp
            }
            val modeLabel = when {
                probe.sshReachable && probe.dnsReachable -> "সংযুক্ত • SSH + $dnsLabel"
                probe.sshReachable -> "সংযুক্ত • Direct SSH (VPN সক্রিয়)"
                activeDohUrl != null -> "সংযুক্ত • Encrypted DoH:443 ($upstreamDnsIp)"
                else -> "সংযুক্ত • SlowDNS ($transport • $dnsLabel)"
            }
            val initialRtt = probe.dnsRttMs ?: probe.sshRttMs

            HttpCustomTunnelStateHolder.updateState { current ->
                current.copy(
                    phase = TunnelConnectionPhase.CONNECTED,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    statusDetail = modeLabel,
                    activeDnsServer = dnsLabel,
                    activeDnsTransport = transport,
                    activeDohUrl = activeDohUrl,
                    dnsRttMs = initialRtt,
                    pingHistoryMs = initialRtt?.let { (current.pingHistoryMs + it).takeLast(24) }
                        ?: current.pingHistoryMs,
                    sshBanner = probe.sshBanner ?: "SlowDNS $transport Active",
                    resolvedIp = probe.resolvedIps.firstOrNull(),
                    connectedSinceMs = current.connectedSinceMs ?: System.currentTimeMillis(),
                    lastError = null
                )
            }
            HttpCustomTunnelStateHolder.appendLog(
                "VPN TUN active: ${HttpCustomTunnelStateHolder.VIRTUAL_TUN_CLIENT_IP}/32 (MTU ${tuning.mtuSize}) -> $dnsLabel [$transport]"
            )
            updateNotification("সংযুক্ত: $dnsLabel (${initialRtt ?: 0} ms)")

            val dohClient = GpNetworkInspector.createProtectedDohClient(
                underlyingNetwork = underlyingNetwork,
                protectTcp = { tcp -> protect(tcp) },
                timeoutMs = 3500L
            )

            launchPacketForwarder(
                tun = established,
                upstreamDnsIp = upstreamDnsIp,
                transport = transport,
                activeDohUrl = activeDohUrl,
                dohClient = dohClient,
                underlyingNetwork = underlyingNetwork
            )
            launchKeepaliveMonitor(
                profile = profile,
                tuning = tuning,
                upstreamDnsIp = upstreamDnsIp,
                transport = transport,
                activeDohUrl = activeDohUrl,
                dohClient = dohClient,
                underlyingNetwork = underlyingNetwork
            )
            launchSpeedMeter()
            if (tuning.autoReconnectWatchdog) {
                registerNetworkWatchdog()
            } else {
                unregisterNetworkWatchdog()
            }
        }
    }

    private fun launchPacketForwarder(
        tun: ParcelFileDescriptor,
        upstreamDnsIp: String,
        transport: String,
        activeDohUrl: String?,
        dohClient: OkHttpClient,
        underlyingNetwork: Network?
    ) {
        packetLoopJob = serviceScope.launch {
            val fd = tun.fileDescriptor
            val input = FileInputStream(fd)
            val output = FileOutputStream(fd)
            val buffer = ByteArray(4096)
            val upstreamAddr = runCatching { InetAddress.getByName(upstreamDnsIp) }.getOrNull() ?: return@launch

            try {
                while (isActive) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) {
                        delay(10L)
                        continue
                    }

                    val udpPacket = DnsPacketCodec.parseTunUdpPacket(buffer, bytesRead) ?: continue
                    HttpCustomTunnelStateHolder.updateState { current ->
                        current.copy(txBytes = current.txBytes + udpPacket.payload.size)
                    }

                    launch(Dispatchers.IO) {
                        val replyPayload = forwardDnsQuery(
                            payload = udpPacket.payload,
                            upstreamAddr = upstreamAddr,
                            port = udpPacket.dstPort,
                            transport = transport,
                            activeDohUrl = activeDohUrl,
                            dohClient = dohClient,
                            underlyingNetwork = underlyingNetwork
                        ) ?: return@launch

                        val tunReply = DnsPacketCodec.buildTunUdpResponsePacket(
                            srcIp = udpPacket.dstIp,
                            dstIp = udpPacket.srcIp,
                            srcPort = udpPacket.dstPort,
                            dstPort = udpPacket.srcPort,
                            payload = replyPayload
                        )
                        synchronized(output) {
                            runCatching {
                                output.write(tunReply)
                                output.flush()
                            }
                        }
                        HttpCustomTunnelStateHolder.updateState { current ->
                            current.copy(
                                rxBytes = current.rxBytes + replyPayload.size,
                                dnsPacketsHandled = current.dnsPacketsHandled + 1
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Expected when ParcelFileDescriptor is closed during disconnect
            }
        }
    }

    private fun forwardDnsQuery(
        payload: ByteArray,
        upstreamAddr: InetAddress,
        port: Int,
        transport: String,
        activeDohUrl: String?,
        dohClient: OkHttpClient,
        underlyingNetwork: Network?
    ): ByteArray? {
        return when (transport) {
            "DoH:443" -> {
                val url = activeDohUrl ?: GpNetworkInspector.CLOUDFLARE_DOH_URL
                GpNetworkInspector.sendDohQuery(dohClient, url, payload)?.first
                    ?: forwardViaUdp(payload, upstreamAddr, port, underlyingNetwork)
            }

            "TCP:53" -> {
                forwardViaTcp(payload, upstreamAddr, port, underlyingNetwork)
                    ?: forwardViaUdp(payload, upstreamAddr, port, underlyingNetwork)
            }

            else -> {
                forwardViaUdp(payload, upstreamAddr, port, underlyingNetwork)
                    ?: forwardViaTcp(payload, upstreamAddr, port, underlyingNetwork)
                    ?: activeDohUrl?.let { GpNetworkInspector.sendDohQuery(dohClient, it, payload)?.first }
            }
        }
    }

    private fun forwardViaUdp(
        payload: ByteArray,
        upstreamAddr: InetAddress,
        port: Int,
        underlyingNetwork: Network?
    ): ByteArray? = runCatching {
        DatagramSocket().use { sock ->
            protect(sock)
            if (underlyingNetwork != null) {
                runCatching { underlyingNetwork.bindSocket(sock) }
            }
            sock.soTimeout = 3000
            sock.send(DatagramPacket(payload, payload.size, upstreamAddr, port))
            val replyBuf = ByteArray(2048)
            val replyPacket = DatagramPacket(replyBuf, replyBuf.size)
            sock.receive(replyPacket)
            replyBuf.copyOfRange(0, replyPacket.length)
        }
    }.getOrNull()

    private fun forwardViaTcp(
        payload: ByteArray,
        upstreamAddr: InetAddress,
        port: Int,
        underlyingNetwork: Network?
    ): ByteArray? = runCatching {
        Socket().use { tcp ->
            protect(tcp)
            if (underlyingNetwork != null) {
                runCatching { underlyingNetwork.bindSocket(tcp) }
            }
            tcp.connect(InetSocketAddress(upstreamAddr, port), 3000)
            tcp.soTimeout = 3000
            val dos = DataOutputStream(tcp.getOutputStream())
            dos.writeShort(payload.size)
            dos.write(payload)
            dos.flush()

            val dis = DataInputStream(tcp.getInputStream())
            val len = dis.readUnsignedShort()
            if (len in 12..4096) {
                val out = ByteArray(len)
                dis.readFully(out)
                out
            } else {
                null
            }
        }
    }.getOrNull()

    private fun launchKeepaliveMonitor(
        profile: HttpCustomTunnelProfile,
        tuning: TunnelTuningConfig,
        upstreamDnsIp: String,
        transport: String,
        activeDohUrl: String?,
        dohClient: OkHttpClient,
        underlyingNetwork: Network?
    ) {
        keepaliveJob = serviceScope.launch {
            val upstreamAddr = runCatching { InetAddress.getByName(upstreamDnsIp) }.getOrNull() ?: return@launch
            val queryDomain = profile.nameserver.ifBlank { profile.host }
            val intervalMs = (tuning.keepaliveIntervalSec.coerceIn(10, 60)) * 1000L
            var consecutiveMisses = 0

            while (isActive) {
                delay(intervalMs)
                if (!isActive) break
                val txId = (System.currentTimeMillis() and 0xFFFFL).toInt()
                val query = DnsPacketCodec.buildDnsQueryPacket(
                    domain = queryDomain,
                    queryType = DnsPacketCodec.TYPE_TXT,
                    transactionId = txId,
                    includeEdns0 = true,
                    ednsUdpPayloadSize = tuning.ednsPayloadSize
                )
                val t0 = System.currentTimeMillis()
                val reply = forwardDnsQuery(
                    payload = query,
                    upstreamAddr = upstreamAddr,
                    port = 53,
                    transport = transport,
                    activeDohUrl = activeDohUrl,
                    dohClient = dohClient,
                    underlyingNetwork = underlyingNetwork
                )
                if (reply != null) {
                    consecutiveMisses = 0
                    val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    HttpCustomTunnelStateHolder.recordPingSample(rtt)
                } else {
                    consecutiveMisses++
                    if (consecutiveMisses >= 2 && tuning.autoReconnectWatchdog) {
                        HttpCustomTunnelStateHolder.appendLog("Watchdog: Keepalive missed ($consecutiveMisses) -> re-calibrating GP route...")
                        triggerWatchdogReconnect()
                        break
                    }
                }
            }
        }
    }

    private fun launchSpeedMeter() {
        speedMeterJob = serviceScope.launch {
            var prevTx = HttpCustomTunnelStateHolder.state.value.txBytes
            var prevRx = HttpCustomTunnelStateHolder.state.value.rxBytes
            while (isActive) {
                delay(1000L)
                val current = HttpCustomTunnelStateHolder.state.value
                val dTx = (current.txBytes - prevTx).coerceAtLeast(0L)
                val dRx = (current.rxBytes - prevRx).coerceAtLeast(0L)
                prevTx = current.txBytes
                prevRx = current.rxBytes
                HttpCustomTunnelStateHolder.updateState {
                    it.copy(txSpeedBps = dTx, rxSpeedBps = dRx)
                }
            }
        }
    }

    private fun registerNetworkWatchdog() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                if (lastBoundNetworkHandle != null && handle != lastBoundNetworkHandle) {
                    HttpCustomTunnelStateHolder.appendLog("Network switched -> Watchdog scheduling GP DNS refresh")
                    triggerWatchdogReconnect()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network.networkHandle == lastBoundNetworkHandle) {
                    HttpCustomTunnelStateHolder.refreshCarrierSnapshot(this@HttpCustomVpnService)
                }
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        }
    }

    private fun unregisterNetworkWatchdog() {
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun triggerWatchdogReconnect() {
        val profile = activeProfileRef ?: return
        watchdogReconnectJob?.cancel()
        watchdogReconnectJob = serviceScope.launch {
            delay(1500L)
            if (isActive && HttpCustomTunnelStateHolder.state.value.phase == TunnelConnectionPhase.CONNECTED) {
                startTunnelConnection(profile, isAutoReconnect = true)
            }
        }
    }

    private fun disconnectAndStop(reason: String) {
        unregisterNetworkWatchdog()
        cleanupResources()
        HttpCustomTunnelStateHolder.updateState { current ->
            current.copy(
                phase = TunnelConnectionPhase.DISCONNECTED,
                statusDetail = "সংযোগ বিচ্ছিন্ন (Disconnected)",
                activeDnsServer = null,
                activeDnsTransport = null,
                activeDohUrl = null,
                txSpeedBps = 0L,
                rxSpeedBps = 0L,
                connectedSinceMs = null,
                lastError = null
            )
        }
        HttpCustomTunnelStateHolder.appendLog("Disconnected: $reason")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupResources() {
        connectJob?.cancel()
        packetLoopJob?.cancel()
        keepaliveJob?.cancel()
        speedMeterJob?.cancel()
        watchdogReconnectJob?.cancel()
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "HTTP Custom Tunnel",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows active GP SlowDNS, DoH and SSH tunnel status"
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(contentText: String): Notification {
        ensureNotificationChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            101,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_TAB, "HTTP_CUSTOM")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, HttpCustomVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GP SlowDNS • HTTP Custom")
            .setContentText(contentText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Disconnect", disconnectIntent)
            .build()
    }

    private fun startForegroundWithStatus(status: String) {
        startForeground(NOTIFICATION_ID, buildNotification(status))
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        const val ACTION_CONNECT = "com.example.tunnel.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.tunnel.ACTION_DISCONNECT"

        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_PROFILE_NAME = "extra_profile_name"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_NAMESERVER = "extra_nameserver"
        const val EXTRA_DNS_SERVER = "extra_dns_server"
        const val EXTRA_PUBKEY = "extra_pubkey"

        private const val CHANNEL_ID = "http_custom_vpn_channel"
        private const val NOTIFICATION_ID = 4401

        fun createConnectIntent(context: Context, profile: HttpCustomTunnelProfile): Intent {
            return Intent(context, HttpCustomVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_PROFILE_ID, profile.id)
                putExtra(EXTRA_PROFILE_NAME, profile.name)
                putExtra(EXTRA_HOST, profile.host)
                putExtra(EXTRA_PORT, profile.port)
                putExtra(EXTRA_USERNAME, profile.username)
                putExtra(EXTRA_NAMESERVER, profile.nameserver)
                putExtra(EXTRA_DNS_SERVER, profile.dnsServer)
                putExtra(EXTRA_PUBKEY, profile.pubKey)
            }
        }

        fun createDisconnectIntent(context: Context): Intent {
            return Intent(context, HttpCustomVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
        }
    }
}
