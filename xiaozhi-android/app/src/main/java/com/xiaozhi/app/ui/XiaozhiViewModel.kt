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
import com.xiaozhi.protocol.audio.ConcentusCodecProvider
import com.xiaozhi.protocol.ws.XiaozhiWsClient
import kotlinx.coroutines.flow.collect
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

    /** 当前设备身份的 device_id，激活界面展示用于与 xiaozhi.me 设备列表核对 */
    val deviceId: String

    /**
     * 这台设备是否曾经成功建立过真实会话（即曾经绑定成功）。
     * 为 true 时**绝不自动换身份** —— 自动换身份会丢掉用户已建立的绑定关系，
     * 表现就是"xiaozhi.me 显示已绑定、手机上却一直在等激活，且激活码每次都不一样"。
     */
    private var everBound: Boolean

    /**
     * 已自动重置身份的次数。
     * 达上限后不再自动换：否则每次连接都换一次身份，激活码跟着每次都变，
     * 用户永远追不上（实测服务端激活码本身恒定不变，变的只可能是身份）。
     */
    private var identityResets: Int

    init {
        val audio = AudioEngine(viewModelScope)
        val transport = XiaozhiWsClient(viewModelScope)
        // Concentus（纯 Java Opus）编解码：上行 16k/60ms，下行按 hello 协商采样率
        session = XiaozhiSession(
            scope = viewModelScope,
            transport = transport,
            audio = audio,
            codecProvider = ConcentusCodecProvider(),
        )
        deviceId = loadIdentity().deviceId
        everBound = sp.getBoolean("ever_bound", false)
        identityResets = sp.getInt("identity_resets", 0)

        // 进入 Ready 说明服务端接受了当前凭据 => 该身份是"已绑定"的，此后不得自动丢弃
        viewModelScope.launch {
            session.phase.collect { phase ->
                if (phase is XiaozhiSession.Phase.Ready && !everBound) {
                    everBound = true
                    sp.edit().putBoolean("ever_bound", true).apply()
                }
            }
        }
    }

    /** ViewModel 生命周期内的 Application Context，用于身份持久化 */
    private val appContext: Context get() = getApplication()

    private val sp get() = appContext.getSharedPreferences("xiaozhi", Context.MODE_PRIVATE)

    /** 连接入口：由 UI 显式触发（首次进入不自动连，等麦克风权限就绪） */
    fun start(onActivationCode: (String) -> Unit) {
        viewModelScope.launch {
            session.start(
                loadIdentity(),
                onActivationCode,
                // 两个硬约束：绑定过的设备不换；重置次数达上限不换。
                // 换身份是不可逆操作（= 丢弃原有绑定关系），只在确实没绑过、
                // 且还没试过几次的全新设备上允许自动换一次。
                onIdentityReset = if (everBound || identityResets >= MAX_AUTO_IDENTITY_RESETS) {
                    null
                } else { fresh ->
                    identityResets++
                    sp.edit().putInt("identity_resets", identityResets).apply()
                    persistIdentity(fresh)
                },
            )
        }
    }

    fun startListening() = session.startListening()
    fun stopListening() = session.stopListening()
    fun abort() = session.abort()

    /** 激活等待期"我已输码，立即检测"：跳过轮询间隔马上做一轮检测 */
    fun nudgeActivation() = session.nudgeActivation()

    /** 主动断开：关闭 WebSocket、释放音频，phase 回到 Idle 后可再次连接 */
    fun disconnect() = session.stop()

    /**
     * 手动重置设备身份（用户确认绑错设备条目时使用）。
     * 清掉持久化身份与 ever_bound 标记，下次连接会生成全新身份、拿到新激活码，
     * 届时需重新到 xiaozhi.me 绑定。
     */
    fun resetIdentity() {
        session.stop()
        sp.edit().clear().apply()
        everBound = false
        identityResets = 0
    }

    private companion object {
        /** 自动换身份的次数上限，超过后需用户在界面上手动重置 */
        const val MAX_AUTO_IDENTITY_RESETS = 1
    }

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
