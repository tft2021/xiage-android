package com.xiaozhi.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.app.audio.AudioEngine
import com.xiaozhi.protocol.ota.DeviceIdentity
import com.xiaozhi.protocol.ota.DeviceCredentials
import com.xiaozhi.protocol.ota.PersistedIdentity
import com.xiaozhi.protocol.session.XiaozhiSession
import com.xiaozhi.protocol.audio.NoOpCodecProvider
import com.xiaozhi.protocol.ws.XiaozhiWsClient
import kotlinx.coroutines.launch

/**
 * 会话 ViewModel
 *
 * 设备身份持久化在 SharedPreferences：
 *   device_id / client_id / serial_number / hmac_key
 * 四项一起决定服务端认定的设备，缺一不可，卸载前不要清除。
 */
class XiaozhiViewModel(application: Application) : AndroidViewModel(application) {

    val session: XiaozhiSession

    init {
        val audio = AudioEngine(viewModelScope)
        val transport = XiaozhiWsClient(viewModelScope)
        // Opus 编解码尚未接入（见 README「Opus 编解码选型」），
        // NoOpCodecProvider 优雅降级：协议与 UI 正常，只是没有声音
        session = XiaozhiSession(
            scope = viewModelScope,
            transport = transport,
            audio = audio,
            codecProvider = NoOpCodecProvider(),
        )
    }

    /** ViewModel 生命周期内的 Application Context，用于身份持久化 */
    private val appContext: Context get() = getApplication()

    /** 连接入口：由 UI 显式触发（首次进入不自动连，等麦克风权限就绪） */
    fun start(onActivationCode: (String) -> Unit) {
        viewModelScope.launch {
            session.start(
                loadIdentity(),
                onActivationCode,
                // Session 检测到异常身份（服务端不下发激活码但只给测试组凭据）时
                // 自动换新身份重试，这里负责把新身份持久化
                onIdentityReset = { fresh -> persistIdentity(fresh) },
            )
        }
    }

    fun startListening() = session.startListening()
    fun stopListening() = session.stopListening()
    fun abort() = session.abort()

    override fun onCleared() {
        session.stop()
    }

    private fun loadIdentity(): DeviceIdentity {
        val sp = appContext.getSharedPreferences("xiaozhi", Context.MODE_PRIVATE)
        val saved = sp.getString("device_id", null)?.let { deviceId ->
            val sn = sp.getString("serial_number", null)
            val key = sp.getString("hmac_key", null)
            val clientId = sp.getString("client_id", null)
            if (sn != null && key != null && clientId != null) {
                PersistedIdentity(
                    deviceId = deviceId,
                    clientId = clientId,
                    credentials = DeviceCredentials(serialNumber = sn, hmacKey = key),
                )
            } else null
        }

        val identity = DeviceIdentity.create(saved)

        // 首次生成后立刻持久化，卸载前保持不变
        if (saved == null) {
            persistIdentity(identity)
        }
        return identity
    }

    /** 身份持久化（首次生成与自动重置共用） */
    private fun persistIdentity(identity: DeviceIdentity) {
        val sp = appContext.getSharedPreferences("xiaozhi", Context.MODE_PRIVATE)
        sp.edit()
            .putString("device_id", identity.deviceId)
            .putString("client_id", identity.clientId)
            .putString("serial_number", identity.credentials.serialNumber)
            .putString("hmac_key", identity.credentials.hmacKey)
            .apply()
    }
}
