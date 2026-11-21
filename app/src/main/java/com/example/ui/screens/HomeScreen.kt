package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AgentActivity
import com.example.data.ConnectionTestState
import com.example.data.LiveCaptionLine
import com.example.data.LiveConnectionPhase
import com.example.data.LiveSessionState
import com.example.data.PermissionSnapshot
import com.example.data.PilotSettings
import com.example.ui.components.PulsingLiveOrb
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.StudioCardContainer
import com.example.ui.theme.AmberDark
import com.example.ui.theme.AmberSoft
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.CobaltDark
import com.example.ui.theme.CobaltSoft
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.EmeraldSoft
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.RedSoft
import com.example.ui.theme.RosePink
import com.example.ui.theme.RoseSoft
import com.example.ui.theme.RoyalViolet
import com.example.ui.theme.SignalRed
import com.example.ui.theme.SignalRedDark
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioOutline
import com.example.ui.theme.StudioSurfaceSubtle
import com.example.ui.theme.VioletSoft
import com.example.ui.theme.WarmAmber

@Composable
fun HomeScreen(
    settings: PilotSettings,
    sessionState: LiveSessionState,
    permissions: PermissionSnapshot,
    connectionTest: ConnectionTestState,
    recentCaptions: List<LiveCaptionLine>,
    onStartOrStopCall: () -> Unit,
    onTestConnection: () -> Unit,
    onToggleMute: () -> Unit,
    onCaptureVision: () -> Unit,
    onEmergencyStop: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onGoToSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "LivePilot AI",
                        style = MaterialTheme.typography.headlineMedium,
                        color = InkPrimary
                    )
                    Text(
                        text = "Voice • Vision • UI Automation Agent",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
                StatusPillBadge(
                    label = when (sessionState.phase) {
                        LiveConnectionPhase.READY -> "LIVE CALL"
                        LiveConnectionPhase.CONNECTING,
                        LiveConnectionPhase.WAITING_SETUP -> "CONNECTING"
                        LiveConnectionPhase.RECONNECTING -> "RECONNECTING"
                        LiveConnectionPhase.ERROR -> "ERROR"
                        LiveConnectionPhase.IDLE -> "${permissions.grantedCount}/4 READY"
                    },
                    accentColor = when (sessionState.phase) {
                        LiveConnectionPhase.READY -> EmeraldDark
                        LiveConnectionPhase.ERROR -> SignalRedDark
                        LiveConnectionPhase.IDLE -> if (permissions.allGranted) CobaltDark else AmberDark
                        else -> RoyalViolet
                    },
                    containerColor = when (sessionState.phase) {
                        LiveConnectionPhase.READY -> EmeraldSoft
                        LiveConnectionPhase.ERROR -> RedSoft
                        LiveConnectionPhase.IDLE -> if (permissions.allGranted) CobaltSoft else AmberSoft
                        else -> VioletSoft
                    }
                )
            }
        }

        // Missing API key callout
        if (settings.apiKey.isBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = AmberSoft,
                    border = BorderStroke(1.dp, WarmAmber.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = "API Key needed",
                            tint = AmberDark,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gemini API Key Required",
                                style = MaterialTheme.typography.titleSmall,
                                color = InkPrimary
                            )
                            Text(
                                text = "No keys are hardcoded. Enter your API key in Settings to enable Live Call & Ask AI.",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onGoToSettings,
                            modifier = Modifier.testTag("open_api_key_settings_button"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, AmberDark)
                        ) {
                            Text("Setup", color = AmberDark, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }

        // Hero Call Control Card
        item {
            val orbColor = when (sessionState.activity) {
                AgentActivity.LISTENING -> CobaltBlue
                AgentActivity.SPEAKING -> EmeraldGreen
                AgentActivity.EXECUTING_TOOL, AgentActivity.SCROLLING -> WarmAmber
                AgentActivity.MUTED -> InkMuted
                AgentActivity.IDLE -> RoyalViolet
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = StudioCard,
                border = BorderStroke(1.dp, StudioOutline),
                shadowElevation = 4.dp
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_livepilot_hero_1795261617988),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                            .alpha(0.32f)
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.55f),
                                        Color.White.copy(alpha = 0.94f)
                                    )
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PulsingLiveOrb(
                                color = orbColor,
                                isPulsing = sessionState.isServiceRunning,
                                size = 48.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (sessionState.isServiceRunning) {
                                        "Live Call Active"
                                    } else {
                                        "Ready to Pilot"
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    color = InkPrimary
                                )
                                Text(
                                    text = sessionState.statusTicker,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = InkSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Active config badges
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusPillBadge(
                                label = settings.liveModel,
                                accentColor = CobaltDark,
                                containerColor = CobaltSoft,
                                leadingDot = false
                            )
                            StatusPillBadge(
                                label = "Voice: ${settings.voiceName}",
                                accentColor = RoyalViolet,
                                containerColor = VioletSoft,
                                leadingDot = false
                            )
                        }

                        // Primary Call CTA Button
                        Button(
                            onClick = onStartOrStopCall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("start_live_call_button"),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (sessionState.isServiceRunning) SignalRed else CobaltBlue,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = if (sessionState.isServiceRunning) Icons.Filled.CallEnd else Icons.Filled.Call,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (sessionState.isServiceRunning) {
                                    "End Live Call Agent"
                                } else {
                                    "Start Live Call Agent"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Live Quick Controls while session running
                        if (sessionState.isServiceRunning) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onToggleMute,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("home_mute_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, CobaltBlue.copy(alpha = 0.4f))
                                ) {
                                    Icon(
                                        imageVector = if (sessionState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                        contentDescription = "Toggle mute",
                                        tint = if (sessionState.isMuted) AmberDark else CobaltBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (sessionState.isMuted) "Unmute" else "Mute",
                                        color = InkPrimary
                                    )
                                }

                                OutlinedButton(
                                    onClick = onCaptureVision,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("home_vision_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, RoyalViolet.copy(alpha = 0.4f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.RemoveRedEye,
                                        contentDescription = "Capture screen vision",
                                        tint = RoyalViolet,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Vision", color = RoyalViolet)
                                }

                                Button(
                                    onClick = onEmergencyStop,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("home_emergency_stop_button"),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SignalRed,
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.StopCircle,
                                        contentDescription = "Stop active scroll or gesture",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("STOP", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        // Test Live API Connection Button + Result
                        OutlinedButton(
                            onClick = onTestConnection,
                            enabled = !connectionTest.isTesting,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("test_live_connection_button"),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, StudioOutline)
                        ) {
                            if (connectionTest.isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = CobaltBlue
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Testing WebSocket Handshake...", color = InkPrimary)
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.NetworkCheck,
                                    contentDescription = "Test Live API Connection",
                                    tint = CobaltBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Test Live API Connection", color = InkPrimary)
                            }
                        }

                        if (connectionTest.message.isNotBlank() && !connectionTest.isTesting) {
                            val ok = connectionTest.success == true
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (ok) EmeraldSoft else RedSoft,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = buildString {
                                        append(if (ok) "PASS: " else "FAILED: ")
                                        append(connectionTest.message)
                                        connectionTest.latencyMs?.let { if (it > 0) append(" • ${it}ms") }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ok) EmeraldDark else SignalRedDark,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Live Captions Preview (when captions exist)
        if (recentCaptions.isNotEmpty()) {
            item {
                StudioCardContainer {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live Voice Captions",
                            style = MaterialTheme.typography.titleMedium,
                            color = InkPrimary
                        )
                        StatusPillBadge(
                            label = "Bilingual",
                            accentColor = EmeraldDark,
                            containerColor = EmeraldSoft
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    recentCaptions.takeLast(4).forEach { line ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            Text(
                                text = "${line.speaker}: ",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (line.speaker == "You") CobaltBlue else RoyalViolet
                            )
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = InkPrimary
                            )
                        }
                    }
                }
            }
        }

        // Permissions Section Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "System Permissions",
                    style = MaterialTheme.typography.titleLarge,
                    color = InkPrimary
                )
                StatusPillBadge(
                    label = "${permissions.grantedCount} of 4 Active",
                    accentColor = if (permissions.allGranted) EmeraldDark else AmberDark,
                    containerColor = if (permissions.allGranted) EmeraldSoft else AmberSoft
                )
            }
        }

        // 4 Clean Permission Status Cards
        item {
            PermissionStatusCard(
                title = "Microphone Access",
                subtitle = "Full-duplex 16kHz voice capture with hardware echo cancellation",
                icon = Icons.Filled.Mic,
                iconAccent = CobaltBlue,
                iconSoftBg = CobaltSoft,
                isGranted = permissions.micGranted,
                buttonTag = "permission_mic_button",
                onSetupClick = onRequestMicPermission
            )
        }

        item {
            PermissionStatusCard(
                title = "Display Over Other Apps",
                subtitle = "Shows the draggable floating call pill with Mute, Vision & red STOP",
                icon = Icons.Filled.Layers,
                iconAccent = RoyalViolet,
                iconSoftBg = VioletSoft,
                isGranted = permissions.overlayGranted,
                buttonTag = "permission_overlay_button",
                onSetupClick = onOpenOverlaySettings
            )
        }

        item {
            PermissionStatusCard(
                title = "Accessibility & Screen Vision",
                subtitle = "Enables taps, continuous scroll, timeline drags & API 30+ screen vision",
                icon = Icons.Filled.AccessibilityNew,
                iconAccent = EmeraldDark,
                iconSoftBg = EmeraldSoft,
                isGranted = permissions.accessibilityEnabled,
                buttonTag = "permission_accessibility_button",
                onSetupClick = onOpenAccessibilitySettings
            )
        }

        item {
            PermissionStatusCard(
                title = "Notification Access (Auto-Chat)",
                subtitle = "Detects incoming imo, WhatsApp, Messenger & Telegram chats in real time",
                icon = Icons.Filled.NotificationsActive,
                iconAccent = RosePink,
                iconSoftBg = RoseSoft,
                isGranted = permissions.notificationAccessEnabled,
                buttonTag = "permission_notification_button",
                onSetupClick = onOpenNotificationSettings
            )
        }

        // Safety Shield Reminder Card
        item {
            StudioCardContainer(
                containerColor = StudioSurfaceSubtle,
                contentPadding = 14.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(EmeraldSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = "Financial safety shield",
                            tint = EmeraldDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Financial Safety Guardrails Active",
                            style = MaterialTheme.typography.titleSmall,
                            color = InkPrimary
                        )
                        Text(
                            text = "bKash, Nagad, NexusPay, Upay, banking, 2FA & password managers are automatically blocked from UI reads and screenshots.",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSecondary
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
                    color = InkPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            if (isGranted) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = EmeraldSoft
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Granted",
                            tint = EmeraldDark,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Active",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmeraldDark
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
                        containerColor = iconAccent,
                        contentColor = Color.White
                    )
                ) {
                    Text("Enable", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
