package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.PilotRoutine
import com.example.data.RoutineAccent
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.StudioTheme

@Composable
fun SmartRoutinesSection(
    routines: List<PilotRoutine>,
    isCallActive: Boolean,
    onLaunchRoutine: (PilotRoutine) -> Unit,
    onDeleteRoutine: (String) -> Unit,
    onOpenCreateDialog: () -> Unit
) {
    val palette = StudioTheme.colors
    val haptics = LocalHapticFeedback.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "One-Tap Smart Routines",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.inkPrimary
                )
                Text(
                    text = if (isCallActive) {
                        "Tap any routine to execute right now"
                    } else {
                        "Tap to auto-start call & run automatically"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary
                )
            }
            Surface(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onOpenCreateDialog)
                    .testTag("add_custom_routine_button"),
                shape = RoundedCornerShape(50),
                color = palette.cobaltSoft,
                border = BorderStroke(1.dp, palette.cobaltAccent.copy(alpha = 0.28f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New custom routine",
                        tint = palette.cobaltAccent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "New",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.cobaltAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        val rows = routines.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pair.forEach { routine ->
                        RoutineTileCard(
                            routine = routine,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLaunchRoutine(routine)
                            },
                            onDelete = if (!routine.isBuiltIn) {
                                { onDeleteRoutine(routine.id) }
                            } else null
                        )
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineTileCard(
    routine: PilotRoutine,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    val palette = StudioTheme.colors
    val (accent, soft) = when (routine.accent) {
        RoutineAccent.COBALT -> palette.cobaltAccent to palette.cobaltSoft
        RoutineAccent.EMERALD -> palette.emeraldAccent to palette.emeraldSoft
        RoutineAccent.VIOLET -> palette.violetAccent to palette.violetSoft
        RoutineAccent.AMBER -> palette.amberAccent to palette.amberSoft
        RoutineAccent.ROSE -> palette.roseAccent to palette.roseSoft
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .testTag("routine_card_${routine.id}"),
        shape = RoundedCornerShape(18.dp),
        color = palette.card,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
        shadowElevation = if (palette.isDark) 0.dp else 2.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(soft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (routine.isBuiltIn) Icons.Filled.Bolt else Icons.Filled.AutoAwesome,
                        contentDescription = routine.title,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }

                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Delete routine",
                            tint = palette.inkMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(soft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Run routine",
                            tint = accent,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = routine.title,
                style = MaterialTheme.typography.titleSmall,
                color = palette.inkPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = routine.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateRoutineDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, subtitle: String, prompt: String, accent: RoutineAccent) -> Unit
) {
    val palette = StudioTheme.colors
    var title by rememberSaveable { mutableStateOf("") }
    var subtitle by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var selectedAccent by rememberSaveable { mutableStateOf(RoutineAccent.VIOLET) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = {
            Text(
                text = "Create Smart Routine",
                style = MaterialTheme.typography.titleLarge,
                color = palette.inkPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Routine Name") },
                    placeholder = { Text("e.g. Night Mode") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("routine_title_input"),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.surfaceSubtle,
                        unfocusedContainerColor = palette.surfaceSubtle,
                        focusedBorderColor = palette.cobaltAccent,
                        unfocusedBorderColor = palette.outline
                    )
                )
                OutlinedTextField(
                    value = subtitle,
                    onValueChange = { subtitle = it },
                    label = { Text("Short Tagline") },
                    placeholder = { Text("e.g. Set 7AM alarm & mute") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.surfaceSubtle,
                        unfocusedContainerColor = palette.surfaceSubtle,
                        focusedBorderColor = palette.cobaltAccent,
                        unfocusedBorderColor = palette.outline
                    )
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Voice / Automation Command") },
                    placeholder = { Text("e.g. Set an alarm for 7:00 AM and mute volume") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("routine_prompt_input"),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = palette.surfaceSubtle,
                        unfocusedContainerColor = palette.surfaceSubtle,
                        focusedBorderColor = palette.cobaltAccent,
                        unfocusedBorderColor = palette.outline
                    )
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoutineAccent.entries.forEach { acc ->
                        FilterChip(
                            selected = selectedAccent == acc,
                            onClick = { selectedAccent = acc },
                            label = { Text(acc.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = palette.cobaltSoft,
                                selectedLabelColor = palette.cobaltAccent
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(title, subtitle, prompt, selectedAccent)
                    onDismiss()
                },
                enabled = title.isNotBlank() && prompt.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CobaltBlue,
                    contentColor = Color.White
                ),
                modifier = Modifier.testTag("save_custom_routine_button")
            ) {
                Text("Save Routine")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = palette.inkSecondary)
            }
        }
    )
}
