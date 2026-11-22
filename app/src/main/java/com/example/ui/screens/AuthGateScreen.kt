package com.example.ui.screens

import android.util.Log
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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.PilotStateHub
import com.example.firebase.DeviceProfileIdentity
import com.example.ui.theme.CobaltBlue
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.StudioTheme
import com.google.firebase.Firebase
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.auth
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
        return when {
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
                "Firebase Console-এ Email/Password অপশন চালু আছে কিনা যাচাই করুন।"
            code == "ERROR_TOO_MANY_REQUESTS" ->
                "অনেকবার ভুল চেষ্টা হয়েছে। কিছুক্ষণ পর আবার চেষ্টা করুন।"
            else ->
                "অ্যাকাউন্ট প্রসেস ব্যর্থ হয়েছে: ${err.localizedMessage ?: "আবার চেষ্টা করুন"}"
        }
    }
}

suspend fun signOutFromFirebase() {
    withContext(NonCancellable) {
        runCatching { Firebase.auth.signOut() }
    }
}

@Composable
fun AuthGateScreen(
    deviceProfile: DeviceProfileIdentity,
    onSignedIn: () -> Unit
) {
    val palette = StudioTheme.colors
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(AuthFormMode.LOGIN) }
    var accountName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    fun submitAuth() {
        if (isLoading) return
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
                if (mode == AuthFormMode.LOGIN) {
                    Firebase.auth.signInWithEmailAndPassword(cleanEmail, password).await()
                    val signedInName = Firebase.auth.currentUser?.displayName?.trim().orEmpty()
                    if (signedInName.isNotBlank() && PilotStateHub.settings.value.ownerDisplayName.isBlank()) {
                        PilotStateHub.updateSettings { it.copy(ownerDisplayName = signedInName.take(80)) }
                    }
                } else {
                    val result = Firebase.auth.createUserWithEmailAndPassword(cleanEmail, password).await()
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
                errorMessage = AuthInputValidator.mapFirebaseAuthError(e)
            } finally {
                isLoading = false
            }
        }
    }

    fun sendPasswordReset() {
        if (isLoading) return
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
                Firebase.auth.sendPasswordResetEmail(cleanEmail).await()
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Brand Header
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(palette.cobaltSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudDone,
                    contentDescription = "LivePilot AI Account",
                    tint = CobaltBlue,
                    modifier = Modifier.size(34.dp)
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
