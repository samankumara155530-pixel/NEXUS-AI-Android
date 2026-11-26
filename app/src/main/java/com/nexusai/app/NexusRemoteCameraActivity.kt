package com.nexusai.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Target-side remote camera. A person holding the Target phone must approve
 * every session. The preview remains visible while JPEG frames are transmitted.
 * Leaving this Activity ends the camera session.
 */
class NexusRemoteCameraActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var approveButton: Button
    private lateinit var declineButton: Button
    private lateinit var switchButton: Button
    private lateinit var stopButton: Button

    private val requestId by lazy { intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty() }
    private var accessToken = ""
    private val handler = Handler(Looper.getMainLooper())
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    @Volatile private var approved = false
    @Volatile private var closing = false
    @Volatile private var uploading = false
    @Volatile private var sequence = 0L
    @Volatile private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val captureLoop = object : Runnable {
        override fun run() {
            if (!approved || closing || isFinishing || isDestroyed) return
            captureFrame()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) approveAndStart()
        else declineRequest("camera_permission_denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requestId.isBlank()) {
            finish()
            return
        }
        buildUi()
        approveButton.isEnabled = false
        lifecycleScope.launch {
            accessToken = withContext(Dispatchers.IO) {
                intent.getStringExtra(EXTRA_ACCESS_TOKEN)?.takeIf { it.isNotBlank() }
                    ?: NexusAuthManager.restoreSession(this@NexusRemoteCameraActivity).orEmpty()
            }
            if (accessToken.isBlank()) {
                statusView.text = "NEXUS AI සැසිය කල් ඉකුත් වී ඇත. නැවත පිවිසෙන්න."
            } else {
                approveButton.isEnabled = true
            }
        }
    }

    private fun buildUi() {
        val prefs = getSharedPreferences("nexus_local_data", MODE_PRIVATE)
        val sinhala = prefs.getBoolean("nexus_sinhala_language", true)
        statusView = TextView(this).apply {
            text = if (sinhala) "දුරකථනයට කැමරා දර්ශනයක් බෙදාගැනීමට ඉල්ලීමක් ලැබී ඇත. ඔබ අනුමත කළහොත් පමණක් කැමරාව ක්‍රියාත්මක වේ." else
                "A remote camera request was received. The camera starts only if you approve."
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            visibility = View.GONE
        }
        approveButton = Button(this).apply {
            text = if (sinhala) "අනුමත කර කැමරාව බෙදාගන්න" else "Approve and share camera"
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@NexusRemoteCameraActivity, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                ) approveAndStart() else cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        declineButton = Button(this).apply {
            text = if (sinhala) "ප්‍රතික්ෂේප කරන්න" else "Decline"
            setOnClickListener { declineRequest("declined_by_user") }
        }
        switchButton = Button(this).apply {
            text = if (sinhala) "ඉදිරිපස / පසුපස මාරු කරන්න" else "Switch front / back"
            visibility = View.GONE
            setOnClickListener {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK
                bindCamera()
            }
        }
        stopButton = Button(this).apply {
            text = if (sinhala) "බෙදාගැනීම නවත්වන්න" else "Stop sharing"
            visibility = View.GONE
            setOnClickListener { endSession("stopped_by_target") }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(statusView)
            addView(previewView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(approveButton)
            addView(declineButton)
            addView(switchButton)
            addView(stopButton)
        })
    }

    private fun approveAndStart() {
        if (approved || closing) return
        approveButton.isEnabled = false
        declineButton.isEnabled = false
        statusView.text = "අනුමැතිය තහවුරු කරමින්..."
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NexusRemoteCameraApi.post(
                        this@NexusRemoteCameraActivity, accessToken,
                        "devices/remote/respond",
                        JSONObject().put("deviceId", stableDeviceId())
                            .put("requestId", requestId)
                            .put("status", "approved")
                            .put("result", JSONObject().put("consent", true))
                    )
                }
                approved = true
                approveButton.visibility = View.GONE
                declineButton.visibility = View.GONE
                previewView.visibility = View.VISIBLE
                switchButton.visibility = View.VISIBLE
                stopButton.visibility = View.VISIBLE
                statusView.text = "කැමරාව සජීවීව බෙදාගනිමින් පවතී. ඔබට ඕනෑම වේලාවක නවත්වන්න පුළුවන්."
                bindCamera()
            } catch (error: Exception) {
                statusView.text = "සම්බන්ධතාව ආරම්භ කළ නොහැක: ${error.message ?: "error"}"
                approveButton.isEnabled = true
                declineButton.isEnabled = true
            }
        }
    }

    private fun bindCamera() {
        if (!approved || closing) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed || closing) return@addListener
            try {
                val provider = future.get()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                if (!provider.hasCamera(selector)) {
                    statusView.text = "තෝරාගත් කැමරාව ලබාගත නොහැක."
                    return@addListener
                }
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(55)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
                cameraProvider = provider
                imageCapture = capture
                handler.removeCallbacks(captureLoop)
                handler.post(captureLoop)
                statusView.text = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                    "ඉදිරිපස කැමරාව සජීවීව බෙදාගනිමින් පවතී." else "පසුපස කැමරාව සජීවීව බෙදාගනිමින් පවතී."
            } catch (error: Exception) {
                statusView.text = "කැමරාව ආරම්භ කළ නොහැක: ${error.localizedMessage ?: "error"}"
                endSession("camera_start_failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureFrame() {
        val capture = imageCapture ?: return
        if (uploading || !approved || closing) return
        uploading = true
        val file = File(cacheDir, "nexus_remote_${System.nanoTime()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    var sampleSize = 1
                    while (bounds.outWidth / sampleSize > 960 || bounds.outHeight / sampleSize > 960) {
                        sampleSize *= 2
                    }
                    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val original = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                        ?: throw IllegalStateException("JPEG_DECODE_FAILED")
                    val scale = minOf(1f, 480f / original.width.toFloat())
                    val resized = if (scale < 1f) Bitmap.createScaledBitmap(
                        original, (original.width * scale).toInt().coerceAtLeast(1),
                        (original.height * scale).toInt().coerceAtLeast(1), true
                    ) else original
                    val bytes = ByteArrayOutputStream().use { stream ->
                        resized.compress(Bitmap.CompressFormat.JPEG, 48, stream)
                        stream.toByteArray()
                    }
                    if (resized !== original) resized.recycle()
                    original.recycle()
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    if (encoded.length > 46000) throw IllegalStateException("FRAME_TOO_LARGE")
                    val currentSequence = ++sequence
                    NexusRemoteCameraApi.uploadFrame(
                        this@NexusRemoteCameraActivity, accessToken, stableDeviceId(),
                        requestId, encoded,
                        if (lensFacing == CameraSelector.LENS_FACING_FRONT) "front" else "back",
                        currentSequence
                    )
                } catch (error: Exception) {
                    val reason = error.message.orEmpty()
                    runOnUiThread {
                        if (!closing) statusView.text = "දර්ශනය යැවීමේ ගැටලුවක්: ${reason.ifBlank { "error" }}"
                    }
                    if (reason.contains("CAMERA_REQUEST_NOT_ACTIVE") ||
                        reason.contains("CAMERA_REQUEST_EXPIRED") ||
                        reason.contains("REMOTE_REQUEST_NOT_FOUND")
                    ) {
                        runOnUiThread { endSession("controller_stopped_or_expired") }
                    }
                } finally {
                    file.delete()
                    uploading = false
                }
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                uploading = false
            }
        })
    }

    private fun declineRequest(reason: String) {
        if (closing) return
        closing = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NexusRemoteCameraApi.post(
                        this@NexusRemoteCameraActivity, accessToken,
                        "devices/remote/respond",
                        JSONObject().put("deviceId", stableDeviceId())
                            .put("requestId", requestId)
                            .put("status", "declined")
                            .put("result", JSONObject().put("reason", reason))
                    )
                }
            } catch (_: Exception) { }
            finish()
        }
    }

    private fun endSession(reason: String) {
        if (closing) return
        closing = true
        approved = false
        handler.removeCallbacks(captureLoop)
        cameraProvider?.unbindAll()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NexusRemoteCameraApi.post(
                        this@NexusRemoteCameraActivity, accessToken,
                        "devices/remote/respond",
                        JSONObject().put("deviceId", stableDeviceId())
                            .put("requestId", requestId)
                            .put("status", "completed")
                            .put("result", JSONObject().put("reason", reason))
                    )
                }
            } catch (_: Exception) { }
            finish()
        }
    }

    private fun stableDeviceId(): String =
        getSharedPreferences("nexus_local_data", MODE_PRIVATE)
            .getString("stable_device_id", "").orEmpty()

    override fun onStop() {
        if (approved && !closing && !isChangingConfigurations) endSession("target_activity_closed")
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REQUEST_ID = "nexus_camera_request_id"
        const val EXTRA_ACCESS_TOKEN = "nexus_access_token"
        const val EXTRA_CONTROLLER_DEVICE_ID = "nexus_camera_controller_device_id"
        const val EXTRA_TARGET_DEVICE_ID = "nexus_camera_target_device_id"
        private const val FRAME_INTERVAL_MS = 1400L
    }
}
