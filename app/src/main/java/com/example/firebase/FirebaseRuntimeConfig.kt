package com.example.firebase

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class ParsedFirebaseConfig(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val storageBucket: String,
    val databaseId: String
)

/**
 * Manages active FirebaseApp / FirebaseAuth / FirebaseFirestore instances seamlessly.
 *
 * - If the APK was compiled with a valid bundled `app/google-services.json` (AI Studio build
 *   or GitHub Actions with `GOOGLE_SERVICES_JSON` secret), it uses the default FirebaseApp automatically.
 * - If the APK was built in CI with a placeholder `google-services.json`, OR if the user wants to
 *   connect their own Firebase project (e.g. JoraShop) at runtime, importing `google-services.json`
 *   activates a persistent custom FirebaseApp immediately without needing to rebuild the APK!
 */
object FirebaseRuntimeConfig {

    private const val PREFS_NAME = "livepilot_firebase_runtime_cfg"
    private const val KEY_PROJECT_ID = "fb_project_id"
    private const val KEY_APP_ID = "fb_app_id"
    private const val KEY_API_KEY = "fb_api_key"
    private const val KEY_STORAGE_BUCKET = "fb_storage_bucket"
    private const val KEY_DATABASE_ID = "fb_database_id"

    private const val CUSTOM_APP_NAME = "livepilot_custom_firebase"
    private const val DEFAULT_AI_STUDIO_PROJECT_ID = "gen-lang-client-0434325320"

    private val _configRevision = MutableStateFlow(0)
    val configRevision: StateFlow<Int> = _configRevision.asStateFlow()

    @Volatile
    private var lastInitializedAppContext: Context? = null

    fun isApiKeyValid(apiKey: String?): Boolean {
        val clean = apiKey?.trim().orEmpty()
        if (clean.length < 20) return false
        if (clean.contains("PLACEHOLDER", ignoreCase = true)) return false
        if (clean.contains("DUMMY", ignoreCase = true)) return false
        return clean.startsWith("AIza")
    }

    fun loadCustomConfig(context: Context): ParsedFirebaseConfig? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val projectId = prefs.getString(KEY_PROJECT_ID, null)?.trim().orEmpty()
        val appId = prefs.getString(KEY_APP_ID, null)?.trim().orEmpty()
        val apiKey = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty()
        val storageBucket = prefs.getString(KEY_STORAGE_BUCKET, null)?.trim().orEmpty()
        val dbId = prefs.getString(KEY_DATABASE_ID, null)?.trim().orEmpty()
        if (projectId.isBlank() || appId.isBlank() || !isApiKeyValid(apiKey)) return null
        return ParsedFirebaseConfig(
            projectId = projectId,
            applicationId = appId,
            apiKey = apiKey,
            storageBucket = storageBucket,
            databaseId = dbId.ifBlank { resolveDefaultDatabaseId(context, projectId) }
        )
    }

    fun isBundledConfigValid(context: Context): Boolean {
        return runCatching {
            val defaultApp = FirebaseApp.getApps(context.applicationContext)
                .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?: FirebaseApp.getInstance()
            val options = defaultApp.options
            isApiKeyValid(options.apiKey) &&
                !options.projectId.orEmpty().contains("placeholder", ignoreCase = true)
        }.getOrDefault(false)
    }

    fun isUsingCustomConfig(context: Context): Boolean = loadCustomConfig(context) != null

    fun isReady(context: Context): Boolean =
        loadCustomConfig(context) != null || isBundledConfigValid(context)

    fun activeProjectId(context: Context): String {
        val custom = loadCustomConfig(context)
        if (custom != null) return custom.projectId
        return runCatching {
            val defaultApp = FirebaseApp.getApps(context.applicationContext)
                .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?: FirebaseApp.getInstance()
            defaultApp.options.projectId.orEmpty()
        }.getOrDefault("")
    }

    fun activeApp(context: Context): FirebaseApp {
        val appCtx = context.applicationContext
        lastInitializedAppContext = appCtx

        val custom = loadCustomConfig(appCtx)
        if (custom != null) {
            val existing = FirebaseApp.getApps(appCtx).firstOrNull { it.name == CUSTOM_APP_NAME }
            if (existing != null &&
                existing.options.apiKey == custom.apiKey &&
                existing.options.projectId == custom.projectId &&
                existing.options.applicationId == custom.applicationId
            ) {
                return existing
            }
            if (existing != null) {
                runCatching { existing.delete() }
            }
            val optionsBuilder = FirebaseOptions.Builder()
                .setApiKey(custom.apiKey)
                .setApplicationId(custom.applicationId)
                .setProjectId(custom.projectId)
            if (custom.storageBucket.isNotBlank()) {
                optionsBuilder.setStorageBucket(custom.storageBucket)
            }
            return FirebaseApp.initializeApp(appCtx, optionsBuilder.build(), CUSTOM_APP_NAME)
        }

        return FirebaseApp.getApps(appCtx).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: FirebaseApp.getInstance()
    }

    fun auth(context: Context): FirebaseAuth {
        return FirebaseAuth.getInstance(activeApp(context))
    }

    fun currentUserOrNull(context: Context? = lastInitializedAppContext): FirebaseUser? {
        if (context != null) {
            return runCatching { auth(context).currentUser }.getOrNull()
        }
        return runCatching {
            val custom = runCatching { FirebaseApp.getInstance(CUSTOM_APP_NAME) }.getOrNull()
            if (custom != null) {
                FirebaseAuth.getInstance(custom).currentUser
            } else {
                FirebaseAuth.getInstance().currentUser
            }
        }.getOrNull()
    }

    fun firestore(context: Context): FirebaseFirestore {
        val appCtx = context.applicationContext
        val app = activeApp(appCtx)
        val custom = loadCustomConfig(appCtx)
        val dbId = custom?.databaseId ?: resolveDefaultDatabaseId(appCtx, app.options.projectId.orEmpty())
        return FirebaseFirestore.getInstance(app, dbId)
    }

    fun parseGoogleServicesJson(
        rawJson: String,
        targetPackage: String,
        defaultAiStudioDbId: String = ""
    ): Result<ParsedFirebaseConfig> {
        return runCatching {
            val trimmed = rawJson.trim()
            if (trimmed.isBlank()) {
                error("google-services.json ফাইলটি খালি।")
            }
            val root = JSONObject(trimmed)
            val projectInfo = root.optJSONObject("project_info")
                ?: error("সঠিক google-services.json নয় (project_info পাওয়া যায়নি)।")
            val projectId = projectInfo.optString("project_id").trim()
            if (projectId.isBlank() || projectId.contains("placeholder", ignoreCase = true)) {
                error("সঠিক Firebase project_id পাওয়া যায়নি। Firebase Console থেকে আসল google-services.json ডাউনলোড করুন।")
            }
            val storageBucket = projectInfo.optString("storage_bucket").trim()

            val clients = root.optJSONArray("client")
                ?: error("google-services.json-এ Android client তথ্য পাওয়া যায়নি।")
            if (clients.length() == 0) {
                error("google-services.json-এ কোনো Android অ্যাপ যুক্ত নেই।")
            }

            // Prefer exact package match; fall back to first client so user's own Firebase Android app works too
            var chosenClient: JSONObject? = null
            for (i in 0 until clients.length()) {
                val candidate = clients.optJSONObject(i) ?: continue
                val pkg = candidate.optJSONObject("client_info")
                    ?.optJSONObject("android_client_info")
                    ?.optString("package_name")
                    ?.trim()
                if (pkg.equals(targetPackage, ignoreCase = true)) {
                    chosenClient = candidate
                    break
                }
            }
            val client = chosenClient ?: clients.optJSONObject(0)
                ?: error("সঠিক client ব্লক পাওয়া যায়নি।")

            val appId = client.optJSONObject("client_info")
                ?.optString("mobilesdk_app_id")
                ?.trim()
                .orEmpty()
            if (appId.isBlank()) {
                error("mobilesdk_app_id পাওয়া যায়নি।")
            }

            val apiKeys = client.optJSONArray("api_key")
            var validKey = ""
            if (apiKeys != null) {
                for (i in 0 until apiKeys.length()) {
                    val key = apiKeys.optJSONObject(i)?.optString("current_key")?.trim().orEmpty()
                    if (isApiKeyValid(key)) {
                        validKey = key
                        break
                    }
                }
            }
            if (!isApiKeyValid(validKey)) {
                error("এই ফাইলে বৈধ Firebase API Key পাওয়া যায়নি। Firebase Console থেকে আসল google-services.json দিন।")
            }

            val dbId = if (projectId == DEFAULT_AI_STUDIO_PROJECT_ID && defaultAiStudioDbId.isNotBlank()) {
                defaultAiStudioDbId
            } else {
                "(default)"
            }

            ParsedFirebaseConfig(
                projectId = projectId,
                applicationId = appId,
                apiKey = validKey,
                storageBucket = storageBucket,
                databaseId = dbId
            )
        }
    }

    fun importAndActivate(context: Context, rawJson: String): Result<ParsedFirebaseConfig> {
        val appCtx = context.applicationContext
        val defaultDbId = runCatching { appCtx.getString(R.string.firestore_database_id) }.getOrDefault("")
        val parsed = parseGoogleServicesJson(
            rawJson = rawJson,
            targetPackage = appCtx.packageName,
            defaultAiStudioDbId = defaultDbId
        ).getOrElse { return Result.failure(it) }

        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PROJECT_ID, parsed.projectId)
            .putString(KEY_APP_ID, parsed.applicationId)
            .putString(KEY_API_KEY, parsed.apiKey)
            .putString(KEY_STORAGE_BUCKET, parsed.storageBucket)
            .putString(KEY_DATABASE_ID, parsed.databaseId)
            .apply()

        // Activate immediately
        runCatching { activeApp(appCtx) }
            .onFailure { Log.e("FirebaseRuntimeConfig", "Failed to initialize imported FirebaseApp", it) }

        _configRevision.value = _configRevision.value + 1
        return Result.success(parsed)
    }

    fun clearCustomConfig(context: Context) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching {
            FirebaseApp.getApps(appCtx).firstOrNull { it.name == CUSTOM_APP_NAME }?.delete()
        }
        _configRevision.value = _configRevision.value + 1
    }

    private fun resolveDefaultDatabaseId(context: Context, projectId: String): String {
        val configured = runCatching { context.getString(R.string.firestore_database_id) }.getOrDefault("")
        return if (projectId == DEFAULT_AI_STUDIO_PROJECT_ID && configured.isNotBlank()) {
            configured
        } else {
            "(default)"
        }
    }
}
