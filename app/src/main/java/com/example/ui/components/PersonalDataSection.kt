package com.example.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonPin
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
import com.example.data.PersonalDataItem
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.StudioTheme

private val QUICK_PERSONAL_STARTERS = listOf(
    Triple("আমার নাম", "পরিচয়", "আমার নাম ফাহিম।"),
    Triple("আমার পেশা / কাজ", "কাজ / পেশা", "আমি ... কাজ করি।"),
    Triple("অফিস / ফ্রি সময়", "সময়সূচি", "আমি সাধারণত সকাল ১০টা থেকে সন্ধ্যা ৬টা পর্যন্ত ব্যস্ত থাকি।"),
    Triple("ঠিকানা", "ঠিকানা / অফিস", "আমি ঢাকায় থাকি।")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonalDataEditorCard(
    editingItem: PersonalDataItem?,
    onCancelEdit: () -> Unit,
    onSaveEntry: (existingId: String?, title: String, content: String, category: String, shareInAutoReply: Boolean) -> Unit
) {
    val palette = StudioTheme.colors
    var title by rememberSaveable(editingItem?.id ?: "new") { mutableStateOf(editingItem?.title.orEmpty()) }
    var content by rememberSaveable(editingItem?.id ?: "new") { mutableStateOf(editingItem?.content.orEmpty()) }
    var selectedCategory by rememberSaveable(editingItem?.id ?: "new") {
        mutableStateOf(editingItem?.category ?: PersonalDataItem.CATEGORIES.first())
    }
    var shareInAutoReply by rememberSaveable(editingItem?.id ?: "new") {
        mutableStateOf(editingItem?.shareInAutoReply ?: true)
    }

    StudioCardContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.cobaltSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PersonPin,
                        contentDescription = "My personal data",
                        tint = CobaltBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (editingItem == null) "নতুন তথ্য যোগ করুন (আমার ডাটা)" else "তথ্য এডিট করুন",
                        style = MaterialTheme.typography.titleSmall,
                        color = palette.inkPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "কেউ আপনার সম্পর্কে জানতে চাইলে AI এখান থেকেই সঠিক উত্তর দেবে",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkSecondary
                    )
                }
            }
        }

        if (editingItem == null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "দ্রুত টেমপ্লেট (ট্যাপ করে পূরণ করুন):",
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkMuted
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QUICK_PERSONAL_STARTERS.forEach { (starterTitle, starterCat, starterHint) ->
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                title = starterTitle
                                selectedCategory = starterCat
                                if (content.isBlank()) content = starterHint
                            },
                        shape = RoundedCornerShape(50),
                        color = palette.surfaceSubtle,
                        border = BorderStroke(1.dp, palette.outline)
                    ) {
                        Text(
                            text = "+ $starterTitle",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.cobaltAccent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "ক্যাটাগরি",
            style = MaterialTheme.typography.labelMedium,
            color = palette.inkSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PersonalDataItem.CATEGORIES.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = palette.cobaltSoft,
                        selectedLabelColor = palette.cobaltAccent
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("বিষয় / শিরোনাম (যেমন: আমার নাম, পেশা, ফ্রি সময়)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("my_data_title_input"),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = palette.surfaceSubtle,
                unfocusedContainerColor = palette.surfaceSubtle,
                focusedBorderColor = palette.cobaltAccent,
                unfocusedBorderColor = palette.outline
            )
        )

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            label = { Text("বিস্তারিত তথ্য / AI যা উত্তর দেবে") },
            minLines = 2,
            maxLines = 5,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("my_data_content_input"),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = palette.surfaceSubtle,
                unfocusedContainerColor = palette.surfaceSubtle,
                focusedBorderColor = palette.cobaltAccent,
                unfocusedBorderColor = palette.outline
            )
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto-Reply-তে শেয়ার করুন",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.inkPrimary
                )
                Text(
                    text = "চালু থাকলে WhatsApp/imo/Messenger-এ কেউ জিজ্ঞেস করলে AI এই তথ্য বলবে",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkSecondary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = shareInAutoReply,
                onCheckedChange = { shareInAutoReply = it },
                modifier = Modifier.testTag("my_data_share_switch"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = EmeraldGreen
                )
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (editingItem != null) {
                OutlinedButton(
                    onClick = onCancelEdit,
                    modifier = Modifier
                        .weight(0.38f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("বাতিল")
                }
            }
            Button(
                onClick = {
                    val t = title.trim()
                    val c = content.trim()
                    if (t.isNotBlank() && c.isNotBlank()) {
                        onSaveEntry(editingItem?.id, t, c, selectedCategory, shareInAutoReply)
                        if (editingItem == null) {
                            title = ""
                            content = ""
                        } else {
                            onCancelEdit()
                        }
                    }
                },
                enabled = title.isNotBlank() && content.isNotBlank(),
                modifier = Modifier
                    .weight(if (editingItem != null) 0.62f else 1f)
                    .height(48.dp)
                    .testTag("my_data_save_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = CobaltBlue,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Save personal data",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (editingItem == null) "Firebase-এ সেভ করুন" else "আপডেট করুন")
            }
        }
    }
}

@Composable
fun PersonalDataItemCard(
    item: PersonalDataItem,
    onEdit: () -> Unit,
    onToggleShare: () -> Unit,
    onDelete: () -> Unit
) {
    val palette = StudioTheme.colors
    StudioCardContainer(contentPadding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                StatusPillBadge(
                    label = item.category,
                    accentColor = palette.cobaltAccent,
                    containerColor = palette.cobaltSoft,
                    leadingDot = false
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.inkPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Row {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("my_data_edit_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit personal fact",
                        tint = palette.cobaltAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("my_data_delete_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Delete personal fact",
                        tint = palette.roseAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.content,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkPrimary
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.CloudDone,
                    contentDescription = "Cloud synced",
                    tint = palette.emeraldAccent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (item.shareInAutoReply) "Auto-Reply-তে চালু" else "শুধুমাত্র ব্যক্তিগত (অফ)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.shareInAutoReply) palette.emeraldAccent else palette.inkMuted
                )
            }
            Switch(
                checked = item.shareInAutoReply,
                onCheckedChange = { onToggleShare() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = EmeraldGreen
                )
            )
        }
    }
}
