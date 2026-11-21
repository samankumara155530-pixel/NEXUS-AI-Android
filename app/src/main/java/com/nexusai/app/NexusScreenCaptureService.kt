package com.nexusai.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Captures exactly one screen preview after the Android system consent dialog.
 * It never runs as a hidden/background screen recorder.
 */
class NexusScreenCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var finished = false
    private var requestId = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        requestId = intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (requestId.isBlank() || resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            createNotificationChannel()
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("NEXUS AI — Screen sharing")
                .setContentText("A one-time screen preview is being sent with your approval.")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            beginCapture(resultCode, resultData)
        } catch (error: Exception) {
            Log.e(TAG, "Could not start consented screen capture", error)
            finishWithFailure("screen_capture_start_failed")
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(resultCode: Int, resultData: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val scale = minOf(1f, 720f / maxOf(metrics.widthPixels, metrics.heightPixels).toFloat())
        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
        val density = metrics.densityDpi

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("MEDIA_PROJECTION_UNAVAILABLE")
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanupCapture()
                stopSelf()
            }
        }, Handler(mainLooper))

        handlerThread = HandlerThread("NEXUS-Screen-Capture").apply { start() }
        val handler = Handler(handlerThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            if (finished) {
                reader.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            finished = true
            try {
                val bitmap = imageToBitmap(image)
                val payload = encodePreview(bitmap)
                serviceScope.launch {
                    try {
                        NexusFcm.respondRemote(
                            applicationContext,
                            requestId,
                            "completed",
                            payload.put("screenCapture", "one_time_preview")
                        )
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not upload screen preview", error)
                    } finally {
                        stopCaptureAndService()
                    }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Screen frame conversion failed", error)
                finishWithFailure("screen_frame_conversion_failed")
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = projection?.createVirtualDisplay(
            "NEXUS-Consent-Screen-Preview",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        ) ?: throw IllegalStateException("VIRTUAL_DISPLAY_UNAVAILABLE")

        // Some devices deliver the first frame asynchronously; fail closed if no frame arrives.
        handler.postDelayed({
            if (!finished) finishWithFailure("screen_capture_timeout")
        }, 12_000L)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    private fun encodePreview(source: Bitmap): JSONObject {
        val ratio = minOf(1f, 480f / maxOf(source.width, source.height).toFloat())
        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else source
        val output = ByteArrayOutputStream()
        var quality = 55
        do {
            output.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
            quality -= 10
        } while (output.size() > 42_000 && quality >= 25)
        if (scaled !== source) scaled.recycle()
        source.recycle()
        if (output.size() > 48_000) throw IllegalStateException("SCREEN_PREVIEW_TOO_LARGE")
        return JSONObject()
            .put("imageMimeType", "image/jpeg")
            .put("imageJpegBase64", android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP))
            .put("imageBytes", output.size())
    }

    private fun finishWithFailure(reason: String) {
        if (finished && reason == "screen_capture_timeout") return
        finished = true
        serviceScope.launch {
            try {
                if (requestId.isNotBlank()) {
                    NexusFcm.respondRemote(
                        applicationContext,
                        requestId,
                        "failed",
                        JSONObject().put("reason", reason)
                    )
                }
            } catch (error: Exception) {
                Log.e(TAG, "Could not report screen capture failure", error)
            } finally {
                stopCaptureAndService()
            }
        }
    }

    private fun stopCaptureAndService() {
        cleanupCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupCapture() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { imageReader?.close() }
        imageReader = null
        val activeProjection = projection
        projection = null
        runCatching { activeProjection?.stop() }
        runCatching { handlerThread?.quitSafely() }
        handlerThread = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "NEXUS Screen Sharing",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Visible notification while a user-approved screen preview is captured"
                }
            )
        }
    }

    override fun onDestroy() {
        cleanupCapture()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_REQUEST_ID = "projection_request_id"
        private const val TAG = "NEXUS_SCREEN"
        private const val CHANNEL_ID = "nexus_screen_capture"
        private const val NOTIFICATION_ID = 3201
    }
}
