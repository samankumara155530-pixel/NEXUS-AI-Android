package com.nexusai.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Confirms a wake only after an authenticated heartbeat succeeds. */
class NexusPresenceWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestId = inputData.getString(KEY_REQUEST_ID).orEmpty()
        val deviceId = applicationContext
            .getSharedPreferences("nexus_local_data", Context.MODE_PRIVATE)
            .getString("stable_device_id", "").orEmpty()
        if (requestId.isBlank() || deviceId.isBlank()) return@withContext Result.failure()

        try {
            val accessToken = NexusAuthManager.restoreSession(applicationContext)
                ?: return@withContext Result.retry()
            post("/devices/heartbeat", accessToken, JSONObject().put("deviceId", deviceId))
            post("/devices/push/ack", accessToken,
                JSONObject().put("deviceId", deviceId)
                    .put("requestId", requestId).put("stage", "processed"))
            Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Presence wake processing failed; WorkManager will retry", error)
            Result.retry()
        }
    }

    private fun post(path: String, accessToken: String, payload: JSONObject) {
        val connection = URL(BACKEND_BASE_URL + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("Backend HTTP $status: ${body.take(160)}")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val KEY_REQUEST_ID = "request_id"
        private const val TAG = "NEXUS_PRESENCE"
        private const val BACKEND_BASE_URL = "https://nexus-ai-backend-six.vercel.app"
    }
}
