package com.nexusai.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Registers this installation's FCM token only after the authenticated
 * installation has been paired with the NEXUS account.
 */
internal object NexusPushManager {
    private const val TAG = "NexusPush"
    private const val PREFS_NAME = "nexus_local_data"
    private const val DEVICE_ID_KEY = "stable_device_id"
    private const val BACKEND_URL = "https://nexus-ai-backend-six.vercel.app"
    private val executor = Executors.newSingleThreadExecutor()

    fun registerCurrentToken(context: Context) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> registerToken(context, token) }
            .addOnFailureListener { error -> Log.w(TAG, "Could not obtain FCM token", error) }
    }

    fun registerToken(context: Context, token: String) {
        if (token.isBlank()) return
        executor.execute {
            try {
                val accessToken = NexusAuthManager.restoreSession(context) ?: return@execute
                val deviceId = stableDeviceId(context) ?: return@execute
                post(
                    "/devices/push/register",
                    accessToken,
                    JSONObject().put("deviceId", deviceId).put("fcmToken", token)
                )
            } catch (error: Exception) {
                Log.w(TAG, "FCM token registration failed", error)
            }
        }
    }

    // FirebaseMessagingService invokes onMessageReceived on a worker thread.
    // Complete the short authenticated heartbeat before returning from the callback.
    fun handleWake(context: Context) {
        try {
            val accessToken = NexusAuthManager.restoreSession(context) ?: return
            val deviceId = stableDeviceId(context) ?: return
            post(
                "/devices/heartbeat",
                accessToken,
                JSONObject().put("deviceId", deviceId)
            )
            NexusWakeNotification.show(context)
        } catch (error: Exception) {
            Log.w(TAG, "NEXUS wake heartbeat failed", error)
        }
    }

    private fun stableDeviceId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(DEVICE_ID_KEY, null)
            ?.takeIf { it.isNotBlank() }

    private fun post(path: String, accessToken: String, payload: JSONObject) {
        val connection = (URL("$BACKEND_URL$path").openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("NEXUS push endpoint returned HTTP $status: $body")
            }
            connection.inputStream?.close()
        } finally {
            connection.disconnect()
        }
    }
}

private object NexusWakeNotification {
    private const val CHANNEL_ID = "nexus_device_wake"
    private const val NOTIFICATION_ID = 4107

    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "NEXUS device connection",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Visible status when this phone receives a NEXUS device wake signal."
                }
            )
        }

        val openApp = PendingIntent.getActivity(
            context,
            4107,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("NEXUS AI")
            .setContentText("Device wake signal received. This phone is online.")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w("NexusPush", "Notification permission is not available", error)
        }
    }
}

class NexusFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        NexusPushManager.registerToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data["type"] == "NEXUS_WAKE" &&
            message.data["action"] == "SYNC_PRESENCE"
        ) {
            NexusPushManager.handleWake(applicationContext)
        }
    }
}
