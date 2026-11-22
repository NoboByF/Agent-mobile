package com.example.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.example.firebase.DeviceProfileIdentity
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StudioTheme
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

suspend fun signOutFromFirebaseAndCredentials(context: Context) {
    withContext(NonCancellable) {
        runCatching { Firebase.auth.signOut() }
        runCatching {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        }
    }
}

@Composable
fun AuthGateScreen(
    deviceProfile: DeviceProfileIdentity,
    onSignedIn: () -> Unit,
    onOneClickAutoStart: () -> Unit
) {
    val context = LocalContext.current
    val palette = StudioTheme.colors
    val scope = rememberCoroutineScope()

    var isSigningIn by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    suspend fun authenticateWithGoogleToken(idToken: String) {
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        Firebase.auth.signInWithCredential(firebaseCredential).await()
        onSignedIn()
    }

    // Silent auto sign-in attempt on launch (separate GetCredentialRequest using GetGoogleIdOption only)
    LaunchedEffect(Unit) {
        if (runCatching { Firebase.auth.currentUser }.getOrNull() != null) {
            onSignedIn()
            return@LaunchedEffect
        }
        val webClientId = runCatching { context.getString(R.string.default_web_client_id) }.getOrNull()
        if (webClientId.isNullOrBlank() || webClientId.startsWith("000000000000-")) {
            return@LaunchedEffect
        }
        try {
            val credentialManager = CredentialManager.create(context)
            val silentOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()
            val silentRequest = GetCredentialRequest.Builder()
                .addCredentialOption(silentOption)
                .build()
            val result = credentialManager.getCredential(context, silentRequest)
            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                isSigningIn = true
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                authenticateWithGoogleToken(token)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            // No previously authorized account; wait cleanly for user tap
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Silent Google Sign-In dismissed by user", e)
        } catch (e: Exception) {
            Log.d("AuthGate", "Silent sign-in skipped: ${e.message}")
        } finally {
            isSigningIn = false
        }
    }

    fun startInteractiveGoogleSignIn() {
        if (isSigningIn) return
        scope.launch {
            isSigningIn = true
            errorMessage = null
            try {
                val webClientId = context.getString(R.string.default_web_client_id)
                if (webClientId.isBlank() || webClientId.startsWith("000000000000-")) {
                    errorMessage = "এই Release APK-তে Firebase কনফিগারেশন (app/google-services.json) যুক্ত করা হয়নি। GitHub Repository Secrets-এ GOOGLE_SERVICES_JSON যোগ করে পুনরায় বিল্ড করুন।"
                    return@launch
                }
                val credentialManager = CredentialManager.create(context)
                val googleOption = GetSignInWithGoogleOption.Builder(webClientId).build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build()

                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    authenticateWithGoogleToken(token)
                } else {
                    errorMessage = "Google আইডি টোকেন পাওয়া যায়নি। আবার চেষ্টা করুন।"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GetCredentialCancellationException) {
                Log.w("Auth", "Interactive Google Sign-In cancelled by user", e)
            } catch (e: Exception) {
                Log.e("AuthGate", "Google Sign-In failed", e)
                errorMessage = "Google লগইন ব্যর্থ হয়েছে: ${e.localizedMessage ?: "আবার চেষ্টা করুন"}"
            } finally {
                isSigningIn = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(palette.cobaltSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudDone,
                    contentDescription = "LivePilot Cloud Sync",
                    tint = CobaltBlue,
                    modifier = Modifier.size(36.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "LivePilot AI",
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.inkPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "এক ক্লিকে অটোমেটিক এই মোবাইলের প্রোফাইল চালু করুন—কোনো লগইন লাগবে না। চাইলে পরে Google দিয়ে Firebase ক্লাউড সিঙ্কও চালু করতে পারবেন।",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkSecondary,
                    textAlign = TextAlign.Center
                )
            }

            // Per-Device Isolation Preview Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = palette.card,
                border = BorderStroke(1.dp, palette.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.emeraldSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PhoneAndroid,
                                contentDescription = "Device isolation",
                                tint = EmeraldGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "এই মোবাইলের আলাদা প্রোফাইল",
                                style = MaterialTheme.typography.titleSmall,
                                color = palette.inkPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = deviceProfile.displayLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.emeraldAccent,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "ID: ${deviceProfile.deviceId}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = palette.inkMuted
                            )
                        }
                    }

                    FeatureRow(
                        icon = Icons.Filled.Shield,
                        tint = CobaltBlue,
                        text = "আপনার API Key, ডিলিট করা মডেল ও সেটিংস শুধুমাত্র এই মোবাইলের আন্ডারেই আলাদা থাকবে — অন্য মোবাইলে যাবে না।"
                    )
                    FeatureRow(
                        icon = Icons.Filled.CloudDone,
                        tint = EmeraldGreen,
                        text = "আপনার \"আমার ডাটা\" (My Data), রুটিন ও চ্যাট হিস্ট্রি Firebase-এ সুরক্ষিত থাকবে — অ্যাপ ডিলিট করলেও মুছবে না।"
                    )
                    FeatureRow(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        tint = palette.roseAccent,
                        text = "স্ক্রিন অফ থাকলেও গুরুত্বপূর্ণ মেসেজ জোরে পড়ে শোনাবে ও দূর থেকে আপনার ভয়েস শুনে অটো রিপ্লাই দেবে।"
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = palette.roseSoft,
                    border = BorderStroke(1.dp, palette.roseAccent.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.roseAccent,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Primary 1-Click Automatic Entry (No Google Sign-In required)
            Button(
                onClick = onOneClickAutoStart,
                enabled = !isSigningIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("one_click_auto_start_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldGreen,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = "One-click auto start",
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "এক ক্লিকে অটো চালু করুন (লগইন ছাড়াই)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            // Optional Google Sign-In for Firebase Cloud Sync
            OutlinedButton(
                onClick = { startInteractiveGoogleSignIn() },
                enabled = !isSigningIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("google_sign_in_button"),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.2.dp, CobaltBlue.copy(alpha = 0.6f))
            ) {
                if (isSigningIn) {
                    CircularProgressIndicator(
                        color = CobaltBlue,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Google সাইন-ইন হচ্ছে...",
                        style = MaterialTheme.typography.titleSmall,
                        color = CobaltBlue
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Login,
                        contentDescription = "Sign in with Google",
                        tint = CobaltBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Google দিয়ে Firebase সিঙ্ক (ঐচ্ছিক)",
                        style = MaterialTheme.typography.titleSmall,
                        color = CobaltBlue,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    text: String
) {
    val palette = StudioTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSecondary
        )
    }
}
