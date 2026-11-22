package com.example.firebase

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType {
    CREATE,
    UPDATE,
    DELETE,
    LIST,
    GET,
    WRITE
}

object FirestoreErrorHandler {

    fun handleFirestoreError(
        exception: Exception,
        operationType: OperationType,
        path: String?
    ) {
        val currentUser = FirebaseRuntimeConfig.currentUserOrNull()
        val providerArray = JSONArray()
        currentUser?.providerData?.forEach { info ->
            providerArray.put(
                JSONObject()
                    .put("providerId", info.providerId)
                    .put("displayName", info.displayName)
                    .put("email", info.email)
                    .put("photoUrl", info.photoUrl?.toString())
            )
        }

        val errJson = JSONObject()
            .put("error", exception.message ?: exception.toString())
            .put("operationType", operationType.name.lowercase())
            .put("path", path)
            .put(
                "authInfo",
                JSONObject()
                    .put("userId", currentUser?.uid)
                    .put("email", currentUser?.email)
                    .put("emailVerified", currentUser?.isEmailVerified)
                    .put("isAnonymous", currentUser?.isAnonymous)
                    .put("providerInfo", providerArray)
            )

        Log.e("FirestoreError", errJson.toString(), exception)
    }
}
