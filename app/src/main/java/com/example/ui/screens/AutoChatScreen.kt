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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.RosePink
import com.example.ui.theme.RoyalViolet
import com.example.ui.theme.StudioTheme

private enum class AutoChatSubTab {
    INBOX,
    RULES
}

private val QUICK_TEMPLATE_REPLIES = listOf(
    "ব্যস্ত আছি, পরে কথা বলছি",
    "ঠিক আছে, দেখছি",
    "On my way!",
    "Ok, got it"
)

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
    onTemplateReply: (IncomingMessageRecord, String) -> Unit,
    onClearFeed: () -> Unit
) {
    val palette = StudioTheme.colors
    val isFullAuto = settings.autoChatMode == AutoChatMode.FULL_AUTO_REPLY
    var activeSubTab by rememberSaveable { mutableStateOf(AutoChatSubTab.INBOX) }
    val autoRepliedCount = messages.count { !it.autoRepliedText.isNullOrBlank() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Chat",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "imo, WhatsApp, Messenger & Telegram copilot",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                }
                StatusPillBadge(
                    label = if (isFullAuto) "AUTO-REPLY ON" else "VOICE ALERT",
                    accentColor = if (isFullAuto) palette.roseAccent else palette.cobaltAccent,
                    containerColor = if (isFullAuto) palette.roseSoft else palette.cobaltSoft
                )
            }
        }

        // Top Control Bar: Quick Full Auto-Reply Switch + Sub-Tabs
        item {
            StudioCardContainer(contentPadding = 12.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isFullAuto) "Full Auto-Reply Active" else "Live Voice Alert Mode",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = if (isFullAuto) {
                                "Using ${settings.textModel} • ${settings.enabledMessagingApps.size} apps"
                            } else {
                                "Announces messages on call • ${settings.enabledMessagingApps.size} apps"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
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

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubTabPill(
                        label = "Live Inbox (${messages.size})",
                        selected = activeSubTab == AutoChatSubTab.INBOX,
                        accent = palette.roseAccent,
                        softBg = palette.roseSoft,
                        badgeSuffix = if (autoRepliedCount > 0) "$autoRepliedCount sent" else null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("autochat_tab_inbox"),
                        onClick = { activeSubTab = AutoChatSubTab.INBOX }
                    )
                    SubTabPill(
                        label = "Rules & Apps",
                        selected = activeSubTab == AutoChatSubTab.RULES,
                        accent = palette.violetAccent,
                        softBg = palette.violetSoft,
                        badgeSuffix = null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("autochat_tab_rules"),
                        onClick = { activeSubTab = AutoChatSubTab.RULES }
                    )
                }
            }
        }

        // Notification Access Banner if missing
        if (!permissions.notificationAccessEnabled) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = palette.amberSoft,
                    border = BorderStroke(1.dp, palette.amberAccent.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsOff,
                            contentDescription = "Notification Access needed",
                            tint = palette.amberAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notification Access Disabled",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.inkPrimary
                            )
                            Text(
                                text = "Grant Notification Access so LivePilot can detect imo & WhatsApp messages live.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary
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

        if (activeSubTab == AutoChatSubTab.INBOX) {
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
                            color = palette.inkPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusPillBadge(
                            label = "${messages.size}",
                            accentColor = palette.roseAccent,
                            containerColor = palette.roseSoft,
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
                                tint = palette.inkSecondary
                            )
                        }
                    }
                }
            }

            if (messages.isEmpty()) {
                item {
                    StudioCardContainer(containerColor = palette.surfaceSubtle) {
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
                                    .background(palette.roseSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ChatBubbleOutline,
                                    contentDescription = "No messages yet",
                                    tint = palette.roseAccent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Waiting for Incoming Messages",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.inkPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "New imo, WhatsApp, Messenger, and Telegram notifications appear here in real time for 1-tap or AI auto-reply.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { activeSubTab = AutoChatSubTab.RULES },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = "Configure rules",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Configure Rules & Apps")
                            }
                        }
                    }
                }
            } else {
                items(messages, key = { it.id }) { msg ->
                    IncomingMessageCard(
                        record = msg,
                        onOpenChat = { onOpenChat(msg) },
                        onQuickReply = { onQuickAiReply(msg) },
                        onTemplateReply = { replyText -> onTemplateReply(msg, replyText) }
                    )
                }
            }
        } else {
            item {
                StudioCardContainer {
                    Text(
                        text = "Assistant Mode",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    ModeOptionTile(
                        title = "Live Voice Alert & Assisted Reply",
                        description = "Announces incoming messages during a live call and asks if you want to open and reply.",
                        icon = Icons.Filled.RecordVoiceOver,
                        accent = palette.cobaltAccent,
                        softBg = palette.cobaltSoft,
                        selected = !isFullAuto,
                        testTag = "mode_voice_alert_card",
                        onClick = { onUpdateMode(AutoChatMode.LIVE_VOICE_ALERT) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    ModeOptionTile(
                        title = "Full Auto-Reply Mode",
                        description = "Automatically generates context-aware replies using ${settings.textModel}.",
                        icon = Icons.Filled.Bolt,
                        accent = palette.roseAccent,
                        softBg = palette.roseSoft,
                        selected = isFullAuto,
                        testTag = "mode_full_auto_reply_card",
                        onClick = { onUpdateMode(AutoChatMode.FULL_AUTO_REPLY) }
                    )
                }
            }

            item {
                StudioCardContainer {
                    Text(
                        text = "Auto-Reply Delivery Strategy",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.inkPrimary
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
                                selectedContainerColor = palette.emeraldSoft,
                                selectedLabelColor = palette.emeraldAccent
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
                                selectedContainerColor = palette.violetSoft,
                                selectedLabelColor = palette.violetAccent
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Monitored Messaging Apps",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.inkPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MessagingApp.entries.forEach { app ->
                            val enabled = app in settings.enabledMessagingApps
                            val appAccent = appAccentColor(app)
                            val appSoft = appSoftColor(app)
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
                                    selectedContainerColor = appSoft,
                                    selectedLabelColor = appAccent,
                                    selectedLeadingIconColor = appAccent
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Custom Auto-Reply Persona Prompt",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.inkPrimary
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
                            focusedContainerColor = palette.surfaceSubtle,
                            unfocusedContainerColor = palette.surfaceSubtle,
                            focusedBorderColor = palette.roseAccent,
                            unfocusedBorderColor = palette.outline
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun SubTabPill(
    label: String,
    selected: Boolean,
    accent: Color,
    softBg: Color,
    badgeSuffix: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = StudioTheme.colors
    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) softBg else palette.surfaceSubtle,
        border = BorderStroke(1.dp, if (selected) accent else palette.outline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) accent else palette.inkSecondary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
            if (!badgeSuffix.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = accent.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = badgeSuffix,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
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
    val palette = StudioTheme.colors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) softBg.copy(alpha = 0.65f) else palette.surfaceSubtle,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) accent else palette.outline
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
                    .background(if (selected) accent else palette.card),
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
                    color = palette.inkPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary
                )
            }
        }
    }
}

@Composable
private fun IncomingMessageCard(
    record: IncomingMessageRecord,
    onOpenChat: () -> Unit,
    onQuickReply: () -> Unit,
    onTemplateReply: (String) -> Unit
) {
    val palette = StudioTheme.colors
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
                    color = palette.inkPrimary
                )
            }
            Text(
                text = record.formattedTime,
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkMuted
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = record.messageText,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkPrimary
        )

        if (!record.autoRepliedText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = palette.emeraldSoft,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Replied: \"${record.autoRepliedText}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.emeraldAccent,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        if (record.hasDirectReply) {
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(QUICK_TEMPLATE_REPLIES) { template ->
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onTemplateReply(template) },
                        shape = RoundedCornerShape(50),
                        color = palette.surfaceSubtle,
                        border = BorderStroke(1.dp, palette.outline)
                    ) {
                        Text(
                            text = template,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
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
                border = BorderStroke(1.dp, palette.outline)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open chat",
                    tint = palette.cobaltAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Chat", color = palette.inkPrimary)
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

@Composable
private fun appAccentColor(app: MessagingApp): Color {
    val p = StudioTheme.colors
    return when (app) {
        MessagingApp.IMO -> p.cobaltAccent
        MessagingApp.WHATSAPP -> p.emeraldAccent
        MessagingApp.MESSENGER -> p.violetAccent
        MessagingApp.TELEGRAM -> p.cyanAccent
    }
}

@Composable
private fun appSoftColor(app: MessagingApp): Color {
    val p = StudioTheme.colors
    return when (app) {
        MessagingApp.IMO -> p.cobaltSoft
        MessagingApp.WHATSAPP -> p.emeraldSoft
        MessagingApp.MESSENGER -> p.violetSoft
        MessagingApp.TELEGRAM -> p.cyanSoft
    }
}
