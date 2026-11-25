package com.nexusai.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object NexusPushApi {
    private const val BACKEND = "https://nexus-ai-backend-six.vercel.app/"

    fun register(context: Context, fallbackAccessToken: String, fcmToken: String): Boolean {
        val accessToken = NexusAuthManager.refreshAccessToken(context, fallbackAccessToken)
        if (accessToken.isBlank() || fcmToken.isBlank()) return false
        return post(
            "devices/push/register",
            accessToken,
            JSONObject()
                .put("deviceId", getStableDeviceId(context))
                .put("fcmToken", fcmToken)
        )
    }

    fun registerWithStoredSession(context: Context, fcmToken: String): Boolean {
        val accessToken = NexusAuthManager.restoreSession(context) ?: return false
        return register(context, accessToken, fcmToken)
    }

    fun acknowledge(context: Context, requestId: String, stage: String): Boolean {
        if (requestId.isBlank() || stage !in setOf("received", "processed")) return false
        val accessToken = NexusAuthManager.restoreSession(context) ?: return false
        return post(
            "devices/push/ack",
            accessToken,
            JSONObject()
                .put("deviceId", getStableDeviceId(context))
                .put("requestId", requestId)
                .put("stage", stage)
        )
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
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}
