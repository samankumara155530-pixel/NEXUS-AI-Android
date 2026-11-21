package com.nexusai.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Best-effort device presence heartbeat.
 *
 * WorkManager persists this work across process death and device reboot.
 * Android controls the actual execution time; periodic work has a 15-minute
 * minimum interval and is not a real-time connection guarantee.
 */
class NexusHeartbeatWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(
            "nexus_local_data",
            Context.MODE_PRIVATE
        )
        val deviceId = prefs.getString("stable_device_id", null)
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure()

        val accessToken = try {
            NexusAuthManager.refreshAccessToken(applicationContext, "")
        } catch (_: Exception) {
            ""
        }

        if (accessToken.isBlank()) {
            return@withContext Result.retry()
        }

        // FCM registration is best-effort; heartbeat must still work without Firebase config.
        try {
            NexusPushManager.registerCurrentToken(applicationContext)
        } catch (error: Exception) {
            Log.w(
                "NexusHeartbeatWorker",
                "FCM token registration failed; heartbeat will continue",
                error
            )
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$BACKEND_BASE_URL/devices/heartbeat")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject().put("deviceId", deviceId)
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            when (connection.responseCode) {
                in 200..299 -> {
                    val wakeRequestId = inputData.getString("wake_request_id").orEmpty()
                    if (wakeRequestId.isNotBlank()) {
                        try {
                            NexusPushManager.acknowledgeWake(
                                applicationContext,
                                deviceId,
                                wakeRequestId,
                                "processed"
                            )
                        } catch (ackError: Exception) {
                            Log.w("NexusHeartbeatWorker", "Wake processing acknowledgement failed", ackError)
                            return@withContext Result.retry()
                        }
                    }
                    Result.success()
                }
                400, 404, 410 -> Result.failure()
                401, 408, 429 -> Result.retry()
                in 500..599 -> Result.retry()
                else -> Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val BACKEND_BASE_URL =
            "https://nexus-ai-backend-six.vercel.app"

        private const val PERIODIC_WORK = "nexus_device_heartbeat_periodic"
        private const val IMMEDIATE_WORK = "nexus_device_heartbeat_initial"

        fun enable(context: Context, wakeRequestId: String? = null) {
            val manager = WorkManager.getInstance(context)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodic = PeriodicWorkRequestBuilder<NexusHeartbeatWorker>(
                15, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

            manager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodic
            )

            val immediateBuilder = OneTimeWorkRequestBuilder<NexusHeartbeatWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
            if (!wakeRequestId.isNullOrBlank()) {
                immediateBuilder.setInputData(
                    Data.Builder().putString("wake_request_id", wakeRequestId).build()
                )
            }
            val immediate = immediateBuilder.build()
            val uniqueName = if (wakeRequestId.isNullOrBlank()) IMMEDIATE_WORK else "$IMMEDIATE_WORK:$wakeRequestId"
            manager.enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.KEEP,
                immediate
            )
        }

        fun disable(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        }
    }
}
