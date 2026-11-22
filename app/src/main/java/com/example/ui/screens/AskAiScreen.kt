package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.GeneratedImageHelper
import com.example.ai.MediaAttachmentHelper
import com.example.data.AskAiMessage
import com.example.data.AttachmentKind
import com.example.data.ChatAttachmentMeta
import com.example.data.PilotSettings
import com.example.ui.AskAiUiState
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.formatAiRichText
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.RoyalViolet
import com.example.ui.theme.StudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AskAiScreen(
    settings: PilotSettings,
    uiState: AskAiUiState,
    isCallActive: Boolean,
    onSelectTextModel: (String) -> Unit,
    onToggleSearchGrounding: (Boolean) -> Unit,
    onToggleImageMode: (Boolean) -> Unit,
    onAddAttachments: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
    onSendToLiveCall: (String) -> Unit,
    onSaveGeneratedImage: (String) -> Unit,
    onShareGeneratedImage: (String, String) -> Unit,
    onClearThread: () -> Unit
) {
    val palette = StudioTheme.colors
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Zero-permission Android Photo Picker (Photos & Videos) — Play Policy compliant
    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        if (uris.isNotEmpty()) onAddAttachments(uris)
    }

    // Zero-permission Storage Access Framework picker (PDF, Audio, Code, Text, Any File)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onAddAttachments(uris)
    }

    val selectableModels = rememberSelectableModels(settings.textModel)
    val canSend = (inputText.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) && !uiState.isGenerating

    LaunchedEffect(uiState.messages.size, uiState.isGenerating) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val isKeyboardOpen = imeBottom > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = if (isKeyboardOpen) 10.dp else 92.dp
            )
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Ask AI",
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.inkPrimary
                )
                Text(
                    text = "ছবি তৈরি (AI Image), ফাইল বিশ্লেষণ ও লাইভ গুগল সার্চ",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isCallActive) {
                    StatusPillBadge(
                        label = "CALL LIVE",
                        accentColor = palette.emeraldAccent,
                        containerColor = palette.emeraldSoft
                    )
                }
                if (uiState.messages.isNotEmpty()) {
                    IconButton(
                        onClick = onClearThread,
                        modifier = Modifier.testTag("ask_ai_clear_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = "Clear conversation",
                            tint = palette.inkSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Model Tier & Google Search Grounding Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = palette.card,
            border = BorderStroke(1.dp, palette.outline)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
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
                            imageVector = Icons.Filled.Public,
                            contentDescription = "Google Search Grounding",
                            tint = if (uiState.searchGroundingEnabled) palette.emeraldAccent else palette.inkMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Google Search Grounding",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.inkPrimary
                            )
                            Text(
                                text = "Live web data & clickable source links",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary
                            )
                        }
                    }
                    Switch(
                        checked = uiState.searchGroundingEnabled,
                        onCheckedChange = onToggleSearchGrounding,
                        modifier = Modifier.testTag("search_grounding_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = EmeraldGreen
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = uiState.imageModeEnabled,
                            onClick = { onToggleImageMode(!uiState.imageModeEnabled) },
                            modifier = Modifier.testTag("ask_ai_image_mode_chip"),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Brush,
                                    contentDescription = "AI Image Mode",
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = if (uiState.imageModeEnabled) "ছবি তৈরি: চালু" else "ছবি তৈরি (AI Image)",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.roseSoft,
                                selectedLabelColor = palette.roseAccent,
                                selectedLeadingIconColor = palette.roseAccent
                            )
                        )
                    }
                    items(selectableModels) { modelId ->
                        val selected = !uiState.imageModeEnabled && modelId.equals(settings.textModel, ignoreCase = true)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (uiState.imageModeEnabled) onToggleImageMode(false)
                                onSelectTextModel(modelId)
                            },
                            label = {
                                Text(
                                    text = modelChipLabel(modelId),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.violetSoft,
                                selectedLabelColor = palette.violetAccent
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Error Banner
        if (!uiState.errorText.isNullOrBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = palette.redSoft
            ) {
                Text(
                    text = uiState.errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.redAccent,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Chat Thread or Empty Starter State
        if (uiState.messages.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(palette.violetSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = "Ask AI",
                        tint = palette.violetAccent,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Ask Anything + Upload Files & Media",
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.inkPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "ছবি, ভিডিও, PDF বা যেকোনো ফাইল আপলোড করে প্রশ্ন করুন — অথবা লাইভ গুগল সার্চ ব্যবহার করুন।",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                val starters = listOf(
                    "একটি রঙিন পাখির সুন্দর ইমেজ তৈরি করে দাও",
                    "আজকের ঢাকার আবহাওয়া ও বড় খবর কী?",
                    "Write a polite Bengali auto-reply while I'm driving"
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    starters.forEach { suggestion ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onSendPrompt(suggestion) },
                            shape = RoundedCornerShape(14.dp),
                            color = palette.card,
                            border = BorderStroke(1.dp, palette.outline)
                        ) {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.inkPrimary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(uiState.messages, key = { it.id }) { msg ->
                    ChatMessageBubble(
                        message = msg,
                        isCallActive = isCallActive,
                        onCopy = { clipboard.setText(AnnotatedString(msg.text)) },
                        onSendToLiveCall = {
                            onSendToLiveCall("Follow this plan or speak this briefly: ${msg.text.take(400)}")
                        },
                        onSaveGeneratedImage = { path -> onSaveGeneratedImage(path) },
                        onShareGeneratedImage = { path, caption -> onShareGeneratedImage(path, caption) },
                        onOpenUrl = { url ->
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    )
                }

                if (uiState.isGenerating) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = palette.violetAccent
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.attachmentStatusText ?: if (uiState.searchGroundingEnabled) {
                                    "Searching Google & composing reply..."
                                } else {
                                    "Thinking with ${settings.textModel}..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Pending Attachment Preview Strip
        if (uiState.pendingAttachments.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.pendingAttachments, key = { it.id }) { att ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = palette.violetSoft,
                        border = BorderStroke(1.dp, palette.violetAccent.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = attachmentIcon(att.kind),
                                contentDescription = att.kind.name,
                                tint = palette.violetAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.widthIn(max = 150.dp)) {
                                Text(
                                    text = att.displayName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = palette.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val sizeStr = MediaAttachmentHelper.formatFileSize(att.sizeBytes)
                                if (sizeStr.isNotBlank()) {
                                    Text(
                                        text = "${att.kind.name} • $sizeStr",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.inkSecondary
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onRemoveAttachment(att.id) },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("remove_attachment_${att.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove ${att.displayName}",
                                    tint = palette.inkSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Composer Bar with Zero-Permission Photo/Video & File Upload Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !uiState.isGenerating) {
                        mediaPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    }
                    .testTag("ask_ai_pick_media_button"),
                color = palette.surfaceSubtle,
                border = BorderStroke(1.dp, palette.outline)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PermMedia,
                        contentDescription = "Attach photos or videos",
                        tint = palette.violetAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(enabled = !uiState.isGenerating) {
                        documentPickerLauncher.launch(arrayOf("*/*"))
                    }
                    .testTag("ask_ai_pick_file_button"),
                color = palette.surfaceSubtle,
                border = BorderStroke(1.dp, palette.outline)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = "Attach files or documents",
                        tint = palette.cobaltAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        text = when {
                            uiState.imageModeEnabled -> "কী ছবি তৈরি করতে চান লিখুন (যেমন: একটি পাখির ছবি)..."
                            uiState.pendingAttachments.isNotEmpty() -> "ফাইল সম্পর্কে প্রশ্ন লিখুন..."
                            else -> "প্রশ্ন করুন বা ছবি তৈরি করতে লিখুন..."
                        },
                        color = palette.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("ask_ai_input"),
                shape = RoundedCornerShape(18.dp),
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSend) {
                            val toSend = inputText
                            inputText = ""
                            onSendPrompt(toSend)
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = palette.card,
                    unfocusedContainerColor = palette.card,
                    focusedBorderColor = palette.violetAccent,
                    unfocusedBorderColor = palette.outline
                )
            )
            Surface(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canSend) {
                        val toSend = inputText
                        inputText = ""
                        onSendPrompt(toSend)
                    }
                    .testTag("ask_ai_send_button"),
                color = if (canSend) RoyalViolet else palette.surfaceSubtle
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send prompt",
                        tint = if (canSend) Color.White else palette.inkMuted,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageBubble(
    message: AskAiMessage,
    isCallActive: Boolean,
    onCopy: () -> Unit,
    onSendToLiveCall: () -> Unit,
    onSaveGeneratedImage: (String) -> Unit,
    onShareGeneratedImage: (String, String) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val palette = StudioTheme.colors
    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (message.isUser) 0.86f else 0.96f),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (message.isUser) 18.dp else 6.dp,
                bottomEnd = if (message.isUser) 6.dp else 18.dp
            ),
            color = if (message.isUser) CobaltBlue else palette.card,
            border = if (message.isUser) null else BorderStroke(1.dp, palette.outline)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (message.attachments.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        message.attachments.forEach { att ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (message.isUser) Color.White.copy(alpha = 0.18f) else palette.violetSoft
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = attachmentIcon(att.kind),
                                        contentDescription = att.kind.name,
                                        tint = if (message.isUser) Color.White else palette.violetAccent,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = att.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (message.isUser) Color.White else palette.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 140.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (message.isUser) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                } else {
                    val imgPath = message.generatedImagePath
                    if (!imgPath.isNullOrBlank()) {
                        val imageBitmap by produceState<ImageBitmap?>(initialValue = null, key1 = imgPath) {
                            value = withContext(Dispatchers.IO) {
                                GeneratedImageHelper.decodeSampledBitmap(imgPath, 1024)?.asImageBitmap()
                            }
                        }
                        if (imageBitmap != null) {
                            Image(
                                bitmap = imageBitmap!!,
                                contentDescription = "AI Generated Image",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 180.dp, max = 300.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(palette.surfaceSubtle)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onSaveGeneratedImage(imgPath) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("save_generated_image_${message.id}"),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, palette.outline),
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Download,
                                        contentDescription = "Save image to gallery",
                                        tint = palette.emeraldAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "গ্যালারিতে সেভ",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = palette.inkPrimary,
                                        maxLines = 1
                                    )
                                }
                                Button(
                                    onClick = { onShareGeneratedImage(imgPath, message.text) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("share_generated_image_${message.id}"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = RoyalViolet,
                                        contentColor = Color.White
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Share,
                                        contentDescription = "Share generated image",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "শেয়ার",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }

                    Text(
                        text = formatAiRichText(message.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkPrimary
                    )
                }

                if (message.citations.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Verified Sources",
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.emeraldAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        message.citations.forEach { citation ->
                            val domain = extractDomain(citation.url)
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { onOpenUrl(citation.url) },
                                shape = RoundedCornerShape(50),
                                color = palette.emeraldSoft,
                                border = BorderStroke(1.dp, palette.emeraldAccent.copy(alpha = 0.35f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!domain.isNullOrBlank()) {
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = palette.emeraldAccent.copy(alpha = 0.16f)
                                        ) {
                                            Text(
                                                text = domain,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = palette.emeraldAccent,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = citation.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = palette.emeraldAccent,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildString {
                            append(message.timestamp)
                            if (!message.isUser && !message.modelUsed.isNullOrBlank()) {
                                append(" • ")
                                append(message.modelUsed)
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.isUser) Color.White.copy(alpha = 0.78f) else palette.inkMuted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!message.isUser) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isCallActive) {
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable(onClick = onSendToLiveCall)
                                        .testTag("ask_ai_send_to_call_button"),
                                    shape = RoundedCornerShape(50),
                                    color = palette.cobaltSoft,
                                    border = BorderStroke(1.dp, palette.cobaltAccent.copy(alpha = 0.25f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.PhoneInTalk,
                                            contentDescription = "Send to Live Call",
                                            tint = palette.cobaltAccent,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Send to Call",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = palette.cobaltAccent,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = onCopy,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "Copy reply",
                                    tint = palette.inkMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun extractDomain(url: String): String? {
    val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return null
    return host.removePrefix("www.").take(24)
}

private fun rememberSelectableModels(currentTextModel: String): List<String> {
    val base = PilotSettings.TEXT_MODEL_PRESETS
    return if (base.any { it.equals(currentTextModel, ignoreCase = true) } || currentTextModel.isBlank()) {
        base
    } else {
        listOf(currentTextModel) + base
    }
}

private fun attachmentIcon(kind: AttachmentKind): ImageVector = when (kind) {
    AttachmentKind.IMAGE -> Icons.Filled.Image
    AttachmentKind.VIDEO -> Icons.Filled.Videocam
    AttachmentKind.AUDIO -> Icons.Filled.AudioFile
    AttachmentKind.DOCUMENT -> Icons.Filled.PictureAsPdf
    AttachmentKind.TEXT -> Icons.Filled.Description
    AttachmentKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun modelChipLabel(modelId: String): String = when (modelId) {
    "gemini-2.5-flash" -> "2.5 Flash (Balanced)"
    "gemini-2.5-pro" -> "2.5 Pro (Deep)"
    "gemini-2.0-flash" -> "2.0 Flash"
    "gemini-2.5-flash-lite" -> "2.5 Flash-Lite (Fast)"
    else -> modelId
}
