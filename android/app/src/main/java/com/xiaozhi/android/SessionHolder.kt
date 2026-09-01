package com.xiaozhi.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话持有者。
 *
 * 骨架工程里用单例持有，UI 通过 [sessionFlow] 观察。
 * 正式项目建议把会话完全收进 service.XiaozhiService，
 * UI 通过 Binder 访问，避免 Activity 重建时状态丢失。
 */
object SessionHolder {
    private val _sessionFlow = MutableStateFlow<XiaozhiSession?>(null)
    val sessionFlow: StateFlow<XiaozhiSession?> = _sessionFlow.asStateFlow()

    val session: XiaozhiSession? get() = _sessionFlow.value

    fun attach(session: XiaozhiSession) {
        _sessionFlow.value = session
    }

    fun detach() {
        _sessionFlow.value = null
    }
}

class XiaozhiApp : Application() {

    /** 贯穿整个进程生命周期的作用域，Service 与 Session 共用 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "小智语音服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "保持语音会话连接" }

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "xiaozhi_foreground"
    }
}

/** 便捷取值：从任意 Context 拿到 Application 作用域 */
val Context.appScope: CoroutineScope
    get() = (applicationContext as XiaozhiApp).appScope
