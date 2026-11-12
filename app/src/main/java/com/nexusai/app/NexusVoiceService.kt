
package com.nexusai.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast

class NexusVoiceService : Service() {

    companion object {
        const val ACTION_START =
            "com.nexusai.app.action.START_VOICE"

        const val ACTION_STOP =
            "com.nexusai.app.action.STOP_VOICE"

        const val EXTRA_LANGUAGE =
            "com.nexusai.app.extra.VOICE_LANGUAGE"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val channelId = "nexus_voice_channel"
    private val notificationId = 1001

    private var recognizer: SpeechRecognizer? = null
    private var serviceRunning = false
    private var recognitionLanguage = "en-US"
    private var retryCount = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_STOP -> {
                stopVoiceEngine()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                // Continue.
            }

            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (serviceRunning) {
            return START_NOT_STICKY
        }

        if (!hasMicrophonePermission()) {
            showMessage("Microphone permission required.")
            stopSelf()
            return START_NOT_STICKY
        }

        recognitionLanguage =
            intent.getStringExtra(EXTRA_LANGUAGE) ?: "en-US"

        try {

            val notification = Notification.Builder(
                this,
                channelId
            )
                .setContentTitle("NEXUS AI")
                .setContentText("NEXUS Voice Engine ක්‍රියාත්මකයි")
                .setSmallIcon(
                    android.R.drawable.ic_btn_speak_now
                )
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= 29) {

                startForeground(
                    notificationId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )

            } else {

                startForeground(
                    notificationId,
                    notification
                )
            }

            serviceRunning = true
            retryCount = 0

            beginRecognitionSession()

        } catch (e: SecurityException) {

            showMessage("Microphone access unavailable.")
            stopVoiceEngine()
            stopSelf()

        } catch (e: Exception) {

            showMessage("NEXUS Voice Engine could not start.")
            stopVoiceEngine()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun hasMicrophonePermission(): Boolean {

        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                channelId,
                "NEXUS Voice Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "NEXUS Voice Recognition Service"
            }

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    private fun beginRecognitionSession() {

        if (!serviceRunning) return

        if (!hasMicrophonePermission()) {
            showMessage("Microphone permission missing.")
            stopSelf()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showMessage("Voice recognition unavailable.")
            stopVoiceEngine()
            stopSelf()
            return
        }

        try {

            recognizer?.destroy()

            val currentRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            recognizer = currentRecognizer

            currentRecognizer.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        retryCount = 0
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {

                        if (recognizer !== currentRecognizer) return

                        if (
                            error ==
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                        ) {
                            showMessage("Microphone permission denied.")
                            stopVoiceEngine()
                            stopSelf()
                            return
                        }

                        restartRecognition()
                    }

                    override fun onResults(results: Bundle?) {

                        if (recognizer !== currentRecognizer) return

                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val spoken = matches
                            ?.firstOrNull()
                            ?.lowercase()
                            .orEmpty()

                        if (
                            spoken.contains("nexus") ||
                            spoken.contains("නෙක්සස්")
                        ) {
                            showMessage("NEXUS අවදි වුණා! 💙")
                        }

                        retryCount = 0
                        restartRecognition()
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {}

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )

            val speechIntent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    recognitionLanguage
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    recognitionLanguage
                )

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
                )

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    3
                )
            }

            currentRecognizer.startListening(speechIntent)

        } catch (e: SecurityException) {

            showMessage("Microphone access unavailable.")
            stopVoiceEngine()
            stopSelf()

        } catch (e: Exception) {

            restartRecognition()
        }
    }

    private fun restartRecognition() {

        if (!serviceRunning) return

        handler.removeCallbacksAndMessages(null)

        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }

        recognizer = null

        retryCount++

        val delay = minOf(
            1000L * retryCount,
            5000L
        )

        handler.postDelayed({

            if (serviceRunning) {
                beginRecognitionSession()
            }

        }, delay)
    }

    private fun stopVoiceEngine() {

        serviceRunning = false

        handler.removeCallbacksAndMessages(null)

        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }

        recognizer = null
    }

    private fun showMessage(message: String) {

        handler.post {

            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {

        stopVoiceEngine()

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
