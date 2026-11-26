package com.nexusai.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object NexusRemoteCameraApi {
    private const val BACKEND = "https://nexus-ai-backend-six.vercel.app/"

    private fun token(context: Context, fallback: String): String {
        if (fallback.isNotBlank()) return fallback
        return NexusAuthManager.restoreSession(context).orEmpty()
    }

    fun post(context: Context, fallbackToken: String, route: String, payload: JSONObject): JSONObject {
        return request(context, fallbackToken, route, "POST", payload)
    }

    fun get(context: Context, fallbackToken: String, route: String): JSONObject {
        return request(context, fallbackToken, route, "GET", null)
    }

    private fun request(
        context: Context,
        fallbackToken: String,
        route: String,
        method: String,
        payload: JSONObject?
    ): JSONObject {
        val accessToken = token(context, fallbackToken)
        if (accessToken.isBlank()) throw IllegalStateException("NEXUS_SESSION_REQUIRED")
        val connection = (URL(BACKEND + route).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 12000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) {
                throw IllegalStateException(json.optString("error").ifBlank { "HTTP_$status" })
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    fun uploadFrame(
        context: Context,
        accessToken: String,
        deviceId: String,
        requestId: String,
        jpegBase64: String,
        lensFacing: String,
        sequence: Long
    ) {
        val frame = JSONObject()
            .put("cameraCapture", "live_frame")
            .put("imageJpegBase64", jpegBase64)
            .put("lensFacing", lensFacing)
            .put("sequence", sequence)
            .put("capturedAt", java.time.Instant.now().toString())
        post(
            context, accessToken, "devices/remote/camera/frame",
            JSONObject().put("deviceId", deviceId)
                .put("requestId", requestId)
                .put("frame", frame)
        )
    }
}
