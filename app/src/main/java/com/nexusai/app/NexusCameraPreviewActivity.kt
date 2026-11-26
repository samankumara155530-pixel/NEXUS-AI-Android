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
 * First isolated step of NEXUS Remote Camera:
 * a visible, user-started camera preview on the phone that owns the camera.
 *
 * This activity does not capture or transmit images. Remote frame transport
 * will be added as a separate step after this camera lifecycle is validated.
 */
class NexusCameraPreviewActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startPreview()
            } else {
                statusView.text = message(
                    "කැමරා අවසරය ලබා දුන්නේ නැහැ. දර්ශනය නවතා ඇත.",
                    "Camera permission was denied. Preview is stopped."
                )
                stopPreview()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("nexus_local_data", MODE_PRIVATE)
        val sinhala = prefs.getBoolean("nexus_sinhala_language", true)

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        statusView = TextView(this).apply {
            text = message(
                "කැමරා දර්ශනය සූදානම් කරමින්...",
                "Preparing camera preview..."
            )
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }

        val switchButton = Button(this).apply {
            text = if (sinhala) "ඉදිරිපස / පසුපස කැමරාව මාරු කරන්න" else "Switch front / back camera"
            setOnClickListener {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                startPreview()
            }
        }
        val stopButton = Button(this).apply {
            text = if (sinhala) "කැමරාව නවත්වන්න" else "Stop camera"
            setOnClickListener {
                stopPreview()
                finish()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            addView(statusView)
            addView(
                previewView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(switchButton)
            addView(stopButton)
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startPreview()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun message(si: String, en: String): String {
        return if (getSharedPreferences("nexus_local_data", MODE_PRIVATE)
                .getBoolean("nexus_sinhala_language", true)
        ) si else en
    }

    private fun startPreview() {
        if (isFinishing || isDestroyed) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isFinishing || isDestroyed) return@addListener
            try {
                val provider = providerFuture.get()
                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                if (!provider.hasCamera(selector)) {
                    statusView.text = message(
                        "තෝරාගත් කැමරාව මෙම දුරකථනයේ නැහැ.",
                        "The selected camera is not available on this phone."
                    )
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview)
                statusView.text = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    message("ඉදිරිපස කැමරාව ක්‍රියාත්මකයි.", "Front camera preview is active.")
                } else {
                    message("පසුපස කැමරාව ක්‍රියාත්මකයි.", "Back camera preview is active.")
                }
            } catch (error: Exception) {
                statusView.text = message(
                    "කැමරාව ආරම්භ කළ නොහැක: ${error.localizedMessage ?: "නොදන්නා දෝෂයක්"}",
                    "Unable to start camera: ${error.localizedMessage ?: "unknown error"}"
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopPreview() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        if (::statusView.isInitialized) {
            statusView.text = message("කැමරා දර්ශනය නවතා ඇත.", "Camera preview stopped.")
        }
    }

    override fun onStop() {
        stopPreview()
        super.onStop()
    }
}
