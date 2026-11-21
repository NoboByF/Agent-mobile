package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AgentActivity
import com.example.data.ConnectionTestState
import com.example.data.LiveCaptionLine
import com.example.data.LiveConnectionPhase
import com.example.data.LiveSessionState
import com.example.data.PermissionSnapshot
import com.example.data.PilotRoutine
import com.example.data.PilotSettings
import com.example.data.RecentToolEvent
import com.example.data.RoutineAccent
import com.example.ui.components.AuroraLiveOrb
import com.example.ui.components.CallDurationBadge
import com.example.ui.components.CreateRoutineDialog
import com.example.ui.components.LiveAudioWaveform
import com.example.ui.components.SmartRoutinesSection
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.StudioCardContainer
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.CyanBright
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.RoyalViolet
import com.example.ui.theme.SignalRed
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.WarmAmber

@Composable
fun HomeScreen(
    settings: PilotSettings,
    sessionState: LiveSessionState,
    permissions: PermissionSnapshot,
    connectionTest: ConnectionTestState,
    recentCaptions: List<LiveCaptionLine>,
    recentToolEvents: List<RecentToolEvent>,
    routines: List<PilotRoutine>,
    onStartOrStopCall: () -> Unit,
    onTestConnection: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleAutoVision: () -> Unit,
    onCaptureVision: () -> Unit,
    onEmergencyStop: () -> Unit,
    onSendSilentCommand: (String) -> Unit,
    onLaunchRoutine: (PilotRoutine) -> Unit,
    onAddRoutine: (String, String, String, RoutineAccent) -> Unit,
    onDeleteRoutine: (String) -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    onRequestMicPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onGoToSettings: () -> Unit
) {
    val palette = StudioTheme.colors
    val haptics = LocalHapticFeedback.current

    var silentCommandText by rememberSaveable { mutableStateOf("") }
    var showAddRoutineDialog by rememberSaveable { mutableStateOf(false) }
    var permissionsExpanded by rememberSaveable(permissions.allGranted) {
        mutableStateOf(!permissions.allGranted)
    }

    val combinedEnergy = maxOf(sessionState.micLevel, sessionState.aiAudioLevel)
    val (orbPrimary, orbSecondary) = when (sessionState.activity) {
        AgentActivity.LISTENING -> CobaltBlue to CyanBright
        AgentActivity.SPEAKING -> EmeraldGreen to EmeraldBright
        AgentActivity.EXECUTING_TOOL, AgentActivity.SCROLLING -> WarmAmber to Color(0xFFFDE047)
        AgentActivity.MUTED -> Color(0xFF64748B) to Color(0xFF94A3B8)
        AgentActivity.IDLE -> RoyalViolet to CobaltBlue
    }

    if (showAddRoutineDialog) {
        CreateRoutineDialog(
            onDismiss = { showAddRoutineDialog = false },
            onCreate = onAddRoutine
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header Row with Theme Toggle & Live Status
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "LivePilot AI",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "Voice • Vision • 22 Android Superpowers",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (sessionState.isServiceRunning) {
                        CallDurationBadge(
                            startedAtMs = sessionState.callStartedAtMs,
                            accentColor = palette.emeraldAccent,
                            containerColor = palette.emeraldSoft
                        )
                    }
                    StatusPillBadge(
                        label = when (sessionState.phase) {
                            LiveConnectionPhase.READY -> "LIVE"
                            LiveConnectionPhase.CONNECTING,
                            LiveConnectionPhase.WAITING_SETUP -> "SYNCING"
                            LiveConnectionPhase.RECONNECTING -> "RETRY"
                            LiveConnectionPhase.ERROR -> "ERROR"
                            LiveConnectionPhase.IDLE -> "${permissions.grantedCount}/4"
                        },
                        accentColor = when (sessionState.phase) {
                            LiveConnectionPhase.READY -> palette.emeraldAccent
                            LiveConnectionPhase.ERROR -> palette.redAccent
                            LiveConnectionPhase.IDLE -> if (permissions.allGranted) palette.cobaltAccent else palette.amberAccent
                            else -> palette.violetAccent
                        },
                        containerColor = when (sessionState.phase) {
                            LiveConnectionPhase.READY -> palette.emeraldSoft
                            LiveConnectionPhase.ERROR -> palette.redSoft
                            LiveConnectionPhase.IDLE -> if (permissions.allGranted) palette.cobaltSoft else palette.amberSoft
                            else -> palette.violetSoft
                        }
                    )
                    Surface(
                        shape = CircleShape,
                        color = palette.card,
                        border = BorderStroke(1.dp, palette.outline)
                    ) {
                        IconButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onToggleTheme(palette.isDark)
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .testTag("theme_quick_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (palette.isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                contentDescription = "Toggle Light or Dark Theme",
                                tint = if (palette.isDark) WarmAmber else palette.cobaltAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // Missing API key callout
        if (settings.apiKey.isBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = palette.amberSoft,
                    border = BorderStroke(1.dp, palette.amberAccent.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = "API Key needed",
                            tint = palette.amberAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gemini API Key Required",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.inkPrimary
                            )
                            Text(
                                text = "Add your key in Settings to unlock Live Voice Call & Google Search.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onGoToSettings,
                            modifier = Modifier.testTag("open_api_key_settings_button"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, palette.amberAccent)
                        ) {
                            Text("Setup", color = palette.amberAccent, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        // Flagship Atmospheric Voice Cockpit Hero Card
        item {
            val isLive = sessionState.isServiceRunning
            val heroTextColor = if (isLive || palette.isDark) Color.White else palette.inkPrimary
            val heroSubColor = if (isLive || palette.isDark) Color(0xFFCBD5E1) else palette.inkSecondary

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = if (isLive) Color(0xFF0B1226) else palette.card,
                border = BorderStroke(
                    width = if (isLive) 1.5.dp else 1.dp,
                    color = if (isLive) orbPrimary.copy(alpha = 0.65f) else palette.outline
                ),
                shadowElevation = if (palette.isDark && !isLive) 0.dp else 6.dp
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (!isLive) {
                        Image(
                            painter = painterResource(id = R.drawable.img_livepilot_hero_1795261617988),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(if (palette.isDark) 0.16f else 0.28f)
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = if (palette.isDark) {
                                            listOf(
                                                Color(0xFF0D1528).copy(alpha = 0.72f),
                                                Color(0xFF111827).copy(alpha = 0.96f)
                                            )
                                        } else {
                                            listOf(
                                                Color.White.copy(alpha = 0.48f),
                                                Color.White.copy(alpha = 0.94f)
                                            )
                                        }
                                    )
                                )
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xFF0D1730),
                                            Color(0xFF111A3A),
                                            Color(0xFF0A0F1F)
                                        )
                                    )
                                )
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Centered Aurora Live Stage
                        AuroraLiveOrb(
                            primaryColor = orbPrimary,
                            secondaryColor = orbSecondary,
                            isPulsing = isLive,
                            audioEnergy = combinedEnergy,
                            size = 82.dp
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (isLive) {
                                    when (sessionState.activity) {
                                        AgentActivity.SPEAKING -> "LivePilot Speaking"
                                        AgentActivity.EXECUTING_TOOL -> "Executing Action"
                                        AgentActivity.SCROLLING -> "Auto-Scrolling"
                                        AgentActivity.MUTED -> "Microphone Muted"
                                        else -> "Listening Live"
                                    }
                                } else {
                                    "Your Hands-Free AI Co-Pilot"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                color = heroTextColor,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            AnimatedContent(
                                targetState = sessionState.statusTicker,
                                label = "ticker_anim"
                            ) { ticker ->
                                Text(
                                    text = ticker,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = heroSubColor,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        if (isLive) {
                            LiveAudioWaveform(
                                energy = combinedEnergy,
                                color = orbSecondary,
                                isActive = !sessionState.isMuted
                            )
                        }

                        // Config & Live Telemetry Chips
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item {
                                StatusPillBadge(
                                    label = settings.liveModel,
                                    accentColor = if (isLive) Color(0xFF93C5FD) else palette.cobaltAccent,
                                    containerColor = if (isLive) Color(0xFF1E3A8A).copy(alpha = 0.55f) else palette.cobaltSoft,
                                    leadingDot = false
                                )
                            }
                            item {
                                StatusPillBadge(
                                    label = "Voice: ${settings.voiceName}",
                                    accentColor = if (isLive) Color(0xFFC4B5FD) else palette.violetAccent,
                                    containerColor = if (isLive) Color(0xFF4C1D95).copy(alpha = 0.55f) else palette.violetSoft,
                                    leadingDot = false
                                )
                            }
                            if (isLive) {
                                item {
                                    StatusPillBadge(
                                        label = "${sessionState.toolsExecutedCount} Actions",
                                        accentColor = EmeraldBright,
                                        containerColor = Color(0xFF064E3B).copy(alpha = 0.7f),
                                        leadingDot = false
                                    )
                                }
                            }
                            if (!sessionState.lastToolSummary.isNullOrBlank() && isLive) {
                                item {
                                    StatusPillBadge(
                                        label = sessionState.lastToolSummary,
                                        accentColor = Color(0xFFFDE68A),
                                        containerColor = Color(0xFF78350F).copy(alpha = 0.65f),
                                        leadingDot = false
                                    )
                                }
                            }
                        }

                        // Primary Call CTA Button
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onStartOrStopCall()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("start_live_call_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isLive) SignalRed else CobaltBlue,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = if (isLive) Icons.Filled.CallEnd else Icons.Filled.Call,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isLive) "End Live Call Agent" else "Start Live Call Agent",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Active Call Controls & Silent Text Bar
                        if (isLive) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleMute()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("home_mute_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    border = BorderStroke(1.dp, Color(0xFF475569))
                                ) {
                                    Icon(
                                        imageVector = if (sessionState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                        contentDescription = "Toggle mute",
                                        tint = if (sessionState.isMuted) WarmAmber else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (sessionState.isMuted) "Unmute" else "Mute",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onCaptureVision()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("home_vision_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    border = BorderStroke(1.dp, Color(0xFF7C3AED))
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.RemoveRedEye,
                                        contentDescription = "Snap vision",
                                        tint = Color(0xFFC4B5FD),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Vision", style = MaterialTheme.typography.labelLarge, color = Color(0xFFEDE9FE))
                                }

                                OutlinedButton(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onToggleAutoVision()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("home_auto_vision_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (sessionState.isAutoVisionActive) EmeraldBright else Color(0xFF475569)
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Visibility,
                                        contentDescription = "Auto-vision toggle",
                                        tint = if (sessionState.isAutoVisionActive) EmeraldBright else Color(0xFFCBD5E1),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (sessionState.isAutoVisionActive) "Auto ON" else "Auto",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (sessionState.isAutoVisionActive) EmeraldBright else Color(0xFFCBD5E1)
                                    )
                                }

                                Button(
                                    onClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onEmergencyStop()
                                    },
                                    modifier = Modifier
                                        .weight(0.9f)
                                        .height(48.dp)
                                        .testTag("home_emergency_stop_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SignalRed,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.StopCircle,
                                        contentDescription = "Stop active scroll or gesture",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("STOP", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = silentCommandText,
                                    onValueChange = { silentCommandText = it },
                                    placeholder = { Text("Type silent command into call...", color = Color(0xFF94A3B8)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(
                                        onSend = {
                                            if (silentCommandText.isNotBlank()) {
                                                val cmd = silentCommandText
                                                silentCommandText = ""
                                                onSendSilentCommand(cmd)
                                            }
                                        }
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("silent_command_input"),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color(0xFF1E293B),
                                        unfocusedContainerColor = Color(0xFF1E293B),
                                        focusedBorderColor = CyanBright,
                                        unfocusedBorderColor = Color(0xFF334155)
                                    )
                                )
                                Surface(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(enabled = silentCommandText.isNotBlank()) {
                                            val cmd = silentCommandText
                                            silentCommandText = ""
                                            onSendSilentCommand(cmd)
                                        }
                                        .testTag("silent_command_send_button"),
                                    color = if (silentCommandText.isNotBlank()) CobaltBlue else Color(0xFF1E293B)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = "Send command to call",
                                            tint = if (silentCommandText.isNotBlank()) Color.White else Color(0xFF64748B),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = onTestConnection,
                                enabled = !connectionTest.isTesting,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("test_live_connection_button"),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, palette.outline)
                            ) {
                                if (connectionTest.isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = palette.cobaltAccent
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Testing WebSocket Handshake...", color = palette.inkPrimary)
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.NetworkCheck,
                                        contentDescription = "Test Live API Connection",
                                        tint = palette.cobaltAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Test Live API Connection", color = palette.inkPrimary)
                                }
                            }

                            if (connectionTest.message.isNotBlank() && !connectionTest.isTesting) {
                                val ok = connectionTest.success == true
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (ok) palette.emeraldSoft else palette.redSoft,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = buildString {
                                            append(if (ok) "PASS: " else "FAILED: ")
                                            append(connectionTest.message)
                                            connectionTest.latencyMs?.let { if (it > 0) append(" • ${it}ms") }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (ok) palette.emeraldAccent else palette.redAccent,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Live Executed Actions Timeline Strip (when tools have run)
        if (recentToolEvents.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Recent Live Actions",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.inkPrimary
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentToolEvents, key = { it.id }) { ev ->
                            StatusPillBadge(
                                label = "${if (ev.succeeded) "✓" else "!"} ${ev.summary} • ${ev.timestamp}",
                                accentColor = if (ev.succeeded) palette.emeraldAccent else palette.amberAccent,
                                containerColor = if (ev.succeeded) palette.emeraldSoft else palette.amberSoft,
                                leadingDot = false
                            )
                        }
                    }
                }
            }
        }

        // One-Tap Smart Routines 2-Column Deck
        item {
            SmartRoutinesSection(
                routines = routines,
                isCallActive = sessionState.isServiceRunning,
                onLaunchRoutine = onLaunchRoutine,
                onDeleteRoutine = onDeleteRoutine,
                onOpenCreateDialog = { showAddRoutineDialog = true }
            )
        }

        // Live Bilingual Transcript Bubbles
        if (recentCaptions.isNotEmpty()) {
            item {
                StudioCardContainer {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live Voice Transcript",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary
                        )
                        StatusPillBadge(
                            label = "Bilingual",
                            accentColor = palette.emeraldAccent,
                            containerColor = palette.emeraldSoft
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recentCaptions.takeLast(6).forEach { line ->
                            val isUser = line.speaker == "You"
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isUser) palette.cobaltSoft.copy(alpha = 0.65f) else palette.surfaceSubtle
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = line.speaker,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (isUser) palette.cobaltAccent else palette.violetAccent,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = line.timestamp,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.inkMuted
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = line.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Smart Collapsible Permissions Header Card
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { permissionsExpanded = !permissionsExpanded }
                    .testTag("permissions_header_toggle"),
                shape = RoundedCornerShape(18.dp),
                color = if (permissions.allGranted) palette.emeraldSoft.copy(alpha = 0.55f) else palette.card,
                border = BorderStroke(
                    1.dp,
                    if (permissions.allGranted) palette.emeraldAccent.copy(alpha = 0.35f) else palette.outline
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (permissions.allGranted) palette.emeraldSoft else palette.amberSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Permissions status",
                                tint = if (permissions.allGranted) palette.emeraldAccent else palette.amberAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (permissions.allGranted) {
                                    "All 4 System Permissions Active"
                                } else {
                                    "System Permissions (${permissions.grantedCount}/4 Ready)"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.inkPrimary
                            )
                            Text(
                                text = if (permissions.allGranted) {
                                    "Mic, Overlay, Accessibility & Auto-Chat ready"
                                } else {
                                    "Tap below to enable remaining permissions"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary
                            )
                        }
                    }
                    Icon(
                        imageVector = if (permissionsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "Expand or collapse permissions",
                        tint = palette.inkSecondary
                    )
                }
            }
        }

        if (permissionsExpanded) {
            item {
                PermissionStatusCard(
                    title = "Microphone Access",
                    subtitle = "Full-duplex 16kHz voice capture with hardware echo cancellation",
                    icon = Icons.Filled.Mic,
                    iconAccent = palette.cobaltAccent,
                    iconSoftBg = palette.cobaltSoft,
                    isGranted = permissions.micGranted,
                    buttonTag = "permission_mic_button",
                    onSetupClick = onRequestMicPermission
                )
            }

            item {
                PermissionStatusCard(
                    title = "Display Over Other Apps",
                    subtitle = "Snap-to-edge Floating HUD, live captions & AI touch ripple overlay",
                    icon = Icons.Filled.Layers,
                    iconAccent = palette.violetAccent,
                    iconSoftBg = palette.violetSoft,
                    isGranted = permissions.overlayGranted,
                    buttonTag = "permission_overlay_button",
                    onSetupClick = onOpenOverlaySettings
                )
            }

            item {
                PermissionStatusCard(
                    title = "Accessibility & Screen Vision",
                    subtitle = "22 real-time tools: tap, scroll, drag, YouTube, torch, timers & vision",
                    icon = Icons.Filled.AccessibilityNew,
                    iconAccent = palette.emeraldAccent,
                    iconSoftBg = palette.emeraldSoft,
                    isGranted = permissions.accessibilityEnabled,
                    buttonTag = "permission_accessibility_button",
                    onSetupClick = onOpenAccessibilitySettings
                )
            }

            item {
                PermissionStatusCard(
                    title = "Notification Access (Auto-Chat)",
                    subtitle = "Detects incoming imo, WhatsApp, Messenger & Telegram chats live",
                    icon = Icons.Filled.NotificationsActive,
                    iconAccent = palette.roseAccent,
                    iconSoftBg = palette.roseSoft,
                    isGranted = permissions.notificationAccessEnabled,
                    buttonTag = "permission_notification_button",
                    onSetupClick = onOpenNotificationSettings
                )
            }
        }

        // Safety Shield Reminder Card
        item {
            StudioCardContainer(
                containerColor = palette.surfaceSubtle,
                contentPadding = 14.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(palette.emeraldSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = "Financial safety shield",
                            tint = palette.emeraldAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Financial Safety Guardrails Active",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = "bKash, Nagad, NexusPay, Upay, banking, 2FA & password managers are permanently blocked from UI reads & screenshots.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconAccent: Color,
    iconSoftBg: Color,
    isGranted: Boolean,
    buttonTag: String,
    onSetupClick: () -> Unit
) {
    val palette = StudioTheme.colors
    StudioCardContainer(contentPadding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconSoftBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconAccent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.inkPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            if (isGranted) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = palette.emeraldSoft
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Granted",
                            tint = palette.emeraldAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.emeraldAccent
                        )
                    }
                }
            } else {
                Button(
                    onClick = onSetupClick,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag(buttonTag),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CobaltBlue,
                        contentColor = Color.White
                    )
                ) {
                    Text("Enable", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
