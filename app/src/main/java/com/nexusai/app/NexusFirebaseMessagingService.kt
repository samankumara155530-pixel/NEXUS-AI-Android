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
                val controllerDeviceId = data["controllerDeviceId"].orEmpty()
                val targetDeviceId = data["targetDeviceId"].orEmpty()
                if (requestId.isBlank() || feature !in REMOTE_FEATURES) return

                getSharedPreferences("nexus_local_data", Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PENDING_REMOTE_REQUEST_ID, requestId)
                    .putString(KEY_PENDING_REMOTE_FEATURE, feature)
                    .putString(KEY_PENDING_CONTROLLER_DEVICE_ID, controllerDeviceId)
                    .putString(KEY_PENDING_TARGET_DEVICE_ID, targetDeviceId)
                    .apply()

                Thread {
                    NexusPushApi.acknowledge(applicationContext, requestId, "received")
                }.start()

                // A visible notification is the only entry point: receiving FCM
                // never starts the camera or grants consent automatically.
                if (feature == "camera") showCameraConsentNotification(requestId)
            }
        }
    }

    private fun showCameraConsentNotification(requestId: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CAMERA_CHANNEL_ID,
                    "NEXUS Remote Camera",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Requests to share this phone's camera"
                }
            )
        }

        val openConsent = Intent(this, NexusRemoteCameraActivity::class.java).apply {
            putExtra(NexusRemoteCameraActivity.EXTRA_REQUEST_ID, requestId)
            putExtra(NexusRemoteCameraActivity.EXTRA_CONTROLLER_DEVICE_ID, getSharedPreferences("nexus_local_data", Context.MODE_PRIVATE).getString(KEY_PENDING_CONTROLLER_DEVICE_ID, "").orEmpty())
            putExtra(NexusRemoteCameraActivity.EXTRA_TARGET_DEVICE_ID, getSharedPreferences("nexus_local_data", Context.MODE_PRIVATE).getString(KEY_PENDING_TARGET_DEVICE_ID, "").orEmpty())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestId.hashCode(),
            openConsent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CAMERA_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("NEXUS AI – Remote Camera")
            .setContentText("A paired phone is requesting camera access. Tap to review and approve or decline.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "A paired phone is requesting camera access. The camera will remain off until you open this request and approve it."
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(requestId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notifications may be disabled by the user; no camera action occurs.
        }
    }

    private companion object {
        const val KEY_FCM_TOKEN = "nexus_fcm_token"
        const val KEY_PENDING_REMOTE_REQUEST_ID = "nexus_pending_remote_request_id"
        const val KEY_PENDING_REMOTE_FEATURE = "nexus_pending_remote_feature"
        const val KEY_PENDING_CONTROLLER_DEVICE_ID = "nexus_pending_controller_device_id"
        const val KEY_PENDING_TARGET_DEVICE_ID = "nexus_pending_target_device_id"
        const val CAMERA_CHANNEL_ID = "nexus_remote_camera_requests"
        val REMOTE_FEATURES = setOf("camera", "microphone", "gallery", "location", "screen")
    }
}
