package com.nexusai.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object NexusPushApi {
    private const val BACKEND = "https://nexus-ai-backend-six.vercel.app/"
    private const val PREFS_NAME = "nexus_local_data"
    private const val KEY_FCM_TOKEN = "nexus_fcm_token"
    private const val TAG = "NexusPushApi"

    private fun stableDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "stable_device_id"
        val existing = prefs.getString(key, null)
        if (!existing.isNullOrBlank()) return existing
        val created = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(key, created).apply()
        return created
    }

    fun register(context: Context, fallbackAccessToken: String, fcmToken: String): Boolean {
        if (fcmToken.isBlank()) {
            Log.w(TAG, "Push registration skipped: FCM token is blank.")
            return false
        }

        // Keep the latest token so a later pairing attempt can retry registration.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FCM_TOKEN, fcmToken)
            .apply()

        val accessToken = NexusAuthManager.refreshAccessToken(context, fallbackAccessToken)
        if (accessToken.isBlank()) {
            Log.w(TAG, "Push registration skipped: no access token is available.")
            return false
        }

        val payload = JSONObject()
            .put("deviceId", stableDeviceId(context))
            .put("fcmToken", fcmToken)

        return postWithRetry("devices/push/register", accessToken, payload)
    }

    fun registerWithStoredSession(context: Context, fcmToken: String): Boolean {
        val accessToken = NexusAuthManager.restoreSession(context) ?: return false
        return register(context, accessToken, fcmToken)
    }

    fun acknowledge(context: Context, requestId: String, stage: String): Boolean {
        if (requestId.isBlank() || stage !in setOf("received", "processed")) return false
        val accessToken = NexusAuthManager.restoreSession(context) ?: return false
        return postWithRetry(
            "devices/push/ack",
            accessToken,
            JSONObject()
                .put("deviceId", stableDeviceId(context))
                .put("requestId", requestId)
                .put("stage", stage)
        )
    }

    private fun postWithRetry(
        route: String,
        accessToken: String,
        payload: JSONObject
    ): Boolean {
        repeat(2) { attempt ->
            if (post(route, accessToken, payload)) return true
            if (attempt == 0) {
                try {
                    Thread.sleep(700)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        Log.e(TAG, "Request failed after retry: $route")
        return false
    }

    private fun post(route: String, accessToken: String, payload: JSONObject): Boolean {
        val connection = (URL(BACKEND + route).openConnection() as HttpURLConnection)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(TAG, "Request failed: $route returned HTTP $status")
            }
            status in 200..299
        } catch (error: Exception) {
            Log.w(TAG, "Request error: $route (${error.javaClass.simpleName})")
            false
        } finally {
            connection.disconnect()
        }
    }
}
