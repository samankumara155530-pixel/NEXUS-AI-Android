package com.nexusai.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
 * User-consented, visible, low-frame-rate screen sharing.
 * Android's MediaProjection consent dialog is mandatory for every session.
 */
class NexusScreenCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    @Volatile private var sessionEnded = false
    @Volatile private var uploadInFlight = false
    private var lastUploadAt = 0L
    private var requestId = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishSession("controller_or_target_stopped")
            return START_NOT_STICKY
        }

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
            val stopIntent = Intent(this, NexusScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                this, requestId.hashCode(), stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("NEXUS AI — Screen sharing active")
                .setContentText("Your screen is being shared. Tap Stop to end the session.")
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop sharing", stopPendingIntent)
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
                if (!sessionEnded) finishSession("android_projection_stopped")
            }
        }, Handler(mainLooper))

        handlerThread = HandlerThread("NEXUS-Screen-Capture").apply { start() }
        val handler = Handler(handlerThread!!.looper)
        captureHandler = handler
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = System.currentTimeMillis()
            if (sessionEnded || uploadInFlight || now - lastUploadAt < FRAME_INTERVAL_MS) {
                image.close()
                return@setOnImageAvailableListener
            }
            uploadInFlight = true
            lastUploadAt = now
            try {
                val bitmap = imageToBitmap(image)
                val payload = encodePreview(bitmap)
                    .put("screenCapture", "live_frame")
                    .put("capturedAt", now)
                serviceScope.launch {
                    try {
                        NexusFcm.uploadScreenFrame(applicationContext, requestId, payload)
                    } catch (error: Exception) {
                        Log.w(TAG, "Screen frame upload failed: ${error.message}")
                    } finally {
                        uploadInFlight = false
                    }
                }
            } catch (error: Exception) {
                uploadInFlight = false
                Log.e(TAG, "Screen frame conversion failed", error)
                finishWithFailure("screen_frame_conversion_failed")
            } finally {
                image.close()
            }
        }, handler)

        virtualDisplay = projection?.createVirtualDisplay(
            "NEXUS-Consent-Screen-Share",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null,
            handler
        ) ?: throw IllegalStateException("VIRTUAL_DISPLAY_UNAVAILABLE")

        handler.postDelayed({ finishSession("screen_share_timeout") }, MAX_SESSION_MS)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
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
        var quality = 50
        do {
            output.reset()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
            quality -= 10
        } while (output.size() > 42_000 && quality >= 20)
        if (scaled !== source) scaled.recycle()
        source.recycle()
        if (output.size() > 42_000) throw IllegalStateException("SCREEN_PREVIEW_TOO_LARGE")
        return JSONObject()
            .put("imageMimeType", "image/jpeg")
            .put("imageJpegBase64", android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP))
            .put("imageBytes", output.size())
    }

    private fun finishWithFailure(reason: String) {
        if (sessionEnded) return
        sessionEnded = true
        reportEnd("failed", JSONObject().put("reason", reason))
    }

    private fun finishSession(reason: String) {
        if (sessionEnded) return
        sessionEnded = true
        reportEnd("completed", JSONObject().put("screenCapture", "session_ended").put("reason", reason))
    }

    private fun reportEnd(status: String, result: JSONObject) {
        serviceScope.launch {
            try {
                NexusFcm.respondRemote(applicationContext, requestId, status, result)
            } catch (error: Exception) {
                Log.e(TAG, "Could not report screen session end", error)
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
        captureHandler = null
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
                    description = "Visible notification while a user-approved screen share is active"
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
        const val ACTION_STOP = "com.nexusai.app.action.STOP_SCREEN_SHARE"
        private const val TAG = "NEXUS_SCREEN"
        private const val CHANNEL_ID = "nexus_screen_capture"
        private const val NOTIFICATION_ID = 3201
        private const val FRAME_INTERVAL_MS = 1200L
        private const val MAX_SESSION_MS = 60_000L
    }
}
