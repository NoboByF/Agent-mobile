package com.example.firebase

import android.content.Context
import com.example.R
import com.example.data.AppThemeMode
import com.example.data.AskAiMessage
import com.example.data.AutoChatMode
import com.example.data.AutoReplyDelivery
import com.example.data.MessagingApp
import com.example.data.PersonalDataItem
import com.example.data.PilotPreferences
import com.example.data.PilotRoutine
import com.example.data.PilotSettings
import com.example.data.RoutineAccent
import com.example.data.VoiceLanguage
import com.example.data.WebCitation
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class CloudSyncRepository(context: Context) {

    private val appContext = context.applicationContext
    val deviceProfile: DeviceProfileIdentity = DeviceIdentityHelper.resolve(appContext)

    private val db: FirebaseFirestore
        get() = FirebaseRuntimeConfig.firestore(appContext)

    private fun requireUserId(): String =
        FirebaseRuntimeConfig.auth(appContext).currentUser?.uid
            ?: throw IllegalStateException("User must be signed in to access cloud storage")

    private fun accountConfigPath(uid: String): String =
        "users/$uid/account/config"

    private fun accountConfigRef(uid: String) =
        db.collection("users")
            .document(uid)
            .collection("account")
            .document("config")

    private fun personalDataColRef(uid: String) =
        db.collection("users")
            .document(uid)
            .collection("personalData")

    private fun routinesColRef(uid: String) =
        db.collection("users")
            .document(uid)
            .collection("routines")

    private fun askAiColRef(uid: String) =
        db.collection("users")
            .document(uid)
            .collection("askAiMessages")

    /**
     * Real-time listener for THIS account's cloud config (owner name, API keys, selected models,
     * deleted models, settings). Restores automatically on ANY new phone signed into this account!
     */
    fun observeAccountConfig(): Flow<PilotSettings?> {
        val uid = requireUserId()
        val path = accountConfigPath(uid)
        return accountConfigRef(uid)
            .snapshots()
            .map { snapshot ->
                if (!snapshot.exists()) return@map null
                val doc = snapshot.toObject(
                    AccountCloudConfigDoc::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: return@map null
                doc.toPilotSettings()
            }
            .catch { error ->
                if (error is CancellationException) {
                    val cause = error.cause
                    if (cause is FirebaseFirestoreException) {
                        FirestoreErrorHandler.handleFirestoreError(cause, OperationType.GET, path)
                    }
                    throw error
                }
                if (error is Exception) {
                    FirestoreErrorHandler.handleFirestoreError(error, OperationType.GET, path)
                }
                emit(null)
            }
    }

    /**
     * Saves this account's complete settings, API key pool, selected models, deletedModels,
     * and ownerDisplayName to Firestore so logging into a new phone restores everything automatically.
     */
    suspend fun saveAccountConfig(settings: PilotSettings): Result<Unit> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val path = accountConfigPath(uid)
        return try {
            val currentUser = FirebaseRuntimeConfig.auth(appContext).currentUser
            val docRef = accountConfigRef(uid)
            val exists = docRef.get().await().exists()
            val serializedKeys = PilotPreferences.serializeApiKeysJson(settings.apiKeys).take(31500)
            val cleanDeletedModels = settings.deletedModels
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(100)
            val enabledAppsList = settings.enabledMessagingApps.map { it.name }.sorted().take(10)
            val resolvedOwnerName = settings.ownerDisplayName.trim()
                .ifBlank { currentUser?.displayName?.trim().orEmpty() }
                .take(80)
            val resolvedEmail = currentUser?.email?.trim().orEmpty().take(160)

            val mutableFields = hashMapOf<String, Any>(
                "ownerDisplayName" to resolvedOwnerName,
                "ownerEmail" to resolvedEmail,
                "lastDeviceModel" to deviceProfile.displayLabel.ifBlank { "Android" }.take(120),
                "apiKeysJson" to serializedKeys,
                "activeApiKeyId" to settings.activeApiKeyId.orEmpty().take(80),
                "autoRotateApiKeys" to settings.autoRotateApiKeys,
                "liveModel" to settings.liveModel.trim().removePrefix("models/").take(120),
                "agentModel" to settings.agentModel.trim().removePrefix("models/").take(120),
                "textModel" to settings.textModel.trim().removePrefix("models/").take(120),
                "deletedModels" to cleanDeletedModels,
                "verifyTaskCompletion" to settings.verifyTaskCompletion,
                "voiceName" to settings.voiceName.trim().take(40),
                "voiceLanguage" to settings.voiceLanguage.name.take(40),
                "speakerphoneOn" to settings.speakerphoneOn,
                "autoVisionDefaultOn" to settings.autoVisionDefaultOn,
                "autoVisionIntervalSec" to settings.autoVisionIntervalSec.coerceIn(2, 10),
                "showTouchVisualizer" to settings.showTouchVisualizer,
                "showFloatingCaptions" to settings.showFloatingCaptions,
                "themeMode" to settings.themeMode.name.take(20),
                "customVoiceInstructions" to settings.customVoiceInstructions.trim().take(2000),
                "autoChatMode" to settings.autoChatMode.name.take(40),
                "autoReplyDelivery" to settings.autoReplyDelivery.name.take(40),
                "autoReplyPersona" to settings.autoReplyPersona.trim().take(1500),
                "sensitiveQuestionGuardEnabled" to settings.sensitiveQuestionGuardEnabled,
                "handsFreeVoiceEscalationEnabled" to settings.handsFreeVoiceEscalationEnabled,
                "enabledMessagingApps" to enabledAppsList,
                "updatedAt" to FieldValue.serverTimestamp()
            )

            if (exists) {
                docRef.update(mutableFields).await()
            } else {
                val createFields = HashMap(mutableFields)
                createFields["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(createFields).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    // --- Account-Level Personal Data ('আমার ডাটা') ---

    fun observePersonalData(): Flow<List<PersonalDataItem>> {
        val uid = requireUserId()
        val path = "users/$uid/personalData"
        return personalDataColRef(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { docSnap ->
                    val doc = docSnap.toObject(
                        PersonalDataDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: return@mapNotNull null
                    PersonalDataItem(
                        id = doc.id.ifBlank { docSnap.id },
                        title = doc.title,
                        content = doc.content,
                        category = doc.category.ifBlank { "পরিচয়" },
                        shareInAutoReply = doc.shareInAutoReply,
                        updatedAtMs = doc.updatedAt?.toDate()?.time
                            ?: doc.createdAt?.toDate()?.time
                            ?: System.currentTimeMillis()
                    )
                }
            }
            .catch { error ->
                if (error is CancellationException) {
                    val cause = error.cause
                    if (cause is FirebaseFirestoreException) {
                        FirestoreErrorHandler.handleFirestoreError(cause, OperationType.LIST, path)
                    }
                    throw error
                }
                if (error is Exception) {
                    FirestoreErrorHandler.handleFirestoreError(error, OperationType.LIST, path)
                }
                emit(emptyList())
            }
    }

    suspend fun upsertPersonalData(item: PersonalDataItem): Result<Unit> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val safeId = sanitizeDocId(item.id, "pd")
        val path = "users/$uid/personalData/$safeId"
        return try {
            val docRef = personalDataColRef(uid).document(safeId)
            val exists = docRef.get().await().exists()
            val cleanTitle = item.title.trim().take(120)
            val cleanContent = item.content.trim().take(1500)
            val cleanCategory = item.category.trim().ifBlank { "পরিচয়" }.take(50)
            if (exists) {
                docRef.update(
                    mapOf(
                        "title" to cleanTitle,
                        "content" to cleanContent,
                        "category" to cleanCategory,
                        "shareInAutoReply" to item.shareInAutoReply,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "title" to cleanTitle,
                        "content" to cleanContent,
                        "category" to cleanCategory,
                        "shareInAutoReply" to item.shareInAutoReply,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deletePersonalData(entryId: String): Result<Unit> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val safeId = sanitizeDocId(entryId, "pd")
        val path = "users/$uid/personalData/$safeId"
        return try {
            personalDataColRef(uid).document(safeId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- Account-Level Custom Routines ---

    fun observeCustomRoutines(): Flow<List<PilotRoutine>> {
        val uid = requireUserId()
        val path = "users/$uid/routines"
        return routinesColRef(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { docSnap ->
                    val doc = docSnap.toObject(
                        CloudRoutineDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: return@mapNotNull null
                    val accent = runCatching { RoutineAccent.valueOf(doc.accent) }
                        .getOrDefault(RoutineAccent.COBALT)
                    PilotRoutine(
                        id = doc.id.ifBlank { docSnap.id },
                        title = doc.title,
                        subtitle = doc.subtitle,
                        prompt = doc.prompt,
                        accent = accent,
                        isBuiltIn = false
                    )
                }
            }
            .catch { error ->
                if (error is CancellationException) {
                    val cause = error.cause
                    if (cause is FirebaseFirestoreException) {
                        FirestoreErrorHandler.handleFirestoreError(cause, OperationType.LIST, path)
                    }
                    throw error
                }
                if (error is Exception) {
                    FirestoreErrorHandler.handleFirestoreError(error, OperationType.LIST, path)
                }
                emit(emptyList())
            }
    }

    suspend fun saveCustomRoutine(routine: PilotRoutine): Result<Unit> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val safeId = sanitizeDocId(routine.id, "routine")
        val path = "users/$uid/routines/$safeId"
        return try {
            val docRef = routinesColRef(uid).document(safeId)
            val exists = docRef.get().await().exists()
            val common = mapOf(
                "title" to routine.title.trim().take(100),
                "subtitle" to routine.subtitle.trim().take(160),
                "prompt" to routine.prompt.trim().take(2000),
                "accent" to routine.accent.name.take(30),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (exists) {
                docRef.update(common).await()
            } else {
                docRef.set(common + ("createdAt" to FieldValue.serverTimestamp())).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteCustomRoutine(routineId: String): Result<Unit> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val safeId = sanitizeDocId(routineId, "routine")
        val path = "users/$uid/routines/$safeId"
        return try {
            routinesColRef(uid).document(safeId).delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    // --- Account-Level Ask AI Chat History ---

    fun observeAskAiMessages(): Flow<List<AskAiMessage>> {
        val uid = requireUserId()
        val path = "users/$uid/askAiMessages"
        return askAiColRef(uid)
            .orderBy("clientId", Query.Direction.ASCENDING)
            .limitToLast(40)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { docSnap ->
                    val doc = docSnap.toObject(
                        CloudAskAiMessageDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: return@mapNotNull null
                    AskAiMessage(
                        id = doc.clientId,
                        isUser = doc.senderRole == "user",
                        text = doc.text,
                        citations = parseCitationsJson(doc.citationsJson),
                        modelUsed = doc.modelUsed.ifBlank { null },
                        timestamp = doc.timestampLabel
                    )
                }
            }
            .catch { error ->
                if (error is CancellationException) {
                    val cause = error.cause
                    if (cause is FirebaseFirestoreException) {
                        FirestoreErrorHandler.handleFirestoreError(cause, OperationType.LIST, path)
                    }
                    throw error
                }
                if (error is Exception) {
                    FirestoreErrorHandler.handleFirestoreError(error, OperationType.LIST, path)
                }
                emit(emptyList())
            }
    }

    suspend fun saveAskAiMessage(message: AskAiMessage): Result<Unit> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val safeId = "msg_${message.id}"
        val path = "users/$uid/askAiMessages/$safeId"
        return try {
            val docRef = askAiColRef(uid).document(safeId)
            val exists = docRef.get().await().exists()
            val citationsJson = serializeCitationsJson(message.citations).take(7900)
            val cleanText = message.text.trim().ifBlank { "(media)" }.take(15900)
            if (exists) {
                docRef.update(
                    mapOf(
                        "text" to cleanText,
                        "modelUsed" to message.modelUsed.orEmpty().take(120),
                        "timestampLabel" to message.timestamp.take(40),
                        "citationsJson" to citationsJson,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "clientId" to message.id.coerceAtLeast(0L),
                        "senderRole" to if (message.isUser) "user" else "assistant",
                        "text" to cleanText,
                        "modelUsed" to message.modelUsed.orEmpty().take(120),
                        "timestampLabel" to message.timestamp.take(40),
                        "citationsJson" to citationsJson,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun clearAskAiMessages(): Result<Unit> {
        val uid = runCatching { requireUserId() }.getOrElse { return Result.failure(it) }
        val path = "users/$uid/askAiMessages"
        return try {
            val snap = askAiColRef(uid).get().await()
            val batch = db.batch()
            snap.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    private fun AccountCloudConfigDoc.toPilotSettings(): PilotSettings {
        val parsedKeys = PilotPreferences.parseApiKeysJson(apiKeysJson)
        val resolvedActiveId = activeApiKeyId.takeIf { id -> id.isNotBlank() && parsedKeys.any { it.id == id } }
            ?: parsedKeys.firstOrNull { it.enabled }?.id
            ?: parsedKeys.firstOrNull()?.id
        val mirrorKey = parsedKeys.firstOrNull { it.id == resolvedActiveId }?.key
            ?: parsedKeys.firstOrNull { it.enabled }?.key
            ?: ""

        val apps = enabledMessagingApps.mapNotNull { name ->
            runCatching { MessagingApp.valueOf(name) }.getOrNull()
        }.toSet().ifEmpty { MessagingApp.entries.toSet() }

        return PilotSettings(
            ownerDisplayName = ownerDisplayName.trim(),
            apiKey = mirrorKey,
            apiKeys = parsedKeys,
            activeApiKeyId = resolvedActiveId,
            autoRotateApiKeys = autoRotateApiKeys,
            liveModel = liveModel.ifBlank { PilotSettings.DEFAULT_LIVE_MODEL },
            agentModel = agentModel.ifBlank { PilotSettings.DEFAULT_AGENT_MODEL },
            textModel = textModel.ifBlank { PilotSettings.DEFAULT_TEXT_MODEL },
            deletedModels = deletedModels.filter { it.isNotBlank() }.distinct(),
            verifyTaskCompletion = verifyTaskCompletion,
            voiceName = voiceName.ifBlank { PilotSettings.DEFAULT_VOICE },
            voiceLanguage = runCatching { VoiceLanguage.valueOf(voiceLanguage) }
                .getOrDefault(VoiceLanguage.BENGALI_BD),
            speakerphoneOn = speakerphoneOn,
            autoVisionDefaultOn = autoVisionDefaultOn,
            autoVisionIntervalSec = autoVisionIntervalSec.coerceIn(2, 10),
            showTouchVisualizer = showTouchVisualizer,
            showFloatingCaptions = showFloatingCaptions,
            themeMode = runCatching { AppThemeMode.valueOf(themeMode) }
                .getOrDefault(AppThemeMode.SYSTEM),
            customVoiceInstructions = customVoiceInstructions,
            autoChatMode = runCatching { AutoChatMode.valueOf(autoChatMode) }
                .getOrDefault(AutoChatMode.LIVE_VOICE_ALERT),
            autoReplyDelivery = runCatching { AutoReplyDelivery.valueOf(autoReplyDelivery) }
                .getOrDefault(AutoReplyDelivery.SILENT_DIRECT_REPLY),
            autoReplyPersona = autoReplyPersona.ifBlank { PilotSettings.DEFAULT_AUTO_REPLY_PERSONA },
            sensitiveQuestionGuardEnabled = sensitiveQuestionGuardEnabled,
            handsFreeVoiceEscalationEnabled = handsFreeVoiceEscalationEnabled,
            enabledMessagingApps = apps
        )
    }

    companion object {
        private val DOC_ID_SANITIZE = Regex("[^a-zA-Z0-9_\\-]")

        fun sanitizeDocId(raw: String, prefix: String): String {
            val cleaned = raw.replace(DOC_ID_SANITIZE, "_").take(72)
            return if (cleaned.isNotBlank()) cleaned else "${prefix}_${System.currentTimeMillis()}"
        }

        private fun serializeCitationsJson(citations: List<WebCitation>): String {
            val arr = JSONArray()
            citations.forEach { c ->
                arr.put(JSONObject().put("title", c.title).put("url", c.url))
            }
            return arr.toString()
        }

        private fun parseCitationsJson(raw: String): List<WebCitation> {
            if (raw.isBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val url = obj.optString("url", "")
                        if (url.isNotBlank()) {
                            add(WebCitation(title = obj.optString("title", url), url = url))
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
