package com.example.ui.screens

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.HttpCustomProfileParser
import com.example.data.HttpCustomTunnelProfile
import com.example.service.HttpCustomVpnService
import com.example.tunnel.GpNetworkInspector
import com.example.tunnel.HttpCustomTunnelStateHolder
import com.example.tunnel.TunnelConnectionPhase
import com.example.tunnel.TunnelTransportMode
import com.example.ui.components.CallDurationBadge
import com.example.ui.components.DnsBenchmarkCard
import com.example.ui.components.EditTunnelProfileDialog
import com.example.ui.components.GpBugHostScannerCard
import com.example.ui.components.GpCarrierStatusCard
import com.example.ui.components.GpProTuningCard
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.StudioCardContainer
import com.example.ui.components.TunnelLatencySparkline
import com.example.ui.components.TunnelPipelineAndInternetBanner
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.ConsoleDarkBg
import com.example.ui.theme.ConsoleDarkCard
import com.example.ui.theme.ConsoleTextMuted
import com.example.ui.theme.ConsoleTextPrimary
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SignalRed
import com.example.ui.theme.StudioPalette
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.WarmAmber
import kotlinx.coroutines.launch

private val DNS_PRESET_OPTIONS = listOf(
    "auto" to "GP Auto",
    "1.1.1.1" to "1.1.1.1",
    "8.8.8.8" to "8.8.8.8",
    "9.9.9.9" to "9.9.9.9",
    GpNetworkInspector.ADGUARD_DNS_IP to "AdGuard"
)

@Composable
fun HttpCustomInspectorScreen(
    onShowNotice: (String) -> Unit,
    standaloneMode: Boolean = false,
    onExitStandalone: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val palette = StudioTheme.colors
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    BackHandler(enabled = standaloneMode && onExitStandalone != null) {
        onExitStandalone?.invoke()
    }

    LaunchedEffect(Unit) {
        HttpCustomTunnelStateHolder.refreshCarrierSnapshot(context)
    }

    val profiles by HttpCustomTunnelStateHolder.profiles.collectAsStateWithLifecycle()
    val selectedProfileId by HttpCustomTunnelStateHolder.selectedProfileId.collectAsStateWithLifecycle()
    val tunnelState by HttpCustomTunnelStateHolder.state.collectAsStateWithLifecycle()
    val tuning by HttpCustomTunnelStateHolder.tuning.collectAsStateWithLifecycle()
    val carrierSnapshot by HttpCustomTunnelStateHolder.carrierSnapshot.collectAsStateWithLifecycle()
    val benchmarkResults by HttpCustomTunnelStateHolder.benchmarkResults.collectAsStateWithLifecycle()
    val isBenchmarkRunning by HttpCustomTunnelStateHolder.isBenchmarkRunning.collectAsStateWithLifecycle()
    val bugHostResults by HttpCustomTunnelStateHolder.bugHostResults.collectAsStateWithLifecycle()
    val isBugHostScanRunning by HttpCustomTunnelStateHolder.isBugHostScanRunning.collectAsStateWithLifecycle()

    val orderedProfiles = remember(profiles) {
        val superAuto = profiles.filter { it.id == HttpCustomTunnelProfile.GP_SUPER_AUTO_ID }
        val others = profiles.filterNot { it.id == HttpCustomTunnelProfile.GP_SUPER_AUTO_ID }
        superAuto + others
    }
    val activeProfile = profiles.firstOrNull { it.id == selectedProfileId } ?: orderedProfiles.first()

    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var importJsonText by rememberSaveable { mutableStateOf("") }
    var showEditDialog by rememberSaveable { mutableStateOf(false) }
    var isProbingOnly by remember { mutableStateOf(false) }
    var pendingProfileForVpn by remember { mutableStateOf<HttpCustomTunnelProfile?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val profile = pendingProfileForVpn ?: activeProfile
        pendingProfileForVpn = null
        if (result.resultCode == Activity.RESULT_OK) {
            ContextCompat.startForegroundService(
                context,
                HttpCustomVpnService.createConnectIntent(context, profile)
            )
            onShowNotice("✓ VPN অনুমতি পাওয়া গেছে, কানেক্ট হচ্ছে...")
        } else {
            HttpCustomTunnelStateHolder.appendLog("VPN permission denied by user")
            onShowNotice("VPN কানেক্ট করতে সিস্টেম অনুমতি প্রয়োজন।")
        }
    }

    fun startOrStopConnection(profile: HttpCustomTunnelProfile) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        when (tunnelState.phase) {
            TunnelConnectionPhase.CONNECTED, TunnelConnectionPhase.CONNECTING -> {
                context.startService(HttpCustomVpnService.createDisconnectIntent(context))
                onShowNotice("সংযোগ বিচ্ছিন্ন করা হচ্ছে...")
            }

            TunnelConnectionPhase.DISCONNECTED, TunnelConnectionPhase.ERROR -> {
                val prepareIntent = runCatching { VpnService.prepare(context) }.getOrNull()
                if (prepareIntent != null) {
                    pendingProfileForVpn = profile
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    ContextCompat.startForegroundService(
                        context,
                        HttpCustomVpnService.createConnectIntent(context, profile)
                    )
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("HTTP Custom JSON ইম্পোর্ট") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "আপনার ডিকোড করা .hc বা JSON কনফিগটি নিচে পেস্ট করুন:",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                    OutlinedTextField(
                        value = importJsonText,
                        onValueChange = { importJsonText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .testTag("hc_import_json_input"),
                        placeholder = { Text("{ \"config\": ..., \"decoded\": ... }") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = importJsonText.trim()
                        if (trimmed.isBlank()) {
                            onShowNotice("প্রথমে JSON পেস্ট করুন।")
                            return@Button
                        }
                        HttpCustomProfileParser.parseFromJson(trimmed).onSuccess { profile ->
                            HttpCustomTunnelStateHolder.addOrSelectProfile(profile)
                            importJsonText = ""
                            showImportDialog = false
                            onShowNotice("✓ প্রফাইল যুক্ত হয়েছে: ${profile.name}")
                        }.onFailure { err ->
                            onShowNotice("JSON পার্স ব্যর্থ: ${err.localizedMessage ?: "সঠিক ফরম্যাট দিন"}")
                        }
                    },
                    modifier = Modifier.testTag("hc_confirm_import_button")
                ) {
                    Text("ইম্পোর্ট করুন")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("বাতিল")
                }
            }
        )
    }

    if (showEditDialog) {
        EditTunnelProfileDialog(
            profile = activeProfile,
            onDismiss = { showEditDialog = false },
            onSave = { updated ->
                HttpCustomTunnelStateHolder.updateProfile(updated)
                showEditDialog = false
                onShowNotice("✓ প্রফাইল আপডেট হয়েছে: ${updated.host}:${updated.port}")
            }
        )
    }

    val isConnected = tunnelState.phase == TunnelConnectionPhase.CONNECTED
    val isConnecting = tunnelState.phase == TunnelConnectionPhase.CONNECTING
    val isError = tunnelState.phase == TunnelConnectionPhase.ERROR

    val statusAccent by animateColorAsState(
        targetValue = when (tunnelState.phase) {
            TunnelConnectionPhase.CONNECTED -> EmeraldGreen
            TunnelConnectionPhase.CONNECTING -> WarmAmber
            TunnelConnectionPhase.ERROR -> SignalRed
            TunnelConnectionPhase.DISCONNECTED -> palette.inkMuted
        },
        label = "hc_status_accent"
    )
    val buttonColor by animateColorAsState(
        targetValue = when (tunnelState.phase) {
            TunnelConnectionPhase.CONNECTED -> SignalRed
            TunnelConnectionPhase.CONNECTING -> WarmAmber
            TunnelConnectionPhase.ERROR, TunnelConnectionPhase.DISCONNECTED -> EmeraldGreen
        },
        label = "hc_connect_btn_color"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .then(
                if (standaloneMode) {
                    Modifier
                        .statusBarsPadding()
                        .navigationBarsPadding()
                } else {
                    Modifier
                }
            ),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = if (standaloneMode) 24.dp else 104.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (standaloneMode && onExitStandalone != null) {
                    IconButton(
                        onClick = onExitStandalone,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("hc_standalone_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "ফিরে যান",
                            tint = palette.inkPrimary
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "GP Pro Tunnel",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "GP, Robi ও Airtel • SlowDNS + SSH WebSocket (443/80)",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                }
                OutlinedButton(
                    onClick = { showImportDialog = true },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("hc_import_json_button"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.outline)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddCircleOutline,
                        contentDescription = "Import JSON",
                        modifier = Modifier.size(16.dp),
                        tint = palette.cobaltAccent
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import", color = palette.inkPrimary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Live Grameenphone SIM & Carrier Telemetry Card
        item {
            GpCarrierStatusCard(
                snapshot = carrierSnapshot,
                onRefresh = {
                    val snap = HttpCustomTunnelStateHolder.refreshCarrierSnapshot(context)
                    onShowNotice("নেটওয়ার্ক রিফ্রেশ: ${snap.operatorName} (${snap.carrierDnsServers.firstOrNull() ?: "Auto"})")
                }
            )
        }

        // Front-and-Center Profile / Engine Switcher (GP Super Auto first!)
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(orderedProfiles, key = { it.id }) { profile ->
                    val isSel = profile.id == activeProfile.id
                    OutlinedButton(
                        onClick = {
                            HttpCustomTunnelStateHolder.selectProfile(profile.id)
                            onShowNotice("প্রফাইল নির্বাচিত: ${profile.name}")
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("hc_profile_tab_${profile.id}"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (isSel) palette.cobaltSoft else palette.card,
                            contentColor = if (isSel) palette.cobaltAccent else palette.inkPrimary
                        ),
                        border = BorderStroke(1.dp, if (isSel) palette.cobaltAccent else palette.outline)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SimCard,
                            contentDescription = "Profile",
                            modifier = Modifier.size(16.dp),
                            tint = if (isSel) palette.cobaltAccent else palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (profile.id) {
                                HttpCustomTunnelProfile.GP_SUPER_AUTO_ID -> "GP Super Auto"
                                "hc_sshocean_nl1_ws_tls" -> "sshOcean WS 443"
                                "hc_sshocean_nl1_gp_sni" -> "GP SNI + CF Edge"
                                "hc_gp_robi_airtel_1" -> "Robi/GP SlowDNS"
                                "hc_cloudflare_slowdns_2" -> "SlowDNS 1.1.1.1"
                                else -> profile.name.take(18)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Hero Connect / Disconnect Card with Live Speed & Ping Sparkline
        item {
            StudioCardContainer(
                borderColor = if (isConnected) EmeraldGreen.copy(alpha = 0.55f)
                else if (isError) SignalRed.copy(alpha = 0.45f)
                else palette.outline
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(statusAccent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = WarmAmber
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.VpnLock,
                                    contentDescription = "Tunnel Status",
                                    tint = statusAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = tunnelState.statusDetail,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = palette.inkPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = tunnelState.activeDnsServer?.let { dns ->
                                    "$dns • ${tunnelState.activeDnsTransport ?: "UDP:53"} • MTU ${tuning.mtuSize}"
                                } ?: "${activeProfile.host}:${activeProfile.port} • ${tuning.transportMode.shortLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    if (isConnected) {
                        CallDurationBadge(
                            startedAtMs = tunnelState.connectedSinceMs,
                            accentColor = EmeraldGreen,
                            containerColor = EmeraldGreen.copy(alpha = 0.14f)
                        )
                    } else {
                        StatusPillBadge(
                            label = when (tunnelState.phase) {
                                TunnelConnectionPhase.CONNECTED -> "CONNECTED"
                                TunnelConnectionPhase.CONNECTING -> "CONNECTING"
                                TunnelConnectionPhase.ERROR -> "FAILED"
                                TunnelConnectionPhase.DISCONNECTED -> "READY"
                            },
                            accentColor = statusAccent,
                            containerColor = statusAccent.copy(alpha = 0.12f),
                            leadingDot = true
                        )
                    }
                }

                AnimatedVisibility(visible = !tunnelState.lastError.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = SignalRed.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, SignalRed.copy(alpha = 0.28f))
                    ) {
                        Text(
                            text = tunnelState.lastError.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = SignalRed,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quick DNS Mode Selector Chips
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(DNS_PRESET_OPTIONS, key = { it.first }) { (value, label) ->
                        val selected = activeProfile.dnsServer.equals(value, ignoreCase = true)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                HttpCustomTunnelStateHolder.updateProfileDns(activeProfile.id, value)
                                onShowNotice("DNS মোড নির্বাচিত: $label")
                            },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.cobaltSoft,
                                selectedLabelColor = palette.cobaltAccent
                            ),
                            modifier = Modifier.testTag("hc_dns_chip_$value")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Primary Connect / Disconnect Button
                Button(
                    onClick = { startOrStopConnection(activeProfile) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("hc_connect_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = if (isConnected) "Disconnect" else "Connect",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (tunnelState.phase) {
                            TunnelConnectionPhase.CONNECTED -> "ডিসকানেক্ট করুন (Disconnect)"
                            TunnelConnectionPhase.CONNECTING -> "বাতিল করুন (Connecting...)"
                            TunnelConnectionPhase.ERROR -> "পুনরায় কানেক্ট করুন (Retry Connect)"
                            TunnelConnectionPhase.DISCONNECTED -> "GP টানেল কানেক্ট করুন (Connect)"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 5-Stage Live Tunnel Pipeline + Real Internet Verification Banner
                if (isConnected || isConnecting || isError || tunnelState.internetCheck.checkedAtMs != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    TunnelPipelineAndInternetBanner(
                        stages = tunnelState.pipelineStages,
                        internetCheck = tunnelState.internetCheck,
                        localProxyPort = tunnelState.localProxyPort,
                        isTunnelActive = isConnected,
                        isWebSocketMode = activeProfile.isWebSocketSsh,
                        carrierLabel = if (carrierSnapshot.isGrameenphone) "GP DNS" else "Carrier DNS",
                        onCheckInternetNow = {
                            coroutineScope.launch {
                                val res = HttpCustomTunnelStateHolder.runManualInternetCheck(context)
                                onShowNotice(res.summaryBn)
                            }
                        }
                    )
                }

                // Live Telemetry Stats & Sparkline
                if (isConnected || tunnelState.dnsRttMs != null || tunnelState.txBytes > 0L) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryStatChip(
                            label = "Ping",
                            value = tunnelState.dnsRttMs?.let { "$it ms" } ?: "--",
                            accent = EmeraldGreen,
                            palette = palette,
                            modifier = Modifier.weight(0.9f)
                        )
                        TelemetryStatChip(
                            label = "Live Speed",
                            value = "↓${HttpCustomTunnelStateHolder.formatSpeed(tunnelState.rxSpeedBps)}",
                            accent = CobaltBlue,
                            palette = palette,
                            modifier = Modifier.weight(1.25f)
                        )
                        TelemetryStatChip(
                            label = "Data (${tunnelState.dnsPacketsHandled})",
                            value = HttpCustomTunnelStateHolder.formatBytes(tunnelState.txBytes + tunnelState.rxBytes),
                            accent = WarmAmber,
                            palette = palette,
                            modifier = Modifier.weight(1.1f)
                        )
                    }
                    if (tunnelState.pingHistoryMs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        TunnelLatencySparkline(
                            samples = tunnelState.pingHistoryMs,
                            accentColor = EmeraldGreen
                        )
                    }
                }
            }
        }

        // GP Bug Host & Cloudflare 101 Edge Scanner (1-Tap Live GP Finder)
        item {
            GpBugHostScannerCard(
                results = bugHostResults,
                isRunning = isBugHostScanRunning,
                onRunScan = {
                    coroutineScope.launch {
                        val res = HttpCustomTunnelStateHolder.runGpBugHostScan(context, activeProfile)
                        val count101 = res.count { it.wsUpgrade101 }
                        val countOk = res.count { it.reachable }
                        onShowNotice(
                            if (count101 > 0) "✓ $count101 টি HTTP 101 চালু GP/CF হোস্ট পাওয়া গেছে!"
                            else if (countOk > 0) "$countOk টি হোস্ট রেসপন্স দিয়েছে — Use চাপুন"
                            else "কোনো Bug Host রেসপন্স দেয়নি (সিম ডাটা/সিগন্যাল চেক করুন)"
                        )
                    }
                },
                onApplyEntry = { entry ->
                    HttpCustomTunnelStateHolder.applyBugHostScanResult(activeProfile.id, entry)
                    onShowNotice("✓ সেট হয়েছে: ${entry.label} (${entry.connectTarget}:${entry.port})")
                }
            )
        }

        // GP DNS Speed Benchmark Scanner
        item {
            DnsBenchmarkCard(
                results = benchmarkResults,
                isRunning = isBenchmarkRunning,
                onRunBenchmark = {
                    coroutineScope.launch {
                        HttpCustomTunnelStateHolder.runDnsBenchmark(context, activeProfile)
                    }
                },
                onApplyResolver = { entry ->
                    HttpCustomTunnelStateHolder.updateProfileDns(activeProfile.id, entry.serverIp)
                    if (entry.transport == "DoH:443") {
                        HttpCustomTunnelStateHolder.updateTuning {
                            it.copy(transportMode = TunnelTransportMode.DOH_HTTPS)
                        }
                    } else if (entry.isCarrier) {
                        HttpCustomTunnelStateHolder.updateTuning {
                            it.copy(transportMode = TunnelTransportMode.AUTO_SMART)
                        }
                    }
                    onShowNotice("✓ নির্বাচিত: ${entry.label} (${entry.serverIp} • ${entry.rttMs}ms)")
                }
            )
        }

        // GP Pro Engine & Advanced Tuning Card
        item {
            GpProTuningCard(
                tuning = tuning,
                onUpdateTuning = { HttpCustomTunnelStateHolder.updateTuning(it) }
            )
        }

        // Active Profile Details Card
        item {
            StudioCardContainer {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeProfile.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(
                        onClick = { showEditDialog = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("hc_edit_profile_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Edit Profile",
                            tint = palette.cobaltAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                StatusPillBadge(
                    label = activeProfile.operatorName,
                    accentColor = WarmAmber,
                    containerColor = WarmAmber.copy(alpha = 0.15f),
                    leadingDot = false
                )
                Spacer(modifier = Modifier.height(10.dp))

                TunnelDetailRow(Icons.Filled.Router, "Server Host & Port", "${activeProfile.host}:${activeProfile.port}", palette)
                TunnelDetailRow(Icons.Filled.Person, "SSH Username", activeProfile.username, palette)
                TunnelDetailRow(Icons.Filled.Key, "SSH Password", activeProfile.password, palette)
                if (activeProfile.isWebSocketSsh) {
                    TunnelDetailRow(
                        Icons.Filled.Security,
                        "Protocol",
                        activeProfile.protocol,
                        palette
                    )
                    TunnelDetailRow(
                        Icons.Filled.NetworkCheck,
                        "SNI / Wildcard",
                        activeProfile.sniHost.ifBlank { activeProfile.host },
                        palette
                    )
                    if (activeProfile.bugHost.isNotBlank()) {
                        TunnelDetailRow(
                            Icons.Filled.Dns,
                            "CF Edge / Bug IP",
                            activeProfile.bugHost,
                            palette
                        )
                    }
                    TunnelDetailRow(
                        Icons.Filled.Dns,
                        "DPI Bypass",
                        buildList {
                            if (activeProfile.enableTlsFragment) add("TLS Frag")
                            if (activeProfile.enableSplitPayload) add("HTTP Split")
                            if (activeProfile.autoFallbackAllModes) add("DoH/DoT SlowDNS Fallback")
                        }.joinToString(" + ").ifBlank { "Standard WS" },
                        palette
                    )
                } else {
                    TunnelDetailRow(Icons.Filled.NetworkCheck, "SlowDNS Nameserver", activeProfile.nameserver, palette)
                    TunnelDetailRow(
                        Icons.Filled.Dns,
                        "DNS Resolver",
                        if (activeProfile.dnsServer.equals("auto", ignoreCase = true)) "GP Auto (Carrier + DoH)"
                        else "${activeProfile.dnsServer}:53",
                        palette
                    )
                    TunnelDetailRow(
                        Icons.Filled.Security,
                        "Public Key (PubKey)",
                        activeProfile.pubKey.take(28).ifBlank { "None" } +
                            if (activeProfile.pubKey.length > 28) "..." else "",
                        palette
                    )
                }
                TunnelDetailRow(Icons.Filled.Code, "Banner / Note", activeProfile.bannerNote, palette)

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(activeProfile.rawJson))
                            onShowNotice("✓ HTTP Custom প্রফাইল JSON ক্লিপবোর্ডে কপি হয়েছে!")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("hc_copy_json_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.outline)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy JSON",
                            tint = palette.cobaltAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy JSON", color = palette.inkPrimary, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        onClick = {
                            if (isProbingOnly) return@OutlinedButton
                            isProbingOnly = true
                            HttpCustomTunnelStateHolder.appendLog("Running GP Smart Probe for ${activeProfile.nameserver}...")
                            coroutineScope.launch {
                                val result = HttpCustomTunnelStateHolder.probeNetworkReachability(
                                    profile = activeProfile,
                                    context = context,
                                    tuningConfig = tuning
                                )
                                isProbingOnly = false
                                val summary = buildString {
                                    if (result.dnsReachable) {
                                        val tag = if (result.workingDnsIsCarrier) "GP DNS" else (result.workingDnsTransport ?: "DNS")
                                        append("$tag ${result.workingDnsServer} OK (${result.dnsRttMs}ms)")
                                    } else {
                                        append("DNS/DoH Offline")
                                    }
                                    append(" • ")
                                    if (result.sshReachable) append("SSH OK (${result.sshRttMs}ms)")
                                    else append("SSH Filtered")
                                }
                                result.dnsRttMs?.let { HttpCustomTunnelStateHolder.recordPingSample(it) }
                                HttpCustomTunnelStateHolder.updateState {
                                    it.copy(
                                        activeDnsServer = result.workingDnsServer ?: it.activeDnsServer,
                                        activeDnsTransport = result.workingDnsTransport ?: it.activeDnsTransport,
                                        sshBanner = result.sshBanner ?: it.sshBanner
                                    )
                                }
                                HttpCustomTunnelStateHolder.appendLog("Probe result: $summary")
                                onShowNotice(summary)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("hc_test_ping_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.outline)
                    ) {
                        if (isProbingOnly) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Speed,
                                contentDescription = "Test Ping",
                                tint = palette.emeraldAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test Ping", color = palette.inkPrimary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Live Tunnel Console Logs Card
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = ConsoleDarkBg,
                border = BorderStroke(1.dp, ConsoleDarkCard)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Terminal,
                                contentDescription = "Tunnel Logs",
                                tint = EmeraldGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GP Tunnel Live Console",
                                style = MaterialTheme.typography.titleSmall,
                                color = ConsoleTextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(
                            onClick = { HttpCustomTunnelStateHolder.clearLogs() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("hc_clear_logs_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteOutline,
                                contentDescription = "Clear Logs",
                                tint = ConsoleTextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (tunnelState.logs.isEmpty()) {
                        Text(
                            text = "GP টানেল কানেক্ট বা Scan DNS চাপলে এখানে রিয়েল-টাইম GP DNS/DoH/SSH লগ দেখাবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = ConsoleTextMuted,
                            fontFamily = JetBrainsMonoFontFamily
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            tunnelState.logs.takeLast(14).forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (line.contains("OK") || line.contains("active") || line.contains("fastest")) EmeraldGreen
                                    else if (line.contains("failed") || line.contains("denied") || line.contains("Timeout")) SignalRed
                                    else ConsoleTextPrimary,
                                    fontFamily = JetBrainsMonoFontFamily
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryStatChip(
    label: String,
    value: String,
    accent: Color,
    palette: StudioPalette,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = palette.surfaceSubtle,
        border = BorderStroke(1.dp, palette.outline)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkSecondary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TunnelDetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    palette: StudioPalette
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(0.45f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = palette.cobaltAccent,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.inkPrimary,
            modifier = Modifier.weight(0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
