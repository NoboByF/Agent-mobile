package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.AutoChatMode
import com.example.data.AutoReplyDelivery
import com.example.data.IncomingMessageRecord
import com.example.data.MessagingApp
import com.example.data.PermissionSnapshot
import com.example.data.PilotSettings
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.StudioCardContainer
import com.example.ui.theme.AmberDark
import com.example.ui.theme.AmberSoft
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.CobaltDark
import com.example.ui.theme.CobaltSoft
import com.example.ui.theme.CyanSoft
import com.example.ui.theme.CyanTeal
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.EmeraldSoft
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.RoseDark
import com.example.ui.theme.RosePink
import com.example.ui.theme.RoseSoft
import com.example.ui.theme.RoyalViolet
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioOutline
import com.example.ui.theme.StudioSurfaceSubtle
import com.example.ui.theme.VioletSoft

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoChatScreen(
    settings: PilotSettings,
    permissions: PermissionSnapshot,
    messages: List<IncomingMessageRecord>,
    onUpdateMode: (AutoChatMode) -> Unit,
    onUpdateDelivery: (AutoReplyDelivery) -> Unit,
    onUpdatePersona: (String) -> Unit,
    onToggleApp: (MessagingApp) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenChat: (IncomingMessageRecord) -> Unit,
    onQuickAiReply: (IncomingMessageRecord) -> Unit,
    onClearFeed: () -> Unit
) {
    val isFullAuto = settings.autoChatMode == AutoChatMode.FULL_AUTO_REPLY

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Auto-Chat",
                        style = MaterialTheme.typography.headlineMedium,
                        color = InkPrimary
                    )
                    Text(
                        text = "Real-time imo, WhatsApp, Messenger & Telegram assistant",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
                StatusPillBadge(
                    label = if (isFullAuto) "AUTO-REPLY ON" else "VOICE ALERT",
                    accentColor = if (isFullAuto) RoseDark else CobaltDark,
                    containerColor = if (isFullAuto) RoseSoft else CobaltSoft
                )
            }
        }

        // Notification Access Banner if missing
        if (!permissions.notificationAccessEnabled) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = AmberSoft,
                    border = BorderStroke(1.dp, AmberDark.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsOff,
                            contentDescription = "Notification Access needed",
                            tint = AmberDark,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notification Access Disabled",
                                style = MaterialTheme.typography.titleSmall,
                                color = InkPrimary
                            )
                            Text(
                                text = "Grant Notification Access so LivePilot can detect imo & WhatsApp messages live.",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onOpenNotificationSettings,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("autochat_enable_notif_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AmberDark,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text("Enable")
                        }
                    }
                }
            }
        }

        // Mode Selection Card
        item {
            StudioCardContainer {
                Text(
                    text = "Assistant Mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = InkPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))

                ModeOptionTile(
                    title = "Live Voice Alert & Assisted Reply",
                    description = "Announces incoming messages during a live call and asks if you want to open and reply.",
                    icon = Icons.Filled.RecordVoiceOver,
                    accent = CobaltBlue,
                    softBg = CobaltSoft,
                    selected = !isFullAuto,
                    testTag = "mode_voice_alert_card",
                    onClick = { onUpdateMode(AutoChatMode.LIVE_VOICE_ALERT) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                ModeOptionTile(
                    title = "Full Auto-Reply Mode",
                    description = "Automatically generates context-aware replies using ${settings.textModel}.",
                    icon = Icons.Filled.Bolt,
                    accent = RosePink,
                    softBg = RoseSoft,
                    selected = isFullAuto,
                    testTag = "mode_full_auto_reply_card",
                    onClick = { onUpdateMode(AutoChatMode.FULL_AUTO_REPLY) }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Master Switch for Full Auto-Reply
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Full Auto-Reply Master Toggle",
                            style = MaterialTheme.typography.titleSmall,
                            color = InkPrimary
                        )
                        Text(
                            text = if (isFullAuto) "Active for selected apps below" else "Off (Voice Alert mode active)",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSecondary
                        )
                    }
                    Switch(
                        checked = isFullAuto,
                        onCheckedChange = { checked ->
                            onUpdateMode(
                                if (checked) AutoChatMode.FULL_AUTO_REPLY else AutoChatMode.LIVE_VOICE_ALERT
                            )
                        },
                        modifier = Modifier.testTag("full_auto_reply_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = RosePink
                        )
                    )
                }
            }
        }

        // Delivery Strategy, Persona & Monitored Apps
        item {
            StudioCardContainer {
                Text(
                    text = "Auto-Reply Delivery Strategy",
                    style = MaterialTheme.typography.titleSmall,
                    color = InkPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val silentSelected = settings.autoReplyDelivery == AutoReplyDelivery.SILENT_DIRECT_REPLY
                    FilterChip(
                        selected = silentSelected,
                        onClick = { onUpdateDelivery(AutoReplyDelivery.SILENT_DIRECT_REPLY) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("delivery_silent_direct_chip"),
                        label = { Text("Silent Direct Reply", maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldSoft,
                            selectedLabelColor = EmeraldDark
                        )
                    )
                    FilterChip(
                        selected = !silentSelected,
                        onClick = { onUpdateDelivery(AutoReplyDelivery.VISUAL_ACCESSIBILITY) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("delivery_visual_open_chip"),
                        label = { Text("Open & Chat Visually", maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VioletSoft,
                            selectedLabelColor = RoyalViolet
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Monitored Messaging Apps",
                    style = MaterialTheme.typography.titleSmall,
                    color = InkPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MessagingApp.entries.forEach { app ->
                        val enabled = app in settings.enabledMessagingApps
                        FilterChip(
                            selected = enabled,
                            onClick = { onToggleApp(app) },
                            modifier = Modifier.testTag("app_toggle_${app.name.lowercase()}"),
                            leadingIcon = if (enabled) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else null,
                            label = { Text(app.displayName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = appSoftColor(app),
                                selectedLabelColor = appAccentColor(app),
                                selectedLeadingIconColor = appAccentColor(app)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Custom Auto-Reply Persona Prompt",
                    style = MaterialTheme.typography.titleSmall,
                    color = InkPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = settings.autoReplyPersona,
                    onValueChange = onUpdatePersona,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("auto_reply_persona_input"),
                    minLines = 2,
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("e.g. Reply politely in Bengali as my personal assistant") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = StudioSurfaceSubtle,
                        unfocusedContainerColor = StudioSurfaceSubtle,
                        focusedBorderColor = RosePink,
                        unfocusedBorderColor = StudioOutline
                    )
                )
            }
        }

        // Incoming Message Feed Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Incoming Message Feed",
                        style = MaterialTheme.typography.titleLarge,
                        color = InkPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusPillBadge(
                        label = "${messages.size}",
                        accentColor = RoseDark,
                        containerColor = RoseSoft,
                        leadingDot = false
                    )
                }
                if (messages.isNotEmpty()) {
                    IconButton(
                        onClick = onClearFeed,
                        modifier = Modifier.testTag("clear_message_feed_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = "Clear feed",
                            tint = InkSecondary
                        )
                    }
                }
            }
        }

        if (messages.isEmpty()) {
            item {
                StudioCardContainer(containerColor = StudioSurfaceSubtle) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(RoseSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ChatBubbleOutline,
                                contentDescription = "No messages yet",
                                tint = RosePink,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Waiting for Incoming Messages",
                            style = MaterialTheme.typography.titleMedium,
                            color = InkPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "New imo, WhatsApp, Messenger, and Telegram messages will appear here live once Notification Access is active.",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSecondary
                        )
                    }
                }
            }
        } else {
            items(messages, key = { it.id }) { msg ->
                IncomingMessageCard(
                    record = msg,
                    onOpenChat = { onOpenChat(msg) },
                    onQuickReply = { onQuickAiReply(msg) }
                )
            }
        }
    }
}

@Composable
private fun ModeOptionTile(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    softBg: Color,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) softBg.copy(alpha = 0.55f) else StudioSurfaceSubtle,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) accent else StudioOutline
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) accent else StudioCard),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (selected) Color.White else accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = InkPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
            }
        }
    }
}

@Composable
private fun IncomingMessageCard(
    record: IncomingMessageRecord,
    onOpenChat: () -> Unit,
    onQuickReply: () -> Unit
) {
    StudioCardContainer(contentPadding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPillBadge(
                    label = record.app.displayName,
                    accentColor = appAccentColor(record.app),
                    containerColor = appSoftColor(record.app),
                    leadingDot = false
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = record.senderName,
                    style = MaterialTheme.typography.titleSmall,
                    color = InkPrimary
                )
            }
            Text(
                text = record.formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = InkMuted
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = record.messageText,
            style = MaterialTheme.typography.bodyMedium,
            color = InkPrimary
        )

        if (!record.autoRepliedText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = EmeraldSoft,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "AI Replied: \"${record.autoRepliedText}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmeraldDark,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenChat,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StudioOutline)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open chat",
                    tint = CobaltBlue,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Chat", color = InkPrimary)
            }

            Button(
                onClick = onQuickReply,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (record.hasDirectReply) EmeraldGreen else RoyalViolet,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "AI Reply",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (record.hasDirectReply) "AI Direct Reply" else "Draft AI Reply")
            }
        }
    }
}

private fun appAccentColor(app: MessagingApp): Color = when (app) {
    MessagingApp.IMO -> CobaltDark
    MessagingApp.WHATSAPP -> EmeraldDark
    MessagingApp.MESSENGER -> RoyalViolet
    MessagingApp.TELEGRAM -> CyanTeal
}

private fun appSoftColor(app: MessagingApp): Color = when (app) {
    MessagingApp.IMO -> CobaltSoft
    MessagingApp.WHATSAPP -> EmeraldSoft
    MessagingApp.MESSENGER -> VioletSoft
    MessagingApp.TELEGRAM -> CyanSoft
}
