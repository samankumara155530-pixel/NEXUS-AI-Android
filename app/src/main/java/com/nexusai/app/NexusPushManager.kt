package com.nexusai.app

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Registers an FCM token against this already-paired installation. */
internal object NexusPushManager {
    private const val TAG = "NexusPushManager"
    private const val BACKEND_BASE_URL = "https://nexus-ai-backend-six.vercel.app"
    private const val PREFS = "nexus_local_data"
    private const val REGISTERED_TOKEN_KEY = "nexus_registered_fcm_token"
    private const val REGISTERED_AT_KEY = "nexus_fcm_registered_at"

    suspend fun registerCurrentToken(context: Context) = withContext(Dispatchers.IO) {
        val token = Tasks.await(FirebaseMessaging.getInstance().token)
        registerToken(context, token)
    }

    suspend fun registerToken(context: Context, token: String) = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            Log.w(TAG, "FCM returned an empty registration token")
            return@withContext
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastRegisteredToken = prefs.getString(REGISTERED_TOKEN_KEY, null)
        val lastRegisteredAt = prefs.getLong(REGISTERED_AT_KEY, 0L)
        if (lastRegisteredToken == token && System.currentTimeMillis() - lastRegisteredAt < 24L * 60L * 60L * 1000L) {
            return@withContext
        }

        val deviceId = prefs.getString("stable_device_id", null)
            ?.takeIf { it.isNotBlank() } ?: run {
                Log.w(TAG, "FCM registration skipped: stable device ID is missing")
                return@withContext
            }
        val accessToken = NexusAuthManager.refreshAccessToken(context, "")
        if (accessToken.isBlank()) {
            Log.w(TAG, "FCM registration skipped: no valid authenticated session")
            return@withContext
        }

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
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val responseBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                val backendError = try {
                    JSONObject(responseBody).optString("error").takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
                throw IllegalStateException(
                    "FCM token registration failed: HTTP $responseCode" +
                        (backendError?.let { " ($it)" } ?: "")
                )
            }
            Log.i(TAG, "FCM token registered successfully")
            prefs.edit()
                .putString(REGISTERED_TOKEN_KEY, token)
                .putLong(REGISTERED_AT_KEY, System.currentTimeMillis())
                .apply()
        } finally {
            connection?.disconnect()
        }
    }
}
