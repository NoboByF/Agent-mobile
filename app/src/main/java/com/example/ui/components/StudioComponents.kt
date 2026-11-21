package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.CobaltSoft
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkPrimary
import com.example.ui.theme.InkSecondary
import com.example.ui.theme.RosePink
import com.example.ui.theme.RoseSoft
import com.example.ui.theme.RoyalViolet
import com.example.ui.theme.StudioCard
import com.example.ui.theme.StudioOutline
import com.example.ui.theme.VioletSoft
import com.example.ui.theme.WarmAmber
import com.example.ui.theme.AmberSoft

enum class PilotTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val accentColor: Color,
    val softContainer: Color,
    val testTag: String
) {
    HOME(
        label = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
        accentColor = CobaltBlue,
        softContainer = CobaltSoft,
        testTag = "nav_tab_home"
    ),
    ASK_AI(
        label = "Ask AI",
        selectedIcon = Icons.Filled.AutoAwesome,
        unselectedIcon = Icons.Outlined.AutoAwesome,
        accentColor = RoyalViolet,
        softContainer = VioletSoft,
        testTag = "nav_tab_ask_ai"
    ),
    AUTO_CHAT(
        label = "Auto-Chat",
        selectedIcon = Icons.Filled.ChatBubble,
        unselectedIcon = Icons.Outlined.ChatBubbleOutline,
        accentColor = RosePink,
        softContainer = RoseSoft,
        testTag = "nav_tab_auto_chat"
    ),
    SETTINGS(
        label = "Settings",
        selectedIcon = Icons.Filled.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        accentColor = WarmAmber,
        softContainer = AmberSoft,
        testTag = "nav_tab_settings"
    )
}

@Composable
fun StudioCardContainer(
    modifier: Modifier = Modifier,
    containerColor: Color = StudioCard,
    borderColor: Color = StudioOutline,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 2.dp
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
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.22f))
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

@Composable
fun PulsingLiveOrb(
    color: Color,
    isPulsing: Boolean,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "live_orb_pulse")
    val pulseRadius by transition.animateFloat(
        initialValue = 0.62f,
        targetValue = if (isPulsing) 1.0f else 0.68f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_radius"
    )
    val pulseAlpha by transition.animateFloat(
        initialValue = if (isPulsing) 0.42f else 0.16f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Canvas(modifier = modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        drawCircle(
            color = color.copy(alpha = pulseAlpha),
            radius = radius * pulseRadius
        )
        drawCircle(
            color = color.copy(alpha = 0.28f),
            radius = radius * 0.68f
        )
        drawCircle(
            color = color,
            radius = radius * 0.46f
        )
    }
}

@Composable
fun FloatingPilotBottomBar(
    selectedTab: PilotTab,
    onSelectTab: (PilotTab) -> Unit,
    unreadMessageCount: Int,
    modifier: Modifier = Modifier
) {
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
                    elevation = 12.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = InkPrimary.copy(alpha = 0.12f),
                    spotColor = InkPrimary.copy(alpha = 0.14f)
                ),
            shape = RoundedCornerShape(28.dp),
            color = StudioCard,
            border = BorderStroke(1.dp, StudioOutline)
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
                    val pillBg by animateColorAsState(
                        targetValue = if (isSelected) tab.softContainer else Color.Transparent,
                        animationSpec = spring(),
                        label = "tab_pill_bg"
                    )
                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) tab.accentColor else InkSecondary,
                        animationSpec = spring(),
                        label = "tab_icon_tint"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(pillBg)
                            .clickable { onSelectTab(tab) }
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
                                color = if (isSelected) tab.accentColor else InkMuted,
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
