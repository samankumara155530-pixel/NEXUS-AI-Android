package com.nexusai.app

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Registers an FCM token against this already-paired installation. */
internal object NexusPushManager {
    private const val BACKEND_BASE_URL = "https://nexus-ai-backend-six.vercel.app"
    private const val PREFS = "nexus_local_data"

    suspend fun registerCurrentToken(context: Context) = withContext(Dispatchers.IO) {
        val token = Tasks.await(FirebaseMessaging.getInstance().token)
        registerToken(context, token)
    }

    suspend fun registerToken(context: Context, token: String) = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val deviceId = prefs.getString("stable_device_id", null)
            ?.takeIf { it.isNotBlank() } ?: return@withContext
        val accessToken = NexusAuthManager.refreshAccessToken(context, "")
        if (accessToken.isBlank()) return@withContext

        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$BACKEND_BASE_URL/devices/push/register")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject().put("deviceId", deviceId).put("fcmToken", token)
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("FCM token registration failed: HTTP ${connection.responseCode}")
            }
        } finally {
            connection?.disconnect()
        }
    }
}
