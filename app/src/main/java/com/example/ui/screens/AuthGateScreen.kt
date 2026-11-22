package com.example.ui.screens

import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.PilotStateHub
import com.example.firebase.DeviceProfileIdentity
import com.example.firebase.FirebaseRuntimeConfig
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.WarmAmber
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private enum class AuthFormMode {
    LOGIN,
    SIGN_UP
}

object AuthInputValidator {
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun isValidEmail(raw: String): Boolean = EMAIL_REGEX.matches(raw.trim())

    fun validateLogin(email: String, password: String): String? {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank()) return "আপনার জিমেইল / ইমেইল ঠিকানা লিখুন।"
        if (!isValidEmail(cleanEmail)) return "সঠিক জিমেইল / ইমেইল ঠিকানা দিন (যেমন: name@gmail.com)।"
        if (password.isBlank()) return "আপনার পাসওয়ার্ড লিখুন।"
        if (password.length < 6) return "পাসওয়ার্ড কমপক্ষে ৬ অক্ষরের হতে হবে।"
        return null
    }

    fun validateSignUp(
        accountName: String,
        email: String,
        password: String,
        confirmPassword: String
    ): String? {
        if (accountName.trim().length < 2) return "আপনার অ্যাকাউন্টের নাম লিখুন (কমপক্ষে ২ অক্ষর)।"
        val loginErr = validateLogin(email, password)
        if (loginErr != null) return loginErr
        if (confirmPassword != password) return "দুটি পাসওয়ার্ড মেলেনি। আবার একই পাসওয়ার্ড লিখুন।"
        return null
    }

    fun validateResetEmail(email: String): String? {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank()) return "পাসওয়ার্ড রিসেট করতে আগে আপনার জিমেইল লিখুন।"
        if (!isValidEmail(cleanEmail)) return "সঠিক জিমেইল / ইমেইল ঠিকানা দিন।"
        return null
    }

    fun mapFirebaseAuthError(err: Throwable): String {
        val code = (err as? FirebaseAuthException)?.errorCode.orEmpty()
        val msg = err.message.orEmpty()
        return when {
            msg.contains("API key not valid", ignoreCase = true) ||
                msg.contains("API_KEY_INVALID", ignoreCase = true) ||
                msg.contains("CI_PLACEHOLDER", ignoreCase = true) ->
                "এই APK-তে Firebase-এর আসল google-services.json যুক্ত নেই। উপরের \"google-services.json যুক্ত করুন\" বাটনে ক্লিক করে আপনার Firebase Console থেকে ডাউনলোড করা google-services.json ফাইল সিলেক্ট করুন (অথবা GitHub Secrets-এ GOOGLE_SERVICES_JSON যোগ করুন)।"
            err is FirebaseAuthUserCollisionException || code == "ERROR_EMAIL_ALREADY_IN_USE" ->
                "এই জিমেইল দিয়ে আগে থেকেই অ্যাকাউন্ট তৈরি করা আছে। 'লগইন' ট্যাব থেকে লগইন করুন।"
            err is FirebaseAuthInvalidUserException || code == "ERROR_USER_NOT_FOUND" ->
                "এই জিমেইলে কোনো অ্যাকাউন্ট পাওয়া যায়নি। 'নতুন অ্যাকাউন্ট' ট্যাবে গিয়ে অ্যাকাউন্ট তৈরি করুন।"
            err is FirebaseAuthWeakPasswordException || code == "ERROR_WEAK_PASSWORD" ->
                "পাসওয়ার্ড আরও শক্তিশালী দিন (কমপক্ষে ৬ অক্ষর)।"
            err is FirebaseAuthInvalidCredentialsException ||
                code == "ERROR_WRONG_PASSWORD" ||
                code == "ERROR_INVALID_CREDENTIAL" ||
                code == "ERROR_INVALID_EMAIL" ->
                "জিমেইল বা পাসওয়ার্ড সঠিক নয়। আবার ভালোভাবে চেক করে চেষ্টা করুন।"
            err is FirebaseNetworkException ->
                "ইন্টারনেট সংযোগ পাওয়া যাচ্ছে না। নেটওয়ার্ক চেক করে আবার চেষ্টা করুন।"
            code == "ERROR_OPERATION_NOT_ALLOWED" ->
                "আপনার Firebase প্রোজেক্টের Authentication → Sign-in method থেকে Email/Password চালু আছে কিনা যাচাই করুন।"
            code == "ERROR_TOO_MANY_REQUESTS" ->
                "অনেকবার ভুল চেষ্টা হয়েছে। কিছুক্ষণ পর আবার চেষ্টা করুন।"
            else ->
                "অ্যাকাউন্ট প্রসেস ব্যর্থ হয়েছে: ${err.localizedMessage ?: "আবার চেষ্টা করুন"}"
        }
    }
}

suspend fun signOutFromFirebase(context: Context) {
    withContext(NonCancellable) {
        runCatching { FirebaseRuntimeConfig.auth(context).signOut() }
    }
}

@Composable
fun AuthGateScreen(
    deviceProfile: DeviceProfileIdentity,
    onSignedIn: () -> Unit,
    onOpenFreeExplorer: () -> Unit = {}
) {
    val context = LocalContext.current
    val palette = StudioTheme.colors
    val scope = rememberCoroutineScope()

    val configRevision by FirebaseRuntimeConfig.configRevision.collectAsStateWithLifecycle()
    val isFirebaseReady = remember(configRevision) { FirebaseRuntimeConfig.isReady(context) }
    val activeProjectId = remember(configRevision) { FirebaseRuntimeConfig.activeProjectId(context) }
    val isCustomFirebase = remember(configRevision) { FirebaseRuntimeConfig.isUsingCustomConfig(context) }

    var showFirebaseConfigPanel by rememberSaveable(isFirebaseReady) { mutableStateOf(!isFirebaseReady) }
    var pasteJsonExpanded by rememberSaveable { mutableStateOf(false) }
    var pastedJsonDraft by rememberSaveable { mutableStateOf("") }

    var mode by rememberSaveable { mutableStateOf(AuthFormMode.LOGIN) }
    var accountName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    fun applyImportedJson(rawJson: String) {
        val result = FirebaseRuntimeConfig.importAndActivate(context, rawJson)
        result.onSuccess { cfg ->
            errorMessage = null
            pastedJsonDraft = ""
            pasteJsonExpanded = false
            showFirebaseConfigPanel = false
            infoMessage = "✓ Firebase প্রোজেক্ট \"${cfg.projectId}\" সফলভাবে যুক্ত হয়েছে! এখন জিমেইল ও পাসওয়ার্ড দিয়ে অ্যাকাউন্ট তৈরি বা লগইন করুন।"
        }.onFailure { err ->
            infoMessage = null
            errorMessage = err.message ?: "সঠিক google-services.json ফাইল সিলেক্ট করুন।"
        }
    }

    val pickGoogleServicesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("ফাইল পড়া সম্ভব হয়নি।")
        }.onSuccess { rawJson ->
            applyImportedJson(rawJson)
        }.onFailure { err ->
            errorMessage = "ফাইল পড়া ব্যর্থ: ${err.localizedMessage ?: "আবার চেষ্টা করুন"}"
        }
    }

    fun submitAuth() {
        if (isLoading) return
        if (!FirebaseRuntimeConfig.isReady(context)) {
            showFirebaseConfigPanel = true
            errorMessage = "আগে উপরের \"google-services.json সিলেক্ট করুন\" বাটনে ক্লিক করে আপনার Firebase ফাইলটি যুক্ত করুন।"
            infoMessage = null
            return
        }

        val cleanEmail = email.trim()
        val cleanName = accountName.trim().take(80)

        val validationError = if (mode == AuthFormMode.LOGIN) {
            AuthInputValidator.validateLogin(cleanEmail, password)
        } else {
            AuthInputValidator.validateSignUp(cleanName, cleanEmail, password, confirmPassword)
        }
        if (validationError != null) {
            errorMessage = validationError
            infoMessage = null
            return
        }

        scope.launch {
            isLoading = true
            errorMessage = null
            infoMessage = null
            try {
                val auth = FirebaseRuntimeConfig.auth(context)
                if (mode == AuthFormMode.LOGIN) {
                    auth.signInWithEmailAndPassword(cleanEmail, password).await()
                    val signedInName = auth.currentUser?.displayName?.trim().orEmpty()
                    if (signedInName.isNotBlank() && PilotStateHub.settings.value.ownerDisplayName.isBlank()) {
                        PilotStateHub.updateSettings { it.copy(ownerDisplayName = signedInName.take(80)) }
                    }
                } else {
                    val result = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
                    val user = result.user
                    if (user != null && cleanName.isNotBlank()) {
                        runCatching {
                            val profileUpdates = userProfileChangeRequest {
                                displayName = cleanName
                            }
                            user.updateProfile(profileUpdates).await()
                        }
                        PilotStateHub.updateSettings { it.copy(ownerDisplayName = cleanName) }
                    }
                }
                password = ""
                confirmPassword = ""
                onSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AuthGate", "Email/password auth failed: ${e.javaClass.simpleName}")
                val mapped = AuthInputValidator.mapFirebaseAuthError(e)
                if (e.message.orEmpty().contains("API key not valid", ignoreCase = true)) {
                    showFirebaseConfigPanel = true
                }
                errorMessage = mapped
            } finally {
                isLoading = false
            }
        }
    }

    fun sendPasswordReset() {
        if (isLoading) return
        if (!FirebaseRuntimeConfig.isReady(context)) {
            showFirebaseConfigPanel = true
            errorMessage = "আগে উপরের কার্ড থেকে আপনার Firebase google-services.json ফাইলটি যুক্ত করুন।"
            return
        }
        val cleanEmail = email.trim()
        val err = AuthInputValidator.validateResetEmail(cleanEmail)
        if (err != null) {
            errorMessage = err
            infoMessage = null
            return
        }
        scope.launch {
            isLoading = true
            errorMessage = null
            infoMessage = null
            try {
                FirebaseRuntimeConfig.auth(context).sendPasswordResetEmail(cleanEmail).await()
                infoMessage = "✓ $cleanEmail ঠিকানায় পাসওয়ার্ড রিসেট লিংক পাঠানো হয়েছে। ইনবক্স বা স্প্যাম ফোল্ডার চেক করুন।"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AuthGate", "Password reset failed: ${e.javaClass.simpleName}")
                errorMessage = AuthInputValidator.mapFirebaseAuthError(e)
            } finally {
                isLoading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Brand Header
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(palette.cobaltSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudDone,
                    contentDescription = "LivePilot AI Account",
                    tint = CobaltBlue,
                    modifier = Modifier.size(32.dp)
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
                    text = "আপনার জিমেইল ও পাসওয়ার্ড দিয়ে লগইন করুন বা নতুন অ্যাকাউন্ট তৈরি করুন — আপনার সব ডাটা Firebase-এ সুরক্ষিত থাকবে।",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.inkSecondary,
                    textAlign = TextAlign.Center
                )
            }

            // Firebase Project Status / One-Tap google-services.json Connector
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (isFirebaseReady) palette.card else palette.amberSoft,
                border = BorderStroke(
                    1.dp,
                    if (isFirebaseReady) palette.outline else WarmAmber.copy(alpha = 0.7f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showFirebaseConfigPanel = !showFirebaseConfigPanel },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (isFirebaseReady) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                contentDescription = "Firebase config status",
                                tint = if (isFirebaseReady) EmeraldGreen else WarmAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (isFirebaseReady) {
                                        "Firebase যুক্ত: $activeProjectId"
                                    } else {
                                        "Firebase কনফিগ (`google-services.json`) যুক্ত করুন"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = palette.inkPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (isFirebaseReady) {
                                        if (isCustomFirebase) "আপনার ইম্পোর্ট করা Firebase প্রোজেক্ট চালু আছে" else "অ্যাপের বিল্ট-ইন Firebase কনফিগ চালু আছে"
                                    } else {
                                        "GitHub Actions APK-তে আসল google-services.json না থাকায় API key পাওয়া যায়নি।"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.inkSecondary
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Toggle Firebase config",
                            tint = palette.inkSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    AnimatedVisibility(visible = showFirebaseConfigPanel) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Firebase Console → Project Settings → Your Apps থেকে google-services.json ডাউনলোড করে এখানে সিলেক্ট করুন (১ বার দিলেই স্থায়ীভাবে সেভ থাকবে):",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.inkSecondary
                            )
                            Button(
                                onClick = {
                                    pickGoogleServicesLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("auth_import_google_services_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CobaltBlue,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.UploadFile,
                                    contentDescription = "Select google-services.json",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("google-services.json ফাইল সিলেক্ট করুন", fontWeight = FontWeight.SemiBold)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { pasteJsonExpanded = !pasteJsonExpanded },
                                    modifier = Modifier.testTag("auth_toggle_paste_json_button")
                                ) {
                                    Text(
                                        text = if (pasteJsonExpanded) "পেস্ট বক্স বন্ধ করুন" else "অথবা JSON টেক্সট পেস্ট করুন",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = CobaltBlue
                                    )
                                }
                                if (isCustomFirebase && FirebaseRuntimeConfig.isBundledConfigValid(context)) {
                                    TextButton(
                                        onClick = {
                                            FirebaseRuntimeConfig.clearCustomConfig(context)
                                            infoMessage = "ডিফল্ট Firebase কনফিগে ফিরে যাওয়া হয়েছে।"
                                        }
                                    ) {
                                        Text(
                                            text = "ডিফল্টে ফিরুন",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = palette.roseAccent
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(visible = pasteJsonExpanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = pastedJsonDraft,
                                        onValueChange = { pastedJsonDraft = it },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("auth_paste_google_services_input"),
                                        minLines = 3,
                                        maxLines = 6,
                                        label = { Text("google-services.json এর পুরো টেক্সট পেস্ট করুন") },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = palette.surfaceSubtle,
                                            unfocusedContainerColor = palette.surfaceSubtle,
                                            focusedBorderColor = CobaltBlue,
                                            unfocusedBorderColor = palette.outline
                                        )
                                    )
                                    OutlinedButton(
                                        onClick = { applyImportedJson(pastedJsonDraft) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("auth_save_pasted_json_button"),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("JSON সেভ ও কানেক্ট করুন", color = CobaltBlue, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Main Email & Password Auth Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = palette.card,
                border = BorderStroke(1.dp, palette.outline)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Login / Create Account Segmented Switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.surfaceSubtle)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AuthModeTabButton(
                            label = "লগইন",
                            selected = mode == AuthFormMode.LOGIN,
                            testTag = "auth_tab_login",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                mode = AuthFormMode.LOGIN
                                errorMessage = null
                                infoMessage = null
                            }
                        )
                        AuthModeTabButton(
                            label = "নতুন অ্যাকাউন্ট",
                            selected = mode == AuthFormMode.SIGN_UP,
                            testTag = "auth_tab_signup",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                mode = AuthFormMode.SIGN_UP
                                errorMessage = null
                                infoMessage = null
                            }
                        )
                    }

                    // Account Name (Create Account mode)
                    AnimatedVisibility(visible = mode == AuthFormMode.SIGN_UP) {
                        OutlinedTextField(
                            value = accountName,
                            onValueChange = {
                                accountName = it
                                errorMessage = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_name_input"),
                            enabled = !isLoading,
                            singleLine = true,
                            label = { Text("আপনার নাম (অ্যাকাউন্টের নাম)") },
                            placeholder = { Text("যেমন: ফাহিম") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = "Account name",
                                    tint = CobaltBlue
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Next
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.surfaceSubtle,
                                unfocusedContainerColor = palette.surfaceSubtle,
                                focusedBorderColor = CobaltBlue,
                                unfocusedBorderColor = palette.outline
                            )
                        )
                    }

                    // Gmail / Email Input
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            errorMessage = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_email_input"),
                        enabled = !isLoading,
                        singleLine = true,
                        label = { Text("জিমেইল / ইমেইল") },
                        placeholder = { Text("example@gmail.com") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Email,
                                contentDescription = "Gmail address",
                                tint = CobaltBlue
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = palette.surfaceSubtle,
                            unfocusedContainerColor = palette.surfaceSubtle,
                            focusedBorderColor = CobaltBlue,
                            unfocusedBorderColor = palette.outline
                        )
                    )

                    // Password Input
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_password_input"),
                        enabled = !isLoading,
                        singleLine = true,
                        label = { Text("পাসওয়ার্ড (কমপক্ষে ৬ অক্ষর)") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = "Password",
                                tint = CobaltBlue
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { showPassword = !showPassword },
                                modifier = Modifier.testTag("auth_toggle_password_visibility")
                            ) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showPassword) "পাসওয়ার্ড লুকান" else "পাসওয়ার্ড দেখুন",
                                    tint = palette.inkSecondary
                                )
                            }
                        },
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (mode == AuthFormMode.LOGIN) ImeAction.Done else ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (mode == AuthFormMode.LOGIN) submitAuth()
                            }
                        ),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = palette.surfaceSubtle,
                            unfocusedContainerColor = palette.surfaceSubtle,
                            focusedBorderColor = CobaltBlue,
                            unfocusedBorderColor = palette.outline
                        )
                    )

                    // Confirm Password (Create Account mode)
                    AnimatedVisibility(visible = mode == AuthFormMode.SIGN_UP) {
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = {
                                confirmPassword = it
                                errorMessage = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_confirm_password_input"),
                            enabled = !isLoading,
                            singleLine = true,
                            label = { Text("পাসওয়ার্ড আবার লিখুন") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = "Confirm password",
                                    tint = EmeraldGreen
                                )
                            },
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { submitAuth() }
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = palette.surfaceSubtle,
                                unfocusedContainerColor = palette.surfaceSubtle,
                                focusedBorderColor = EmeraldGreen,
                                unfocusedBorderColor = palette.outline
                            )
                        )
                    }

                    // Forgot Password Link (Login mode)
                    if (mode == AuthFormMode.LOGIN) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { sendPasswordReset() },
                                enabled = !isLoading,
                                modifier = Modifier.testTag("auth_forgot_password_button")
                            ) {
                                Text(
                                    text = "পাসওয়ার্ড ভুলে গেছেন?",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = CobaltBlue
                                )
                            }
                        }
                    }

                    // Error / Success Feedback Banner
                    if (!errorMessage.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = palette.roseSoft,
                            border = BorderStroke(1.dp, palette.roseAccent.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = errorMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.roseAccent,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (!infoMessage.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = palette.emeraldSoft,
                            border = BorderStroke(1.dp, palette.emeraldAccent.copy(alpha = 0.45f))
                        ) {
                            Text(
                                text = infoMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.emeraldAccent,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    // Primary Submit Button
                    Button(
                        onClick = { submitAuth() },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("auth_submit_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mode == AuthFormMode.LOGIN) CobaltBlue else EmeraldGreen,
                            contentColor = Color.White
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (mode == AuthFormMode.LOGIN) "লগইন হচ্ছে..." else "অ্যাকাউন্ট তৈরি হচ্ছে...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Icon(
                                imageVector = if (mode == AuthFormMode.LOGIN) {
                                    Icons.AutoMirrored.Filled.Login
                                } else {
                                    Icons.Filled.PersonAdd
                                },
                                contentDescription = if (mode == AuthFormMode.LOGIN) "Login" else "Create account",
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (mode == AuthFormMode.LOGIN) {
                                    "জিমেইল ও পাসওয়ার্ড দিয়ে লগইন"
                                } else {
                                    "নতুন অ্যাকাউন্ট তৈরি করুন"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Free Explorer Offline Access Card (usable even without internet balance before sign-in)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = palette.emeraldSoft,
                border = BorderStroke(1.dp, palette.emeraldAccent.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(EmeraldGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Explore,
                            contentDescription = "Free Explorer",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Free Explorer",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.inkPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "লগইন বা ইন্টারনেট প্যাকেজ ছাড়াই ফ্রিতে Grameenphone শপ ব্রাউজ করুন।",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkSecondary
                        )
                    }
                    Button(
                        onClick = onOpenFreeExplorer,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("auth_open_free_explorer_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldGreen,
                            contentColor = Color.White
                        )
                    ) {
                        Text("খুলুন", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Account Cloud Sync Summary Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = palette.card,
                border = BorderStroke(1.dp, palette.outline)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "প্রতিটি অ্যাকাউন্টের আলাদা ক্লাউড ডাটাবেস (${deviceProfile.displayLabel})",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.inkPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    FeatureRow(
                        icon = Icons.Filled.CloudDone,
                        tint = EmeraldGreen,
                        text = "আপনার API Key, নির্বাচিত/ডিলিট করা মডেল, \"আমার ডাটা\" ও সেটিংস আপনার অ্যাকাউন্টেই সুরক্ষিত থাকবে — নতুন মোবাইলেও অটো চলে আসবে।"
                    )
                    FeatureRow(
                        icon = Icons.Filled.Shield,
                        tint = CobaltBlue,
                        text = "বকাবাজি ও স্প্যাম গার্ড: ৩ বার সতর্কতার পর বকাবাজি/আবুল-তাবুল এড়িয়ে যাবে, কিন্তু ভালো মেসেজ ও কবিতা/ছবির অনুরোধ নিজেই উত্তর দেবে।"
                    )
                    FeatureRow(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        tint = palette.roseAccent,
                        text = "জরুরি মেসেজে ১ বারই জিজ্ঞেস করবে; কাছে না থাকলে আপনার অ্যাকাউন্টের নাম জানিয়ে অপেক্ষা করতে বলবে।"
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthModeTabButton(
    label: String,
    selected: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = StudioTheme.colors
    Surface(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(11.dp),
        color = if (selected) palette.card else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, CobaltBlue.copy(alpha = 0.55f)) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) CobaltBlue else palette.inkSecondary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
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
