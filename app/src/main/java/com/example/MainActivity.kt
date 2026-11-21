package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.PilotViewModel
import com.example.ui.components.FloatingPilotBottomBar
import com.example.ui.components.PilotTab
import com.example.ui.screens.AskAiScreen
import com.example.ui.screens.AutoChatScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsLogsScreen
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.LivePilotTheme
import com.example.ui.theme.StudioBackground

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LivePilotTheme {
                LivePilotAppRoot()
            }
        }
    }
}

@Composable
private fun LivePilotAppRoot(
    viewModel: PilotViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedTab by rememberSaveable { mutableStateOf(PilotTab.HOME) }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val connectionTest by viewModel.connectionTest.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val captions by viewModel.captions.collectAsStateWithLifecycle()
    val incomingMessages by viewModel.incomingMessages.collectAsStateWithLifecycle()
    val discoveredModels by viewModel.discoveredModels.collectAsStateWithLifecycle()
    val isFetchingModels by viewModel.isFetchingModels.collectAsStateWithLifecycle()
    val modelFetchStatus by viewModel.modelFetchStatus.collectAsStateWithLifecycle()
    val askAiState by viewModel.askAiState.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()

    // Runtime permission launcher for Microphone (+ POST_NOTIFICATIONS on Android 13+)
    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissions()
    }

    // Refresh permissions every time user returns from Android system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Back press from secondary tabs returns smoothly to Home tab
    BackHandler(enabled = selectedTab != PilotTab.HOME) {
        selectedTab = PilotTab.HOME
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBackground)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = !bannerMessage.isNullOrBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = CobaltBlue
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Notice",
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = bannerMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.dismissBanner() }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Dismiss notice",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    PilotTab.HOME -> HomeScreen(
                        settings = settings,
                        sessionState = sessionState,
                        permissions = permissions,
                        connectionTest = connectionTest,
                        recentCaptions = captions,
                        onStartOrStopCall = {
                            val needed = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            if (!permissions.micGranted) {
                                runtimePermLauncher.launch(needed.toTypedArray())
                            } else {
                                viewModel.startOrStopLiveCall()
                            }
                        },
                        onTestConnection = { viewModel.testLiveConnection() },
                        onToggleMute = { viewModel.toggleMute() },
                        onCaptureVision = { viewModel.triggerVisionCapture() },
                        onEmergencyStop = { viewModel.emergencyStop() },
                        onRequestMicPermission = {
                            val needed = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            runtimePermLauncher.launch(needed.toTypedArray())
                        },
                        onOpenOverlaySettings = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            runCatching { context.startActivity(intent) }
                        },
                        onOpenAccessibilitySettings = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        },
                        onOpenNotificationSettings = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        },
                        onGoToSettings = { selectedTab = PilotTab.SETTINGS }
                    )

                    PilotTab.ASK_AI -> AskAiScreen(
                        settings = settings,
                        uiState = askAiState,
                        onSelectTextModel = { viewModel.updateTextModel(it) },
                        onToggleSearchGrounding = { viewModel.toggleSearchGrounding(it) },
                        onSendPrompt = { viewModel.sendAskAiPrompt(it) },
                        onClearThread = { viewModel.clearAskAiThread() }
                    )

                    PilotTab.AUTO_CHAT -> AutoChatScreen(
                        settings = settings,
                        permissions = permissions,
                        messages = incomingMessages,
                        onUpdateMode = { viewModel.updateAutoChatMode(it) },
                        onUpdateDelivery = { viewModel.updateAutoReplyDelivery(it) },
                        onUpdatePersona = { viewModel.updateAutoReplyPersona(it) },
                        onToggleApp = { viewModel.toggleMessagingApp(it) },
                        onOpenNotificationSettings = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        },
                        onOpenChat = { viewModel.openChatFromFeed(it) },
                        onQuickAiReply = { viewModel.sendQuickAiReplyFromFeed(it) },
                        onClearFeed = { viewModel.clearIncomingFeed() }
                    )

                    PilotTab.SETTINGS -> SettingsLogsScreen(
                        settings = settings,
                        discoveredModels = discoveredModels,
                        isFetchingModels = isFetchingModels,
                        modelFetchStatus = modelFetchStatus,
                        logs = logs,
                        onUpdateApiKey = { viewModel.updateApiKey(it) },
                        onUpdateLiveModel = { viewModel.updateLiveModel(it) },
                        onUpdateTextModel = { viewModel.updateTextModel(it) },
                        onFetchModels = { viewModel.fetchModels() },
                        onUpdateVoice = { viewModel.updateVoiceName(it) },
                        onUpdateSpeakerphone = { viewModel.updateSpeakerphone(it) },
                        onClearLogs = { viewModel.clearLogs() },
                        onExportLogsText = { viewModel.buildLogsExportText() }
                    )
                }
            }
        }

        FloatingPilotBottomBar(
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
            unreadMessageCount = incomingMessages.size,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
