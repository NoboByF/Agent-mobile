package com.example.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AskAiMessage
import com.example.data.PilotSettings
import com.example.ui.AskAiUiState
import com.example.ui.components.StatusPillBadge
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
import com.example.ui.theme.RoyalViolet
import com.example.ui.theme.SignalRedDark
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioOutline
import com.example.ui.theme.StudioSurfaceSubtle
import com.example.ui.theme.VioletSoft

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskAiScreen(
    settings: PilotSettings,
    uiState: AskAiUiState,
    onSelectTextModel: (String) -> Unit,
    onToggleSearchGrounding: (Boolean) -> Unit,
    onSendPrompt: (String) -> Unit,
    onClearThread: () -> Unit
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val selectableModels = rememberSelectableModels(settings.textModel)

    LaunchedEffect(uiState.messages.size, uiState.isGenerating) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 92.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Ask AI",
                    style = MaterialTheme.typography.headlineMedium,
                    color = InkPrimary
                )
                Text(
                    text = "Multi-turn Gemini chat + Google Search Grounding",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
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
                        tint = InkSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Model Tier & Google Search Grounding Bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = StudioCard,
            border = BorderStroke(1.dp, StudioOutline)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Public,
                            contentDescription = "Google Search Grounding",
                            tint = if (uiState.searchGroundingEnabled) EmeraldDark else InkMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Google Search Grounding",
                                style = MaterialTheme.typography.titleSmall,
                                color = InkPrimary
                            )
                            Text(
                                text = "Live web data & clickable citations",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
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

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(selectableModels) { modelId ->
                        val selected = modelId.equals(settings.textModel, ignoreCase = true)
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectTextModel(modelId) },
                            label = {
                                Text(
                                    text = modelChipLabel(modelId),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VioletSoft,
                                selectedLabelColor = RoyalViolet
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
                color = RedSoft
            ) {
                Text(
                    text = uiState.errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = SignalRedDark,
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
                        .background(VioletSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = "Ask AI",
                        tint = RoyalViolet,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Ask Anything with Live Search",
                    style = MaterialTheme.typography.titleLarge,
                    color = InkPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Ask in Bengali or English. Change or customize the text model anytime in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                val starters = listOf(
                    "আজকের ঢাকার আবহাওয়া ও বড় খবর কী?",
                    "Best CapCut workflow for smooth mobile reels",
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
                                .clickable { onSendPrompt(suggestion) },
                            shape = RoundedCornerShape(14.dp),
                            color = StudioCard,
                            border = BorderStroke(1.dp, StudioOutline)
                        ) {
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkPrimary,
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
                        onCopy = { clipboard.setText(AnnotatedString(msg.text)) },
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
                                color = RoyalViolet
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.searchGroundingEnabled) {
                                    "Searching Google & composing reply..."
                                } else {
                                    "Thinking with ${settings.textModel}..."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Composer Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Ask in Bengali or English...", color = InkMuted) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("ask_ai_input"),
                shape = RoundedCornerShape(18.dp),
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = StudioCard,
                    unfocusedContainerColor = StudioCard,
                    focusedBorderColor = RoyalViolet,
                    unfocusedBorderColor = StudioOutline
                )
            )
            Surface(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .clickable(enabled = inputText.isNotBlank() && !uiState.isGenerating) {
                        val toSend = inputText
                        inputText = ""
                        onSendPrompt(toSend)
                    }
                    .testTag("ask_ai_send_button"),
                color = if (inputText.isNotBlank() && !uiState.isGenerating) RoyalViolet else StudioSurfaceSubtle
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send prompt",
                        tint = if (inputText.isNotBlank() && !uiState.isGenerating) Color.White else InkMuted,
                        modifier = Modifier.size(22.dp)
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
    onCopy: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
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
            color = if (message.isUser) CobaltBlue else StudioCard,
            border = if (message.isUser) null else BorderStroke(1.dp, StudioOutline)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isUser) Color.White else InkPrimary
                )

                if (message.citations.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Sources",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldDark
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        message.citations.forEach { citation ->
                            Surface(
                                modifier = Modifier.clickable { onOpenUrl(citation.url) },
                                shape = RoundedCornerShape(50),
                                color = EmeraldSoft,
                                border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = citation.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
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
                        color = if (message.isUser) Color.White.copy(alpha = 0.78f) else InkMuted
                    )
                    if (!message.isUser) {
                        IconButton(
                            onClick = onCopy,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy reply",
                                tint = InkMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun rememberSelectableModels(currentTextModel: String): List<String> {
    val base = PilotSettings.TEXT_MODEL_PRESETS
    return if (base.any { it.equals(currentTextModel, ignoreCase = true) } || currentTextModel.isBlank()) {
        base
    } else {
        listOf(currentTextModel) + base
    }
}

private fun modelChipLabel(modelId: String): String = when (modelId) {
    "gemini-4-hydrogen" -> "Hydrogen (Search)"
    "gemini-4-carbon" -> "Carbon (Deep)"
    "gemini-3.1-flash-lite" -> "Flash-Lite (Fast)"
    else -> modelId
}
