package com.nexusai.app

import android.content.Context
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
                    // Keep the request queued for the background feature handler.
                    // Mark it processed only after the requested operation completes.
                    NexusPushApi.acknowledge(applicationContext, requestId, "received")
                }.start()
            }
        }
    }

    private companion object {
        const val KEY_FCM_TOKEN = "nexus_fcm_token"
        const val KEY_PENDING_REMOTE_REQUEST_ID = "nexus_pending_remote_request_id"
        const val KEY_PENDING_REMOTE_FEATURE = "nexus_pending_remote_feature"
        val REMOTE_FEATURES = setOf("camera", "microphone", "gallery", "location", "screen")
    }
}
