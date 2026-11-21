package com.nexusai.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives narrowly scoped FCM data messages.
 * Remote messages can request presence sync only; they cannot start sensitive features.
 */
class NexusFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NexusPushManager.registerToken(applicationContext, token)
            } catch (_: Exception) {
                // The paired heartbeat worker retries registration later.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data["type"] == "NEXUS_WAKE" &&
            message.data["action"] == "SYNC_PRESENCE"
        ) {
            val requestId = message.data["requestId"].orEmpty()
            val prefs = getSharedPreferences("nexus_local_data", MODE_PRIVATE)
            val deviceId = prefs.getString("stable_device_id", null).orEmpty()
            if (requestId.isNotBlank() && deviceId.isNotBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        NexusPushManager.acknowledgeWake(
                            applicationContext,
                            deviceId,
                            requestId,
                            "received"
                        )
                    } catch (_: Exception) {
                        // The processed acknowledgement from the worker is retried if needed.
                    }
                }
                NexusHeartbeatWorker.enable(applicationContext, requestId)
            } else {
                // Legacy or malformed wake messages must not trigger unrelated work.
                android.util.Log.w("NexusFCM", "Ignored wake message without request/device identity")
            }
        }
    }
}
