package com.nexusai.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class NexusFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("nexus_local_data", Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FCM_TOKEN, token)
            .apply()

        Thread {
            NexusPushApi.registerWithStoredSession(applicationContext, token)
        }.start()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val requestId = data["requestId"].orEmpty()
        when (data["type"]) {
            "NEXUS_WAKE" -> {
                if (requestId.isNotBlank()) {
                    Thread {
                        NexusPushApi.acknowledge(applicationContext, requestId, "received")
                        NexusPushApi.acknowledge(applicationContext, requestId, "processed")
                    }.start()
                }
            }

            "NEXUS_REMOTE_REQUEST" -> {
                val feature = data["feature"].orEmpty()
                if (requestId.isBlank() || feature !in REMOTE_FEATURES) return

                getSharedPreferences("nexus_local_data", Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PENDING_REMOTE_REQUEST_ID, requestId)
                    .putString(KEY_PENDING_REMOTE_FEATURE, feature)
                    .apply()

                Thread {
                    NexusPushApi.acknowledge(applicationContext, requestId, "received")
                    showApprovalNotification(requestId, feature)
                    NexusPushApi.acknowledge(applicationContext, requestId, "processed")
                }.start()
            }
        }
    }

    private fun showApprovalNotification(requestId: String, feature: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "NEXUS device requests",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Requests that need approval on this device"
                }
            )
        }

        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("nexus_remote_request_id", requestId)
            putExtra("nexus_remote_feature", feature)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestId.hashCode(),
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val label = feature.replaceFirstChar { it.uppercase() }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("NEXUS AI – Permission request")
            .setContentText("$label access requested. Open NEXUS AI to review.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$label access was requested by your paired NEXUS device. Open the app to review and approve or decline.")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(requestId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission may be disabled; the pending request remains saved in app storage.
        }
    }

    private companion object {
        const val CHANNEL_ID = "nexus_device_requests"
        const val KEY_FCM_TOKEN = "nexus_fcm_token"
        const val KEY_PENDING_REMOTE_REQUEST_ID = "nexus_pending_remote_request_id"
        const val KEY_PENDING_REMOTE_FEATURE = "nexus_pending_remote_feature"
        val REMOTE_FEATURES = setOf("camera", "microphone", "gallery", "location", "screen")
    }
}
