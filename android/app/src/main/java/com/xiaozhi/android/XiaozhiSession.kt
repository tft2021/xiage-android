package com.xiaozhi.android

import android.content.Context
import com.xiaozhi.android.audio.AudioPipeline
import com.xiaozhi.android.data.ActivateResult
import com.xiaozhi.android.data.DeviceIdentity
import com.xiaozhi.android.data.OtaClient
import com.xiaozhi.android.data.OtaConfig
import com.xiaozhi.android.protocol.AbortReason
import com.xiaozhi.android.protocol.AudioConfig
import com.xiaozhi.android.protocol.DeviceState
import com.xiaozhi.android.protocol.ListeningMode
import com.xiaozhi.android.protocol.Messages
import com.xiaozhi.android.protocol.ServerMessageType
import com.xiaozhi.android.protocol.TtsState
import com.xiaozhi.android.protocol.WebSocketProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 会话编排：OTA → 激活 → WebSocket → 音频。
 *
 * 状态通过 [phase] 与 [deviceState] 暴露给 UI。
 */
class XiaozhiSession(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    sealed interface Phase {
        data object Idle : Phase
        data object FetchingConfig : Phase
        data class NeedActivation(val code: String, val message: String?) : Phase
        data class Activating(val code: String, val attempt: Int) : Phase
        data object Connecting : Phase
        data object Ready : Phase
        data class Error(val message: String) : Phase
    }

    data class ConversationState(
        val deviceState: DeviceState = DeviceState.IDLE,
        val userText: String = "",
        val assistantText: String = "",
        val emotion: String = "",
        val levelDb: Float = -120f,
    )

    private val identity = DeviceIdentity(context)
    private val otaClient = OtaClient(identity)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _conversation = MutableStateFlow(ConversationState())
    val conversation: StateFlow<ConversationState> = _conversation.asStateFlow()

    private var config: OtaConfig? = null
    private var ws: WebSocketProtocol? = null
    private var audio: AudioPipeline? = null
    private var activationJob: Job? = null

    /** 开启 AEC 时，说话期间仍继续采集，才能实现随时打断 */
    private var aecEnabled = true
    private var listeningMode = ListeningMode.AUTO_STOP

    // ------------------------------------------------------------ 启动

    fun bootstrap(localIp: String = "192.168.1.100") {
        scope.launch {
            _phase.value = Phase.FetchingConfig
            runCatching { otaClient.fetchConfig(localIp) }
                .onSuccess { cfg ->
                    config = cfg
                    if (cfg.needsActivation) {
                        startActivationPolling(cfg)
                    } else {
                        connect()
                    }
                }
                .onFailure { _phase.value = Phase.Error(it.message ?: "OTA 失败") }
        }
    }

    private fun startActivationPolling(cfg: OtaConfig) {
        val code = cfg.activationCode ?: return
        _phase.value = Phase.NeedActivation(code, cfg.activationMessage)

        activationJob?.cancel()
        activationJob = scope.launch {
            repeat(ACTIVATION_MAX_RETRIES) { attempt ->
                _phase.value = Phase.Activating(code, attempt + 1)
                when (val result = runCatching { otaClient.activate() }.getOrNull()) {
                    is ActivateResult.Activated -> {
                        // 激活完成后重新拉取一次，拿到真实凭据
                        val fresh = runCatching { otaClient.fetchConfig() }.getOrNull()
                        if (fresh != null) config = fresh
                        connect()
                        return@launch
                    }
                    is ActivateResult.Failed -> {
                        _phase.value = Phase.Error("激活失败 HTTP ${result.code}: ${result.body}")
                        return@launch
                    }
                    else -> if (!isActive) return@launch
                }
                delay(ACTIVATION_RETRY_INTERVAL_MS)
            }
            _phase.value = Phase.Error("激活超时，请确认已在 xiaozhi.me 输入激活码")
        }
    }

    private fun connect() {
        val cfg = config ?: run {
            _phase.value = Phase.Error("缺少 OTA 配置")
            return
        }
        if (cfg.websocketUrl.isBlank()) {
            _phase.value = Phase.Error("OTA 未返回 websocket.url")
            return
        }
        if (cfg.isTestTier) {
            // 未绑定账号：能连上 101，但发完 hello 会被立即 Close
            android.util.Log.w(TAG, "仍处于测试组，token=${cfg.websocketToken}，可能未完成账号绑定")
        }

        _phase.value = Phase.Connecting
        ws = WebSocketProtocol(
            url = cfg.websocketUrl,
            token = cfg.websocketToken,
            deviceId = identity.deviceId,
            clientId = identity.clientId,
            listener = object : WebSocketProtocol.Listener {
                override fun onConnected(sessionId: String, audioParams: JSONObject?) {
                    audioParams?.let {
                        AudioConfig.applyServerParams(
                            sampleRate = it.optInt("sample_rate").takeIf { v -> v > 0 },
                            frameDuration = it.optInt("frame_duration").takeIf { v -> v > 0 },
                        )
                    }
                    ensureAudio()
                    _phase.value = Phase.Ready
                }

                override fun onJson(json: JSONObject) = handleJson(json)

                override fun onAudio(data: ByteArray) {
                    // 监听态收到的下行音频一律丢弃，避免和麦克风流打架
                    if (_conversation.value.deviceState == DeviceState.LISTENING) return
                    audio?.play(data)
                }

                override fun onDisconnected(reason: String) {
                    stopListening()
                    _phase.value = Phase.Error("连接断开: $reason")
                }

                override fun onError(message: String) {
                    _phase.value = Phase.Error(message)
                }
            }
        )
        scope.launch {
            if (ws?.connect() != true) {
                _phase.value = Phase.Error("连接失败，详见日志")
            }
        }
    }

    // ------------------------------------------------------------ 服务端消息

    private fun handleJson(json: JSONObject) {
        when (json.optString("type")) {
            ServerMessageType.STT -> {
                _conversation.value = _conversation.value.copy(userText = json.optString("text"))
            }

            ServerMessageType.LLM -> {
                _conversation.value = _conversation.value.copy(
                    emotion = json.optString("emotion"),
                    assistantText = json.optString("text"),
                )
            }

            ServerMessageType.TTS -> when (json.optString("state")) {
                TtsState.START -> {
                    _conversation.value = _conversation.value.copy(deviceState = DeviceState.SPEAKING)
                    audio?.preparePlayback()
                    // 未开 AEC 时必须停采集，否则会把自己的播放当成输入
                    if (!aecEnabled) stopCaptureOnly()
                }

                TtsState.SENTENCE_START -> {
                    _conversation.value = _conversation.value.copy(
                        assistantText = json.optString("text"),
                    )
                }

                TtsState.STOP -> {
                    _conversation.value = _conversation.value.copy(deviceState = DeviceState.IDLE)
                }
            }

            ServerMessageType.MCP -> handleMcp(json.optJSONObject("payload"))

            ServerMessageType.ALERT -> {
                _conversation.value = _conversation.value.copy(
                    assistantText = json.optString("message"),
                    emotion = json.optString("emotion"),
                )
            }

            ServerMessageType.SYSTEM -> {
                android.util.Log.i(TAG, "系统指令: ${json.optString("command")}")
            }
        }
    }

    private fun handleMcp(payload: JSONObject?) {
        payload ?: return
        if (payload.optString("method") != "tools/call") return

        val id = payload.opt("id")
        val name = payload.optJSONObject("params")?.optString("name").orEmpty()
        android.util.Log.i(TAG, "MCP 调用: $name")

        // TODO 按 name 分派到手机端能力（导航 / 播放音乐 / 查日程 ...）
        val resultText = "true"
        ws?.sendText(Messages.mcpResult(ws?.currentSessionId.orEmpty(), id, resultText))
    }

    // ------------------------------------------------------------ 交互

    fun startListening(mode: ListeningMode = listeningMode) {
        listeningMode = mode
        val w = ws ?: return
        ensureAudio()
        w.sendText(Messages.listenStart(w.currentSessionId, mode))
        _conversation.value = _conversation.value.copy(deviceState = DeviceState.LISTENING)
        startCaptureOnly()
    }

    fun stopListening() {
        ws?.sendText(Messages.listenStop(ws?.currentSessionId.orEmpty()))
        stopCaptureOnly()
        _conversation.value = _conversation.value.copy(deviceState = DeviceState.IDLE)
    }

    /** 打断：清空播放缓冲并切回监听 */
    fun abort(reason: AbortReason = AbortReason.USER_INTERRUPTION) {
        val w = ws ?: return
        w.sendText(Messages.abort(w.currentSessionId, reason))
        audio?.flushPlayback()
        _conversation.value = _conversation.value.copy(deviceState = DeviceState.LISTENING)
        startCaptureOnly()
    }

    // ------------------------------------------------------------ 音频

    private fun ensureAudio() {
        if (audio != null) return
        audio = AudioPipeline(
            scope = scope,
            onOpusFrame = { opus ->
                // 监听态才上行；说话且开了 AEC 时在实时模式下也继续上行（支持打断）
                val state = _conversation.value.deviceState
                val shouldSend = state == DeviceState.LISTENING ||
                    (aecEnabled && listeningMode == ListeningMode.REALTIME)
                if (shouldSend) ws?.sendAudio(opus)
            },
            onLevel = { db -> _conversation.value = _conversation.value.copy(levelDb = db) },
        ).apply { aecEnabled = this@XiaozhiSession.aecEnabled; prepare() }
    }

    private fun startCaptureOnly() = audio?.startCapture()

    private fun stopCaptureOnly() = audio?.stopCapture()

    fun release() {
        activationJob?.cancel()
        runCatching { stopListening() }
        audio?.release()
        audio = null
        ws?.close()
        ws = null
    }

    companion object {
        private const val TAG = "XiaozhiSession"

        // 与 py-xiaozhi 的 SystemConstants 一致
        private const val ACTIVATION_MAX_RETRIES = 60
        private const val ACTIVATION_RETRY_INTERVAL_MS = 5_000L
    }
}
