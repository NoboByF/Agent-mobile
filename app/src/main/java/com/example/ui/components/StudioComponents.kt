package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.CyanBright
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.RosePink
import com.example.ui.theme.RoyalViolet
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.WarmAmber
import kotlinx.coroutines.delay
import java.util.Locale

enum class PilotTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    HOME(
        label = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
        testTag = "nav_tab_home"
    ),
    ASK_AI(
        label = "Ask AI",
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome,
        testTag = "nav_tab_ask_ai"
    ),
    AUTO_CHAT(
        label = "Auto-Chat",
        selectedIcon = Icons.Filled.ChatBubble,
        unselectedIcon = Icons.Outlined.ChatBubbleOutline,
        testTag = "nav_tab_auto_chat"
    ),
    SETTINGS(
        label = "Settings",
        selectedIcon = Icons.Filled.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        testTag = "nav_tab_settings"
    )
}

@Composable
fun StudioCardContainer(
    modifier: Modifier = Modifier,
    containerColor: Color = StudioTheme.colors.card,
    borderColor: Color = StudioTheme.colors.outline,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = StudioTheme.colors.isDark
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = if (isDark) 0.dp else 2.dp
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

@Composable
fun StatusPillBadge(
    label: String,
    accentColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
    leadingDot: Boolean = true
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingDot) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = accentColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Flagship Aurora Multi-Ring Voice Orb with rotating sweep glow and real-time audio energy scaling.
 */
@Composable
fun AuroraLiveOrb(
    primaryColor: Color,
    secondaryColor: Color = CyanBright,
    isPulsing: Boolean,
    audioEnergy: Float = 0f,
    size: Dp = 78.dp,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "aurora_orb")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isPulsing) 2600 else 7500, easing = LinearEasing)
        ),
        label = "aurora_rot"
    )
    val breath by transition.animateFloat(
        initialValue = 0.78f,
        targetValue = if (isPulsing) 0.96f else 0.84f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aurora_breath"
    )

    val energy = audioEnergy.coerceIn(0f, 1f)
    val outerScale = (breath + energy * 0.22f).coerceAtMost(1.0f)

    Canvas(modifier = modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)

        // Outer ambient radial halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = if (isPulsing) 0.42f + energy * 0.25f else 0.22f),
                    secondaryColor.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = center,
                radius = radius * outerScale
            ),
            radius = radius * outerScale,
            center = center
        )

        // Rotating iridescent aurora ring
        rotate(degrees = rotation, pivot = center) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        primaryColor,
                        secondaryColor,
                        RoyalViolet,
                        primaryColor
                    ),
                    center = center
                ),
                radius = radius * (0.64f + energy * 0.10f),
                center = center,
                style = Stroke(width = (3.5.dp).toPx())
            )
        }

        // Core glossy sphere
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.72f),
                    secondaryColor.copy(alpha = 0.85f),
                    primaryColor
                ),
                center = Offset(center.x - radius * 0.14f, center.y - radius * 0.16f),
                radius = radius * 0.62f
            ),
            radius = radius * (0.48f + energy * 0.08f),
            center = center
        )
    }
}

@Composable
fun LiveAudioWaveform(
    energy: Float,
    color: Color,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val multipliers = remember {
        listOf(0.32f, 0.56f, 0.80f, 0.96f, 1.0f, 0.88f, 1.0f, 0.92f, 0.74f, 0.52f, 0.34f)
    }
    val effective = if (isActive) energy.coerceIn(0.10f, 1.0f) else 0.08f
    val mutedColor = StudioTheme.colors.inkMuted

    Row(
        modifier = modifier.height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(3.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        multipliers.forEach { weight ->
            val targetH by animateDpAsState(
                targetValue = (5f + 22f * effective * weight).dp,
                animationSpec = tween(durationMillis = 85),
                label = "waveform_bar"
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(targetH)
                    .clip(RoundedCornerShape(50))
                    .background(if (isActive) color else mutedColor.copy(alpha = 0.35f))
            )
        }
    }
}

@Composable
fun CallDurationBadge(
    startedAtMs: Long?,
    accentColor: Color,
    containerColor: Color
) {
    var elapsedSec by remember(startedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAtMs) {
        if (startedAtMs == null) {
            elapsedSec = 0L
            return@LaunchedEffect
        }
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L)
            delay(1000L)
        }
    }
    if (startedAtMs != null) {
        val mins = elapsedSec / 60L
        val secs = elapsedSec % 60L
        val formatted = String.format(Locale.US, "%02d:%02d", mins, secs)
        Surface(
            shape = RoundedCornerShape(50),
            color = containerColor,
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.28f))
        ) {
            Text(
                text = formatted,
                fontFamily = JetBrainsMonoFontFamily,
                style = MaterialTheme.typography.labelMedium,
                color = accentColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Formats markdown-style **bold** segments and bullet prefixes cleanly into AnnotatedString.
 */
fun formatAiRichText(raw: String): AnnotatedString {
    val normalized = raw
        .replace(Regex("(?m)^\\s*[*-]\\s+"), "• ")
        .replace(Regex("(?m)^#{1,3}\\s+"), "")

    return buildAnnotatedString {
        var i = 0
        while (i < normalized.length) {
            if (i + 1 < normalized.length && normalized[i] == '*' && normalized[i + 1] == '*') {
                val close = normalized.indexOf("**", startIndex = i + 2)
                if (close != -1) {
                    val boldSpan = normalized.substring(i + 2, close)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(boldSpan)
                    }
                    i = close + 2
                    continue
                }
            }
            append(normalized[i])
            i++
        }
    }
}

@Composable
fun FloatingPilotBottomBar(
    selectedTab: PilotTab,
    onSelectTab: (PilotTab) -> Unit,
    unreadMessageCount: Int,
    modifier: Modifier = Modifier
) {
    val palette = StudioTheme.colors
    val haptics = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (palette.isDark) 6.dp else 14.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = Color.Black.copy(alpha = 0.16f),
                    spotColor = Color.Black.copy(alpha = 0.18f)
                ),
            shape = RoundedCornerShape(28.dp),
            color = palette.card,
            border = BorderStroke(1.dp, palette.outline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PilotTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    val accent = when (tab) {
                        PilotTab.HOME -> if (palette.isDark) palette.cobaltAccent else CobaltBlue
                        PilotTab.ASK_AI -> if (palette.isDark) palette.violetAccent else RoyalViolet
                        PilotTab.AUTO_CHAT -> if (palette.isDark) palette.roseAccent else RosePink
                        PilotTab.SETTINGS -> if (palette.isDark) palette.amberAccent else WarmAmber
                    }
                    val soft = when (tab) {
                        PilotTab.HOME -> palette.cobaltSoft
                        PilotTab.ASK_AI -> palette.violetSoft
                        PilotTab.AUTO_CHAT -> palette.roseSoft
                        PilotTab.SETTINGS -> palette.amberSoft
                    }
                    val pillBg by animateColorAsState(
                        targetValue = if (isSelected) soft else Color.Transparent,
                        animationSpec = spring(),
                        label = "tab_pill_bg"
                    )
                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) accent else palette.inkSecondary,
                        animationSpec = spring(),
                        label = "tab_icon_tint"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(pillBg)
                            .clickable {
                                if (!isSelected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelectTab(tab)
                                }
                            }
                            .testTag(tab.testTag),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box {
                                Icon(
                                    imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label,
                                    tint = iconTint,
                                    modifier = Modifier.size(22.dp)
                                )
                                if (tab == PilotTab.AUTO_CHAT && unreadMessageCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(RosePink)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) accent else palette.inkMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
