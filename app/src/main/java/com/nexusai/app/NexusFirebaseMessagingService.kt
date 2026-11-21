package com.nexusai.app

import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Accepts only the fixed NEXUS presence wake; never executes remote sensor commands. */
class NexusFirebaseMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        serviceScope.launch { registerToken(token) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data["type"] != "NEXUS_WAKE" || data["action"] != "SYNC_PRESENCE") return
        val requestId = data["requestId"]?.takeIf { it.isNotBlank() } ?: return
        val deviceId = stableDeviceId()
        if (deviceId.isBlank()) return

        serviceScope.launch {
            try {
                postAuthenticated("/devices/push/ack",
                    JSONObject().put("deviceId", deviceId)
                        .put("requestId", requestId).put("stage", "received"))
                val work = OneTimeWorkRequestBuilder<NexusPresenceWorker>()
                    .setInputData(Data.Builder()
                        .putString(NexusPresenceWorker.KEY_REQUEST_ID, requestId).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                    "nexus-presence-$requestId", ExistingWorkPolicy.KEEP, work
                )
            } catch (error: Exception) {
                Log.e(TAG, "Could not queue or acknowledge NEXUS wake request", error)
            }
        }
    }

    private fun stableDeviceId(): String =
        getSharedPreferences("nexus_local_data", MODE_PRIVATE)
            .getString("stable_device_id", "").orEmpty()

    private suspend fun registerToken(token: String) {
        if (token.isBlank()) return
        val deviceId = stableDeviceId()
        if (deviceId.isBlank()) return
        try {
            postAuthenticated("/devices/push/register",
                JSONObject().put("deviceId", deviceId).put("fcmToken", token))
        } catch (error: Exception) {
            Log.e(TAG, "FCM token registration failed; retry on next app launch", error)
        }
    }

    private suspend fun postAuthenticated(path: String, payload: JSONObject) {
        withContext(Dispatchers.IO) {
            val accessToken = NexusAuthManager.restoreSession(applicationContext)
                ?: throw IllegalStateException("NEXUS session is not available")
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
                    throw IllegalStateException("NEXUS backend HTTP $status: ${body.take(180)}")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    companion object {
        private const val TAG = "NEXUS_FCM"
        private const val BACKEND_BASE_URL = "https://nexus-ai-backend-six.vercel.app"
    }
}
