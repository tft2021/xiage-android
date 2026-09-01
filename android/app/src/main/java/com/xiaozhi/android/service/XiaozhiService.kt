package com.xiaozhi.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xiaozhi.android.MainActivity
import com.xiaozhi.android.R
import com.xiaozhi.android.SessionHolder
import com.xiaozhi.android.XiaozhiApp
import com.xiaozhi.android.XiaozhiSession
import com.xiaozhi.android.appScope

/**
 * 前台服务：持有会话，保证息屏后不被系统回收。
 *
 * Android 14+ 启动前台服务必须在 manifest 声明具体类型（此处为 microphone），
 * 且需在**服务启动前**已获得 RECORD_AUDIO 权限。
 */
class XiaozhiService : Service() {

    private lateinit var session: XiaozhiSession

    override fun onCreate() {
        super.onCreate()
        session = XiaozhiSession(applicationContext, appScope).also {
            SessionHolder.attach(it)
        }
        startForegroundCompat()
        session.bootstrap(localIp())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        session.release()
        SessionHolder.detach()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("正在连接小智…")

        // API 34+ 必须显式指定前台服务类型，否则抛 MissingForegroundServiceTypeException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, XiaozhiApp.CHANNEL_ID)
            .setContentTitle("小智")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_xiaozhi)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** 取本机局域网 IP，仅用于 OTA 上报，拿不到不影响功能 */
    private fun localIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { addr ->
                !addr.isLoopbackAddress && addr is java.net.Inet4Address
            }?.hostAddress ?: "192.168.1.100"
    } catch (e: Exception) {
        "192.168.1.100"
    }

    companion object {
        const val ACTION_STOP = "com.xiaozhi.android.action.STOP"
        private const val NOTIFICATION_ID = 1001

        fun start(context: android.content.Context) {
            val intent = Intent(context, XiaozhiService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
