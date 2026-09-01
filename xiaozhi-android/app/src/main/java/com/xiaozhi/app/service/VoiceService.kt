package com.xiaozhi.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 前台服务占位。
 *
 * 为什么必须有它：
 *  - Android 9+ 切后台后无法持续使用麦克风
 *  - WebSocket 长连接会被 Doze 模式切断
 * 把会话逻辑迁进来时，foregroundServiceType 必须是 microphone，
 * 且 Android 14+ 需要先申请 FOREGROUND_SERVICE_MICROPHONE 权限。
 */
class VoiceService : Service() {

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "语音会话", NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("小智")
            .setContentText("语音会话进行中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "xiaozhi_voice"
        private const val NOTIFICATION_ID = 1001
    }
}
