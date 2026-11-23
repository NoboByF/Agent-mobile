package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import com.example.data.HttpCustomTunnelProfile
import com.example.tunnel.DnsBenchmarkEntry
import com.example.tunnel.GpBugHostScanEntry
import com.example.tunnel.GpCarrierSnapshot
import com.example.tunnel.InternetCheckResult
import com.example.tunnel.InternetCheckStatus
import com.example.tunnel.PipelineStageState
import com.example.tunnel.SshWebSocketTransport
import com.example.tunnel.TunnelPipelineStages
import com.example.tunnel.TunnelTransportMode
import com.example.tunnel.TunnelTuningConfig
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.CyanTeal
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SignalRed
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.WarmAmber

@Composable
fun GpCarrierStatusCard(
    snapshot: GpCarrierSnapshot,
    onRefresh: () -> Unit
) {
    val palette = StudioTheme.colors
    val accent = if (snapshot.isGrameenphone) CyanTeal else palette.cobaltAccent
    val dnsSummary = snapshot.carrierDnsServers.take(2).joinToString(", ")
        .ifBlank { "Auto Detecting..." }

    StudioCardContainer(
        borderColor = accent.copy(alpha = 0.35f),
        contentPadding = 14.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.CellTower,
                        contentDescription = "Carrier Signal",
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (snapshot.isGrameenphone) "Grameenphone" else snapshot.operatorName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (snapshot.isGrameenphone) {
                            StatusPillBadge(
                                label = "GP PRO",
                                accentColor = CyanTeal,
                                containerColor = CyanTeal.copy(alpha = 0.14f),
                                leadingDot = true
                            )
                        }
                    }
                    Text(
                        text = "${snapshot.transportLabel} • ${snapshot.localIpv4 ?: "--"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.inkSecondary,
                        fontFamily = JetBrainsMonoFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("gp_refresh_carrier_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh SIM Network",
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = palette.surfaceSubtle,
            border = BorderStroke(1.dp, palette.outline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Detected Carrier DNS",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkSecondary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = dnsSummary,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun TunnelLatencySparkline(
    samples: List<Long>,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val palette = StudioTheme.colors
    if (samples.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = palette.surfaceSubtle,
        border = BorderStroke(1.dp, palette.outline)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Ping Graph",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkSecondary
                )
                Text(
                    text = "Min ${samples.minOrNull() ?: 0}ms • Avg ${samples.average().toInt()}ms",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                val padded = if (samples.size == 1) listOf(samples.first(), samples.first()) else samples
                val maxVal = (padded.maxOrNull() ?: 100L).coerceAtLeast(40L).toFloat()
                val minVal = ((padded.minOrNull() ?: 0L).toFloat() * 0.6f).coerceAtLeast(0f)
                val range = (maxVal - minVal).coerceAtLeast(10f)
                val stepX = w / (padded.size - 1).coerceAtLeast(1)

                val linePath = Path()
                val fillPath = Path()
                var lastPoint = Offset.Zero

                padded.forEachIndexed { idx, value ->
                    val x = idx * stepX
                    val norm = ((value.toFloat() - minVal) / range).coerceIn(0f, 1f)
                    val y = h - (norm * (h - 8.dp.toPx())) - 4.dp.toPx()
                    if (idx == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, h)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                    lastPoint = Offset(x, y)
                }
                fillPath.lineTo(lastPoint.x, h)
                fillPath.close()

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.28f),
                            accentColor.copy(alpha = 0.02f)
                        )
                    )
                )
                drawPath(
                    path = linePath,
                    color = accentColor,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
                drawCircle(
                    color = accentColor,
                    radius = 4.dp.toPx(),
                    center = lastPoint
                )
            }
        }
    }
}

@Composable
fun GpProTuningCard(
    tuning: TunnelTuningConfig,
    onUpdateTuning: ((TunnelTuningConfig) -> TunnelTuningConfig) -> Unit
) {
    val palette = StudioTheme.colors
    var expanded by rememberSaveable { mutableStateOf(true) }

    StudioCardContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .testTag("gp_tuning_toggle_header"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(palette.cobaltSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "GP Pro Tuning",
                        tint = palette.cobaltAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "GP Pro Engine & Tuning",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "${tuning.transportMode.shortLabel} • MTU ${tuning.mtuSize} • EDNS ${tuning.ednsPayloadSize}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = palette.inkSecondary
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Transport Engine Mode
                Text(
                    text = "ট্রান্সপোর্ট প্রোটোকল (Transport Mode)",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.inkSecondary,
                    fontWeight = FontWeight.SemiBold
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TunnelTransportMode.entries, key = { it.name }) { mode ->
                        val isSel = tuning.transportMode == mode
                        FilterChip(
                            selected = isSel,
                            onClick = { onUpdateTuning { it.copy(transportMode = mode) } },
                            label = { Text(mode.shortLabel, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.cobaltSoft,
                                selectedLabelColor = palette.cobaltAccent
                            ),
                            modifier = Modifier.testTag("gp_transport_${mode.name}")
                        )
                    }
                }

                // MTU & EDNS0 Selectors
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "GP 4G MTU Size",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1280, 1400, 1500).forEach { mtu ->
                                val isSel = tuning.mtuSize == mtu
                                FilterChip(
                                    selected = isSel,
                                    onClick = { onUpdateTuning { it.copy(mtuSize = mtu) } },
                                    label = { Text("$mtu", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("gp_mtu_$mtu")
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "EDNS0 SlowDNS Size",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(512, 1232, 1400).forEach { edns ->
                                val isSel = tuning.ednsPayloadSize == edns
                                FilterChip(
                                    selected = isSel,
                                    onClick = { onUpdateTuning { it.copy(ednsPayloadSize = edns) } },
                                    label = { Text("${edns}B", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("gp_edns_$edns")
                                )
                            }
                        }
                    }
                }

                // Switches: Auto-Reconnect Watchdog & Ad-Block DNS
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = palette.surfaceSubtle,
                    border = BorderStroke(1.dp, palette.outline)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Bolt,
                                    contentDescription = "Auto-Reconnect",
                                    tint = WarmAmber,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Auto-Reconnect Watchdog",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.inkPrimary
                                    )
                                    Text(
                                        text = "GP টাওয়ার/নেটওয়ার্ক বদলালে অটো রিকানেক্ট",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.inkSecondary
                                    )
                                }
                            }
                            Switch(
                                checked = tuning.autoReconnectWatchdog,
                                onCheckedChange = { enabled ->
                                    onUpdateTuning { it.copy(autoReconnectWatchdog = enabled) }
                                },
                                modifier = Modifier.testTag("gp_watchdog_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Shield,
                                    contentDescription = "AdBlock DNS",
                                    tint = EmeraldGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Ad-Block DNS (AdGuard)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.inkPrimary
                                    )
                                    Text(
                                        text = "বিজ্ঞাপন ও ট্র্যাকার ব্লক করে দ্রুত ব্রাউজিং",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.inkSecondary
                                    )
                                }
                            }
                            Switch(
                                checked = tuning.adBlockDnsEnabled,
                                onCheckedChange = { enabled ->
                                    onUpdateTuning { it.copy(adBlockDnsEnabled = enabled) }
                                },
                                modifier = Modifier.testTag("gp_adblock_switch")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DnsBenchmarkCard(
    results: List<DnsBenchmarkEntry>,
    isRunning: Boolean,
    onRunBenchmark: () -> Unit,
    onApplyResolver: (DnsBenchmarkEntry) -> Unit
) {
    val palette = StudioTheme.colors

    StudioCardContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GP DNS Speed Scanner",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary
                )
                Text(
                    text = "সবচেয়ে দ্রুত রেসপন্স করা GP/DoH DNS খুঁজুন",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onRunBenchmark,
                enabled = !isRunning,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("gp_run_benchmark_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CobaltBlue)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scanning...", color = Color.White, style = MaterialTheme.typography.labelMedium)
                } else {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = "Scan DNS",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scan DNS", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (results.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                results.forEach { entry ->
                    val rttColor = when {
                        !entry.reachable -> SignalRed
                        (entry.rttMs ?: 999L) <= 90L -> EmeraldGreen
                        (entry.rttMs ?: 999L) <= 220L -> WarmAmber
                        else -> CobaltBlue
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = palette.surfaceSubtle,
                        border = BorderStroke(
                            1.dp,
                            if (entry.reachable) rttColor.copy(alpha = 0.30f) else palette.outline
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${entry.serverIp} • ${entry.transport} • ${entry.detail}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = palette.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (entry.reachable) {
                                OutlinedButton(
                                    onClick = { onApplyResolver(entry) },
                                    modifier = Modifier
                                        .height(40.dp)
                                        .testTag("gp_use_dns_${entry.serverIp}_${entry.transport}"),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, rttColor.copy(alpha = 0.55f))
                                ) {
                                    Text(
                                        text = "${entry.rttMs}ms • Use",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = rttColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                StatusPillBadge(
                                    label = "BLOCKED",
                                    accentColor = SignalRed,
                                    containerColor = SignalRed.copy(alpha = 0.12f),
                                    leadingDot = false
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
fun GpBugHostScannerCard(
    results: List<GpBugHostScanEntry>,
    isRunning: Boolean,
    onRunScan: () -> Unit,
    onApplyEntry: (GpBugHostScanEntry) -> Unit
) {
    val palette = StudioTheme.colors

    StudioCardContainer(borderColor = CyanTeal.copy(alpha = 0.35f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GP Bug Host & CF 101 Scanner",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = palette.inkPrimary
                )
                Text(
                    text = "১৬টি GP পোর্টাল ও Cloudflare Edge IP স্ক্যান করে চালু HTTP 101 খুঁজুন",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onRunScan,
                enabled = !isRunning,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("gp_run_bughost_scan_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CyanTeal)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Scanning...", color = Color.White, style = MaterialTheme.typography.labelMedium)
                } else {
                    Icon(
                        imageVector = Icons.Filled.Bolt,
                        contentDescription = "Scan GP Bug Hosts",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Scan GP", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        if (results.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                results.take(8).forEach { entry ->
                    val accent = when {
                        entry.wsUpgrade101 -> EmeraldGreen
                        entry.reachable -> WarmAmber
                        else -> SignalRed
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = palette.surfaceSubtle,
                        border = BorderStroke(
                            1.dp,
                            if (entry.wsUpgrade101) EmeraldGreen.copy(alpha = 0.45f)
                            else if (entry.reachable) WarmAmber.copy(alpha = 0.30f)
                            else palette.outline
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${entry.connectTarget}:${entry.port} • ${entry.detail}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = if (entry.wsUpgrade101) EmeraldGreen else palette.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (entry.reachable) {
                                OutlinedButton(
                                    onClick = { onApplyEntry(entry) },
                                    modifier = Modifier
                                        .height(40.dp)
                                        .testTag("gp_use_bughost_${entry.connectTarget}_${entry.port}"),
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, accent.copy(alpha = 0.6f))
                                ) {
                                    Text(
                                        text = if (entry.wsUpgrade101) "101 • Use" else "${entry.rttMs}ms • Use",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                StatusPillBadge(
                                    label = "BLOCKED",
                                    accentColor = SignalRed,
                                    containerColor = SignalRed.copy(alpha = 0.12f),
                                    leadingDot = false
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
fun EditTunnelProfileDialog(
    profile: HttpCustomTunnelProfile,
    onDismiss: () -> Unit,
    onSave: (HttpCustomTunnelProfile) -> Unit
) {
    var isWebSocketSsh by remember(profile.id) { mutableStateOf(profile.isWebSocketSsh) }
    var useTls by remember(profile.id) { mutableStateOf(profile.useTls) }
    var enableSplitPayload by remember(profile.id) { mutableStateOf(profile.enableSplitPayload) }
    var enableTlsFragment by remember(profile.id) { mutableStateOf(profile.enableTlsFragment) }
    var host by remember(profile.id) { mutableStateOf(profile.host) }
    var port by remember(profile.id) { mutableStateOf(profile.port.toString()) }
    var sniHost by remember(profile.id) { mutableStateOf(profile.sniHost) }
    var bugHost by remember(profile.id) { mutableStateOf(profile.bugHost) }
    var wsPath by remember(profile.id) { mutableStateOf(profile.wsPath) }
    var customPayload by remember(profile.id) { mutableStateOf(profile.customPayload) }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !isWebSocketSsh,
                        onClick = { isWebSocketSsh = false },
                        label = { Text("SlowDNS + SSH") }
                    )
                    FilterChip(
                        selected = isWebSocketSsh,
                        onClick = { isWebSocketSsh = true },
                        label = { Text("SSH WebSocket") }
                    )
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Server Host (e.g. nl1.wssht.to)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                    label = { Text(if (isWebSocketSsh) "Port (443 / 80 / 8080)" else "Port (22)") },
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

                if (isWebSocketSsh) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "SSL/TLS Encryption (Port 443)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Switch(checked = useTls, onCheckedChange = { useTls = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DPI Split Payload ([split])",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Switch(checked = enableSplitPayload, onCheckedChange = { enableSplitPayload = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TLS ClientHello DPI Fragment",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Switch(checked = enableTlsFragment, onCheckedChange = { enableTlsFragment = it })
                    }
                    OutlinedTextField(
                        value = sniHost,
                        onValueChange = { sniHost = it },
                        label = { Text("SNI / Bug Host (e.g. www.grameenphone.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bugHost,
                        onValueChange = { bugHost = it },
                        label = { Text("CF Edge / Bug IP (e.g. 104.16.132.229)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = wsPath,
                        onValueChange = { wsPath = it },
                        label = { Text("WebSocket Path (/)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = customPayload == SshWebSocketTransport.PRESET_SSHOCEAN_STANDARD,
                                onClick = { customPayload = SshWebSocketTransport.PRESET_SSHOCEAN_STANDARD },
                                label = { Text("sshOcean WS", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = customPayload == SshWebSocketTransport.PRESET_GP_SPLIT_DPI,
                                onClick = { customPayload = SshWebSocketTransport.PRESET_GP_SPLIT_DPI },
                                label = { Text("GP Split DPI", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        item {
                            FilterChip(
                                selected = customPayload == SshWebSocketTransport.PRESET_BACK_INJECT,
                                onClick = { customPayload = SshWebSocketTransport.PRESET_BACK_INJECT },
                                label = { Text("Back-Inject", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = customPayload,
                        onValueChange = { customPayload = it },
                        label = { Text("Custom Payload ([crlf][split][host][sni])") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily)
                    )
                } else {
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
                        value = pubKey,
                        onValueChange = { pubKey = it },
                        label = { Text("SlowDNS Public Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanHost = host.trim().ifBlank { profile.host }
                    val cleanPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: if (isWebSocketSsh) (if (useTls) 443 else 80) else 22
                    onSave(
                        profile.copy(
                            host = cleanHost,
                            port = cleanPort,
                            protocol = if (isWebSocketSsh) {
                                if (useTls) "SSH WebSocket (SSL/TLS)" else "SSH WebSocket (HTTP)"
                            } else {
                                "SlowDns + SSH"
                            },
                            nameserver = nameserver.trim().ifBlank { cleanHost },
                            dnsServer = dnsServer.trim().ifBlank { "auto" },
                            username = username.trim(),
                            password = password.trim(),
                            pubKey = pubKey.trim(),
                            isWebSocketSsh = isWebSocketSsh,
                            useTls = useTls,
                            sniHost = sniHost.trim(),
                            bugHost = bugHost.trim(),
                            wsPath = wsPath.trim().ifBlank { "/" },
                            customPayload = customPayload.trim(),
                            enableSplitPayload = enableSplitPayload,
                            enableTlsFragment = enableTlsFragment
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TunnelPipelineAndInternetBanner(
    stages: TunnelPipelineStages,
    internetCheck: InternetCheckResult,
    localProxyPort: Int?,
    isTunnelActive: Boolean,
    isWebSocketMode: Boolean = false,
    carrierLabel: String = "Carrier DNS",
    onCheckInternetNow: () -> Unit
) {
    val palette = StudioTheme.colors
    val isChecking = internetCheck.status == InternetCheckStatus.CHECKING
    val bannerAccent = when (internetCheck.status) {
        InternetCheckStatus.VERIFIED_ONLINE -> EmeraldGreen
        InternetCheckStatus.CHECKING -> CobaltBlue
        InternetCheckStatus.DNS_ONLY_NO_INTERNET -> WarmAmber
        InternetCheckStatus.OFFLINE -> SignalRed
        InternetCheckStatus.IDLE -> palette.inkSecondary
    }
    val bannerIcon = when (internetCheck.status) {
        InternetCheckStatus.VERIFIED_ONLINE -> Icons.Filled.Public
        InternetCheckStatus.CHECKING -> Icons.Filled.Schedule
        InternetCheckStatus.DNS_ONLY_NO_INTERNET -> Icons.Filled.WarningAmber
        InternetCheckStatus.OFFLINE -> Icons.Filled.Cancel
        InternetCheckStatus.IDLE -> Icons.Filled.Public
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 5-Stage Live Pipeline Strip (wrapped cleanly so all 5 stages stay visible)
        val stageItems = if (isWebSocketMode) {
            listOf(
                carrierLabel to stages.carrierDns,
                "WS / TLS" to stages.slowDnsServer,
                "Upgrade 101" to stages.noiseSmux,
                "SSH Auth" to stages.sshAuth,
                "Internet" to stages.internetCheck
            )
        } else {
            listOf(
                carrierLabel to stages.carrierDns,
                "SlowDNS" to stages.slowDnsServer,
                "Noise+SMUX" to stages.noiseSmux,
                "SSH Auth" to stages.sshAuth,
                "Internet" to stages.internetCheck
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            stageItems.forEach { (label, state) ->
                val stageColor = when (state) {
                    PipelineStageState.PASSED -> EmeraldGreen
                    PipelineStageState.RUNNING -> CobaltBlue
                    PipelineStageState.FAILED -> SignalRed
                    PipelineStageState.PENDING -> palette.inkMuted
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = stageColor.copy(alpha = 0.11f),
                    border = BorderStroke(1.dp, stageColor.copy(alpha = 0.30f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        when (state) {
                            PipelineStageState.RUNNING -> CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.8.dp,
                                color = stageColor
                            )

                            PipelineStageState.PASSED -> Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "$label OK",
                                tint = stageColor,
                                modifier = Modifier.size(13.dp)
                            )

                            PipelineStageState.FAILED -> Icon(
                                imageVector = Icons.Filled.Cancel,
                                contentDescription = "$label Failed",
                                tint = stageColor,
                                modifier = Modifier.size(13.dp)
                            )

                            PipelineStageState.PENDING -> Icon(
                                imageVector = Icons.Filled.Schedule,
                                contentDescription = "$label Pending",
                                tint = stageColor,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = stageColor
                        )
                    }
                }
            }
        }

        // Real Internet Connectivity Verification Card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = bannerAccent.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, bannerAccent.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = bannerAccent
                            )
                        } else {
                            Icon(
                                imageVector = bannerIcon,
                                contentDescription = "Internet Status",
                                tint = bannerAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = internetCheck.summaryBn,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = palette.inkPrimary
                            )
                            val subtitle = internetCheck.detailText
                                ?: localProxyPort?.let { "Local HTTP/SOCKS5 Proxy: 127.0.0.1:$it" }
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = palette.inkSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (isTunnelActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onCheckInternetNow,
                            enabled = !isChecking,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("hc_check_internet_button"),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, bannerAccent.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = if (isChecking) "চেক হচ্ছে" else "চেক করুন",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = bannerAccent
                            )
                        }
                    }
                }

                if (!stages.failureHintBn.isNullOrBlank()) {
                    Text(
                        text = "কারণ: ${stages.failureHintBn}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SignalRed,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (localProxyPort != null) {
                    Text(
                        text = "Proxy Active: 127.0.0.1:$localProxyPort (HTTP CONNECT + SOCKS5)",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = EmeraldGreen,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
