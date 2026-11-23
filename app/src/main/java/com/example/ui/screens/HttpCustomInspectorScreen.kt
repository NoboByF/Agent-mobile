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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.tunnel.HttpCustomTunnelStateHolder
import com.example.tunnel.TunnelConnectionPhase
import com.example.ui.components.CallDurationBadge
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.StudioCardContainer
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
    "auto" to "Auto (Carrier)",
    "1.1.1.1" to "1.1.1.1",
    "8.8.8.8" to "8.8.8.8",
    "9.9.9.9" to "9.9.9.9"
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

    val profiles by HttpCustomTunnelStateHolder.profiles.collectAsStateWithLifecycle()
    val selectedProfileId by HttpCustomTunnelStateHolder.selectedProfileId.collectAsStateWithLifecycle()
    val tunnelState by HttpCustomTunnelStateHolder.state.collectAsStateWithLifecycle()

    val activeProfile = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.first()

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
                        val parsed = HttpCustomProfileParser.parseFromJson(trimmed)
                        parsed.onSuccess { profile ->
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
                        text = "HTTP Custom Tunnel",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "গ্রামীণফোন, রবি ও এয়ারটেল SlowDNS + SSH কানেক্টর",
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

        // Hero Connect / Disconnect Card
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
                                    "DNS: $dns • ${tunnelState.activeDnsTransport ?: "UDP:53"}"
                                } ?: "${activeProfile.host}:${activeProfile.port} • DNS: ${activeProfile.dnsServer}",
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

                // Quick DNS Mode Selector Chips (Auto Carrier DNS solves 8.8.8.8 timeout on GP/Robi/Airtel)
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
                            TunnelConnectionPhase.DISCONNECTED -> "কানেক্ট করুন (Connect)"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Live Telemetry Stats Row
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
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryStatChip(
                            label = "TX / RX",
                            value = "${HttpCustomTunnelStateHolder.formatBytes(tunnelState.txBytes)} / ${
                                HttpCustomTunnelStateHolder.formatBytes(tunnelState.rxBytes)
                            }",
                            accent = CobaltBlue,
                            palette = palette,
                            modifier = Modifier.weight(1.3f)
                        )
                        TelemetryStatChip(
                            label = "DNS Pkts",
                            value = "${tunnelState.dnsPacketsHandled}",
                            accent = WarmAmber,
                            palette = palette,
                            modifier = Modifier.weight(0.9f)
                        )
                    }
                }
            }
        }

        // Profile Selector Tabs
        item {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    val isSel = profile.id == activeProfile.id
                    OutlinedButton(
                        onClick = { HttpCustomTunnelStateHolder.selectProfile(profile.id) },
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
                            contentDescription = "Operator",
                            modifier = Modifier.size(16.dp),
                            tint = if (isSel) palette.cobaltAccent else palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (profile.id) {
                                "hc_gp_robi_airtel_1" -> "GP/Robi/Airtel (Auto)"
                                "hc_cloudflare_slowdns_2" -> "Cloudflare 1.1.1.1"
                                else -> profile.name.take(18)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }
                }
            }
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

                TunnelDetailRow(
                    icon = Icons.Filled.Router,
                    label = "Server Host & Port",
                    value = "${activeProfile.host}:${activeProfile.port}",
                    palette = palette
                )
                TunnelDetailRow(
                    icon = Icons.Filled.Person,
                    label = "SSH Username",
                    value = activeProfile.username,
                    palette = palette
                )
                TunnelDetailRow(
                    icon = Icons.Filled.Key,
                    label = "SSH Password",
                    value = activeProfile.password,
                    palette = palette
                )
                TunnelDetailRow(
                    icon = Icons.Filled.NetworkCheck,
                    label = "SlowDNS Nameserver",
                    value = activeProfile.nameserver,
                    palette = palette
                )
                TunnelDetailRow(
                    icon = Icons.Filled.Dns,
                    label = "DNS Resolver",
                    value = if (activeProfile.dnsServer.equals("auto", ignoreCase = true)) {
                        "Auto (Carrier + Public)"
                    } else {
                        "${activeProfile.dnsServer}:53"
                    },
                    palette = palette
                )
                TunnelDetailRow(
                    icon = Icons.Filled.Security,
                    label = "Public Key (PubKey)",
                    value = activeProfile.pubKey.take(28).ifBlank { "None" } +
                        if (activeProfile.pubKey.length > 28) "..." else "",
                    palette = palette
                )
                TunnelDetailRow(
                    icon = Icons.Filled.Code,
                    label = "Banner / Note",
                    value = activeProfile.bannerNote,
                    palette = palette
                )

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
                            HttpCustomTunnelStateHolder.appendLog(
                                "Probing Carrier & Public DNS for ${activeProfile.nameserver}..."
                            )
                            coroutineScope.launch {
                                val result = HttpCustomTunnelStateHolder.probeNetworkReachability(
                                    profile = activeProfile,
                                    context = context
                                )
                                isProbingOnly = false
                                val summary = buildString {
                                    if (result.dnsReachable) {
                                        val tag = if (result.workingDnsIsCarrier) "Carrier" else "DNS"
                                        append("$tag ${result.workingDnsServer} OK (${result.dnsRttMs}ms)")
                                    } else {
                                        append("DNS Blocked/Offline")
                                    }
                                    append(" • ")
                                    if (result.sshReachable) append("SSH OK (${result.sshRttMs}ms)")
                                    else append("SSH Filtered")
                                }
                                HttpCustomTunnelStateHolder.updateState {
                                    it.copy(
                                        dnsRttMs = result.dnsRttMs ?: result.sshRttMs,
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
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
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
                                text = "Tunnel Connection Logs",
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
                            text = "কানেক্ট বাটনে ট্যাপ করলে এখানে ক্যারিয়ার DNS অটো-ডিটেকশন ও রিয়েল-টাইম লগ দেখাবে।",
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
                                    color = if (line.contains("OK") || line.contains("active")) EmeraldGreen
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
private fun EditTunnelProfileDialog(
    profile: HttpCustomTunnelProfile,
    onDismiss: () -> Unit,
    onSave: (HttpCustomTunnelProfile) -> Unit
) {
    var host by remember(profile.id) { mutableStateOf(profile.host) }
    var port by remember(profile.id) { mutableStateOf(profile.port.toString()) }
    var nameserver by remember(profile.id) { mutableStateOf(profile.nameserver) }
    var dnsServer by remember(profile.id) { mutableStateOf(profile.dnsServer) }
    var username by remember(profile.id) { mutableStateOf(profile.username) }
    var password by remember(profile.id) { mutableStateOf(profile.password) }
    var pubKey by remember(profile.id) { mutableStateOf(profile.pubKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("প্রফাইল এডিট করুন") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Server Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Port (22)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = nameserver,
                    onValueChange = { nameserver = it },
                    label = { Text("SlowDNS Nameserver (NS)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dnsServer,
                    onValueChange = { dnsServer = it },
                    label = { Text("DNS Resolver (auto / IP)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("SSH Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("SSH Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pubKey,
                    onValueChange = { pubKey = it },
                    label = { Text("SlowDNS Public Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanHost = host.trim().ifBlank { profile.host }
                    val cleanPort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: profile.port
                    onSave(
                        profile.copy(
                            host = cleanHost,
                            port = cleanPort,
                            nameserver = nameserver.trim().ifBlank { cleanHost },
                            dnsServer = dnsServer.trim().ifBlank { "auto" },
                            username = username.trim(),
                            password = password.trim(),
                            pubKey = pubKey.trim()
                        )
                    )
                },
                modifier = Modifier.testTag("hc_save_profile_button")
            ) {
                Text("সেভ করুন")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("বাতিল")
            }
        }
    )
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
