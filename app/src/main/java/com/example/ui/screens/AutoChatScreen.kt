package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ai.GeneratedImageHelper
import com.example.data.AutoChatMode
import com.example.data.AutoReplyDelivery
import com.example.data.IncomingMessageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.data.MessagingApp
import com.example.data.PermissionSnapshot
import com.example.data.PersonalDataItem
import com.example.data.PilotSettings
import com.example.ui.components.PersonalDataEditorCard
import com.example.ui.components.PersonalDataItemCard
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
    MY_DATA,
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
    personalData: List<PersonalDataItem>,
    onUpdateMode: (AutoChatMode) -> Unit,
    onUpdateDelivery: (AutoReplyDelivery) -> Unit,
    onUpdatePersona: (String) -> Unit,
    onToggleSensitiveGuard: (Boolean) -> Unit,
    onToggleHandsFreeVoiceEscalation: (Boolean) -> Unit,
    onUpdateAutoReplyDelaySec: (Int) -> Unit,
    onTogglePauseWhenOwnerActive: (Boolean) -> Unit,
    onToggleSmartConversationClosing: (Boolean) -> Unit,
    onToggleApp: (MessagingApp) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenChat: (IncomingMessageRecord) -> Unit,
    onQuickAiReply: (IncomingMessageRecord) -> Unit,
    onCancelPendingAiReply: (IncomingMessageRecord) -> Unit,
    onTemplateReply: (IncomingMessageRecord, String) -> Unit,
    onSendOwnerSensitiveAnswer: (IncomingMessageRecord, String) -> Unit,
    onSavePersonalData: (existingId: String?, title: String, content: String, category: String, shareInAutoReply: Boolean) -> Unit,
    onTogglePersonalDataShare: (PersonalDataItem) -> Unit,
    onDeletePersonalData: (String) -> Unit,
    onSaveGeneratedImage: (String) -> Unit,
    onShareGeneratedImage: (String, String, String?) -> Unit,
    onClearFeed: () -> Unit
) {
    val palette = StudioTheme.colors
    val isFullAuto = settings.autoChatMode == AutoChatMode.FULL_AUTO_REPLY
    var activeSubTab by rememberSaveable { mutableStateOf(AutoChatSubTab.INBOX) }
    var editingPersonalItem by remember { mutableStateOf<PersonalDataItem?>(null) }
    val pendingSensitive = messages.filter { it.needsOwnerAnswer }
    val regularMessages = messages.filterNot { it.needsOwnerAnswer }
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
                                "${settings.autoReplyDelaySec}s অপেক্ষা • ${if (settings.pauseAutoReplyWhenOwnerActive) "আপনি সক্রিয় থাকলে বিরতি" else "অবিরত"} • ${settings.enabledMessagingApps.size} apps"
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SubTabPill(
                        label = "Inbox (${messages.size})",
                        selected = activeSubTab == AutoChatSubTab.INBOX,
                        accent = palette.roseAccent,
                        softBg = palette.roseSoft,
                        badgeSuffix = null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("autochat_tab_inbox"),
                        onClick = { activeSubTab = AutoChatSubTab.INBOX }
                    )
                    SubTabPill(
                        label = "আমার ডাটা (${personalData.size})",
                        selected = activeSubTab == AutoChatSubTab.MY_DATA,
                        accent = palette.cobaltAccent,
                        softBg = palette.cobaltSoft,
                        badgeSuffix = null,
                        modifier = Modifier
                            .weight(1.1f)
                            .testTag("autochat_tab_my_data"),
                        onClick = { activeSubTab = AutoChatSubTab.MY_DATA }
                    )
                    SubTabPill(
                        label = "Rules",
                        selected = activeSubTab == AutoChatSubTab.RULES,
                        accent = palette.violetAccent,
                        softBg = palette.violetSoft,
                        badgeSuffix = null,
                        modifier = Modifier
                            .weight(0.9f)
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
            if (pendingSensitive.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WarningAmber,
                            contentDescription = "Sensitive questions needing answer",
                            tint = palette.amberAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "আপনার উত্তর প্রয়োজন",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        StatusPillBadge(
                            label = "${pendingSensitive.size} SENSITIVE",
                            accentColor = palette.amberAccent,
                            containerColor = palette.amberSoft,
                            leadingDot = false
                        )
                    }
                }

                items(pendingSensitive, key = { "sensitive_${it.id}" }) { msg ->
                    SensitiveEscalationCard(
                        record = msg,
                        onOpenChat = { onOpenChat(msg) },
                        onSendOwnerAnswer = { answer -> onSendOwnerSensitiveAnswer(msg, answer) }
                    )
                }
            }

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
                items(regularMessages, key = { it.id }) { msg ->
                    IncomingMessageCard(
                        record = msg,
                        onOpenChat = { onOpenChat(msg) },
                        onQuickReply = { onQuickAiReply(msg) },
                        onCancelPendingReply = { onCancelPendingAiReply(msg) },
                        onTemplateReply = { replyText -> onTemplateReply(msg, replyText) },
                        onSaveGeneratedImage = { path -> onSaveGeneratedImage(path) },
                        onShareGeneratedImage = { path ->
                            onShareGeneratedImage(path, "AI তৈরি ছবি (${msg.senderName})", msg.packageName)
                        }
                    )
                }
            }
        } else if (activeSubTab == AutoChatSubTab.MY_DATA) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = palette.emeraldSoft,
                    border = BorderStroke(1.dp, palette.emeraldAccent.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = "Firebase backed My Data",
                            tint = palette.emeraldAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "আপনার সম্পর্কে তথ্য এখানে সেভ করুন। কেউ জানতে চাইলে AI এখান থেকেই উত্তর দেবে এবং সব ডাটা এই মোবাইলের Firebase ক্লাউডে সুরক্ষিত থাকবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkPrimary
                        )
                    }
                }
            }

            item {
                PersonalDataEditorCard(
                    editingItem = editingPersonalItem,
                    onCancelEdit = { editingPersonalItem = null },
                    onSaveEntry = onSavePersonalData
                )
            }

            if (personalData.isEmpty()) {
                item {
                    StudioCardContainer(containerColor = palette.surfaceSubtle) {
                        Text(
                            text = "এখনো কোনো ব্যক্তিগত তথ্য যোগ করা হয়নি",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.inkPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "উপরের ফর্মে আপনার নাম, পেশা, অফিস সময় বা সাধারণ উত্তর সেভ করলে Auto-Chat এবং Voice AI সেগুলো ব্যবহার করতে পারবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
                        )
                    }
                }
            } else {
                items(personalData, key = { "pd_${it.id}" }) { entry ->
                    PersonalDataItemCard(
                        item = entry,
                        onEdit = { editingPersonalItem = entry },
                        onToggleShare = { onTogglePersonalDataShare(entry) },
                        onDelete = {
                            if (editingPersonalItem?.id == entry.id) editingPersonalItem = null
                            onDeletePersonalData(entry.id)
                        }
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

            // Smart Human-First & Conversation-Ending Control Card
            item {
                StudioCardContainer {
                    Text(
                        text = "স্মার্ট রিপ্লাই ও অ্যান্টি-লুপ কন্ট্রোল",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Toggle 1: Pause AI when owner is actively chatting / typing / opened chat
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
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.cobaltSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = "Pause when owner active",
                                    tint = palette.cobaltAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "আপনি চ্যাটে থাকলে AI থেমে থাকবে",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.inkPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "আপনি মেসেজিং অ্যাপে থাকলে, টাইপ করলে, নোটিফিকেশন খুললে বা নিজে রিপ্লাই দিলে AI সাথে সাথে বিরতি নেবে।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = settings.pauseAutoReplyWhenOwnerActive,
                            onCheckedChange = onTogglePauseWhenOwnerActive,
                            modifier = Modifier.testTag("pause_when_owner_active_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = CobaltBlue
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Toggle 2: Smart Conversation Closing (No-Reply when finished)
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
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.emeraldSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = "Smart conversation closing",
                                    tint = palette.emeraldAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "কথা শেষ হলে নিজে থেকে থামবে (Smart No-Reply)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.inkPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "ধন্যবাদ, আচ্ছা, ঠিক আছে, ওয়েলকাম, আল্লাহ হাফেজ বা কথা শেষ হয়ে গেলে AI বুঝে চুপ থাকবে — আর অযথা লুপ হবে না।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = settings.smartConversationClosingEnabled,
                            onCheckedChange = onToggleSmartConversationClosing,
                            modifier = Modifier.testTag("smart_conversation_closing_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = EmeraldGreen
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Human Grace Period Delay Chips
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = "Reply wait delay",
                            tint = palette.violetAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "AI রিপ্লাই দেওয়ার আগে অপেক্ষা (আপনার সুযোগ)",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.inkPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "মেসেজ এলে AI এত সেকেন্ড অপেক্ষা করবে। এর মধ্যে আপনি চ্যাট খুললে বা নিজে মেসেজ দিলে AI আর রিপ্লাই দেবে না।",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val delayOptions = listOf(
                            5 to "5s (দ্রুত)",
                            15 to "15s (প্রস্তাবিত)",
                            30 to "30s",
                            60 to "60s"
                        )
                        delayOptions.forEach { (sec, label) ->
                            val selected = settings.autoReplyDelaySec == sec
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateAutoReplyDelaySec(sec) },
                                modifier = Modifier.testTag("auto_reply_delay_${sec}s_chip"),
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.violetSoft,
                                    selectedLabelColor = palette.violetAccent
                                )
                            )
                        }
                    }
                }
            }

            // Sensitive Question Guard Toggle Card
            item {
                StudioCardContainer {
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
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.amberSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Shield,
                                    contentDescription = "Sensitive Question Guard",
                                    tint = palette.amberAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Sensitive Question Guard",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.inkPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "টাকা, OTP, পাসওয়ার্ড, ঠিকানা বা ব্যক্তিগত স্পর্শকাতর প্রশ্নে AI নিজে উত্তর না দিয়ে 'আমি জেনে বলছি।' পাঠাবে এবং সাথে সাথে আপনাকে জিজ্ঞেস করবে।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = settings.sensitiveQuestionGuardEnabled,
                            onCheckedChange = onToggleSensitiveGuard,
                            modifier = Modifier.testTag("sensitive_guard_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AmberDark
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Hands-Free Screen-Off Loud Voice Escalation Switch
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
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.roseSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VolumeUp,
                                    contentDescription = "Hands-free loud voice ask",
                                    tint = palette.roseAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "স্ক্রিন অফ লাইভ ভয়েস পরামর্শ ও দূর থেকে রিপ্লাই",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = palette.inkPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "গুরুত্বপূর্ণ মেসেজ এলে জোরে পড়ে শোনাবে — আপনি আগের মেসেজ জানতে চাইতে, AI-এর মতামত নিতে বা সরাসরি উত্তর বলে রিপ্লাই পাঠাতে পারবেন। দূরে থাকলে ১ বার অপেক্ষার মেসেজ দিয়ে থামবে।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = settings.handsFreeVoiceEscalationEnabled,
                            onCheckedChange = onToggleHandsFreeVoiceEscalation,
                            modifier = Modifier.testTag("hands_free_voice_escalation_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = RosePink
                            )
                        )
                    }
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
    onCancelPendingReply: () -> Unit,
    onTemplateReply: (String) -> Unit,
    onSaveGeneratedImage: (String) -> Unit,
    onShareGeneratedImage: (String) -> Unit
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

        // Smart AI Status Banner: Waiting (with Cancel button), Smart No-Reply (Conversation Ended), or Owner Active
        when (record.aiStatus) {
            "WAITING" -> {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = palette.amberSoft,
                    border = BorderStroke(1.dp, palette.amberAccent.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "⏳ ${record.aiStatusNote ?: "অপেক্ষা করছে..."}",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.amberAccent,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onCancelPendingReply)
                                .testTag("cancel_pending_ai_${record.id}"),
                            shape = RoundedCornerShape(10.dp),
                            color = palette.card,
                            border = BorderStroke(1.dp, palette.amberAccent)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Cancel AI reply",
                                    tint = palette.amberAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "আমি নিজে দেব",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.amberAccent,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
            "SKIPPED_ENDED" -> {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = palette.surfaceSubtle,
                    border = BorderStroke(1.dp, palette.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "✓ AI চুপ ছিল: ${record.aiStatusNote ?: "কথা শেষ হয়েছে — বাড়তি রিপ্লাই প্রয়োজন নেই"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            "SKIPPED_OWNER_ACTIVE" -> {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = palette.cobaltSoft,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "👤 ${record.aiStatusNote ?: "আপনি নিজে চ্যাটে আছেন — AI বিরতিতে"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.cobaltAccent,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
            "SKIPPED_MODERATION" -> {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = palette.surfaceSubtle,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "🛡️ ${record.aiStatusNote ?: "এড়িয়ে যাওয়া হয়েছে"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkMuted,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

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

        if (!record.ownerResolvedReply.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = palette.cobaltSoft,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "✓ আপনার উত্তর পাঠানো হয়েছে: \"${record.ownerResolvedReply}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.cobaltAccent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        val generatedPath = record.generatedImagePath
        if (!generatedPath.isNullOrBlank()) {
            val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = generatedPath) {
                value = withContext(Dispatchers.IO) {
                    GeneratedImageHelper.decodeSampledBitmap(generatedPath, 960)?.asImageBitmap()
                }
            }
            if (bitmap != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Image(
                    bitmap = bitmap!!,
                    contentDescription = "AI Generated Image for ${record.senderName}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 260.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.surfaceSubtle)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSaveGeneratedImage(generatedPath) },
                        modifier = Modifier
                            .weight(0.45f)
                            .height(48.dp)
                            .testTag("autochat_save_image_${record.id}"),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, palette.outline),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Save generated image",
                            tint = palette.emeraldAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("সেভ", color = palette.inkPrimary)
                    }
                    Button(
                        onClick = { onShareGeneratedImage(generatedPath) },
                        modifier = Modifier
                            .weight(0.55f)
                            .height(48.dp)
                            .testTag("autochat_share_image_${record.id}"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RoyalViolet,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Share image to ${record.app.displayName}",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("${record.app.displayName}-এ শেয়ার", maxLines = 1)
                    }
                }
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
private fun SensitiveEscalationCard(
    record: IncomingMessageRecord,
    onOpenChat: () -> Unit,
    onSendOwnerAnswer: (String) -> Unit
) {
    val palette = StudioTheme.colors
    var draftAnswer by rememberSaveable(record.id) { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = palette.amberSoft.copy(alpha = 0.55f),
        border = BorderStroke(1.5.dp, palette.amberAccent)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
                        color = palette.inkPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = record.formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkSecondary
                )
            }

            if (!record.sensitivityReason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "কারণ: ${record.sensitivityReason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.amberAccent,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "\"${record.messageText}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkPrimary,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = palette.emeraldSoft,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "✓ স্বয়ংক্রিয়ভাবে পাঠানো হয়েছে: \"${record.autoRepliedText ?: "আমি জেনে বলছি।"}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.emeraldAccent,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = record.ownerQuestion ?: "এখন আপনি সঠিক উত্তর লিখুন — LivePilot সাথে সাথে পাঠিয়ে দেবে:",
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = draftAnswer,
                onValueChange = { draftAnswer = it },
                placeholder = { Text("${record.senderName}-কে পাঠানোর উত্তর লিখুন...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sensitive_answer_input_${record.id}"),
                shape = RoundedCornerShape(14.dp),
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = palette.card,
                    unfocusedContainerColor = palette.card,
                    focusedBorderColor = palette.amberAccent,
                    unfocusedBorderColor = palette.outline
                )
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenChat,
                    modifier = Modifier
                        .weight(0.42f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, palette.outline)
                ) {
                    Text("Open Chat", color = palette.inkPrimary)
                }
                Button(
                    onClick = {
                        val toSend = draftAnswer.trim()
                        if (toSend.isNotEmpty()) {
                            draftAnswer = ""
                            onSendOwnerAnswer(toSend)
                        }
                    },
                    enabled = draftAnswer.isNotBlank(),
                    modifier = Modifier
                        .weight(0.58f)
                        .height(48.dp)
                        .testTag("sensitive_answer_send_${record.id}"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AmberDark,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send owner answer",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("উত্তর পাঠান")
                }
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
