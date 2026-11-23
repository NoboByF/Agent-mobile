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
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.HttpCustomTunnelProfile
import com.example.tunnel.DnsPacketCodec
import com.example.tunnel.DnsttPacketTransport
import com.example.tunnel.GpNetworkInspector
import com.example.tunnel.HttpCustomTunnelStateHolder
import com.example.tunnel.InternetCheckResult
import com.example.tunnel.InternetCheckStatus
import com.example.tunnel.InternetConnectivityVerifier
import com.example.tunnel.KcpSession
import com.example.tunnel.NoiseNkChannel
import com.example.tunnel.PipelineStageState
import com.example.tunnel.SmuxSession
import com.example.tunnel.SmuxStream
import com.example.tunnel.SshProxyBridge
import com.example.tunnel.DiagnosticCheckRow
import com.example.tunnel.FailureCategory
import com.example.tunnel.SshWebSocketConfig
import com.example.tunnel.SshWebSocketConnectResult
import com.example.tunnel.SshWebSocketTransport
import com.example.tunnel.TunnelAutoDoctor
import com.example.tunnel.TunnelConnectionPhase
import com.example.tunnel.TunnelDiagnosis
import com.example.tunnel.TunnelPipelineStages
import com.example.tunnel.TunnelTuningConfig
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import java.util.concurrent.atomic.AtomicLong

class HttpCustomVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var connectJob: Job? = null
    private var packetLoopJob: Job? = null
    private var keepaliveJob: Job? = null
    private var speedMeterJob: Job? = null
    private var watchdogReconnectJob: Job? = null

    // Full SlowDNS (dnstt) -> KCP -> Noise_NK -> SMUX -> SSH-2.0 -> Local Proxy stack
    private var dnsttTransport: DnsttPacketTransport? = null
    private var kcpSession: KcpSession? = null
    private var smuxSession: SmuxSession? = null
    private var smuxStream: SmuxStream? = null
    private var sshBridge: SshProxyBridge? = null

    private var activeProfileRef: HttpCustomTunnelProfile? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastBoundNetworkHandle: Long? = null
    private val lastConnectAttemptMs = AtomicLong(0L)
    private val lastDnsRxAtMs = AtomicLong(0L)

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

        val isWs = intent?.getBooleanExtra(EXTRA_IS_WS_SSH, false) == true
        val useTls = intent?.getBooleanExtra(EXTRA_USE_TLS, port == 443 || port == 8443) == true
        val sniHost = intent?.getStringExtra(EXTRA_SNI_HOST).orEmpty()
        val bugHost = intent?.getStringExtra(EXTRA_BUG_HOST).orEmpty()
        val wsPath = intent?.getStringExtra(EXTRA_WS_PATH)?.takeIf { it.isNotBlank() } ?: "/"
        val customPayload = intent?.getStringExtra(EXTRA_CUSTOM_PAYLOAD).orEmpty()
        val splitPayload = intent?.getBooleanExtra(EXTRA_SPLIT_PAYLOAD, false) == true
        val tlsFragment = intent?.getBooleanExtra(EXTRA_TLS_FRAGMENT, false) == true
        val autoFallback = intent?.getBooleanExtra(EXTRA_AUTO_FALLBACK, false) == true

        return HttpCustomTunnelProfile(
            id = id.ifBlank { "hc_active" },
            name = name,
            operatorName = if (isWs) "SSH WebSocket" else "SlowDNS + SSH",
            host = host,
            port = port,
            username = intent?.getStringExtra(EXTRA_USERNAME).orEmpty(),
            password = intent?.getStringExtra(EXTRA_PASSWORD).orEmpty(),
            protocol = if (isWs) "SSH WebSocket (${if (useTls) "SSL/TLS" else "HTTP"})" else "SlowDns + SSH",
            nameserver = ns,
            pubKey = intent?.getStringExtra(EXTRA_PUBKEY).orEmpty(),
            bannerNote = "",
            rawJson = "",
            dnsServer = dns,
            isWebSocketSsh = isWs,
            useTls = useTls,
            sniHost = sniHost,
            bugHost = bugHost,
            wsPath = wsPath,
            customPayload = customPayload,
            enableSplitPayload = splitPayload,
            enableTlsFragment = tlsFragment,
            autoFallbackAllModes = autoFallback
        )
    }

    private fun startTunnelConnection(profile: HttpCustomTunnelProfile, isAutoReconnect: Boolean) {
        lastConnectAttemptMs.set(System.currentTimeMillis())
        cleanupTunnelOnly()

        val tuning = HttpCustomTunnelStateHolder.tuning.value
        val carrierSnap = HttpCustomTunnelStateHolder.refreshCarrierSnapshot(this)
        val opTag = if (carrierSnap.isGrameenphone) "Grameenphone (GP)" else carrierSnap.operatorName

        HttpCustomTunnelStateHolder.updateState { current ->
            current.copy(
                phase = TunnelConnectionPhase.CONNECTING,
                activeProfileId = profile.id,
                activeProfileName = profile.name,
                statusDetail = if (isAutoReconnect) {
                    "$opTag নেটওয়ার্ক রি-ক্যালিব্রেট হচ্ছে..."
                } else {
                    "ধাপ ১/৫: $opTag DNS যাচাই হচ্ছে..."
                },
                activeDnsServer = null,
                activeDnsTransport = null,
                activeDohUrl = null,
                dnsRttMs = null,
                sshBanner = null,
                resolvedIp = null,
                localProxyPort = null,
                tunnelEngineLabel = null,
                pipelineStages = TunnelPipelineStages(carrierDns = PipelineStageState.RUNNING),
                internetCheck = InternetCheckResult(
                    status = InternetCheckStatus.IDLE,
                    summaryBn = "টানেল কানেক্ট হলে অটো ইন্টারনেট চেক হবে"
                ),
                lastError = null,
                diagnosis = null,
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

            // Stage 1: Fast-Path Carrier DNS + Direct SSH probe
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
                HttpCustomTunnelStateHolder.appendLog("OK Direct SSH ${profile.host}:${profile.port} (${probe.sshRttMs} ms) [${probe.sshBanner}]")
            }

            if (!probe.dnsReachable && !probe.sshReachable) {
                val diag = TunnelAutoDoctor.diagnose(
                    profile = profile,
                    carrierName = opTag,
                    isGrameenphone = carrierSnap.isGrameenphone,
                    dnsReachable = false,
                    workingDnsServer = null,
                    workingDnsTransport = null,
                    dnsRttMs = null,
                    wsAttempted = false,
                    wsSucceeded = false,
                    wsFailures = emptyList(),
                    slowDnsAttempted = false,
                    slowDnsSucceeded = false,
                    slowDnsDiagnostics = emptyList(),
                    sshError = null
                )
                HttpCustomTunnelStateHolder.updateState { current ->
                    current.copy(
                        phase = TunnelConnectionPhase.ERROR,
                        statusDetail = diag.titleBn,
                        pipelineStages = TunnelPipelineStages(
                            carrierDns = PipelineStageState.FAILED,
                            failureHintBn = diag.explanationBn
                        ),
                        dnsRttMs = null,
                        lastError = diag.explanationBn,
                        diagnosis = diag,
                        connectedSinceMs = null
                    )
                }
                HttpCustomTunnelStateHolder.appendLog("Connection failed: ${diag.titleBn}")
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
            val dohClient = GpNetworkInspector.createProtectedDohClient(
                underlyingNetwork = underlyingNetwork,
                protectTcp = { tcp -> protect(tcp) },
                timeoutMs = 3800L
            )

            updatePipeline { it.copy(carrierDns = PipelineStageState.PASSED) }
            lastDnsRxAtMs.set(System.currentTimeMillis())

            // Stage 2..4: Establish real full TCP proxy via Parallel SSH WebSocket OR Direct SSH OR Parallel SlowDNS (dnstt)
            var activeProxyPort: Int? = null
            var activeEngineLabel = "Tunnel"
            var resolvedSshBanner: String? = probe.sshBanner

            var wsAttempted = false
            var wsSucceeded = false
            val wsFailures = mutableListOf<String>()
            var slowDnsAttempted = false
            var slowDnsSucceeded = false
            val slowDnsDiagnostics = mutableListOf<String>()
            var sshErrorDetail: String? = null

            if (profile.isWebSocketSsh) {
                wsAttempted = true
                val wsOutcome = attemptSshWebSocketPipeline(
                    profile = profile,
                    resolvedHostIp = probe.resolvedIps.firstOrNull(),
                    underlyingNetwork = underlyingNetwork,
                    failuresOut = wsFailures,
                    onSshError = { sshErrorDetail = it }
                )
                if (wsOutcome != null) {
                    wsSucceeded = true
                    activeProxyPort = wsOutcome.first
                    activeEngineLabel = "${wsOutcome.second} (127.0.0.1:${wsOutcome.first})"
                    resolvedSshBanner = sshBridge?.serverVersionBanner ?: wsOutcome.second
                }

                // GP Super Auto Waterfall: if all SSH-WS + CF Edge strategies failed, automatically try fast Parallel SlowDNS (UDP/DoH/DoT)!
                if (activeProxyPort == null && profile.autoFallbackAllModes &&
                    sshErrorDetail?.contains("Auth fail", ignoreCase = true) != true
                ) {
                    val slowDnsFallback = HttpCustomTunnelProfile.DEFAULT_PROFILES.firstOrNull {
                        !it.isWebSocketSsh && it.pubKey.trim().length == 64
                    }
                    if (slowDnsFallback != null) {
                        slowDnsAttempted = true
                        HttpCustomTunnelStateHolder.appendLog(
                            "GP Super Auto: WS blocked -> racing SlowDNS (Carrier UDP + DoH:443 + DoT:853)..."
                        )
                        activeProxyPort = attemptFullDnsttSshPipeline(
                            profile = slowDnsFallback,
                            tuning = tuning,
                            upstreamDnsIp = upstreamDnsIp,
                            carrierDnsList = carrierDnsList,
                            resolvedHostIp = null,
                            transport = transport,
                            activeDohUrl = activeDohUrl,
                            dohClient = dohClient,
                            underlyingNetwork = underlyingNetwork,
                            diagnosticsOut = slowDnsDiagnostics,
                            onSlowDnsReached = { slowDnsSucceeded = true },
                            onSshError = { sshErrorDetail = it }
                        )?.also { port ->
                            activeEngineLabel = "GP Auto SlowDNS+SSH (127.0.0.1:$port)"
                            resolvedSshBanner = sshBridge?.serverVersionBanner ?: "SlowDNS+SSH Active"
                        }
                    }
                }
            } else {
                if (probe.sshReachable && profile.username.isNotBlank()) {
                    HttpCustomTunnelStateHolder.updateState {
                        it.copy(statusDetail = "ধাপ ২/৫: Direct SSH অথেন্টিকেশন হচ্ছে...")
                    }
                    updatePipeline {
                        it.copy(
                            slowDnsServer = PipelineStageState.PASSED,
                            noiseSmux = PipelineStageState.PASSED,
                            sshAuth = PipelineStageState.RUNNING
                        )
                    }
                    runCatching {
                        val bridge = SshProxyBridge(
                            profile = profile,
                            customSocketFactory = null,
                            underlyingNetwork = underlyingNetwork,
                            protectTcp = { s -> protect(s) },
                            onTraffic = { tx, rx -> recordTrafficDelta(tx, rx) },
                            onLog = { msg -> HttpCustomTunnelStateHolder.appendLog(msg) }
                        )
                        val port = bridge.connectAndStartProxy(connectTimeoutMs = 15_000)
                        sshBridge = bridge
                        activeProxyPort = port
                        activeEngineLabel = "Direct SSH Proxy (127.0.0.1:$port)"
                        resolvedSshBanner = bridge.serverVersionBanner
                        updatePipeline { it.copy(sshAuth = PipelineStageState.PASSED) }
                    }.onFailure { err ->
                        val errMsg = err.localizedMessage ?: err::class.java.simpleName
                        sshErrorDetail = errMsg
                        HttpCustomTunnelStateHolder.appendLog("Direct SSH auth failed: $errMsg (trying SlowDNS dnstt...)")
                    }
                }

                if (activeProxyPort == null && profile.nameserver.isNotBlank() && profile.pubKey.trim().length == 64) {
                    slowDnsAttempted = true
                    activeProxyPort = attemptFullDnsttSshPipeline(
                        profile = profile,
                        tuning = tuning,
                        upstreamDnsIp = upstreamDnsIp,
                        carrierDnsList = carrierDnsList,
                        resolvedHostIp = probe.resolvedIps.firstOrNull(),
                        transport = transport,
                        activeDohUrl = activeDohUrl,
                        dohClient = dohClient,
                        underlyingNetwork = underlyingNetwork,
                        diagnosticsOut = slowDnsDiagnostics,
                        onSlowDnsReached = { slowDnsSucceeded = true },
                        onSshError = { sshErrorDetail = it }
                    )?.also { port ->
                        activeEngineLabel = "SlowDNS (dnstt) + SSH Proxy (127.0.0.1:$port)"
                        resolvedSshBanner = sshBridge?.serverVersionBanner ?: "SlowDNS+SSH Active"
                    }
                } else if (activeProxyPort == null) {
                    updatePipeline {
                        it.copy(
                            slowDnsServer = PipelineStageState.FAILED,
                            failureHintBn = "সঠিক ৬৪-অক্ষরের SlowDNS Public Key বা Nameserver দেওয়া নেই।"
                        )
                    }
                }
            }

            if (!isActive) {
                cleanupTunnelOnly()
                return@launch
            }

            // NEVER establish a dummy "DNS Only" VPN when no real proxy tunnel connected!
            // Instead, produce an exact plain-Bengali TunnelDiagnosis with 1-tap Auto-Fix and stop cleanly.
            if (activeProxyPort == null) {
                val diag = TunnelAutoDoctor.diagnose(
                    profile = profile,
                    carrierName = opTag,
                    isGrameenphone = carrierSnap.isGrameenphone,
                    dnsReachable = probe.dnsReachable,
                    workingDnsServer = upstreamDnsIp,
                    workingDnsTransport = transport,
                    dnsRttMs = probe.dnsRttMs,
                    wsAttempted = wsAttempted,
                    wsSucceeded = wsSucceeded,
                    wsFailures = wsFailures,
                    slowDnsAttempted = slowDnsAttempted,
                    slowDnsSucceeded = slowDnsSucceeded,
                    slowDnsDiagnostics = slowDnsDiagnostics,
                    sshError = sshErrorDetail
                )
                HttpCustomTunnelStateHolder.updateState { current ->
                    current.copy(
                        phase = TunnelConnectionPhase.ERROR,
                        statusDetail = diag.titleBn,
                        activeDnsServer = upstreamDnsIp,
                        activeDnsTransport = transport,
                        dnsRttMs = probe.dnsRttMs,
                        localProxyPort = null,
                        tunnelEngineLabel = null,
                        lastError = diag.explanationBn,
                        diagnosis = diag,
                        connectedSinceMs = null
                    )
                }
                HttpCustomTunnelStateHolder.appendLog("Auto-Doctor diagnosed: ${diag.category} -> ${diag.titleBn}")
                cleanupTunnelOnly()
                unregisterNetworkWatchdog()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            // Build Android VPN TUN interface + attach System HTTP Proxy (API 29+)
            val builder = Builder()
                .setSession(profile.name)
                .setMtu(tuning.mtuSize.coerceIn(1280, 1500))
                .addAddress(HttpCustomTunnelStateHolder.VIRTUAL_TUN_CLIENT_IP, 32)
                .addDnsServer(HttpCustomTunnelStateHolder.VIRTUAL_TUN_DNS_IP)
                .addRoute(HttpCustomTunnelStateHolder.VIRTUAL_TUN_DNS_IP, 32)

            runCatching {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", activeProxyPort!!))
                HttpCustomTunnelStateHolder.appendLog("VPN System HTTP/HTTPS Proxy bound -> 127.0.0.1:$activeProxyPort")
            }.onFailure { e ->
                HttpCustomTunnelStateHolder.appendLog("setHttpProxy warning: ${e.localizedMessage}")
            }

            if (underlyingNetwork != null) {
                runCatching { builder.setUnderlyingNetworks(arrayOf(underlyingNetwork)) }
            }

            val established = runCatching { builder.establish() }.getOrNull()
            if (established == null) {
                val permDiag = TunnelDiagnosis(
                    category = FailureCategory.VPN_PERMISSION_DENIED,
                    titleBn = "Android VPN অনুমতি দেওয়া হয়নি",
                    explanationBn = "VPN চালু করার জন্য সিস্টেম পপ-আপে 'OK / Allow' চাপুন এবং আবার কানেক্ট দিন।",
                    autoFixActionLabelBn = "আবার কানেক্ট করুন",
                    canAutoFix = false,
                    checks = listOf(
                        DiagnosticCheckRow("Android VPN অনুমতি", false, "অনুমতি পাওয়া যায়নি")
                    )
                )
                HttpCustomTunnelStateHolder.updateState { current ->
                    current.copy(
                        phase = TunnelConnectionPhase.ERROR,
                        statusDetail = permDiag.titleBn,
                        lastError = permDiag.explanationBn,
                        diagnosis = permDiag,
                        connectedSinceMs = null
                    )
                }
                HttpCustomTunnelStateHolder.appendLog("TUN establish returned null")
                cleanupTunnelOnly()
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
                activeProxyPort != null && profile.isWebSocketSsh -> "সংযুক্ত • SSH WebSocket প্রক্সি ($dnsLabel)"
                activeProxyPort != null && probe.sshReachable -> "সংযুক্ত • SSH প্রক্সি + $dnsLabel"
                activeProxyPort != null -> "সংযুক্ত • SlowDNS + SSH প্রক্সি ($dnsLabel)"
                activeDohUrl != null -> "সংযুক্ত • DoH:443 ($upstreamDnsIp)"
                else -> "সংযুক্ত (শুধু DNS) • $dnsLabel"
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
                    sshBanner = resolvedSshBanner ?: "SlowDNS $transport Active",
                    resolvedIp = probe.resolvedIps.firstOrNull(),
                    localProxyPort = activeProxyPort,
                    tunnelEngineLabel = activeEngineLabel,
                    internetCheck = InternetCheckResult(
                        status = InternetCheckStatus.CHECKING,
                        summaryBn = "ইন্টারনেট চলছে কিনা যাচাই হচ্ছে (HTTP 204)..."
                    ),
                    connectedSinceMs = current.connectedSinceMs ?: System.currentTimeMillis(),
                    lastError = null
                )
            }
            updatePipeline { it.copy(internetCheck = PipelineStageState.RUNNING) }
            HttpCustomTunnelStateHolder.appendLog(
                "VPN TUN active: ${HttpCustomTunnelStateHolder.VIRTUAL_TUN_CLIENT_IP}/32 -> $dnsLabel [$activeEngineLabel]"
            )
            updateNotification("সংযুক্ত: $dnsLabel — ইন্টারনেট চেক হচ্ছে...")

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

            // Stage 5: Mandatory real post-connect Internet Verification (HTTP 204 + Exit Public IP)
            launch(Dispatchers.IO) {
                val checkResult = InternetConnectivityVerifier.verifyInternet(
                    localProxyPort = activeProxyPort,
                    underlyingNetwork = underlyingNetwork,
                    protectTcp = { tcp -> protect(tcp) },
                    timeoutSec = 10L
                )
                val postDiag = if (checkResult.status != InternetCheckStatus.VERIFIED_ONLINE) {
                    TunnelAutoDoctor.diagnose(
                        profile = profile,
                        carrierName = opTag,
                        isGrameenphone = carrierSnap.isGrameenphone,
                        dnsReachable = true,
                        workingDnsServer = upstreamDnsIp,
                        workingDnsTransport = transport,
                        dnsRttMs = probe.dnsRttMs,
                        wsAttempted = wsAttempted,
                        wsSucceeded = wsSucceeded,
                        wsFailures = wsFailures,
                        slowDnsAttempted = slowDnsAttempted,
                        slowDnsSucceeded = slowDnsSucceeded,
                        slowDnsDiagnostics = slowDnsDiagnostics,
                        sshError = null,
                        internetCheck = checkResult
                    )
                } else {
                    null
                }
                HttpCustomTunnelStateHolder.updateState { current ->
                    current.copy(
                        internetCheck = checkResult,
                        diagnosis = postDiag,
                        pipelineStages = current.pipelineStages.copy(
                            internetCheck = if (checkResult.status == InternetCheckStatus.VERIFIED_ONLINE) {
                                PipelineStageState.PASSED
                            } else {
                                PipelineStageState.FAILED
                            }
                        )
                    )
                }
                HttpCustomTunnelStateHolder.appendLog(
                    "Internet Verification -> ${checkResult.summaryBn} | ${checkResult.detailText.orEmpty()}"
                )
                val notifLine = when (checkResult.status) {
                    InternetCheckStatus.VERIFIED_ONLINE ->
                        "ইন্টারনেট চালু (${checkResult.httpLatencyMs ?: 0}ms${checkResult.exitPublicIp?.let { " • $it" } ?: ""})"
                    else ->
                        "টানেল যুক্ত কিন্তু ইন্টারনেট রেসপন্স নেই"
                }
                updateNotification(notifLine)
            }
        }
    }

    /**
     * Runs the genuine 4-layer dnstt SlowDNS stack:
     * DnsttPacketTransport (Parallel UDP:53 / DoH:443 / DoT:853 / TCP:53) -> KcpSession -> NoiseNkChannel -> SmuxSession -> SshProxyBridge.
     */
    private fun attemptFullDnsttSshPipeline(
        profile: HttpCustomTunnelProfile,
        tuning: TunnelTuningConfig,
        upstreamDnsIp: String,
        carrierDnsList: List<String>,
        resolvedHostIp: String?,
        transport: String,
        activeDohUrl: String?,
        dohClient: OkHttpClient,
        underlyingNetwork: Network?,
        diagnosticsOut: MutableList<String> = mutableListOf(),
        onSlowDnsReached: () -> Unit = {},
        onSshError: (String) -> Unit = {}
    ): Int? {
        HttpCustomTunnelStateHolder.updateState {
            it.copy(statusDetail = "SlowDNS (UDP + DoH 443 + DoT 853) একসাথে যাচাই হচ্ছে...")
        }
        updatePipeline { it.copy(slowDnsServer = PipelineStageState.RUNNING) }

        val dnstt = DnsttPacketTransport(
            resolverIp = upstreamDnsIp,
            transportType = transport,
            tunnelDomain = profile.nameserver,
            ednsPayloadSize = tuning.ednsPayloadSize,
            dohUrl = activeDohUrl,
            dohClient = dohClient,
            underlyingNetwork = underlyingNetwork,
            protectUdp = { sock -> protect(sock) },
            protectTcp = { tcp -> protect(tcp) },
            onBytesTransferred = { tx, rx, rtt ->
                recordTrafficDelta(tx, rx)
                if (rx > 0) lastDnsRxAtMs.set(System.currentTimeMillis())
                if (rtt != null) HttpCustomTunnelStateHolder.recordPingSample(rtt)
            },
            onLog = { msg -> HttpCustomTunnelStateHolder.appendLog(msg) }
        )

        val probeCandidates = (carrierDnsList + listOf(upstreamDnsIp)).distinct()
        val dnsttProbe = dnstt.verifyDnsttServerReachable(
            timeoutMs = 3200,
            candidateResolverIps = probeCandidates,
            directServerIp = resolvedHostIp
        )
        diagnosticsOut.addAll(dnsttProbe.diagnostics)
        dnsttProbe.diagnostics.forEach { diag ->
            HttpCustomTunnelStateHolder.appendLog("SlowDNS probe: $diag")
        }
        if (!dnsttProbe.reachable) {
            val diagSummary = dnsttProbe.diagnostics.lastOrNull() ?: "No TXT reply"
            HttpCustomTunnelStateHolder.appendLog(
                "SlowDNS TXT poll failed on ${profile.nameserver} ($diagSummary)"
            )
            updatePipeline {
                it.copy(
                    slowDnsServer = PipelineStageState.FAILED,
                    noiseSmux = PipelineStageState.FAILED,
                    sshAuth = PipelineStageState.FAILED,
                    failureHintBn = "${profile.nameserver} সার্ভার থেকে SlowDNS TXT রেসপন্স আসেনি ($diagSummary)।"
                )
            }
            dnstt.close()
            return null
        }

        onSlowDnsReached()
        HttpCustomTunnelStateHolder.appendLog(
            "OK SlowDNS dnstt-server via ${dnsttProbe.workingResolverIp} (${dnsttProbe.workingTransport}, EDNS=${dnsttProbe.includeEdns0}, ${dnsttProbe.rttMs ?: 0} ms, KCP MTU=${dnstt.maxKcpMtu}B)"
        )
        updatePipeline {
            it.copy(
                slowDnsServer = PipelineStageState.PASSED,
                noiseSmux = PipelineStageState.RUNNING
            )
        }
        HttpCustomTunnelStateHolder.updateState {
            it.copy(statusDetail = "Noise_NK + KCP + SMUX এনক্রিপশন হচ্ছে...")
        }

        dnstt.start(workerCount = 3)
        dnsttTransport = dnstt

        val kcp = KcpSession(
            mtu = dnstt.maxKcpMtu,
            sendPacket = { pkt -> dnstt.enqueueSend(pkt) },
            pollPacket = { waitMs -> dnstt.pollReceived(waitMs) },
            windowSize = 256
        )
        kcp.start()
        kcpSession = kcp

        val noiseChannel = runCatching {
            NoiseNkChannel.connectClient(
                rawIn = kcp.asInputStream(readTimeoutMs = 15_000L),
                rawOut = kcp.asOutputStream(),
                serverPubKeyHex = profile.pubKey
            )
        }.getOrElse { err ->
            val msg = err.localizedMessage ?: err::class.java.simpleName
            HttpCustomTunnelStateHolder.appendLog("Noise_NK handshake failed: $msg")
            updatePipeline {
                it.copy(
                    noiseSmux = PipelineStageState.FAILED,
                    sshAuth = PipelineStageState.FAILED,
                    failureHintBn = "Noise_NK হ্যান্ডশেক ব্যর্থ ($msg) — SlowDNS Public Key বা সার্ভার চেক করুন।"
                )
            }
            cleanupDnsttStack()
            return null
        }

        val smux = SmuxSession(noiseChannel.inputStream, noiseChannel.outputStream)
        smux.start()
        val stream = runCatching { smux.openStream() }.getOrElse { err ->
            HttpCustomTunnelStateHolder.appendLog("SMUX stream open failed: ${err.localizedMessage}")
            updatePipeline {
                it.copy(
                    noiseSmux = PipelineStageState.FAILED,
                    sshAuth = PipelineStageState.FAILED,
                    failureHintBn = "SMUX স্ট্রিম ওপেন করা যায়নি (${err.localizedMessage ?: "error"})।"
                )
            }
            cleanupDnsttStack()
            return null
        }
        smuxSession = smux
        smuxStream = stream

        HttpCustomTunnelStateHolder.appendLog("OK Noise_NK + SMUX encrypted tunnel established (stream #${stream.streamId})")
        updatePipeline {
            it.copy(
                noiseSmux = PipelineStageState.PASSED,
                sshAuth = PipelineStageState.RUNNING
            )
        }
        HttpCustomTunnelStateHolder.updateState {
            it.copy(statusDetail = "SSH লগইন (${profile.username}) যাচাই হচ্ছে...")
        }

        return runCatching {
            val bridge = SshProxyBridge(
                profile = profile,
                customSocketFactory = { stream.asSocket() },
                underlyingNetwork = underlyingNetwork,
                protectTcp = { s -> protect(s) },
                onTraffic = { tx, rx -> recordTrafficDelta(tx, rx) },
                onLog = { msg -> HttpCustomTunnelStateHolder.appendLog(msg) }
            )
            val port = bridge.connectAndStartProxy(connectTimeoutMs = 22_000)
            sshBridge = bridge
            updatePipeline { it.copy(sshAuth = PipelineStageState.PASSED) }
            port
        }.getOrElse { err ->
            val msg = err.localizedMessage ?: err::class.java.simpleName
            onSshError(msg)
            HttpCustomTunnelStateHolder.appendLog("SSH login over SlowDNS failed: $msg")
            val hint = if (msg.contains("Auth fail", ignoreCase = true) || msg.contains("Auth cancel", ignoreCase = true)) {
                "SSH ইউজারনেম/পাসওয়ার্ড (${profile.username}) মেয়াদ শেষ বা ভুল!"
            } else {
                "SlowDNS এর ভিতরে SSH কানেক্ট ব্যর্থ ($msg)।"
            }
            updatePipeline {
                it.copy(
                    sshAuth = PipelineStageState.FAILED,
                    failureHintBn = hint
                )
            }
            cleanupDnsttStack()
            null
        }
    }

    /**
     * Fast 2-Wave Parallel Racing Engine for SSH over WebSocket / Cloudflare Anycast Edge / DPI Bypass.
     * Races up to 6 routes concurrently per wave so the first HTTP 101 winner connects in ~1-2s,
     * and full failure completes in ~7s instead of 60+ seconds!
     */
    private fun attemptSshWebSocketPipeline(
        profile: HttpCustomTunnelProfile,
        resolvedHostIp: String?,
        underlyingNetwork: Network?,
        failuresOut: MutableList<String> = mutableListOf(),
        onSshError: (String) -> Unit = {}
    ): Pair<Int, String>? {
        val candidates = SshWebSocketTransport.buildCandidateConfigs(
            host = profile.host,
            port = profile.port,
            sniHost = profile.sniHost,
            bugHost = profile.bugHost,
            wsPath = profile.wsPath,
            useTls = profile.useTls,
            resolvedHostIp = resolvedHostIp,
            customPayload = profile.customPayload,
            enableSplitPayload = profile.enableSplitPayload,
            enableTlsFragment = profile.enableTlsFragment
        )
        HttpCustomTunnelStateHolder.appendLog(
            "Parallel Racing ${candidates.size} SSH-WS / CF-Edge / DPI routes for ${profile.host}..."
        )
        updatePipeline {
            it.copy(
                slowDnsServer = PipelineStageState.RUNNING,
                noiseSmux = PipelineStageState.PENDING,
                sshAuth = PipelineStageState.PENDING
            )
        }

        val waves = candidates.chunked(6)
        for ((waveIdx, wave) in waves.withIndex()) {
            HttpCustomTunnelStateHolder.updateState {
                it.copy(
                    statusDetail = "রুট রেসিং ওয়েভ ${waveIdx + 1}/${waves.size}: ${wave.size}টি CF/WS রুট একসাথে চেক হচ্ছে..."
                )
            }

            data class RaceOutcome(
                val cfg: SshWebSocketConfig,
                val result: SshWebSocketConnectResult?,
                val error: String?
            )

            val pool = Executors.newFixedThreadPool(wave.size.coerceIn(1, 6))
            val completion = ExecutorCompletionService<RaceOutcome>(pool)
            val winningConnections = mutableListOf<Pair<SshWebSocketConfig, SshWebSocketConnectResult>>()
            try {
                wave.forEach { cfg ->
                    completion.submit {
                        val res = runCatching {
                            SshWebSocketTransport.connect(
                                config = cfg,
                                connectTimeoutMs = 3600,
                                underlyingNetwork = underlyingNetwork,
                                protectTcp = { s -> protect(s) }
                            )
                        }
                        if (res.isSuccess) {
                            RaceOutcome(cfg, res.getOrThrow(), null)
                        } else {
                            val err = res.exceptionOrNull()
                            val msg = err?.localizedMessage ?: err?.javaClass?.simpleName ?: "Timeout"
                            RaceOutcome(cfg, null, msg)
                        }
                    }
                }

                val deadlineMs = System.currentTimeMillis() + 4200L
                var received = 0
                while (received < wave.size) {
                    val remain = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(100L)
                    val fut = runCatching { completion.poll(remain, TimeUnit.MILLISECONDS) }.getOrNull() ?: break
                    received++
                    val outcome = runCatching { fut.get() }.getOrNull() ?: continue
                    if (outcome.result != null) {
                        winningConnections.add(outcome.cfg to outcome.result)
                        // Stop waiting immediately on first HTTP 101 winner!
                        break
                    } else {
                        val line = "${outcome.cfg.label} -> ${outcome.error ?: "Timeout"}"
                        failuresOut.add(line)
                        HttpCustomTunnelStateHolder.appendLog("WS probe failed: $line")
                    }
                }
            } finally {
                pool.shutdownNow()
            }

            // Try SSH login over the winning WebSocket connection
            for ((cfg, wsResult) in winningConnections) {
                HttpCustomTunnelStateHolder.appendLog(
                    "OK ${wsResult.summaryLabel} via ${cfg.label} (${wsResult.rttMs} ms)"
                )
                HttpCustomTunnelStateHolder.recordPingSample(wsResult.rttMs)
                updatePipeline {
                    it.copy(
                        slowDnsServer = PipelineStageState.PASSED,
                        noiseSmux = PipelineStageState.PASSED,
                        sshAuth = PipelineStageState.RUNNING
                    )
                }
                HttpCustomTunnelStateHolder.updateState {
                    it.copy(statusDetail = "SSH লগইন (${profile.username}) যাচাই হচ্ছে...")
                }

                val sshAttempt = runCatching {
                    val bridge = SshProxyBridge(
                        profile = profile,
                        customSocketFactory = { wsResult.socket },
                        underlyingNetwork = underlyingNetwork,
                        protectTcp = { s -> protect(s) },
                        onTraffic = { tx, rx -> recordTrafficDelta(tx, rx) },
                        onLog = { msg -> HttpCustomTunnelStateHolder.appendLog(msg) }
                    )
                    val port = bridge.connectAndStartProxy(connectTimeoutMs = 18_000)
                    sshBridge = bridge
                    updatePipeline { it.copy(sshAuth = PipelineStageState.PASSED) }
                    port to "SSH-WS ${wsResult.summaryLabel}"
                }

                if (sshAttempt.isSuccess) {
                    return sshAttempt.getOrNull()
                } else {
                    val err = sshAttempt.exceptionOrNull()
                    val msg = err?.localizedMessage ?: err?.javaClass?.simpleName ?: "SSH error"
                    onSshError(msg)
                    runCatching { wsResult.socket.close() }
                    HttpCustomTunnelStateHolder.appendLog("SSH Auth over ${cfg.label} failed: $msg")
                    if (msg.contains("Auth fail", ignoreCase = true) || msg.contains("Auth cancel", ignoreCase = true)) {
                        updatePipeline {
                            it.copy(
                                sshAuth = PipelineStageState.FAILED,
                                failureHintBn = "SSH ইউজারনেম/পাসওয়ার্ড (${profile.username}) ভুল বা মেয়াদ শেষ!"
                            )
                        }
                        return null
                    }
                    failuresOut.add("${cfg.label} SSH -> $msg")
                }
            }
        }

        val lastReason = failuresOut.lastOrNull() ?: "Connection timed out"
        updatePipeline {
            it.copy(
                slowDnsServer = PipelineStageState.FAILED,
                noiseSmux = PipelineStageState.FAILED,
                sshAuth = PipelineStageState.FAILED,
                failureHintBn = "SSH WebSocket (${profile.host}) সব পোর্ট (443/8443/80/8080/2052) ব্লক বা টাইমআউট ($lastReason)।"
            )
        }
        return null
    }

    private fun updatePipeline(transform: (TunnelPipelineStages) -> TunnelPipelineStages) {
        HttpCustomTunnelStateHolder.updateState { current ->
            current.copy(pipelineStages = transform(current.pipelineStages))
        }
    }

    private fun recordTrafficDelta(txDelta: Long, rxDelta: Long) {
        if (txDelta <= 0L && rxDelta <= 0L) return
        HttpCustomTunnelStateHolder.updateState { current ->
            current.copy(
                txBytes = current.txBytes + txDelta.coerceAtLeast(0L),
                rxBytes = current.rxBytes + rxDelta.coerceAtLeast(0L),
                dnsPacketsHandled = current.dnsPacketsHandled + if (rxDelta > 0L) 1 else 0
            )
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

                        lastDnsRxAtMs.set(System.currentTimeMillis())
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
        underlyingNetwork: Network?,
        timeoutMs: Int = 3500
    ): ByteArray? = runCatching {
        DatagramSocket().use { sock ->
            protect(sock)
            if (underlyingNetwork != null) {
                runCatching { underlyingNetwork.bindSocket(sock) }
            }
            sock.soTimeout = timeoutMs
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

    /**
     * Stabilized keepalive monitor that NEVER causes rapid connect/disconnect loops on GP 4G:
     * - When SSH proxy is active, monitors real SSH session liveness instead of tearing down on DNS jitter.
     * - In DNS mode, skips probe when real DNS traffic flowed within last 12s, retries with EDNS=512,
     *   and requires 5 consecutive failures before re-calibrating.
     */
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
            val intervalMs = (tuning.keepaliveIntervalSec.coerceIn(15, 60)) * 1000L
            var consecutiveMisses = 0

            while (isActive) {
                delay(intervalMs)
                if (!isActive) break

                // If full SlowDNS+SSH proxy is running and healthy, keep connection stable without DNS teardown
                val activeBridge = sshBridge
                if (activeBridge != null) {
                    if (activeBridge.isConnected()) {
                        consecutiveMisses = 0
                        continue
                    } else if (tuning.autoReconnectWatchdog) {
                        HttpCustomTunnelStateHolder.appendLog("Watchdog: SSH session dropped -> reconnecting...")
                        triggerWatchdogReconnect()
                        break
                    }
                }

                // Skip redundant probe if real DNS RX happened recently
                val msSinceRx = System.currentTimeMillis() - lastDnsRxAtMs.get()
                if (msSinceRx in 0L..12_000L) {
                    consecutiveMisses = 0
                    continue
                }

                val txId = (System.currentTimeMillis() and 0xFFFFL).toInt()
                val query = DnsPacketCodec.buildDnsQueryPacket(
                    domain = queryDomain,
                    queryType = DnsPacketCodec.TYPE_A,
                    transactionId = txId,
                    includeEdns0 = true,
                    ednsUdpPayloadSize = 512
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
                    lastDnsRxAtMs.set(System.currentTimeMillis())
                    val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    HttpCustomTunnelStateHolder.recordPingSample(rtt)
                } else {
                    consecutiveMisses++
                    if (consecutiveMisses >= 5 && tuning.autoReconnectWatchdog) {
                        HttpCustomTunnelStateHolder.appendLog(
                            "Watchdog: 5 consecutive GP DNS timeouts -> refreshing route..."
                        )
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
                val sinceLastConnect = System.currentTimeMillis() - lastConnectAttemptMs.get()
                if (lastBoundNetworkHandle != null &&
                    handle != lastBoundNetworkHandle &&
                    sinceLastConnect > 20_000L
                ) {
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
        val sinceLast = System.currentTimeMillis() - lastConnectAttemptMs.get()
        if (sinceLast < 20_000L) return
        watchdogReconnectJob?.cancel()
        watchdogReconnectJob = serviceScope.launch {
            delay(2000L)
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
                localProxyPort = null,
                tunnelEngineLabel = null,
                pipelineStages = TunnelPipelineStages(),
                internetCheck = InternetCheckResult(),
                txSpeedBps = 0L,
                rxSpeedBps = 0L,
                connectedSinceMs = null,
                lastError = null,
                diagnosis = null
            )
        }
        HttpCustomTunnelStateHolder.appendLog("Disconnected: $reason")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupDnsttStack() {
        runCatching { sshBridge?.close() }
        sshBridge = null
        runCatching { smuxStream?.close() }
        smuxStream = null
        runCatching { smuxSession?.close() }
        smuxSession = null
        runCatching { kcpSession?.close() }
        kcpSession = null
        runCatching { dnsttTransport?.close() }
        dnsttTransport = null
    }

    private fun cleanupTunnelOnly() {
        connectJob?.cancel()
        packetLoopJob?.cancel()
        keepaliveJob?.cancel()
        speedMeterJob?.cancel()
        cleanupDnsttStack()
        runCatching { tunInterface?.close() }
        tunInterface = null
    }

    private fun cleanupResources() {
        watchdogReconnectJob?.cancel()
        cleanupTunnelOnly()
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
            .setContentTitle("GP Pro Tunnel • SlowDNS + SSH")
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
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_NAMESERVER = "extra_nameserver"
        const val EXTRA_DNS_SERVER = "extra_dns_server"
        const val EXTRA_PUBKEY = "extra_pubkey"
        const val EXTRA_IS_WS_SSH = "extra_is_ws_ssh"
        const val EXTRA_USE_TLS = "extra_use_tls"
        const val EXTRA_SNI_HOST = "extra_sni_host"
        const val EXTRA_BUG_HOST = "extra_bug_host"
        const val EXTRA_WS_PATH = "extra_ws_path"
        const val EXTRA_CUSTOM_PAYLOAD = "extra_custom_payload"
        const val EXTRA_SPLIT_PAYLOAD = "extra_split_payload"
        const val EXTRA_TLS_FRAGMENT = "extra_tls_fragment"
        const val EXTRA_AUTO_FALLBACK = "extra_auto_fallback"

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
                putExtra(EXTRA_PASSWORD, profile.password)
                putExtra(EXTRA_NAMESERVER, profile.nameserver)
                putExtra(EXTRA_DNS_SERVER, profile.dnsServer)
                putExtra(EXTRA_PUBKEY, profile.pubKey)
                putExtra(EXTRA_IS_WS_SSH, profile.isWebSocketSsh)
                putExtra(EXTRA_USE_TLS, profile.useTls)
                putExtra(EXTRA_SNI_HOST, profile.sniHost)
                putExtra(EXTRA_BUG_HOST, profile.bugHost)
                putExtra(EXTRA_WS_PATH, profile.wsPath)
                putExtra(EXTRA_CUSTOM_PAYLOAD, profile.customPayload)
                putExtra(EXTRA_SPLIT_PAYLOAD, profile.enableSplitPayload)
                putExtra(EXTRA_TLS_FRAGMENT, profile.enableTlsFragment)
                putExtra(EXTRA_AUTO_FALLBACK, profile.autoFallbackAllModes)
            }
        }

        fun createDisconnectIntent(context: Context): Intent {
            return Intent(context, HttpCustomVpnService::class.java).apply {
                action = ACTION_DISCONNECT
            }
        }
    }
}
