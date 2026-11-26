package com.nexusai.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Controller-side viewer for the consented low-frame-rate remote camera preview. */
class NexusRemoteCameraViewerActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var imageView: ImageView
    private lateinit var stopButton: Button
    private var polling = false
    private var stopped = false
    private var lastSequence = -1L

    private val requestId by lazy { intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty() }
    private val targetDeviceId by lazy { intent.getStringExtra(EXTRA_TARGET_DEVICE_ID).orEmpty() }
    private val controllerDeviceId by lazy { intent.getStringExtra(EXTRA_CONTROLLER_DEVICE_ID).orEmpty() }
    private var accessToken = ""

    private val poll = object : Runnable {
        override fun run() {
            if (!polling || stopped || isFinishing || isDestroyed) return
            pollStatus()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accessToken = intent.getStringExtra(EXTRA_ACCESS_TOKEN).orEmpty()
        statusView = TextView(this).apply {
            text = "Target දුරකථනයේ අවසරය බලාපොරොත්තු වේ..."
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }
        imageView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        stopButton = Button(this).apply {
            text = "සම්බන්ධතාව නවත්වන්න"
            setOnClickListener { stopRemoteSession() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(statusView)
            addView(imageView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(stopButton)
        })

        if (requestId.isBlank() || targetDeviceId.isBlank() || controllerDeviceId.isBlank()) {
            statusView.text = "Remote Camera session විස්තර අසම්පූර්ණයි."
            stopButton.isEnabled = false
            return
        }
        if (accessToken.isBlank()) {
            lifecycleScope.launch {
                accessToken = withContext(Dispatchers.IO) {
                    NexusAuthManager.restoreSession(this@NexusRemoteCameraViewerActivity).orEmpty()
                }
                if (accessToken.isBlank()) {
                    statusView.text = "NEXUS AI සැසිය කල් ඉකුත් වී ඇත. නැවත පිවිසෙන්න."
                    stopButton.isEnabled = false
                } else startPolling()
            }
        } else startPolling()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        handler.post(poll)
    }

    private fun pollStatus() {
        lifecycleScope.launch {
            try {
                val route = "devices/$targetDeviceId/remote/status?controllerDeviceId=$controllerDeviceId&requestId=$requestId"
                val json = withContext(Dispatchers.IO) {
                    NexusRemoteCameraApi.get(this@NexusRemoteCameraViewerActivity, accessToken, route)
                }
                val request = json.optJSONObject("request") ?: JSONObject()
                when (request.optString("status")) {
                    "pending" -> statusView.text = "Target දුරකථනයේ අවසරය බලාපොරොත්තු වේ..."
                    "declined" -> {
                        statusView.text = "Target දුරකථනයේ පරිශීලකයා ඉල්ලීම ප්‍රතික්ෂේප කළා."
                        finishPolling()
                    }
                    "failed", "completed", "expired" -> {
                        statusView.text = "Remote Camera සම්බන්ධතාව අවසන් වී ඇත."
                        finishPolling()
                    }
                    "approved" -> {
                        val result = request.optJSONObject("result") ?: JSONObject()
                        val encoded = result.optString("imageJpegBase64")
                        val sequence = result.optLong("sequence", -1)
                        if (result.optString("cameraCapture") == "live_frame" &&
                            encoded.isNotBlank() && sequence > lastSequence
                        ) {
                            val bytes = Base64.decode(encoded, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                imageView.setImageBitmap(bitmap)
                                lastSequence = sequence
                                statusView.text = "සජීවී කැමරා දර්ශනය • ${result.optString("lensFacing")} • Frame $sequence"
                            }
                        } else if (lastSequence < 0) {
                            statusView.text = "අවසර ලැබුණා. පළමු කැමරා දර්ශනය ලැබෙන තෙක් රැඳී සිටින්න..."
                        }
                    }
                }
            } catch (error: Exception) {
                statusView.text = "දර්ශනය ලබාගැනීමේ ගැටලුවක්: ${error.message ?: "connection"}"
            }
        }
    }

    private fun stopRemoteSession() {
        if (stopped) return
        stopped = true
        polling = false
        handler.removeCallbacks(poll)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NexusRemoteCameraApi.post(
                        this@NexusRemoteCameraViewerActivity,
                        accessToken,
                        "devices/$targetDeviceId/remote/camera/stop",
                        JSONObject().put("controllerDeviceId", controllerDeviceId)
                            .put("requestId", requestId)
                    )
                }
            } catch (_: Exception) { }
            statusView.text = "Remote Camera නවතා ඇත."
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!stopped && accessToken.isNotBlank()) startPolling()
    }

    private fun finishPolling() {
        polling = false
        handler.removeCallbacks(poll)
        stopButton.isEnabled = false
    }

    override fun onStop() {
        polling = false
        handler.removeCallbacks(poll)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "nexus_camera_request_id"
        const val EXTRA_TARGET_DEVICE_ID = "nexus_camera_target_device_id"
        const val EXTRA_CONTROLLER_DEVICE_ID = "nexus_camera_controller_device_id"
        const val EXTRA_ACCESS_TOKEN = "nexus_access_token"
        private const val POLL_INTERVAL_MS = 1300L
    }
}
