package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.HttpCustomTunnelProfile
import com.example.tunnel.DnsPacketCodec
import com.example.tunnel.HttpCustomTunnelStateHolder
import com.example.tunnel.TunnelConnectionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnectAndStop("ব্যবহারকারী সংযোগ বিচ্ছিন্ন করেছেন")
                return START_NOT_STICKY
            }

            ACTION_CONNECT, null -> {
                val profile = extractProfile(intent)
                startForegroundWithStatus("কানেক্ট হচ্ছে: ${profile.host}...")
                startTunnelConnection(profile)
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

    private fun startTunnelConnection(profile: HttpCustomTunnelProfile) {
        connectJob?.cancel()
        packetLoopJob?.cancel()
        keepaliveJob?.cancel()
        runCatching { tunInterface?.close() }
        tunInterface = null

        HttpCustomTunnelStateHolder.updateState { current ->
            current.copy(
                phase = TunnelConnectionPhase.CONNECTING,
                activeProfileId = profile.id,
                activeProfileName = profile.name,
                statusDetail = "সিম/নেটওয়ার্ক DNS ও SlowDNS রুট খোঁজা হচ্ছে...",
                activeDnsServer = null,
                activeDnsTransport = null,
                dnsRttMs = null,
                sshBanner = null,
                resolvedIp = null,
                lastError = null,
                txBytes = 0L,
                rxBytes = 0L,
                dnsPacketsHandled = 0,
                connectedSinceMs = null
            )
        }
        HttpCustomTunnelStateHolder.appendLog("Connecting: ${profile.name} (mode=${profile.dnsServer})")

        connectJob = serviceScope.launch {
            // Discover active physical network & carrier DNS BEFORE establishing TUN
            val (underlyingNetwork, carrierDnsList) =
                HttpCustomTunnelStateHolder.discoverUnderlyingNetworkAndDns(this@HttpCustomVpnService)

            if (carrierDnsList.isNotEmpty()) {
                HttpCustomTunnelStateHolder.appendLog("Carrier/System DNS detected: ${carrierDnsList.joinToString(", ")}")
            } else {
                HttpCustomTunnelStateHolder.appendLog("No carrier DNS reported; probing public resolvers...")
            }

            val probe = HttpCustomTunnelStateHolder.probeNetworkReachability(
                profile = profile,
                context = this@HttpCustomVpnService,
                underlyingNetworkOverride = underlyingNetwork,
                carrierDnsOverride = carrierDnsList,
                protectUdp = { sock -> protect(sock) },
                protectTcp = { tcp -> protect(tcp) }
            )

            probe.testedResolvers.forEach { res ->
                val tag = if (res.isCarrierDns) "Carrier" else "Public"
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
                    ?: "কোনো DNS বা SSH রেসপন্স পাওয়া যায়নি। মোবাইল ডাটা/ওয়াই-ফাই চালু আছে কিনা দেখুন।"
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            val upstreamDnsIp = probe.workingDnsServer
                ?: carrierDnsList.firstOrNull()
                ?: "1.1.1.1"
            val preferTcpUpstream = probe.workingDnsTransport == "TCP:53"

            val builder = Builder()
                .setSession(profile.name)
                .setMtu(1500)
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            tunInterface = established
            val dnsLabel = if (probe.workingDnsIsCarrier) {
                "Carrier DNS ($upstreamDnsIp)"
            } else {
                upstreamDnsIp
            }
            val modeLabel = when {
                probe.sshReachable && probe.dnsReachable -> "সংযুক্ত • SSH + SlowDNS ($dnsLabel)"
                probe.sshReachable -> "সংযুক্ত • Direct SSH (VPN সক্রিয়)"
                else -> "সংযুক্ত • SlowDNS (${probe.workingDnsTransport ?: "UDP:53"} • $dnsLabel)"
            }

            HttpCustomTunnelStateHolder.updateState { current ->
                current.copy(
                    phase = TunnelConnectionPhase.CONNECTED,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    statusDetail = modeLabel,
                    activeDnsServer = dnsLabel,
                    activeDnsTransport = probe.workingDnsTransport ?: "UDP:53",
                    dnsRttMs = probe.dnsRttMs ?: probe.sshRttMs,
                    sshBanner = probe.sshBanner ?: "SlowDNS ${probe.workingDnsTransport ?: "UDP:53"} Active",
                    resolvedIp = probe.resolvedIps.firstOrNull(),
                    connectedSinceMs = System.currentTimeMillis(),
                    lastError = null
                )
            }
            HttpCustomTunnelStateHolder.appendLog(
                "VPN TUN active: ${HttpCustomTunnelStateHolder.VIRTUAL_TUN_CLIENT_IP}/32 -> $upstreamDnsIp (${probe.workingDnsTransport ?: "UDP:53"})"
            )
            updateNotification("সংযুক্ত: $dnsLabel (${probe.dnsRttMs ?: probe.sshRttMs ?: 0} ms)")

            launchPacketForwarder(
                tun = established,
                upstreamDnsIp = upstreamDnsIp,
                preferTcp = preferTcpUpstream,
                underlyingNetwork = underlyingNetwork
            )
            launchKeepaliveMonitor(
                profile = profile,
                upstreamDnsIp = upstreamDnsIp,
                preferTcp = preferTcpUpstream,
                underlyingNetwork = underlyingNetwork
            )
        }
    }

    private fun launchPacketForwarder(
        tun: ParcelFileDescriptor,
        upstreamDnsIp: String,
        preferTcp: Boolean,
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
                            preferTcp = preferTcp,
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
        preferTcp: Boolean,
        underlyingNetwork: Network?
    ): ByteArray? {
        if (!preferTcp) {
            val udpReply = forwardViaUdp(payload, upstreamAddr, port, underlyingNetwork)
            if (udpReply != null) return udpReply
        }
        return forwardViaTcp(payload, upstreamAddr, port, underlyingNetwork)
            ?: if (preferTcp) forwardViaUdp(payload, upstreamAddr, port, underlyingNetwork) else null
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
            sock.soTimeout = 3200
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
            tcp.connect(InetSocketAddress(upstreamAddr, port), 3200)
            tcp.soTimeout = 3200
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
        upstreamDnsIp: String,
        preferTcp: Boolean,
        underlyingNetwork: Network?
    ) {
        keepaliveJob = serviceScope.launch {
            val upstreamAddr = runCatching { InetAddress.getByName(upstreamDnsIp) }.getOrNull() ?: return@launch
            val queryDomain = profile.nameserver.ifBlank { profile.host }
            while (isActive) {
                delay(15_000L)
                if (!isActive) break
                val txId = (System.currentTimeMillis() and 0xFFFFL).toInt()
                val query = DnsPacketCodec.buildDnsQueryPacket(
                    domain = queryDomain,
                    queryType = DnsPacketCodec.TYPE_TXT,
                    transactionId = txId,
                    includeEdns0 = true
                )
                val t0 = System.currentTimeMillis()
                val reply = forwardDnsQuery(query, upstreamAddr, 53, preferTcp, underlyingNetwork)
                if (reply != null) {
                    val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    HttpCustomTunnelStateHolder.updateState { current ->
                        current.copy(dnsRttMs = rtt)
                    }
                }
            }
        }
    }

    private fun disconnectAndStop(reason: String) {
        cleanupResources()
        HttpCustomTunnelStateHolder.updateState { current ->
            current.copy(
                phase = TunnelConnectionPhase.DISCONNECTED,
                statusDetail = "সংযোগ বিচ্ছিন্ন (Disconnected)",
                activeDnsServer = null,
                activeDnsTransport = null,
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
                    description = "Shows active SlowDNS and SSH tunnel connection status"
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
            .setContentTitle("HTTP Custom Tunnel")
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
