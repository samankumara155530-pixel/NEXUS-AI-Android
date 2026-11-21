package com.nexusai.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * User-visible, consent-gated microphone recording for a remote NEXUS session.
 * Records AAC audio for at most 30 seconds and always exposes a Stop action.
 */
class NexusAudioRecordService : Service() {

    companion object {
        const val ACTION_START = "com.nexusai.app.action.START_REMOTE_AUDIO"
        const val ACTION_STOP = "com.nexusai.app.action.STOP_REMOTE_AUDIO"
        const val EXTRA_REQUEST_ID = "remote_audio_request_id"
        private const val CHANNEL_ID = "nexus_remote_audio_channel"
        private const val NOTIFICATION_ID = 1003
        private const val MAX_DURATION_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var requestId: String = ""
    private var recording = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishRecording("stopped_by_user")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (recording) return START_NOT_STICKY
                requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
                if (requestId.isBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startRecording()
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            saveFailure("microphone_permission_missing")
            stopSelf()
            return
        }

        try {
            val stopIntent = Intent(this, NexusAudioRecordService::class.java).apply { action = ACTION_STOP }
            val stopPendingIntent = PendingIntent.getService(
                this,
                NOTIFICATION_ID,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("NEXUS AI — Microphone recording")
                .setContentText("Recording up to 30 seconds. Tap Stop to finish now.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop recording", stopPendingIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val directory = File(cacheDir, "nexus_remote_audio").apply { mkdirs() }
            outputFile = File(directory, "remote_${requestId}.m4a")
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = mediaRecorder
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(64_000)
            mediaRecorder.setAudioSamplingRate(22_050)
            mediaRecorder.setOutputFile(outputFile!!.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recording = true

            getSharedPreferences("nexus_local_data", MODE_PRIVATE).edit()
                .putString("remote_audio_active_request_id", requestId)
                .remove("remote_audio_result_request_id")
                .remove("remote_audio_result_path")
                .remove("remote_audio_result_error")
                .apply()

            handler.postDelayed({
                finishRecording("duration_limit_reached")
                stopSelf()
            }, MAX_DURATION_MS)
        } catch (error: SecurityException) {
            saveFailure("microphone_security_exception")
            stopSelf()
        } catch (error: Exception) {
            saveFailure(error.message ?: "audio_recording_start_failed")
            stopSelf()
        }
    }

    private fun finishRecording(reason: String) {
        handler.removeCallbacksAndMessages(null)
        val active = recorder
        recorder = null
        var success = false
        try {
            if (recording && active != null) {
                active.stop()
                success = true
            }
        } catch (_: RuntimeException) {
            success = false
        } finally {
            recording = false
            runCatching { active?.reset() }
            runCatching { active?.release() }
        }

        val file = outputFile
        val prefs = getSharedPreferences("nexus_local_data", MODE_PRIVATE)
        if (success && file != null && file.exists() && file.length() > 0L) {
            prefs.edit()
                .putString("remote_audio_result_request_id", requestId)
                .putString("remote_audio_result_path", file.absolutePath)
                .putLong("remote_audio_result_size", file.length())
                .putString("remote_audio_result_mime", "audio/mp4")
                .putString("remote_audio_result_reason", reason)
                .remove("remote_audio_active_request_id")
                .apply()
        } else {
            file?.delete()
            prefs.edit()
                .putString("remote_audio_result_request_id", requestId)
                .putString("remote_audio_result_error", "audio_recording_empty_or_failed")
                .remove("remote_audio_active_request_id")
                .apply()
        }
    }

    private fun saveFailure(reason: String) {
        getSharedPreferences("nexus_local_data", MODE_PRIVATE).edit()
            .putString("remote_audio_result_request_id", requestId)
            .putString("remote_audio_result_error", reason.take(160))
            .remove("remote_audio_active_request_id")
            .apply()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NEXUS Remote Audio Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Visible notification while the user-approved NEXUS microphone recording is active"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (recording) finishRecording("service_destroyed")
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
