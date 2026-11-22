package com.nexusai.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.util.Log

/**
 * Stores a consent-gated remote request until the Target user opens NEXUS
 * and explicitly approves or declines it. A push message never starts a
 * camera, microphone, screen capture, or other sensor by itself.
 */
internal object NexusRemoteRequestStore {
    private const val PREFS = "nexus_remote_requests"
    private const val KEY_ID = "pending_request_id"
    private const val KEY_FEATURE = "pending_feature"
    private const val KEY_CREATED = "pending_created_at"
    private const val CHANNEL = "nexus_remote_approval"

    data class Pending(val requestId: String, val feature: String, val createdAt: String)

    fun save(context: Context, requestId: String, feature: String, createdAt: String) {
        if (!requestId.matches(Regex("[0-9a-fA-F-]{36}"))) return
        if (feature !in setOf("camera", "microphone", "gallery", "location", "screen")) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ID, requestId)
            .putString(KEY_FEATURE, feature)
            .putString(KEY_CREATED, createdAt)
            .apply()
        showApprovalNotification(context, feature)
    }

    fun load(context: Context): Pending? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_ID, null) ?: return null
        val feature = prefs.getString(KEY_FEATURE, null) ?: return null
        if (feature !in setOf("camera", "microphone", "gallery", "location", "screen")) return null
        return Pending(id, feature, prefs.getString(KEY_CREATED, "").orEmpty())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ID).remove(KEY_FEATURE).remove(KEY_CREATED).apply()
        NotificationManagerCompat.from(context).cancel(4108)
    }

    private fun showApprovalNotification(context: Context, feature: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "NEXUS remote access requests", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Visible approval prompts for remote feature requests." }
            )
        }
        val open = PendingIntent.getActivity(
            context, 4108,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val label = feature.replaceFirstChar { it.uppercase() }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("NEXUS AI — $label access request")
            .setContentText("Open NEXUS AI to review and approve or decline this request.")
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(4108, notification)
        } catch (error: SecurityException) {
            Log.w("NexusRemote", "Notification permission unavailable; request remains pending in app.", error)
        }
    }
}
