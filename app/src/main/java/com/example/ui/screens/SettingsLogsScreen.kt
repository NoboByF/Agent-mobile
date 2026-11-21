package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.DiagnosticLogEntry
import com.example.data.DiscoveredModel
import com.example.data.LogLevel
import com.example.data.PilotSettings
import com.example.data.SafetyGuardrails
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.StudioCardContainer
import com.example.ui.theme.AmberDark
import com.example.ui.theme.AmberSoft
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.CobaltDark
import com.example.ui.theme.CobaltSoft
import com.example.ui.theme.ConsoleDarkBg
import com.example.ui.theme.ConsoleTextMuted
import com.example.ui.theme.ConsoleTextPrimary
import com.example.ui.theme.CyanTeal
import com.example.ui.theme.EmeraldDark
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.EmeraldSoft
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RedSoft
import com.example.ui.theme.RosePink
import com.example.ui.theme.RoyalViolet
import com.example.ui.theme.SignalRed
import com.example.ui.theme.SignalRedDark
import com.example.ui.theme.StudioOutline
import com.example.ui.theme.StudioSurfaceSubtle
import com.example.ui.theme.VioletSoft
import com.example.ui.theme.WarmAmber

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsLogsScreen(
    settings: PilotSettings,
    discoveredModels: List<DiscoveredModel>,
    isFetchingModels: Boolean,
    modelFetchStatus: String?,
    logs: List<DiagnosticLogEntry>,
    onUpdateApiKey: (String) -> Unit,
    onUpdateLiveModel: (String) -> Unit,
    onUpdateTextModel: (String) -> Unit,
    onFetchModels: () -> Unit,
    onUpdateVoice: (String) -> Unit,
    onUpdateSpeakerphone: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onExportLogsText: () -> String
) {
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val liveDiscovered = discoveredModels.filter { it.isLiveCapable }
    val textDiscovered = discoveredModels.filter { it.supportsGenerateContent }

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
                        text = "Settings & Logs",
                        style = MaterialTheme.typography.headlineMedium,
                        color = InkPrimary
                    )
                    Text(
                        text = "API Key, Live/Text Models, Voice & Protocol Diagnostics",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary
                    )
                }
                StatusPillBadge(
                    label = if (settings.apiKey.isNotBlank()) "KEY SAVED" else "NO KEY",
                    accentColor = if (settings.apiKey.isNotBlank()) EmeraldDark else AmberDark,
                    containerColor = if (settings.apiKey.isNotBlank()) EmeraldSoft else AmberSoft
                )
            }
        }

        // 1. Secure API Key Card (SharedPreferences, zero hardcoded key)
        item {
            StudioCardContainer {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Key,
                        contentDescription = "Gemini API Key",
                        tint = CobaltBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Gemini API Key",
                        style = MaterialTheme.typography.titleMedium,
                        color = InkPrimary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Stored locally in SharedPreferences on this device. Never hardcoded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = onUpdateApiKey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_api_key_input"),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("Paste AIza... Gemini API key", color = InkMuted) },
                    visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(
                            onClick = { apiKeyVisible = !apiKeyVisible },
                            modifier = Modifier.testTag("toggle_api_key_visibility")
                        ) {
                            Icon(
                                imageVector = if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (apiKeyVisible) "Hide key" else "Show key",
                                tint = InkSecondary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = StudioSurfaceSubtle,
                        unfocusedContainerColor = StudioSurfaceSubtle,
                        focusedBorderColor = CobaltBlue,
                        unfocusedBorderColor = StudioOutline
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Fetch Models Button
                Button(
                    onClick = onFetchModels,
                    enabled = !isFetchingModels,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("fetch_models_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CobaltBlue,
                        contentColor = Color.White
                    )
                ) {
                    if (isFetchingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fetching v1beta Models...")
                    } else {
                        Icon(
                            imageVector = Icons.Filled.CloudDownload,
                            contentDescription = "Fetch available models",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fetch Models from Gemini API")
                    }
                }

                if (!modelFetchStatus.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = modelFetchStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = CobaltDark
                    )
                }
            }
        }

        // 2. Live Voice Model Selector (Presets + Custom + Auto-Discovered)
        item {
            StudioCardContainer {
                Text(
                    text = "Live API Voice Model (WebSocket)",
                    style = MaterialTheme.typography.titleMedium,
                    color = InkPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PilotSettings.LIVE_MODEL_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = settings.liveModel.equals(preset, ignoreCase = true),
                            onClick = { onUpdateLiveModel(preset) },
                            label = { Text(preset, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CobaltSoft,
                                selectedLabelColor = CobaltDark
                            )
                        )
                    }
                }

                if (liveDiscovered.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Discovered Live / Native-Audio Models (tap to apply):",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldDark
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        liveDiscovered.forEach { model ->
                            FilterChip(
                                selected = settings.liveModel.equals(model.id, ignoreCase = true),
                                onClick = { onUpdateLiveModel(model.id) },
                                label = { Text(model.id, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EmeraldSoft,
                                    selectedLabelColor = EmeraldDark
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.liveModel,
                    onValueChange = onUpdateLiveModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_live_model_input"),
                    singleLine = true,
                    label = { Text("Custom Live Model ID") },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = StudioSurfaceSubtle,
                        unfocusedContainerColor = StudioSurfaceSubtle,
                        focusedBorderColor = CobaltBlue,
                        unfocusedBorderColor = StudioOutline
                    )
                )
            }
        }

        // 3. Customizable Text / Ask AI / Auto-Reply Model Selector (Safety Check #2)
        item {
            StudioCardContainer {
                Text(
                    text = "Text / Ask AI / Auto-Reply Model",
                    style = MaterialTheme.typography.titleMedium,
                    color = InkPrimary
                )
                Text(
                    text = "Used by Ask AI Search-grounded chat and Full Auto-Reply. Customize or tap any fetched model if a preset isn't enabled on your API key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PilotSettings.TEXT_MODEL_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = settings.textModel.equals(preset, ignoreCase = true),
                            onClick = { onUpdateTextModel(preset) },
                            label = { Text(preset, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VioletSoft,
                                selectedLabelColor = RoyalViolet
                            )
                        )
                    }
                }

                if (textDiscovered.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Discovered Text / Chat Models (tap to apply):",
                        style = MaterialTheme.typography.labelMedium,
                        color = RoyalViolet
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        textDiscovered.take(14).forEach { model ->
                            FilterChip(
                                selected = settings.textModel.equals(model.id, ignoreCase = true),
                                onClick = { onUpdateTextModel(model.id) },
                                label = { Text(model.id, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = VioletSoft,
                                    selectedLabelColor = RoyalViolet
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.textModel,
                    onValueChange = onUpdateTextModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_text_model_input"),
                    singleLine = true,
                    label = { Text("Custom Text / Auto-Reply Model ID") },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = StudioSurfaceSubtle,
                        unfocusedContainerColor = StudioSurfaceSubtle,
                        focusedBorderColor = RoyalViolet,
                        unfocusedBorderColor = StudioOutline
                    )
                )
            }
        }

        // 4. Voice Preset & Speakerphone Audio Routing
        item {
            StudioCardContainer {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.RecordVoiceOver,
                        contentDescription = "Voice selector",
                        tint = RoyalViolet,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Live Voice & Audio Output",
                        style = MaterialTheme.typography.titleMedium,
                        color = InkPrimary
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    PilotSettings.VOICE_PRESETS.forEach { voice ->
                        FilterChip(
                            selected = settings.voiceName.equals(voice, ignoreCase = true),
                            onClick = { onUpdateVoice(voice) },
                            modifier = Modifier.testTag("voice_chip_${voice.lowercase()}"),
                            label = {
                                Text(
                                    text = if (voice == PilotSettings.DEFAULT_VOICE) "$voice (Default)" else voice,
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

                Spacer(modifier = Modifier.height(12.dp))

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
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Speakerphone toggle",
                            tint = CobaltBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Speakerphone Routing",
                                style = MaterialTheme.typography.titleSmall,
                                color = InkPrimary
                            )
                            Text(
                                text = "Hardware AEC + NoiseSuppressor prevent self-echo",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        }
                    }
                    Switch(
                        checked = settings.speakerphoneOn,
                        onCheckedChange = onUpdateSpeakerphone,
                        modifier = Modifier.testTag("speakerphone_switch"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = CobaltBlue
                        )
                    )
                }
            }
        }

        // 5. Safety Guardrails Shield Card
        item {
            StudioCardContainer {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = "Blocked financial apps",
                        tint = SignalRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Hardcoded Safety Guardrails",
                        style = MaterialTheme.typography.titleMedium,
                        color = InkPrimary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Screen reading, vision capture, clicks, drags, and typing are permanently blocked inside:",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SafetyGuardrails.PROTECTED_DISPLAY_LABELS.forEach { label ->
                        StatusPillBadge(
                            label = label,
                            accentColor = SignalRedDark,
                            containerColor = RedSoft,
                            leadingDot = false
                        )
                    }
                }
            }
        }

        // 6. Live WebSocket & Tool Diagnostics Log Console
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = ConsoleDarkBg,
                border = BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Live Protocol Diagnostics",
                                style = MaterialTheme.typography.titleMedium,
                                color = ConsoleTextPrimary
                            )
                            Text(
                                text = "${logs.size} events (onOpen, setupComplete, toolCall, failures)",
                                style = MaterialTheme.typography.labelSmall,
                                color = ConsoleTextMuted
                            )
                        }
                        Row {
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(onExportLogsText())) },
                                modifier = Modifier.testTag("copy_logs_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "Copy diagnostic logs",
                                    tint = ConsoleTextMuted
                                )
                            }
                            IconButton(
                                onClick = onClearLogs,
                                modifier = Modifier.testTag("clear_logs_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteSweep,
                                    contentDescription = "Clear diagnostic logs",
                                    tint = ConsoleTextMuted
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (logs.isEmpty()) {
                        Text(
                            text = "No events logged yet. Tap 'Test Live API Connection' on Home or start a Live Call to watch real-time protocol telemetry.",
                            style = MaterialTheme.typography.labelSmall,
                            color = ConsoleTextMuted
                        )
                    } else {
                        logs.takeLast(45).reversed().forEach { entry ->
                            val badgeColor = when (entry.level) {
                                LogLevel.WS -> CyanTeal
                                LogLevel.TOOL -> WarmAmber
                                LogLevel.AUDIO -> EmeraldGreen
                                LogLevel.SAFETY -> RosePink
                                LogLevel.ERROR -> SignalRed
                                LogLevel.CAPTION -> RoyalViolet
                                LogLevel.INFO -> Color(0xFF93C5FD)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "${entry.timestamp} ",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ConsoleTextMuted
                                )
                                Text(
                                    text = "[${entry.tag}] ",
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = entry.message,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ConsoleTextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
