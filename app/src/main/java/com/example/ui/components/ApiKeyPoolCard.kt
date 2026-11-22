package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ApiKeyStatus
import com.example.data.GeminiApiKeyEntry
import com.example.data.PilotSettings
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StudioTheme
import kotlinx.coroutines.delay

@Composable
fun ApiKeyPoolCard(
    settings: PilotSettings,
    isFetchingModels: Boolean,
    modelFetchStatus: String?,
    onAddKeys: (rawInput: String, label: String) -> Unit,
    onSetActiveKey: (String) -> Unit,
    onToggleKeyEnabled: (String) -> Unit,
    onRemoveKey: (String) -> Unit,
    onToggleAutoRotate: (Boolean) -> Unit,
    onResetKeyHealth: () -> Unit,
    onFetchModels: () -> Unit
) {
    val palette = StudioTheme.colors
    var keyInput by rememberSaveable { mutableStateOf("") }
    var labelInput by rememberSaveable { mutableStateOf("") }
    var showKeyInput by rememberSaveable { mutableStateOf(false) }

    // Periodic clock tick when any key is in cooldown so countdown labels update live
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasCooldownKey = settings.apiKeys.any { it.isCoolingDown(nowMs) }
    val hasUnhealthyKey = settings.apiKeys.any { it.isInvalid || it.isCoolingDown(nowMs) }
    LaunchedEffect(hasCooldownKey) {
        if (!hasCooldownKey) return@LaunchedEffect
        while (true) {
            delay(5_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    val totalKeys = settings.apiKeys.size
    val readyCount = settings.healthyKeyCount(nowMs)

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
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = "Gemini API Key Pool",
                    tint = palette.cobaltAccent,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Gemini API Key Pool",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "Add multiple keys — auto-switches when quota runs out",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                }
            }
            StatusPillBadge(
                label = if (totalKeys == 0) "NO KEYS" else "$readyCount/$totalKeys READY",
                accentColor = when {
                    readyCount > 0 -> palette.emeraldAccent
                    totalKeys > 0 -> palette.redAccent
                    else -> palette.amberAccent
                },
                containerColor = when {
                    readyCount > 0 -> palette.emeraldSoft
                    totalKeys > 0 -> palette.redSoft
                    else -> palette.amberSoft
                },
                leadingDot = false
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Auto-Rotation Toggle
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = palette.surfaceSubtle,
            border = BorderStroke(1.dp, palette.outline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-Failover Rotation",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.inkPrimary
                    )
                    Text(
                        text = "Switches automatically on 429 quota or key error (Voice, Search & Auto-Reply)",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = settings.autoRotateApiKeys,
                    onCheckedChange = onToggleAutoRotate,
                    modifier = Modifier.testTag("auto_rotate_keys_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = EmeraldGreen
                    )
                )
            }
        }

        // Saved Keys List
        if (settings.apiKeys.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.apiKeys.forEach { entry ->
                    ApiKeyRowItem(
                        entry = entry,
                        activeId = settings.activeApiKeyId,
                        nowMs = nowMs,
                        onSetActive = { onSetActiveKey(entry.id) },
                        onToggleEnabled = { onToggleKeyEnabled(entry.id) },
                        onRemove = { onRemoveKey(entry.id) }
                    )
                }
            }
        }

        if (hasUnhealthyKey) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onResetKeyHealth,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("reset_api_keys_health_button"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, palette.amberAccent.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Reset key cooldowns",
                    tint = palette.amberAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Reset All Cooldowns & Retry Flags",
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.amberAccent
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Add New Key(s) Form
        Text(
            text = "Add Gemini API Key(s)",
            style = MaterialTheme.typography.titleSmall,
            color = palette.inkPrimary
        )
        Text(
            text = "Paste 1 key or multiple keys separated by new lines. Stored locally only.",
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = labelInput,
            onValueChange = { labelInput = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_api_key_label_input"),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            label = { Text("Optional Label (e.g. Account 2)") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = palette.surfaceSubtle,
                unfocusedContainerColor = palette.surfaceSubtle,
                focusedBorderColor = palette.cobaltAccent,
                unfocusedBorderColor = palette.outline
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_api_key_input"),
            minLines = 1,
            maxLines = 3,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text("Paste AIza... key (or multiple lines)", color = palette.inkMuted) },
            visualTransformation = if (showKeyInput || keyInput.contains('\n')) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(
                    onClick = { showKeyInput = !showKeyInput },
                    modifier = Modifier.testTag("toggle_api_key_visibility")
                ) {
                    Icon(
                        imageVector = if (showKeyInput) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showKeyInput) "Hide key" else "Show key",
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

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val toAdd = keyInput.trim()
                    if (toAdd.isNotBlank()) {
                        onAddKeys(toAdd, labelInput.trim())
                        keyInput = ""
                        labelInput = ""
                    }
                },
                enabled = keyInput.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("add_api_key_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldGreen,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add API key",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Key")
            }

            Button(
                onClick = onFetchModels,
                enabled = !isFetchingModels && settings.hasAnyApiKey,
                modifier = Modifier
                    .weight(1.25f)
                    .height(48.dp)
                    .testTag("fetch_models_button"),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CobaltBlue,
                    contentColor = Color.White
                )
            ) {
                if (isFetchingModels) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Fetching...", maxLines = 1)
                } else {
                    Icon(
                        imageVector = Icons.Filled.CloudDownload,
                        contentDescription = "Fetch available models",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Fetch Models", maxLines = 1)
                }
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

@Composable
private fun ApiKeyRowItem(
    entry: GeminiApiKeyEntry,
    activeId: String?,
    nowMs: Long,
    onSetActive: () -> Unit,
    onToggleEnabled: () -> Unit,
    onRemove: () -> Unit
) {
    val palette = StudioTheme.colors
    val status = entry.status(activeId, nowMs)
    val (statusLabel, accent, soft) = when (status) {
        ApiKeyStatus.ACTIVE -> Triple("ACTIVE", palette.emeraldAccent, palette.emeraldSoft)
        ApiKeyStatus.READY -> Triple("BACKUP", palette.cobaltAccent, palette.cobaltSoft)
        ApiKeyStatus.COOLDOWN -> {
            val remSec = entry.remainingCooldownSec(nowMs)
            val label = if (remSec >= 60) "WAIT ${remSec / 60}m" else "WAIT ${remSec}s"
            Triple(label, palette.amberAccent, palette.amberSoft)
        }
        ApiKeyStatus.INVALID -> Triple("INVALID", palette.redAccent, palette.redSoft)
        ApiKeyStatus.DISABLED -> Triple("PAUSED", palette.inkMuted, palette.surfaceSubtle)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = status != ApiKeyStatus.ACTIVE) { onSetActive() }
            .testTag("api_key_row_${entry.id}"),
        shape = RoundedCornerShape(14.dp),
        color = if (status == ApiKeyStatus.ACTIVE) palette.emeraldSoft.copy(alpha = 0.42f) else palette.surfaceSubtle,
        border = BorderStroke(
            width = if (status == ApiKeyStatus.ACTIVE) 1.5.dp else 1.dp,
            color = if (status == ApiKeyStatus.ACTIVE) palette.emeraldAccent.copy(alpha = 0.55f) else palette.outline
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
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
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(accent)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.inkPrimary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            StatusPillBadge(
                                label = statusLabel,
                                accentColor = accent,
                                containerColor = soft,
                                leadingDot = false
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${entry.maskedKey()}  •  ${entry.totalRequests} reqs",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status != ApiKeyStatus.ACTIVE && entry.enabled) {
                        IconButton(
                            onClick = onSetActive,
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Use this API key now",
                                tint = palette.cobaltAccent,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onToggleEnabled,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (entry.enabled) Icons.Filled.PauseCircleOutline else Icons.Filled.PlayCircleOutline,
                            contentDescription = if (entry.enabled) "Pause key" else "Enable key",
                            tint = palette.inkSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Remove API key",
                            tint = palette.redAccent,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            if (!entry.lastError.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = entry.lastError,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status == ApiKeyStatus.INVALID) palette.redAccent else palette.amberAccent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
