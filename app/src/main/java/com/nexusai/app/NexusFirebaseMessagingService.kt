package com.nexusai.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
 * Target-only handler for the fixed NEXUS presence wake.
 * It records the event locally, posts a visible notification when Android
 * notification permission is available, then acknowledges the real result.
 * It never executes arbitrary commands or accesses camera/microphone/location.
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

        val data: Map<String, String> = message.data
        val requestId = data["requestId"].orEmpty()
        if (requestId.isBlank()) return

        if (data["type"] == "NEXUS_REMOTE_REQUEST") {
            val feature = data["feature"] ?: return
            val action = data["action"] ?: return
            val allowedFeatures = setOf("camera", "microphone", "gallery", "location", "screen", "session")
            if (feature !in allowedFeatures) return

            val prefs = getSharedPreferences("nexus_local_data", Context.MODE_PRIVATE)
            if (action == "REQUEST_APPROVAL") {
                prefs.edit()
                    .putString("pending_remote_request_id", requestId)
                    .putString("pending_remote_feature", feature)
                    .putLong("pending_remote_created_at", System.currentTimeMillis())
                    .apply()
                showRemoteApprovalNotification(requestId, feature)
                return
            }

            if (action == "EXECUTE_APPROVED") {
                val expectedSessionId = data["sessionRequestId"].orEmpty()
                val storedSessionId = prefs.getString("remote_session_request_id", "").orEmpty()
                val storedExpiry = prefs.getLong("remote_session_expires_at", 0L)
                if (expectedSessionId.isBlank() || expectedSessionId != storedSessionId ||
                    storedExpiry <= System.currentTimeMillis()
                ) {
                    Log.w(TAG, "Ignored remote action because the local approved session is not active")
                    return
                }
                prefs.edit()
                    .putString("pending_remote_request_id", requestId)
                    .putString("pending_remote_feature", feature)
                    .putLong("pending_remote_created_at", System.currentTimeMillis())
                    .apply()
                if (!prefs.getBoolean("target_ui_foreground", false)) {
                    showRemoteActionNotification(feature)
                }
                return
            }
            return
        }

        if (data["type"] != "NEXUS_WAKE" || data["action"] != "SYNC_PRESENCE") return

        serviceScope.launch {
            try {
                // Persist first so the target can display the last event on next launch.
                val receivedAt = System.currentTimeMillis()
                getSharedPreferences("nexus_local_data", Context.MODE_PRIVATE)
                    .edit()
                    .putString("target_last_wake_request_id", requestId)
                    .putLong("target_last_wake_received_at", receivedAt)
                    .putString("target_last_wake_status", "processed")
                    .commit()

                NexusFcm.acknowledge(applicationContext, requestId, "received")

                // Visible proof on the Target phone when notifications are allowed.
                withContext(Dispatchers.Main) {
                    showWakeNotification(requestId)
                }

                NexusFcm.acknowledge(applicationContext, requestId, "processed")
                Log.i(TAG, "NEXUS_WAKE processed requestId=$requestId")
            } catch (error: Exception) {
                getSharedPreferences("nexus_local_data", Context.MODE_PRIVATE)
                    .edit()
                    .putString("target_last_wake_status", "acknowledgement_failed")
                    .apply()
                Log.e(TAG, "Wake handling/acknowledgement failed: ${error.message}", error)
            }
        }
    }

    private fun showRemoteApprovalNotification(requestId: String, feature: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "NEXUS Target Requests",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Remote request stored; notification permission is not granted")
            return
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("nexus_remote_request_id", requestId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, requestId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when (feature) {
            "camera" -> "Camera access request"
            "microphone" -> "Microphone access request"
            "gallery" -> "Gallery access request"
            "screen" -> "Screen sharing request"
            else -> "Location access request"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NEXUS AI — $title")
            .setContentText("Open NEXUS AI on this phone to review and approve or decline.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(requestId.hashCode(), notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "Remote request notification could not be shown: ${error.message}")
        }
    }

    private fun showRemoteActionNotification(feature: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "NEXUS Target Requests",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, ("approved-$feature").hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NEXUS AI — Approved remote session")
            .setContentText("Open NEXUS AI to continue the approved $feature action.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(("approved-$feature").hashCode(), notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "Remote action notification could not be shown: ${error.message}")
        }
    }

    private fun showWakeNotification(requestId: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "NEXUS Target Wake",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Shows when this Target phone receives a NEXUS wake request"
                }
            )
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Wake recorded; notification permission is not granted")
            return
        }

        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("NEXUS AI — Target Wake")
                .setContentText("Wake message received and processed.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "This Target phone received and processed a NEXUS wake request. Request: ${requestId.take(8)}"
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification could not be shown: ${error.message}")
        }
    }

    private companion object {
        const val TAG = "NEXUS_FCM"
        const val CHANNEL_ID = "nexus_target_wake"
        const val NOTIFICATION_ID = 2101
    }
}

internal object NexusFcm {
    private const val BACKEND = "https://nexus-ai-backend-six.vercel.app"

    suspend fun registerCurrentInstallation(
        context: android.content.Context,
        currentAccessToken: String
    ) {
        val token = withContext(Dispatchers.IO) {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.await()
        }
        registerToken(context, token, currentAccessToken)
    }

    suspend fun registerToken(
        context: android.content.Context,
        token: String,
        currentAccessToken: String? = null
    ) {
        if (token.isBlank()) throw IllegalStateException("FCM_TOKEN_EMPTY")
        val accessToken = currentAccessToken?.takeIf { it.isNotBlank() }
            ?: withContext(Dispatchers.IO) { NexusAuthManager.restoreSession(context) }
            ?: throw IllegalStateException("NEXUS_SESSION_UNAVAILABLE_FOR_FCM_REGISTRATION")

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

    suspend fun uploadScreenFrame(
        context: android.content.Context,
        requestId: String,
        frame: JSONObject
    ) {
        val accessToken = withContext(Dispatchers.IO) {
            NexusAuthManager.restoreSession(context)
        } ?: throw IllegalStateException("NEXUS session unavailable")
        post(
            context,
            accessToken,
            "/devices/remote/screen/frame",
            JSONObject()
                .put("deviceId", getStableDeviceId(context))
                .put("requestId", requestId)
                .put("frame", frame)
        )
    }

    suspend fun respondRemote(
        context: android.content.Context,
        requestId: String,
        status: String,
        result: JSONObject
    ) {
        val accessToken = withContext(Dispatchers.IO) {
            NexusAuthManager.restoreSession(context)
        } ?: throw IllegalStateException("NEXUS session unavailable")
        post(
            context,
            accessToken,
            "/devices/remote/respond",
            JSONObject()
                .put("deviceId", getStableDeviceId(context))
                .put("requestId", requestId)
                .put("status", status)
                .put("result", result)
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
