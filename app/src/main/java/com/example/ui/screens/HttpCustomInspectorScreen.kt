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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
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
import com.example.ui.components.GpProTuningCard
import com.example.ui.components.SmartDiagnosisAutoFixCard
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.StudioCardContainer
import com.example.ui.components.TunnelLatencySparkline
import com.example.ui.components.TunnelPipelineAndInternetBanner
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.ConsoleDarkBg
import com.example.ui.theme.ConsoleDarkCard
import com.example.ui.theme.ConsoleTextMuted
import com.example.ui.theme.ConsoleTextPrimary
import com.example.ui.theme.CyanTeal
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
    var showAdvancedTools by rememberSaveable { mutableStateOf(false) }
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

    fun launchConnectNow(profile: HttpCustomTunnelProfile) {
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

    fun startOrStopConnection(profile: HttpCustomTunnelProfile) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        when (tunnelState.phase) {
            TunnelConnectionPhase.CONNECTED, TunnelConnectionPhase.CONNECTING -> {
                context.startService(HttpCustomVpnService.createDisconnectIntent(context))
                onShowNotice("সংযোগ বিচ্ছিন্ন করা হচ্ছে...")
            }

            TunnelConnectionPhase.DISCONNECTED, TunnelConnectionPhase.ERROR -> {
                launchConnectNow(profile)
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("নতুন কনফিগ (JSON) ইম্পোর্ট") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "আপনার ডিকোড করা .hc বা SSH WebSocket JSON কনফিগটি নিচে পেস্ট করুন:",
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
                        placeholder = { Text("{ \"host\": \"...\", \"username\": \"...\" }") },
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
                            onShowNotice("✓ নতুন কনফিগ যুক্ত হয়েছে — কানেক্ট হচ্ছে...")
                            launchConnectNow(profile)
                        }.onFailure { err ->
                            onShowNotice("JSON পার্স ব্যর্থ: ${err.localizedMessage ?: "সঠিক ফরম্যাট দিন"}")
                        }
                    },
                    modifier = Modifier.testTag("hc_confirm_import_button")
                ) {
                    Text("ইম্পোর্ট ও কানেক্ট")
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
            TunnelConnectionPhase.DISCONNECTED -> palette.cobaltAccent
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
        // 1. Clean Compact Header + SIM Telemetry Pill
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            text = "১-ক্লিক অটো কানেক্ট ও স্মার্ট সমাধান",
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

                // Slim SIM Bar (zero visual clutter!)
                val simAccent = if (carrierSnapshot.isGrameenphone) CyanTeal else palette.cobaltAccent
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = palette.card,
                    border = BorderStroke(1.dp, simAccent.copy(alpha = 0.28f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CellTower,
                                contentDescription = "SIM Network",
                                tint = simAccent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = buildString {
                                    append(if (carrierSnapshot.isGrameenphone) "Grameenphone" else carrierSnapshot.operatorName)
                                    append(" • ")
                                    append(carrierSnapshot.transportLabel.substringBefore(" /"))
                                    carrierSnapshot.carrierDnsServers.firstOrNull()?.let { append(" • $it") }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = palette.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = {
                                val snap = HttpCustomTunnelStateHolder.refreshCarrierSnapshot(context)
                                onShowNotice("নেটওয়ার্ক রিফ্রেশ: ${snap.operatorName}")
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("gp_refresh_carrier_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh SIM",
                                tint = simAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Centerpiece 1-Tap Hero Connect Card (Clean, Bold, Focused)
        item {
            StudioCardContainer(
                borderColor = if (isConnected) EmeraldGreen.copy(alpha = 0.55f)
                else if (isError) SignalRed.copy(alpha = 0.40f)
                else palette.outline,
                contentPadding = 18.dp
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
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(statusAccent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isConnecting || tunnelState.isAutoFixing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.6.dp,
                                    color = statusAccent
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.VpnLock,
                                    contentDescription = "Tunnel Status",
                                    tint = statusAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = when {
                                    tunnelState.isAutoFixing -> "অটো সমাধান চলছে..."
                                    isConnected -> "সংযুক্ত (Connected)"
                                    isConnecting -> "কানেক্ট হচ্ছে..."
                                    isError -> "কানেক্ট হয়নি"
                                    else -> "কানেক্ট করতে প্রস্তুত"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = palette.inkPrimary
                            )
                            Text(
                                text = when {
                                    tunnelState.isAutoFixing -> tunnelState.autoFixStatusText ?: "চালু রুট খোঁজা হচ্ছে..."
                                    isConnecting -> tunnelState.statusDetail
                                    isConnected -> tunnelState.tunnelEngineLabel ?: activeProfile.name
                                    else -> if (activeProfile.id == HttpCustomTunnelProfile.GP_SUPER_AUTO_ID) {
                                        "GP Super Auto • সব রুট অটো চেক হবে"
                                    } else {
                                        "${activeProfile.name} (${activeProfile.host})"
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                                maxLines = 2,
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
                            label = when {
                                tunnelState.isAutoFixing -> "FIXING"
                                isConnecting -> "RACING"
                                isError -> "FAILED"
                                else -> "AUTO"
                            },
                            accentColor = statusAccent,
                            containerColor = statusAccent.copy(alpha = 0.12f),
                            leadingDot = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Big 1-Tap Connect / Disconnect Button
                Button(
                    onClick = { startOrStopConnection(activeProfile) },
                    enabled = !tunnelState.isAutoFixing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .testTag("hc_connect_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = if (isConnected) "Disconnect" else "Connect",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (tunnelState.phase) {
                            TunnelConnectionPhase.CONNECTED -> "ডিসকানেক্ট করুন (Disconnect)"
                            TunnelConnectionPhase.CONNECTING -> "বাতিল করুন (Cancel)"
                            TunnelConnectionPhase.ERROR -> "আবার কানেক্ট করুন (Retry)"
                            TunnelConnectionPhase.DISCONNECTED -> "কানেক্ট করুন (Auto Connect)"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Only show pipeline & internet check banner while actively Connecting or Connected
                if (isConnected || isConnecting) {
                    Spacer(modifier = Modifier.height(14.dp))
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

                // Live Telemetry only when Connected
                if (isConnected) {
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
                            label = "Speed",
                            value = "↓${HttpCustomTunnelStateHolder.formatSpeed(tunnelState.rxSpeedBps)}",
                            accent = CobaltBlue,
                            palette = palette,
                            modifier = Modifier.weight(1.2f)
                        )
                        TelemetryStatChip(
                            label = "Data",
                            value = HttpCustomTunnelStateHolder.formatBytes(tunnelState.txBytes + tunnelState.rxBytes),
                            accent = WarmAmber,
                            palette = palette,
                            modifier = Modifier.weight(1.0f)
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

        // 3. Automatic "Why It Failed + 1-Tap Auto-Fix" Card (Appears automatically on any error or blocked internet!)
        val activeDiagnosis = tunnelState.diagnosis
        if (activeDiagnosis != null && !isConnecting) {
            item {
                SmartDiagnosisAutoFixCard(
                    diagnosis = activeDiagnosis,
                    isAutoFixing = tunnelState.isAutoFixing,
                    autoFixStatusText = tunnelState.autoFixStatusText,
                    onAutoFixClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        coroutineScope.launch {
                            val plan = HttpCustomTunnelStateHolder.runSmartAutoFix(context, activeProfile)
                            onShowNotice(plan.summaryBn)
                            if (plan.shouldReconnect) {
                                val latestProfiles = HttpCustomTunnelStateHolder.profiles.value
                                val latestSelectedId = HttpCustomTunnelStateHolder.selectedProfileId.value
                                val targetProfile = plan.updatedProfile
                                    ?: latestProfiles.firstOrNull { it.id == latestSelectedId }
                                    ?: activeProfile
                                launchConnectNow(targetProfile)
                            }
                        }
                    },
                    onImportConfigClick = { showImportDialog = true },
                    onEditProfileClick = { showEditDialog = true }
                )
            }
        }

        // 4. Single Clean Collapsible "Advanced Tools" Accordion (Collapsed by default for zero clutter!)
        item {
            StudioCardContainer(contentPadding = 14.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvancedTools = !showAdvancedTools }
                        .testTag("hc_advanced_tools_toggle"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(palette.surfaceSubtle),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = "Advanced Tools",
                                tint = palette.inkSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "অ্যাডভান্সড টুলস (Advanced)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.inkPrimary
                            )
                            Text(
                                text = if (showAdvancedTools) "লুকাতে ট্যাপ করুন" else "প্রফাইল, স্ক্যানার, DNS, টিউনিং ও কনসোল",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary
                            )
                        }
                    }
                    Icon(
                        imageVector = if (showAdvancedTools) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (showAdvancedTools) "Collapse" else "Expand",
                        tint = palette.inkSecondary
                    )
                }
            }
        }

        if (showAdvancedTools) {
            // Profile Switcher Tabs
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

            // Quick DNS Mode Chips
            item {
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
            }

            // GP Bug Host & Cloudflare 101 Edge Scanner
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
                                else "কোনো Bug Host রেসপন্স দেয়নি"
                            )
                        }
                    },
                    onApplyEntry = { entry ->
                        HttpCustomTunnelStateHolder.applyBugHostScanResult(activeProfile.id, entry)
                        onShowNotice("✓ সেট হয়েছে: ${entry.label}")
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
                        onShowNotice("✓ নির্বাচিত: ${entry.label} (${entry.rttMs}ms)")
                    }
                )
            }

            // GP Pro Engine & Tuning
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
                        TunnelDetailRow(Icons.Filled.Security, "Protocol", activeProfile.protocol, palette)
                        TunnelDetailRow(Icons.Filled.NetworkCheck, "SNI / Wildcard", activeProfile.sniHost.ifBlank { activeProfile.host }, palette)
                        if (activeProfile.bugHost.isNotBlank()) {
                            TunnelDetailRow(Icons.Filled.Dns, "CF Edge / Bug IP", activeProfile.bugHost, palette)
                        }
                    } else {
                        TunnelDetailRow(Icons.Filled.NetworkCheck, "SlowDNS Nameserver", activeProfile.nameserver, palette)
                        TunnelDetailRow(
                            Icons.Filled.Dns,
                            "DNS Resolver",
                            if (activeProfile.dnsServer.equals("auto", ignoreCase = true)) "GP Auto (Carrier + DoH)"
                            else "${activeProfile.dnsServer}:53",
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
                                onShowNotice("✓ JSON ক্লিপবোর্ডে কপি হয়েছে!")
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
                                coroutineScope.launch {
                                    val result = HttpCustomTunnelStateHolder.probeNetworkReachability(
                                        profile = activeProfile,
                                        context = context,
                                        tuningConfig = tuning
                                    )
                                    isProbingOnly = false
                                    val summary = if (result.dnsReachable) {
                                        "DNS ${result.workingDnsServer} OK (${result.dnsRttMs}ms)"
                                    } else {
                                        "DNS/DoH Offline"
                                    }
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
                                text = "কানেক্ট চাপলে এখানে লাইভ টানেল লগ দেখাবে।",
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
