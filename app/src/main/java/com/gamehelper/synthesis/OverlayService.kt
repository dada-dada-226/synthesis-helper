package com.gamehelper.synthesis

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.*

/**
 * 悬浮窗服务：实时监控屏幕 + AI 分析 + 持续显示推荐方向。
 *
 * 点击气泡 → 开始实时监控，每 1.5 秒自动截图分析。
 * 再次点击 → 暂停监控。
 * 拖动气泡移动位置。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var bubbleIcon: TextView? = null
    private var directionText: TextView? = null
    private var detailText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isMonitoring = false
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var prevBoardHash: Int = 0  // 用于判断棋盘是否变化

    // 拖动相关
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragTouchX = 0f
    private var dragTouchY = 0f
    private var isDragging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupOverlay()
        startForeground(NOTIFY_ID, buildNotification("攻略器就绪", "点击气泡开始"))
    }

    // ============ 悬浮窗布局 ============

    private fun setupOverlay() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayView = inflater.inflate(R.layout.overlay_view, null)

        bubbleIcon = overlayView?.findViewById(R.id.bubbleIcon)
        directionText = overlayView?.findViewById(R.id.directionText)
        detailText = overlayView?.findViewById(R.id.detailText)

        val lpType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            lpType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 300
        }

        windowManager.addView(overlayView, layoutParams)
        setupTouch()
    }

    // ============ 触摸：拖动 / 点击切换监控 ============

    private fun setupTouch() {
        overlayView?.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = layoutParams!!.x
                    dragStartY = layoutParams!!.y
                    dragTouchX = event.rawX
                    dragTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragTouchX).toInt()
                    val dy = (event.rawY - dragTouchY).toInt()
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) isDragging = true
                    if (isDragging) {
                        layoutParams!!.x = dragStartX + dx
                        layoutParams!!.y = dragStartY + dy
                        windowManager.updateViewLayout(overlayView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) toggleMonitoring()
                    true
                }
                else -> false
            }
        }
    }

    // ============ 实时监控 ============

    private fun toggleMonitoring() {
        if (isMonitoring) stopMonitoring() else startMonitoring()
    }

    private fun startMonitoring() {
        isMonitoring = true
        bubbleIcon?.text = "⏳"  // 表示工作中
        directionText?.visibility = View.VISIBLE
        detailText?.visibility = View.VISIBLE

        monitorJob = scope.launch {
            while (isActive && isMonitoring) {
                runAnalysis()
                delay(1500)  // 每 1.5 秒分析一次
            }
        }

        updateNotification("监控中…", "自动识别棋盘并推荐走法")
    }

    private fun stopMonitoring() {
        isMonitoring = false
        monitorJob?.cancel()
        bubbleIcon?.text = "🎯"
        directionText?.text = ""
        directionText?.visibility = View.GONE
        detailText?.visibility = View.GONE

        updateNotification("攻略器就绪", "点击气泡开始")
    }

    private suspend fun runAnalysis() {
        try {
            val captureService = ScreenCaptureService.instance ?: return

            val screenshot = withContext(Dispatchers.Default) {
                captureService.captureScreen()
            } ?: return

            val board = withContext(Dispatchers.Default) {
                BoardAnalyzer.extractBoard(screenshot)
            }
            screenshot.recycle()

            // 检查棋盘是否有变化
            val hash = board.contentDeepHashCode()
            if (hash == prevBoardHash) return  // 没变化，跳过
            prevBoardHash = hash

            // 检查是否有数字
            val count = board.sumOf { row -> row.count { it > 0 } }
            if (count < 4) return

            // AI 分析
            val result = AIEngine.analyze(board) ?: run {
                withContext(Dispatchers.Main) {
                    directionText?.text = "😵"
                    detailText?.text = "无法移动"
                }
                return
            }

            withContext(Dispatchers.Main) {
                directionText?.text = result.direction.arrow
                detailText?.text = result.direction.label
                animateArrow()
            }

        } catch (_: Exception) {
            // 静默忽略单次失败
        }
    }

    // ============ 视觉 ============

    private var arrowAnimator: ValueAnimator? = null

    private fun animateArrow() {
        arrowAnimator?.cancel()
        arrowAnimator = ValueAnimator.ofFloat(1f, 1.3f, 1f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val s = it.animatedValue as Float
                directionText?.scaleX = s
                directionText?.scaleY = s
            }
            start()
        }
    }

    // ============ 通知 ============

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFY_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopMonitoring()
        scope.cancel()
        overlayView?.let { windowManager.removeView(it) }
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "overlay"
        private const val NOTIFY_ID = 2001
        var instance: OverlayService? = null
            private set

        private fun createNotificationChannel() {
            val ctx = instance ?: return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "攻略器", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
