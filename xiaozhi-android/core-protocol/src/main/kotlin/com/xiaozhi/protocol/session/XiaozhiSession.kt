package com.xiaozhi.protocol.session

import com.xiaozhi.protocol.audio.AudioCodecProvider
import com.xiaozhi.protocol.audio.AudioIO
import com.xiaozhi.protocol.audio.NoOpCodecProvider
import com.xiaozhi.protocol.audio.OpusDecoder
import com.xiaozhi.protocol.ota.ActivateResult
import com.xiaozhi.protocol.ota.DeviceIdentity
import com.xiaozhi.protocol.ota.OtaApi
import com.xiaozhi.protocol.ota.OtaClient
import com.xiaozhi.protocol.ota.OtaConfig
import com.xiaozhi.protocol.ws.AudioParams
import com.xiaozhi.protocol.ws.ConnectionState
import com.xiaozhi.protocol.ws.TtsState
import com.xiaozhi.protocol.ws.XiaozhiMessage
import com.xiaozhi.protocol.ws.XiaozhiTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 会话状态机：OTA 激活 -> WebSocket 会话 -> 音频收发
 *
 * 依赖全部以接口注入（[XiaozhiTransport] / [AudioIO] / [AudioCodecProvider] /
 * [OtaApi]），可在 JVM 上用 Fake 实现做单元测试；生产装配见 app 模块。
 */
class XiaozhiSession(
    private val scope: CoroutineScope,
    private val transport: XiaozhiTransport,
    private val audio: AudioIO,
    private val otaApi: OtaApi = OtaClient(),
    private val codecProvider: AudioCodecProvider = NoOpCodecProvider(),
    /** 激活轮询间隔，生产 5s，测试可调小 */
    private val activationPollIntervalMs: Long = 5_000,
    /** 激活最长等待时间 */
    private val activationTimeoutMs: Long = 5 * 60_000,
) {

    sealed class Phase {
        data object Idle : Phase()
        data object FetchingConfig : Phase()
        /** 需要激活，code 为 6 位激活码，展示给用户去 xiaozhi.me 输入 */
        data class NeedActivation(val code: String) : Phase()
        data object Connecting : Phase()
        /** 收到服务端 hello，会话已建立；sampleRate 为协商的下行采样率 */
        data class Ready(val sampleRate: Int) : Phase()
        data object Listening : Phase()
        data class Speaking(val text: String?) : Phase()
        data class Error(val message: String) : Phase()
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    private val _subtitle = MutableStateFlow("")
    val subtitle: StateFlow<String> = _subtitle

    private val _emotion = MutableStateFlow("neutral")
    val emotion: StateFlow<String> = _emotion

    private var decoderJob: Job? = null
    private var messageJob: Job? = null
    private var stateJob: Job? = null

    private var identity: DeviceIdentity? = null
    private var downlinkSampleRate: Int = DEFAULT_DOWNLINK_RATE
    private var stopped = false

    /**
     * 启动会话。
     *
     * @param onNeedActivation 激活码回调，UI 应展示并引导用户到 xiaozhi.me 输入
     * @param onIdentityReset 身份自动重置回调（见下），UI 应持久化新身份
     * @return true 表示会话建立完成（进入 Ready）；false 表示停留在激活或出错
     */
    suspend fun start(
        identity: DeviceIdentity,
        onNeedActivation: (String) -> Unit,
        onIdentityReset: ((DeviceIdentity) -> Unit)? = null,
    ): Boolean {
        stopped = false
        _phase.value = Phase.FetchingConfig

        val (effective, config) = try {
            fetchConfigWithRecovery(identity, onIdentityReset)
        } catch (e: Exception) {
            return fail("OTA 失败: ${e.message}")
        }
        this.identity = effective

        // 服务端未下发激活码说明该设备已绑定，直接进会话
        if (!config.needsActivation) {
            return openSession(config)
        }

        // ---- 激活流程 ----
        val code = config.activationCode ?: return fail("服务端未下发激活码")
        val challenge = config.activationChallenge
        if (challenge.isNullOrEmpty()) return fail("服务端未下发激活 challenge")
        onNeedActivation(code)
        _phase.value = Phase.NeedActivation(code)

        var activated = false
        val deadline = System.currentTimeMillis() + activationTimeoutMs
        while (!activated && System.currentTimeMillis() < deadline && !stopped) {
            when (otaApi.activate(effective, challenge)) {
                ActivateResult.Success -> activated = true
                ActivateResult.Waiting -> delay(activationPollIntervalMs)
                is ActivateResult.Failed ->
                    return fail("激活请求被拒绝，请检查网络或重新生成设备身份")
            }
        }
        if (stopped || !activated) return false

        // 激活成功后必须重新拉配置，才能拿到真实凭据（实测：激活前后 token 不同）
        val refreshed = try {
            otaApi.checkVersion(effective)
        } catch (e: Exception) {
            return fail("激活后重新拉取配置失败: ${e.message}")
        }
        return openSession(refreshed)
    }

    /**
     * 拉取配置，带身份异常自愈。
     *
     * 实测（probe/probe_server_states.py + probe_deviceid_rule.py，2026-09-01）：
     * 服务端对「退化 MAC」等被标记的身份返回 200 + 测试组凭据且【不下发 activation 段】
     * （如 02:00:00:00:00:00 / AA:AA:AA:AA:AA:AA），客户端会误判为「已绑定但凭据异常」。
     * 由于服务端并未认可绑定（token 仍是 test-token），此时重置设备身份零损失：
     * 生成全新身份重新注册，最多重试一次。
     */
    private suspend fun fetchConfigWithRecovery(
        identity: DeviceIdentity,
        onIdentityReset: ((DeviceIdentity) -> Unit)?,
    ): Pair<DeviceIdentity, OtaConfig> {
        val first = otaApi.checkVersion(identity)
        if (first.needsActivation || !first.isTestGroup) return identity to first

        // 异常身份：换新身份重试一次
        val fresh = DeviceIdentity.create(null)
        onIdentityReset?.invoke(fresh) // 先持久化，避免中途被杀后下次又用旧身份
        val second = otaApi.checkVersion(fresh)
        return fresh to second
    }

    // ------------------------------------------------------------------ 会话

    private fun openSession(config: OtaConfig): Boolean {
        val url = config.websocketUrl ?: return fail("服务端未返回 WebSocket 地址")
        val token = config.websocketToken ?: return fail("服务端未返回 token")

        if (config.isTestGroup) {
            // 重置身份重试后仍是测试组凭据：绑定确实没生效，带出设备标识便于排查
            return fail(
                "凭据仍为测试组，绑定未生效。请在设置中重置设备身份后重试 " +
                    "（device_id=${identity?.deviceId}, client_id=${identity?.clientId}）"
            )
        }

        _phase.value = Phase.Connecting
        messageJob = scope.launch { collectMessages() }
        stateJob = scope.launch { collectConnectionState() }
        decoderJob = scope.launch(Dispatchers.IO) { collectOpusFrames() }
        transport.connect(url, token, identity?.deviceId.orEmpty(), identity?.clientId.orEmpty())
        return true
    }

    private suspend fun collectMessages() {
        transport.messages.collect { msg ->
            when (msg) {
                is XiaozhiMessage.Stt -> _subtitle.value = msg.text
                is XiaozhiMessage.Llm -> {
                    _emotion.value = msg.emotion
                    if (msg.text.isNotEmpty()) _subtitle.value = msg.text
                }
                is XiaozhiMessage.Tts -> when (msg.state) {
                    TtsState.START -> {
                        _phase.value = Phase.Speaking(msg.text)
                        audio.flushPlayback()
                    }
                    TtsState.SENTENCE_START -> msg.text?.let { _subtitle.value = it }
                    TtsState.STOP -> if (_phase.value is Phase.Speaking) {
                        _phase.value = Phase.Ready(downlinkSampleRate)
                    }
                }
                is XiaozhiMessage.Alert ->
                    _subtitle.value = "${msg.status}: ${msg.message}"
                else -> Unit
            }
        }
    }

    private suspend fun collectConnectionState() {
        transport.state.collect { st ->
            when (st) {
                is ConnectionState.Open -> {
                    // 下行采样率以服务端 hello 协商结果为准（可能 24k，也可能 16k）
                    downlinkSampleRate = st.audioParams?.sampleRate ?: DEFAULT_DOWNLINK_RATE
                    _phase.value = Phase.Ready(downlinkSampleRate)
                    audio.startPlayback(downlinkSampleRate)
                }
                is ConnectionState.Failed ->
                    if (_phase.value !is Phase.Error) fail("连接失败: ${st.reason}")
                ConnectionState.Closed ->
                    if (_phase.value is Phase.Listening) audio.stopCapture()
                else -> Unit
            }
        }
    }

    private suspend fun collectOpusFrames() {
        // 解码器按下行采样率懒创建（hello 协商前默认 24k，协商后如有变化则重建）
        var decoder: OpusDecoder? = null
        var decoderRate = 0
        transport.opusFrames.collect { opus ->
            if (decoderRate != downlinkSampleRate) {
                decoder?.release()
                decoder = codecProvider.createDecoder(downlinkSampleRate)
                decoderRate = downlinkSampleRate
            }
            val pcm = decoder?.decode(opus) ?: return@collect
            audio.enqueuePcm(pcm)
        }
        decoder?.release()
    }

    // ------------------------------------------------------------------ 用户操作

    /** 开始聆听（按住说话或唤醒词触发） */
    fun startListening() {
        if (_phase.value !is Phase.Ready) return
        // 编码器只创建一次，避免每帧重建
        val enc = codecProvider.createEncoder()
        // 先挂回调再开采集，避免丢首帧
        audio.onPcmFrame = { pcm ->
            enc?.let { e -> transport.sendOpus(e.encode(pcm)) }
        }
        audio.startCapture()
        transport.send(XiaozhiMessage.Listen(com.xiaozhi.protocol.ws.ListenState.START))
        _phase.value = Phase.Listening
    }

    /** 停止聆听，等服务端回复 */
    fun stopListening() {
        if (_phase.value !is Phase.Listening) return
        transport.send(XiaozhiMessage.Listen(com.xiaozhi.protocol.ws.ListenState.STOP))
        audio.stopCapture()
        _phase.value = Phase.Ready(downlinkSampleRate)
    }

    /** 用户打断 TTS 播放 */
    fun abort() {
        transport.send(XiaozhiMessage.Abort(REASON_USER_ABORT))
        audio.flushPlayback()
    }

    fun stop() {
        stopped = true
        decoderJob?.cancel(); messageJob?.cancel(); stateJob?.cancel()
        audio.releaseAll()
        transport.close()
        _phase.value = Phase.Idle
    }

    private fun fail(message: String): Boolean {
        _phase.value = Phase.Error(message)
        return false
    }

    companion object {
        const val DEFAULT_DOWNLINK_RATE = 24_000
        const val REASON_USER_ABORT = "user_interruption"
    }
}
