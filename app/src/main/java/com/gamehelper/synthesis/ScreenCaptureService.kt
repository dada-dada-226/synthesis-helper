package com.gamehelper.synthesis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 屏幕捕获服务：MediaProjection + ImageReader。
 * 前台服务，防止被系统杀死。
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        if (code != -1 && data != null) {
            startCapture(code, data)
        }

        startForeground(1001, Notification.Builder(this, CHANNEL)
            .setContentTitle("攻略器运行中")
            .setContentText("屏幕识别服务")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build())

        return START_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        screenDpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)

        // 注册回调，确保 ImageReader 有可用图像
        imageReader?.setOnImageAvailableListener({ /* no-op, captureScreen 主动 acquire */ },
            Handler(Looper.getMainLooper()))

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "capture", screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    /**
     * 捕获当前屏幕。调用后请 recycle() 返回的 Bitmap。
     */
    suspend fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null
        val w = screenW
        val h = screenH
        if (w <= 0 || h <= 0) return null

        return suspendCancellableCoroutine { cont ->
            try {
                val image = reader.acquireLatestImage()
                if (image == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * w

                val bitmap = Bitmap.createBitmap(
                    w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                val cropped = if (rowPadding > 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, w, h)
                } else bitmap

                if (cropped != bitmap) bitmap.recycle()
                cont.resume(cropped)
            } catch (e: Exception) {
                cont.resume(null)
            }
        }
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "capture"
        var instance: ScreenCaptureService? = null
            private set

        private fun createChannel() {
            val ctx = instance ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(
                        NotificationChannel(CHANNEL, "屏幕捕获", NotificationManager.IMPORTANCE_LOW)
                    )
            }
        }
    }
}
