package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RemoveRedEye
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.AppThemeMode
import com.example.data.DiagnosticLogEntry
import com.example.data.DiscoveredModel
import com.example.data.LogLevel
import com.example.data.PilotSettings
import com.example.data.SafetyGuardrails
import com.example.ui.components.StatusPillBadge
import com.example.ui.components.StudioCardContainer
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.ConsoleDarkBg
import com.example.ui.theme.ConsoleTextMuted
import com.example.ui.theme.ConsoleTextPrimary
import com.example.ui.theme.CyanTeal
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RosePink
import com.example.ui.theme.RoyalViolet
import com.example.ui.theme.SignalRed
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.WarmAmber

private enum class SettingsSubTab(val label: String) {
    API_MODELS("API & Models"),
    VOICE_PILOT("Voice & Pilot"),
    CONSOLE("Live Console")
}

private enum class LogFilter(val label: String, val level: LogLevel?) {
    ALL("All", null),
    WS("WS", LogLevel.WS),
    TOOL("Tools", LogLevel.TOOL),
    AUDIO("Audio", LogLevel.AUDIO),
    SAFETY("Safety", LogLevel.SAFETY),
    ERROR("Errors", LogLevel.ERROR)
}

private val VOICE_PERSONA_PRESETS = listOf(
    "Reply briefly in friendly Bengali unless I speak English.",
    "Speak very concisely in 1 sentence and focus on fast UI execution.",
    "Guide me step-by-step for CapCut & social media apps."
)

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
    onUpdateAutoVisionDefault: (Boolean) -> Unit,
    onUpdateAutoVisionInterval: (Int) -> Unit,
    onUpdateShowTouchVisualizer: (Boolean) -> Unit,
    onUpdateShowFloatingCaptions: (Boolean) -> Unit,
    onUpdateThemeMode: (AppThemeMode) -> Unit,
    onUpdateCustomVoiceInstructions: (String) -> Unit,
    onClearLogs: () -> Unit,
    onExportLogsText: () -> String
) {
    val palette = StudioTheme.colors
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var activeSubTab by rememberSaveable { mutableStateOf(SettingsSubTab.API_MODELS) }
    var logFilter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    val clipboard = LocalClipboardManager.current

    val liveDiscovered = discoveredModels.filter { it.isLiveCapable }
    val textDiscovered = discoveredModels.filter { it.supportsGenerateContent }
    val filteredLogs = if (logFilter.level == null) {
        logs
    } else {
        logs.filter { it.level == logFilter.level }
    }

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
                        text = "Settings & Logs",
                        style = MaterialTheme.typography.headlineMedium,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "API Key, Models, Theme, HUD & Diagnostics",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                }
                StatusPillBadge(
                    label = if (settings.apiKey.isNotBlank()) "KEY SAVED" else "NO KEY",
                    accentColor = if (settings.apiKey.isNotBlank()) palette.emeraldAccent else palette.amberAccent,
                    containerColor = if (settings.apiKey.isNotBlank()) palette.emeraldSoft else palette.amberSoft
                )
            }
        }

        // 3 Segmented Sub-Tabs
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsSubTab.entries.forEach { tab ->
                    val selected = tab == activeSubTab
                    val accent = when (tab) {
                        SettingsSubTab.API_MODELS -> palette.cobaltAccent
                        SettingsSubTab.VOICE_PILOT -> palette.violetAccent
                        SettingsSubTab.CONSOLE -> palette.emeraldAccent
                    }
                    val soft = when (tab) {
                        SettingsSubTab.API_MODELS -> palette.cobaltSoft
                        SettingsSubTab.VOICE_PILOT -> palette.violetSoft
                        SettingsSubTab.CONSOLE -> palette.emeraldSoft
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { activeSubTab = tab }
                            .testTag("settings_subtab_${tab.name.lowercase()}"),
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) soft else palette.surfaceSubtle,
                        border = BorderStroke(1.dp, if (selected) accent else palette.outline)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 6.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (tab == SettingsSubTab.CONSOLE && logs.isNotEmpty()) {
                                    "Console (${logs.size})"
                                } else {
                                    tab.label
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) accent else palette.inkSecondary,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        when (activeSubTab) {
            SettingsSubTab.API_MODELS -> {
                // 1. Secure API Key Card
                item {
                    StudioCardContainer {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Key,
                                contentDescription = "Gemini API Key",
                                tint = palette.cobaltAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Gemini API Key",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.inkPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Stored locally in SharedPreferences on this device. Never hardcoded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
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
                            placeholder = { Text("Paste AIza... Gemini API key", color = palette.inkMuted) },
                            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(
                                    onClick = { apiKeyVisible = !apiKeyVisible },
                                    modifier = Modifier.testTag("toggle_api_key_visibility")
                                ) {
                                    Icon(
                                        imageVector = if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (apiKeyVisible) "Hide key" else "Show key",
                                        tint = palette.inkSecondary
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.surfaceSubtle,
                                unfocusedContainerColor = palette.surfaceSubtle,
                                focusedBorderColor = palette.cobaltAccent,
                                unfocusedBorderColor = palette.outline
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

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
                                color = palette.cobaltAccent
                            )
                        }
                    }
                }

                // 2. Live Voice Model Selector
                item {
                    StudioCardContainer {
                        Text(
                            text = "Live API Voice Model (WebSocket)",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary
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
                                        selectedContainerColor = palette.cobaltSoft,
                                        selectedLabelColor = palette.cobaltAccent
                                    )
                                )
                            }
                        }

                        if (liveDiscovered.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Discovered Live / Native-Audio Models:",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.emeraldAccent
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
                                            selectedContainerColor = palette.emeraldSoft,
                                            selectedLabelColor = palette.emeraldAccent
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
                                focusedContainerColor = palette.surfaceSubtle,
                                unfocusedContainerColor = palette.surfaceSubtle,
                                focusedBorderColor = palette.cobaltAccent,
                                unfocusedBorderColor = palette.outline
                            )
                        )
                    }
                }

                // 3. Text / Ask AI / Auto-Reply Model Selector
                item {
                    StudioCardContainer {
                        Text(
                            text = "Text / Ask AI / Live Web Search Model",
                            style = MaterialTheme.typography.titleMedium,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = "Used by Ask AI, Live Call Google Search bridge, and Auto-Reply.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
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
                                        selectedContainerColor = palette.violetSoft,
                                        selectedLabelColor = palette.violetAccent
                                    )
                                )
                            }
                        }

                        if (textDiscovered.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Discovered Text / Chat Models:",
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.violetAccent
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
                                            selectedContainerColor = palette.violetSoft,
                                            selectedLabelColor = palette.violetAccent
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
                                focusedContainerColor = palette.surfaceSubtle,
                                unfocusedContainerColor = palette.surfaceSubtle,
                                focusedBorderColor = palette.violetAccent,
                                unfocusedBorderColor = palette.outline
                            )
                        )
                    }
                }
            }

            SettingsSubTab.VOICE_PILOT -> {
                // Appearance / Theme Switcher Card
                item {
                    StudioCardContainer {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Palette,
                                contentDescription = "Theme appearance",
                                tint = palette.cobaltAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Studio Appearance",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.inkPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                AppThemeMode.SYSTEM to "System",
                                AppThemeMode.LIGHT to "Studio White",
                                AppThemeMode.DARK to "Midnight Pro"
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = settings.themeMode == mode,
                                    onClick = { onUpdateThemeMode(mode) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = palette.cobaltSoft,
                                        selectedLabelColor = palette.cobaltAccent
                                    )
                                )
                            }
                        }
                    }
                }

                // Voice Preset & Speakerphone Audio Routing
                item {
                    StudioCardContainer {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.RecordVoiceOver,
                                contentDescription = "Voice selector",
                                tint = palette.violetAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Live Voice & Audio Output",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.inkPrimary
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
                                        selectedContainerColor = palette.violetSoft,
                                        selectedLabelColor = palette.violetAccent
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
                                    tint = palette.cobaltAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Speakerphone Routing",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = palette.inkPrimary
                                    )
                                    Text(
                                        text = "Hardware AEC + NoiseSuppressor prevent self-echo",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = palette.inkSecondary
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

                // Visual HUD, Auto-Vision & Custom Live Voice Instructions
                item {
                    StudioCardContainer {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.RemoveRedEye,
                                contentDescription = "Vision and HUD settings",
                                tint = palette.emeraldAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Floating HUD, Touch Ripple & Vision",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.inkPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        ToggleSettingRow(
                            title = "On-Screen AI Touch Ripple",
                            subtitle = "Flashes a glowing ring wherever LivePilot taps or drags",
                            checked = settings.showTouchVisualizer,
                            tag = "touch_visualizer_switch",
                            trackColor = CobaltBlue,
                            onCheckedChange = onUpdateShowTouchVisualizer
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        ToggleSettingRow(
                            title = "Floating Live Subtitles",
                            subtitle = "Shows a sleek 1-line subtitle strip below the floating capsule",
                            checked = settings.showFloatingCaptions,
                            tag = "floating_captions_switch",
                            trackColor = RoyalViolet,
                            onCheckedChange = onUpdateShowFloatingCaptions
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        ToggleSettingRow(
                            title = "Auto-Start Screen Vision on Call",
                            subtitle = "Streams screen frames periodically (auto-pauses on banking apps)",
                            checked = settings.autoVisionDefaultOn,
                            tag = "auto_vision_default_switch",
                            trackColor = EmeraldGreen,
                            onCheckedChange = onUpdateAutoVisionDefault
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Auto-Vision Capture Interval",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.inkPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(2, 4, 6, 8).forEach { sec ->
                                FilterChip(
                                    selected = settings.autoVisionIntervalSec == sec,
                                    onClick = { onUpdateAutoVisionInterval(sec) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("${sec}s", maxLines = 1) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = palette.emeraldSoft,
                                        selectedLabelColor = palette.emeraldAccent
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Custom Live Voice Instructions",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.inkPrimary
                        )
                        Text(
                            text = "Appended to the Live AI system prompt when starting your next call.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(VOICE_PERSONA_PRESETS) { preset ->
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable { onUpdateCustomVoiceInstructions(preset) },
                                    shape = RoundedCornerShape(50),
                                    color = palette.violetSoft
                                ) {
                                    Text(
                                        text = preset.take(32) + if (preset.length > 32) "..." else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.violetAccent,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = settings.customVoiceInstructions,
                            onValueChange = onUpdateCustomVoiceInstructions,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_custom_voice_instructions_input"),
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(14.dp),
                            placeholder = { Text("e.g. Call me Boss and always speak short Bengali") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.surfaceSubtle,
                                unfocusedContainerColor = palette.surfaceSubtle,
                                focusedBorderColor = palette.violetAccent,
                                unfocusedBorderColor = palette.outline
                            )
                        )
                    }
                }

                // Safety Guardrails Shield Card
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
                                color = palette.inkPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Screen reading, vision capture, clicks, drags, and typing are permanently blocked inside:",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SafetyGuardrails.PROTECTED_DISPLAY_LABELS.forEach { label ->
                                StatusPillBadge(
                                    label = label,
                                    accentColor = palette.redAccent,
                                    containerColor = palette.redSoft,
                                    leadingDot = false
                                )
                            }
                        }
                    }
                }
            }

            SettingsSubTab.CONSOLE -> {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(LogFilter.entries) { filter ->
                            val selected = filter == logFilter
                            FilterChip(
                                selected = selected,
                                onClick = { logFilter = filter },
                                label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.cobaltSoft,
                                    selectedLabelColor = palette.cobaltAccent
                                )
                            )
                        }
                    }
                }

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
                                        text = "${filteredLogs.size} shown of ${logs.size} events",
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

                            if (filteredLogs.isEmpty()) {
                                Text(
                                    text = if (logs.isEmpty()) {
                                        "No events logged yet. Tap 'Test Live API Connection' on Home or start a Live Call to watch real-time telemetry."
                                    } else {
                                        "No events match '${logFilter.label}' filter."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ConsoleTextMuted
                                )
                            } else {
                                filteredLogs.takeLast(60).reversed().forEach { entry ->
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
    }
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    tag: String,
    trackColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = StudioTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.inkPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkSecondary
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = trackColor
            )
        )
    }
}
