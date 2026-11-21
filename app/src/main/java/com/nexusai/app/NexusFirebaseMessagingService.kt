package com.nexusai.app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handles only NEXUS's fixed, data-only presence wake message.
 * It never opens the UI or accesses camera, microphone, files, or location.
 */
class NexusFirebaseMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        serviceScope.launch {
            try {
                NexusFcm.registerToken(applicationContext, token)
            } catch (error: Exception) {
                Log.w(TAG, "FCM token registration deferred: ${error.message}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (BuildConfig.NEXUS_DEVICE_ROLE != "target") return

        val data = message.data
        if (data["type"] != "NEXUS_WAKE" || data["action"] != "SYNC_PRESENCE") return

        val requestId = data["requestId"].orEmpty()
        if (requestId.isBlank()) return

        serviceScope.launch {
            try {
                NexusFcm.acknowledge(applicationContext, requestId, "received")
                // The allow-listed wake has been received and safely handled.
                NexusFcm.acknowledge(applicationContext, requestId, "processed")
            } catch (error: Exception) {
                Log.e(TAG, "Wake acknowledgement failed: ${error.message}", error)
            }
        }
    }

    private companion object {
        const val TAG = "NEXUS_FCM"
    }
}

internal object NexusFcm {
    private const val BACKEND = "https://nexus-ai-backend-six.vercel.app"

    suspend fun registerCurrentInstallation(context: android.content.Context) {
        val token = withContext(Dispatchers.IO) {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
        }
        registerToken(context, token)
    }

    suspend fun registerToken(context: android.content.Context, token: String) {
        if (token.isBlank()) return
        val accessToken = withContext(Dispatchers.IO) {
            NexusAuthManager.restoreSession(context)
        } ?: return

        post(
            context,
            accessToken,
            "/devices/push/register",
            JSONObject()
                .put("deviceId", getStableDeviceId(context))
                .put("fcmToken", token)
        )
    }

    suspend fun acknowledge(
        context: android.content.Context,
        requestId: String,
        stage: String
    ) {
        val accessToken = withContext(Dispatchers.IO) {
            NexusAuthManager.restoreSession(context)
        } ?: throw IllegalStateException("NEXUS session unavailable")

        post(
            context,
            accessToken,
            "/devices/push/ack",
            JSONObject()
                .put("deviceId", getStableDeviceId(context))
                .put("requestId", requestId)
                .put("stage", stage)
        )
    }

    private suspend fun post(
        context: android.content.Context,
        accessToken: String,
        path: String,
        payload: JSONObject
    ) = withContext(Dispatchers.IO) {
        val connection = (URL(BACKEND + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("NEXUS FCM API HTTP $status: ${body.take(180)}")
            }
        } finally {
            connection.disconnect()
        }
    }
}
