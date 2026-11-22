package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.components.StatusPillBadge
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.StudioTheme

object FreeExplorerUrlPolicy {
    const val DEFAULT_URL = "https://www.grameenphone.com/shop/devices/phones"

    fun isSafeNavigableUrl(rawUrl: String?): Boolean {
        val clean = rawUrl?.trim().orEmpty()
        if (clean.isBlank()) return false
        val parsed = runCatching { Uri.parse(clean) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase().orEmpty()
        val host = parsed.host?.trim().orEmpty()
        return (scheme == "https" || scheme == "http") && host.isNotBlank()
    }

    fun formatHostDisplay(rawUrl: String?): String {
        val parsed = runCatching { Uri.parse(rawUrl.orEmpty()) }.getOrNull()
        val host = parsed?.host?.removePrefix("www.").orEmpty()
        return host.ifBlank { "grameenphone.com" }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FreeExplorerScreen(
    standaloneMode: Boolean = false,
    onExitStandalone: (() -> Unit)? = null
) {
    val palette = StudioTheme.colors
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(FreeExplorerUrlPolicy.DEFAULT_URL) }
    var pageTitle by remember { mutableStateOf("Free Explorer") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loadErrorMessage by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = canGoBack || standaloneMode) {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (standaloneMode) {
            onExitStandalone?.invoke()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.webChromeClient = null
                    wv.destroy()
                }
            }
            webViewRef = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .then(
                if (standaloneMode) {
                    Modifier
                        .statusBarsPadding()
                        .navigationBarsPadding()
                } else {
                    Modifier.padding(bottom = 86.dp)
                }
            )
    ) {
        // Top Compact Explorer Control Bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = palette.card,
            border = BorderStroke(1.dp, palette.outline)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (standaloneMode && onExitStandalone != null) {
                        IconButton(
                            onClick = onExitStandalone,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("free_explorer_exit_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "লগইনে ফিরে যান",
                                tint = palette.inkPrimary
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(palette.emeraldSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Explore,
                                contentDescription = "Free Explorer",
                                tint = palette.emeraldAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Free Explorer",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.inkPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            StatusPillBadge(
                                label = "FREE",
                                accentColor = palette.emeraldAccent,
                                containerColor = palette.emeraldSoft,
                                leadingDot = true
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = null,
                                tint = palette.emeraldAccent,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = FreeExplorerUrlPolicy.formatHostDisplay(currentUrl),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Browser Navigation Controls (minimum 48dp touch targets)
                    IconButton(
                        onClick = { webViewRef?.takeIf { it.canGoBack() }?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("free_explorer_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "পিছনে যান",
                            tint = if (canGoBack) palette.inkPrimary else palette.inkMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { webViewRef?.takeIf { it.canGoForward() }?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("free_explorer_forward_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "সামনে যান",
                            tint = if (canGoForward) palette.inkPrimary else palette.inkMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            loadErrorMessage = null
                            currentUrl = FreeExplorerUrlPolicy.DEFAULT_URL
                            webViewRef?.loadUrl(FreeExplorerUrlPolicy.DEFAULT_URL)
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("free_explorer_home_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Home,
                            contentDescription = "Grameenphone হোম পেজ",
                            tint = palette.emeraldAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val wv = webViewRef ?: return@IconButton
                            if (isLoading) {
                                wv.stopLoading()
                                isLoading = false
                            } else {
                                loadErrorMessage = null
                                wv.reload()
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("free_explorer_reload_button")
                    ) {
                        Icon(
                            imageVector = if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                            contentDescription = if (isLoading) "লোড বন্ধ করুন" else "রিলোড করুন",
                            tint = palette.inkPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                AnimatedVisibility(visible = isLoading && loadProgress in 1..99) {
                    LinearProgressIndicator(
                        progress = { (loadProgress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = EmeraldGreen,
                        trackColor = palette.emeraldSoft
                    )
                }
            }
        }

        // Embedded WebView Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .background(palette.card)
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("free_explorer_webview"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            safeBrowsingEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            @Suppress("DEPRECATION")
                            allowFileAccessFromFileURLs = false
                            @Suppress("DEPRECATION")
                            allowUniversalAccessFromFileURLs = false
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                                if (newProgress >= 100) {
                                    isLoading = false
                                }
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) {
                                    pageTitle = title
                                }
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val target = request?.url?.toString()
                                // Block unsafe schemes (javascript:, file:, content:, intent:, data:)
                                return !FreeExplorerUrlPolicy.isSafeNavigableUrl(target)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                loadErrorMessage = null
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                if (!url.isNullOrBlank()) {
                                    currentUrl = url
                                }
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    isLoading = false
                                    loadErrorMessage = error?.description?.toString()?.takeIf { it.isNotBlank() }
                                        ?: "পেজটি লোড করা যায়নি। মোবাইল ডাটা চালু আছে কিনা চেক করে আবার চেষ্টা করুন।"
                                }
                            }
                        }

                        webViewRef = this
                        loadUrl(FreeExplorerUrlPolicy.DEFAULT_URL)
                    }
                }
            )

            // Friendly Error Overlay if main frame fails (e.g., Airplane mode / mobile data off)
            if (!loadErrorMessage.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(palette.background.copy(alpha = 0.96f))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = palette.card,
                        border = BorderStroke(1.dp, palette.outline)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(palette.amberSoft),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WifiOff,
                                    contentDescription = "Connection error",
                                    tint = palette.amberAccent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Text(
                                text = "পেজটি লোড করা যায়নি",
                                style = MaterialTheme.typography.titleMedium,
                                color = palette.inkPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "মোবাইল ডাটা (Grameenphone SIM) চালু আছে কিনা দেখে আবার চেষ্টা করুন।",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    loadErrorMessage = null
                                    isLoading = true
                                    webViewRef?.loadUrl(
                                        if (FreeExplorerUrlPolicy.isSafeNavigableUrl(currentUrl)) {
                                            currentUrl
                                        } else {
                                            FreeExplorerUrlPolicy.DEFAULT_URL
                                        }
                                    )
                                },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("free_explorer_retry_button"),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldGreen,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Retry",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("আবার চেষ্টা করুন", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
