package com.gamehelper.synthesis

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 主界面：授权 + 启动服务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView

    companion object {
        private const val REQ_CAPTURE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.tvStatus)

        btnStart.setOnClickListener { requestPermissions() }
        btnStop.setOnClickListener { stopAll() }

        refreshUi()
    }

    private fun requestPermissions() {
        // 1. 悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
                Toast.makeText(this, "请开启「允许显示在其他应用上层」后返回", Toast.LENGTH_LONG).show()
                return
            }
        }

        // 2. 屏幕录制权限
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startServices(resultCode, data)
            } else {
                Toast.makeText(this, "需要屏幕录制权限才能工作", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startServices(resultCode: Int, data: Intent) {
        // 屏幕捕获服务
        val capIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("code", resultCode)
            putExtra("data", data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(capIntent)
        } else {
            startService(capIntent)
        }

        // 悬浮窗服务
        val ovIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(ovIntent)
        } else {
            startService(ovIntent)
        }

        Toast.makeText(this, "✅ 已启动！切换到游戏，点击 🎯 气泡开始", Toast.LENGTH_LONG).show()
        refreshUi()
    }

    private fun stopAll() {
        stopService(Intent(this, OverlayService::class.java))
        stopService(Intent(this, ScreenCaptureService::class.java))
        Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
        refreshUi()
    }

    private fun refreshUi() {
        val running = OverlayService.instance != null
        btnStart.visibility = if (running) android.view.View.GONE else android.view.View.VISIBLE
        btnStop.visibility = if (running) android.view.View.VISIBLE else android.view.View.GONE
        tvStatus.text = if (running) "🟢 运行中" else "⚪ 未启动"
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }
}
