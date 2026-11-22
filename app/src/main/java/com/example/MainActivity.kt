package com.example

import android.Manifest
import android.app.Activity
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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.PilotRoutine
import com.example.ui.PilotViewModel
import com.example.ui.components.FloatingPilotBottomBar
import com.example.ui.components.PilotTab
import com.example.ui.screens.AskAiScreen
import com.example.ui.screens.AutoChatScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsLogsScreen
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.LivePilotTheme
import com.example.ui.theme.StudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LivePilotAppEntry()
        }
    }
}

@Composable
private fun LivePilotAppEntry(
    viewModel: PilotViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    LivePilotTheme(themeMode = settings.themeMode) {
        val isDark = StudioTheme.colors.isDark
        val view = LocalView.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }
        LivePilotAppRoot(viewModel = viewModel)
    }
}

@Composable
private fun LivePilotAppRoot(
    viewModel: PilotViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = StudioTheme.colors

    var selectedTab by rememberSaveable { mutableStateOf(PilotTab.HOME) }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val connectionTest by viewModel.connectionTest.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val captions by viewModel.captions.collectAsStateWithLifecycle()
    val recentToolEvents by viewModel.recentToolEvents.collectAsStateWithLifecycle()
    val routines by viewModel.routines.collectAsStateWithLifecycle()
    val incomingMessages by viewModel.incomingMessages.collectAsStateWithLifecycle()
    val discoveredModels by viewModel.discoveredModels.collectAsStateWithLifecycle()
    val isFetchingModels by viewModel.isFetchingModels.collectAsStateWithLifecycle()
    val modelFetchStatus by viewModel.modelFetchStatus.collectAsStateWithLifecycle()
    val askAiState by viewModel.askAiState.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()

    var pendingStartCallAfterPerm by remember { mutableStateOf(false) }
    var pendingRoutineAfterPerm by remember { mutableStateOf<PilotRoutine?>(null) }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.refreshPermissions()
        val micNowGranted = result[Manifest.permission.RECORD_AUDIO] == true
        if (micNowGranted) {
            val routine = pendingRoutineAfterPerm
            pendingRoutineAfterPerm = null
            if (routine != null) {
                pendingStartCallAfterPerm = false
                viewModel.launchRoutine(routine)
            } else if (pendingStartCallAfterPerm) {
                pendingStartCallAfterPerm = false
                viewModel.startOrStopLiveCall()
            }
        } else {
            pendingStartCallAfterPerm = false
            pendingRoutineAfterPerm = null
        }
    }

    val isKeyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = selectedTab != PilotTab.HOME) {
        selectedTab = PilotTab.HOME
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .imePadding()
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
                Crossfade(
                    targetState = selectedTab,
                    animationSpec = tween(durationMillis = 190),
                    label = "tab_crossfade"
                ) { tab ->
                    when (tab) {
                        PilotTab.HOME -> HomeScreen(
                            settings = settings,
                            sessionState = sessionState,
                            permissions = permissions,
                            connectionTest = connectionTest,
                            recentCaptions = captions,
                            recentToolEvents = recentToolEvents,
                            routines = routines,
                            onStartOrStopCall = {
                                if (sessionState.isServiceRunning) {
                                    viewModel.startOrStopLiveCall()
                                } else if (!permissions.micGranted) {
                                    val needed = buildList {
                                        add(Manifest.permission.RECORD_AUDIO)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    pendingStartCallAfterPerm = true
                                    runtimePermLauncher.launch(needed.toTypedArray())
                                } else {
                                    viewModel.startOrStopLiveCall()
                                }
                            },
                            onTestConnection = { viewModel.testLiveConnection() },
                            onToggleMute = { viewModel.toggleMute() },
                            onToggleAutoVision = { viewModel.toggleAutoVision() },
                            onCaptureVision = { viewModel.triggerVisionCapture() },
                            onSendSilentCommand = { viewModel.sendSilentCommandToCall(it) },
                            onEmergencyStop = { viewModel.emergencyStop() },
                            onLaunchRoutine = { routine ->
                                if (!permissions.micGranted) {
                                    val needed = buildList {
                                        add(Manifest.permission.RECORD_AUDIO)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            add(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    }
                                    pendingRoutineAfterPerm = routine
                                    runtimePermLauncher.launch(needed.toTypedArray())
                                } else {
                                    viewModel.launchRoutine(routine)
                                }
                            },
                            onAddRoutine = { t, s, p, a -> viewModel.addCustomRoutine(t, s, p, a) },
                            onDeleteRoutine = { viewModel.deleteCustomRoutine(it) },
                            onToggleTheme = { currentlyDark -> viewModel.cycleThemeMode(currentlyDark) },
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
                            isCallActive = sessionState.isServiceRunning,
                            onSelectTextModel = { viewModel.updateTextModel(it) },
                            onToggleSearchGrounding = { viewModel.toggleSearchGrounding(it) },
                            onSendPrompt = { viewModel.sendAskAiPrompt(it) },
                            onSendToLiveCall = { viewModel.sendSilentCommandToCall(it) },
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
                            onTemplateReply = { msg, replyText ->
                                viewModel.sendTemplateDirectReply(msg, replyText)
                            },
                            onClearFeed = { viewModel.clearIncomingFeed() }
                        )

                        PilotTab.SETTINGS -> SettingsLogsScreen(
                            settings = settings,
                            discoveredModels = discoveredModels,
                            isFetchingModels = isFetchingModels,
                            modelFetchStatus = modelFetchStatus,
                            logs = logs,
                            onAddApiKeys = { raw, label -> viewModel.addApiKeys(raw, label) },
                            onSetActiveApiKey = { viewModel.setActiveApiKey(it) },
                            onToggleApiKeyEnabled = { viewModel.toggleApiKeyEnabled(it) },
                            onRemoveApiKey = { viewModel.removeApiKey(it) },
                            onToggleAutoRotateKeys = { viewModel.updateAutoRotateApiKeys(it) },
                            onResetApiKeyHealth = { viewModel.resetApiKeyHealth() },
                            onUpdateLiveModel = { viewModel.updateLiveModel(it) },
                            onUpdateTextModel = { viewModel.updateTextModel(it) },
                            onFetchModels = { viewModel.fetchModels() },
                            onUpdateVoice = { viewModel.updateVoiceName(it) },
                            onUpdateSpeakerphone = { viewModel.updateSpeakerphone(it) },
                            onUpdateAutoVisionDefault = { viewModel.updateAutoVisionDefault(it) },
                            onUpdateAutoVisionInterval = { viewModel.updateAutoVisionInterval(it) },
                            onUpdateShowTouchVisualizer = { viewModel.updateShowTouchVisualizer(it) },
                            onUpdateShowFloatingCaptions = { viewModel.updateShowFloatingCaptions(it) },
                            onUpdateThemeMode = { viewModel.updateThemeMode(it) },
                            onUpdateCustomVoiceInstructions = { viewModel.updateCustomVoiceInstructions(it) },
                            onClearLogs = { viewModel.clearLogs() },
                            onExportLogsText = { viewModel.buildLogsExportText() }
                        )
                    }
                }
            }
        }

        if (!isKeyboardVisible) {
            FloatingPilotBottomBar(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
                unreadMessageCount = incomingMessages.size,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}
