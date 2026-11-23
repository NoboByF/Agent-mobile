package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

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
                startForegroundWithStatus("সংযোগ স্থাপন করা হচ্ছে: ${profile.host}...")
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
        val dns = intent?.getStringExtra(EXTRA_DNS_SERVER)?.takeIf { it.isNotBlank() } ?: "8.8.8.8"

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
                statusDetail = "SlowDNS (UDP:53) ও SSH (${profile.host}:${profile.port}) যাচাই হচ্ছে...",
                lastError = null,
                txBytes = 0L,
                rxBytes = 0L,
                dnsPacketsHandled = 0,
                connectedSinceMs = null
            )
        }
        HttpCustomTunnelStateHolder.appendLog("Connecting profile: ${profile.name}")
        HttpCustomTunnelStateHolder.appendLog("Probing DNS ${profile.dnsServer}:53 for NS ${profile.nameserver}...")

        connectJob = serviceScope.launch {
            val probe = HttpCustomTunnelStateHolder.probeNetworkReachability(
                profile = profile,
                protectUdp = { sock -> protect(sock) },
                protectTcp = { tcp -> protect(tcp) }
            )

            if (probe.dnsReachable) {
                val resolvedLabel = probe.resolvedIps.firstOrNull() ?: "Resolved"
                HttpCustomTunnelStateHolder.appendLog("UDP DNS OK (${probe.dnsRttMs} ms) -> $resolvedLabel")
            } else {
                HttpCustomTunnelStateHolder.appendLog("UDP DNS probe failed")
            }

            if (probe.sshReachable) {
                HttpCustomTunnelStateHolder.appendLog("TCP ${profile.host}:${profile.port} OK (${probe.sshRttMs} ms) [${probe.sshBanner}]")
            } else {
                HttpCustomTunnelStateHolder.appendLog("TCP ${profile.host}:${profile.port} unreachable (${probe.errorSummary ?: "timeout"})")
            }

            if (!probe.dnsReachable && !probe.sshReachable) {
                val errText = probe.errorSummary ?: "নেটওয়ার্ক সংযোগ পাওয়া যায়নি (ইন্টারনেট চালু আছে কিনা দেখুন)"
                HttpCustomTunnelStateHolder.updateState { current ->
                    current.copy(
                        phase = TunnelConnectionPhase.ERROR,
                        statusDetail = "সংযোগ ব্যর্থ হয়েছে",
                        lastError = errText,
                        connectedSinceMs = null
                    )
                }
                HttpCustomTunnelStateHolder.appendLog("Connection failed: $errText")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            // Validate numeric IPv4 DNS address for VpnService Builder
            val numericDns = resolveNumericIpv4(profile.dnsServer) ?: "8.8.8.8"
            val builder = Builder()
                .setSession(profile.name)
                .setMtu(1500)
                .addAddress("10.88.0.2", 32)
                .addDnsServer(numericDns)
                .addRoute(numericDns, 32)

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
            val modeLabel = when {
                probe.sshReachable && probe.dnsReachable -> "সংযুক্ত • SSH + SlowDNS (VPN সক্রিয়)"
                probe.sshReachable -> "সংযুক্ত • Direct SSH (VPN সক্রিয়)"
                else -> "সংযুক্ত • SlowDNS UDP:53 টানেল সক্রিয়"
            }

            HttpCustomTunnelStateHolder.updateState { current ->
                current.copy(
                    phase = TunnelConnectionPhase.CONNECTED,
                    activeProfileId = profile.id,
                    activeProfileName = profile.name,
                    statusDetail = modeLabel,
                    dnsRttMs = probe.dnsRttMs ?: probe.sshRttMs,
                    sshBanner = probe.sshBanner ?: "SlowDNS UDP:53 Ready",
                    resolvedIp = probe.resolvedIps.firstOrNull(),
                    connectedSinceMs = System.currentTimeMillis(),
                    lastError = null
                )
            }
            HttpCustomTunnelStateHolder.appendLog("VPN TUN active: 10.88.0.2/32 -> DNS $numericDns")
            updateNotification("সংযুক্ত: ${profile.host} (${probe.dnsRttMs ?: probe.sshRttMs ?: 0} ms)")

            launchPacketForwarder(established, numericDns)
            launchKeepaliveMonitor(profile)
        }
    }

    private fun launchPacketForwarder(tun: ParcelFileDescriptor, fallbackDns: String) {
        packetLoopJob = serviceScope.launch {
            val fd = tun.fileDescriptor
            val input = FileInputStream(fd)
            val output = FileOutputStream(fd)
            val buffer = ByteArray(4096)

            try {
                while (isActive) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) {
                        delay(10L)
                        continue
                    }

                    val udpPacket = DnsPacketCodec.parseTunUdpPacket(buffer, bytesRead) ?: continue
                    val targetIp = runCatching { InetAddress.getByAddress(udpPacket.dstIp) }
                        .getOrElse { InetAddress.getByName(fallbackDns) }

                    HttpCustomTunnelStateHolder.updateState { current ->
                        current.copy(txBytes = current.txBytes + udpPacket.payload.size)
                    }

                    // Forward UDP DNS query asynchronously so TUN reading stays responsive
                    launch(Dispatchers.IO) {
                        runCatching {
                            DatagramSocket().use { sock ->
                                protect(sock)
                                sock.soTimeout = 4000
                                sock.send(
                                    DatagramPacket(
                                        udpPacket.payload,
                                        udpPacket.payload.size,
                                        targetIp,
                                        udpPacket.dstPort
                                    )
                                )
                                val replyBuf = ByteArray(2048)
                                val replyPacket = DatagramPacket(replyBuf, replyBuf.size)
                                sock.receive(replyPacket)
                                val replyPayload = replyBuf.copyOfRange(0, replyPacket.length)

                                val tunReply = DnsPacketCodec.buildTunUdpResponsePacket(
                                    srcIp = udpPacket.dstIp,
                                    dstIp = udpPacket.srcIp,
                                    srcPort = udpPacket.dstPort,
                                    dstPort = udpPacket.srcPort,
                                    payload = replyPayload
                                )
                                synchronized(output) {
                                    output.write(tunReply)
                                    output.flush()
                                }
                                HttpCustomTunnelStateHolder.updateState { current ->
                                    current.copy(
                                        rxBytes = current.rxBytes + replyPayload.size,
                                        dnsPacketsHandled = current.dnsPacketsHandled + 1
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Expected when ParcelFileDescriptor is closed during disconnect
            }
        }
    }

    private fun launchKeepaliveMonitor(profile: HttpCustomTunnelProfile) {
        keepaliveJob = serviceScope.launch {
            while (isActive) {
                delay(12_000L)
                if (!isActive) break
                val ping = HttpCustomTunnelStateHolder.probeNetworkReachability(
                    profile = profile,
                    protectUdp = { sock -> protect(sock) },
                    protectTcp = { tcp -> protect(tcp) }
                )
                val rtt = ping.dnsRttMs ?: ping.sshRttMs
                if (rtt != null) {
                    HttpCustomTunnelStateHolder.updateState { current ->
                        current.copy(
                            dnsRttMs = rtt,
                            sshBanner = ping.sshBanner ?: current.sshBanner
                        )
                    }
                }
            }
        }
    }

    private fun resolveNumericIpv4(hostOrIp: String): String? {
        val clean = hostOrIp.trim()
        val ipv4Regex = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        if (ipv4Regex.matches(clean)) return clean
        return runCatching {
            InetAddress.getAllByName(clean).firstOrNull { it.address.size == 4 }?.hostAddress
        }.getOrNull()
    }

    private fun disconnectAndStop(reason: String) {
        cleanupResources()
        HttpCustomTunnelStateHolder.updateState { current ->
            current.copy(
                phase = TunnelConnectionPhase.DISCONNECTED,
                statusDetail = "সংযোগ বিচ্ছিন্ন (Disconnected)",
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
