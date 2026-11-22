package com.nexusai.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * Target-side camera preview. It only opens while this visible Activity is
 * on screen and after the Target user grants CAMERA permission.
 * It deliberately does not start from an FCM push or in the background.
 */
class NexusCameraPreviewActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startPreview() else {
                status.text = "Camera permission denied. Preview is stopped."
                stopPreview()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.NEXUS_DEVICE_ROLE != "target") {
            finish()
            return
        }

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        status = TextView(this).apply { text = "Camera preview is stopped." }
        val switch = Button(this).apply {
            text = "Switch front / back"
            setOnClickListener {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                startPreview()
            }
        }
        val stop = Button(this).apply {
            text = "Stop camera"
            setOnClickListener {
                stopPreview()
                finish()
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(previewView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(switch)
            addView(stop)
        }
        setContentView(layout)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            startPreview()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startPreview() {
        if (isFinishing || isDestroyed) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()
                if (!provider.hasCamera(selector)) {
                    status.text = "Selected camera is unavailable."
                    return@addListener
                }
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview)
                status.text = if (lensFacing == CameraSelector.LENS_FACING_FRONT)
                    "Front camera preview is active. Tap Stop camera to end."
                else "Back camera preview is active. Tap Stop camera to end."
            } catch (error: Exception) {
                status.text = "Unable to start camera: ${error.localizedMessage ?: "unknown error"}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopPreview() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        if (::status.isInitialized) status.text = "Camera preview stopped."
    }

    override fun onStop() {
        stopPreview()
        super.onStop()
    }
}
