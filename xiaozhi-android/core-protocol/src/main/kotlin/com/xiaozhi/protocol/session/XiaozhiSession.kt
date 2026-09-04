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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * 轮询期间重新拉配置刷新 challenge 的间隔。
     *
     * **默认禁用**：2026-09-04 实测（probe/probe_activate_semantics.py）证明
     * 服务端在绑定前对 /activate 根本不校验 challenge —— 用旧 challenge 签名返回的
     * 也是同一个 202。于是"刷新 challenge"对激活成功毫无帮助，却有两个确定的害处：
     *  1. 多发的 OTA 请求可能让服务端刷新激活码，导致用户刚在 xiaozhi.me 输的码作废，
     *     表现就是"每点一次连接激活码就变一次"，永远绑不上
     *  2. 白白消耗请求配额
     * 保留参数只为测试（传 0 即每轮都刷新）。
     */
    private val challengeRefreshIntervalMs: Long = Long.MAX_VALUE,
    /** 激活成功后重新拉配置的次数（服务端绑定生效可能有短暂延迟） */
    private val postActivateOtaRetries: Int = 10,
    /**
     * 判定配置"不健康"后、同身份重试前的等待。
     * 给服务端下发真实凭据留出时间，避免把刚绑定的身份误判成异常而丢弃。
     */
    private val unhealthyRetryDelayMs: Long = 3_000,
    /** 激活成功后重新拉配置的重试间隔（总窗口 = 次数 × 间隔 ≈ 20s） */
    private val postActivateRetryDelayMs: Long = 2_000,
) {

    sealed class Phase {
        data object Idle : Phase()
        data object FetchingConfig : Phase()
        /** 需要激活，code 为 6 位激活码，展示给用户去 xiaozhi.me 输入 */
        data class NeedActivation(val code: String) : Phase()
        /** /activate 已返回 200（服务端确认绑定），正在等 OTA 下发真实凭据 */
        data object FetchingCredentials : Phase()
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

    /** 激活等待期"立即检测"信号：值变化即唤醒轮询，不必等满一个间隔 */
    private val activationNudge = MutableStateFlow(0L)

    /**
     * start() 的世代号。每次 start() 自增；激活循环醒来时发现世代已变，
     * 立即退出——解决"激活等待中点重新获取"时新旧循环并发的问题
     * （不能用取消 Job 实现：start() 可能直接运行在测试的 runBlocking 上）
     */
    private var generation = 0L

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
        // 防重入：激活/连接进行中再 start 会并发跑两个循环，互相覆盖 phase。
        // UI 需要重新获取激活码时必须先 stop() 再 start()。
        when (_phase.value) {
            is Phase.FetchingConfig, is Phase.Connecting, is Phase.NeedActivation -> return false
            else -> {}
        }
        val myGeneration = ++generation
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
        val initialCode = config.activationCode ?: return fail("服务端未下发激活码")
        val initialChallenge = config.activationChallenge
        if (initialChallenge.isNullOrEmpty()) return fail("服务端未下发激活 challenge")
        var code: String = initialCode
        var challenge: String = initialChallenge
        onNeedActivation(code)
        _phase.value = Phase.NeedActivation(code)

        var activated = false
        val deadline = System.currentTimeMillis() + activationTimeoutMs
        var lastChallengeRefresh = System.currentTimeMillis()
        while (!activated && System.currentTimeMillis() < deadline && !stopped) {
            // 被"重新获取"取代：新 start() 已把世代推进，本循环立即退场
            if (generation != myGeneration) return false
            // 实测：服务端每次 OTA 都会刷新 challenge，长期用旧 challenge 轮询有失效风险。
            // 定期重新拉配置既刷新 challenge，又能顺带发现「用户已绑定」。
            if (System.currentTimeMillis() - lastChallengeRefresh >= challengeRefreshIntervalMs) {
                val polled = try {
                    otaApi.checkVersion(effective)
                } catch (_: Exception) {
                    null // 轮询刷新失败不影响主流程，下一轮继续
                }
                if (polled != null) {
                    lastChallengeRefresh = System.currentTimeMillis()
                    // 只有"无激活码且非测试组"才算真正绑定成功。
                    // 若刷新拿到测试组凭据（服务端瞬时脏响应），不能当成已绑定去连，
                    // 也不能因此中断等待——保持原 challenge 继续轮询。
                    if (!polled.needsActivation && !polled.isTestGroup) {
                        return openSession(polled)
                    }
                    polled.activationChallenge?.let { challenge = it }
                    val newCode = polled.activationCode
                    if (newCode != null && newCode != code) {
                        code = newCode
                        onNeedActivation(code)
                        _phase.value = Phase.NeedActivation(code)
                    }
                }
            }

            // 网络抖动不能让整个流程崩掉：异常等同 Waiting，下一轮继续
            val result = try {
                otaApi.activate(effective, challenge)
            } catch (e: Exception) {
                ActivateResult.Failed(NETWORK_ERROR_CODE, e.message.orEmpty())
            }
            when (result) {
                ActivateResult.Success -> {
                    activated = true
                    // 绑定已被服务端确认：从激活码界面切到"获取凭据中"，
                    // 让用户知道输码生效了，只差最后一步
                    if (_phase.value is Phase.NeedActivation) {
                        _phase.value = Phase.FetchingCredentials
                    }
                }
                ActivateResult.Waiting -> waitActivationInterval()
                is ActivateResult.Failed ->
                    if (result.code == NETWORK_ERROR_CODE) {
                        waitActivationInterval() // 网络问题：重试
                    } else {
                        return fail("激活请求被拒绝（HTTP ${result.code}），请检查网络后重试")
                    }
            }
        }
        if (stopped) return false
        if (generation != myGeneration) return false
        if (!activated) {
            return fail("等待绑定超时（${activationTimeoutMs / 1000}s 内未检测到激活），请点击连接重新获取激活码")
        }

        // 激活成功后必须重新拉配置，才能拿到真实凭据（实测：激活前后 token 不同）。
        // 服务端绑定生效可能有短暂延迟，允许重试几次再判失败。
        var refreshed: OtaConfig? = null
        for (attempt in 1..postActivateOtaRetries) {
            // 用户点了断开/重新获取：立即退场，不要继续占用 OTA
            if (stopped || generation != myGeneration) return false
            refreshed = try {
                otaApi.checkVersion(effective)
            } catch (_: Exception) {
                null
            }
            if (refreshed != null && !refreshed.isTestGroup) break
            if (attempt < postActivateOtaRetries) delay(postActivateRetryDelayMs)
        }
        // 重试耗尽仍是测试组：绑定已确认但凭据下发有延迟（实测可能超过 5s），
        // 这里显式报错并给出恢复路径；身份绝不能在此处更换
        if (refreshed == null || refreshed.isTestGroup) {
            return fail(
                "绑定已确认，但服务端下发凭据延迟（已重试 $postActivateOtaRetries 次）。" +
                    "请等 10 秒后点击连接重试；若反复出现，请核对激活界面设备号与 xiaozhi.me 是否一致"
            )
        }
        return openSession(refreshed)
    }

    /**
     * 激活等待期的休眠：默认睡满一个轮询间隔，但 UI 调用 [nudgeActivation]
     * （用户在 xiaozhi.me 输完码点"立即检测"）时立即醒来做下一轮检测。
     *
     * 背景：App 切到后台（去浏览器输码）时轮询协程可能被系统冻结或限制网络，
     * 回到前台后如果只剩几秒就到下一轮，用户面对的是完全静止的界面——
     * 必须给用户一个主动触发检测的手段，而不是干等。
     */
    private suspend fun waitActivationInterval() {
        val seen = activationNudge.value
        withTimeoutOrNull(activationPollIntervalMs) {
            activationNudge.first { it != seen }
        }
    }

    /** NeedActivation 阶段由 UI 调用：立即唤醒激活循环做一轮检测 */
    fun nudgeActivation() {
        if (_phase.value is Phase.NeedActivation) {
            activationNudge.value = activationNudge.value + 1
        }
    }

    /**
     * 拉取配置，带身份异常自愈。
     *
     * 背景（实测，probe/probe_server_states.py + probe_deviceid_rule.py）：
     * 服务端对「退化 MAC」等被标记的身份返回 200 + 测试组凭据且【不下发 activation 段】
     * （如 02:00:00:00:00:00 / AA:AA:AA:AA:AA:AA），客户端会误判为「已绑定但凭据异常」。
     *
     * 自愈策略刻意保守，因为丢弃身份是不可逆操作（已绑定设备换身份 = 被迫重新绑定）：
     *  1. 同身份再拉一次，排除服务端瞬时抖动 / 限流导致的偶发脏响应
     *  2. 仍异常才换新身份试探
     *  3. 新身份【同样】异常 ⇒ 说明是服务端侧问题，回滚原身份，绝不丢弃
     *     用户原有的绑定关系（若存在）得以保留
     */
    private suspend fun fetchConfigWithRecovery(
        identity: DeviceIdentity,
        onIdentityReset: ((DeviceIdentity) -> Unit)?,
    ): Pair<DeviceIdentity, OtaConfig> {
        val first = otaApi.checkVersion(identity)
        if (isHealthy(first)) return identity to first

        // 1) 同身份重试，排除偶发脏响应。
        //    **必须带延迟**：用户在 xiaozhi.me 刚绑完就点连接时，服务端下发真实凭据
        //    可能有几秒滞后；间隔为 0 的连续重试拿到的是同样滞后的脏响应，
        //    会被误判成"身份异常"进而换掉身份 —— 那等于把用户刚建立的绑定关系丢掉，
        //    表现就是"每次连接激活码都不一样，永远绑不上"。
        delay(unhealthyRetryDelayMs)
        val retry = otaApi.checkVersion(identity)
        if (isHealthy(retry)) return identity to retry

        // 2) 换新身份试探。
        //    **UI 不传 onIdentityReset 即表示不允许换身份**：曾经成功绑定过的设备
        //    一旦自动换身份就等于丢掉用户已建立的绑定关系，表现正是
        //    "xiaozhi.me 显示已绑定、手机上却永远在等激活"，且每次连接激活码都不一样。
        //    这个不可逆操作必须由 UI 显式授权。
        if (onIdentityReset == null) return identity to retry

        val fresh = DeviceIdentity.create(null)
        val second = otaApi.checkVersion(fresh)

        // 3) 新身份同样异常 -> 回滚原身份，不丢绑定
        if (!isHealthy(second)) return identity to second

        onIdentityReset.invoke(fresh) // 只有确认新身份可用才持久化
        return fresh to second
    }

    /** 健康 = 拿到真实凭据（已绑定）或拿到激活码（可走绑定流程） */
    private fun isHealthy(config: OtaConfig): Boolean =
        config.needsActivation || !config.isTestGroup

    // ------------------------------------------------------------------ 会话

    private fun openSession(config: OtaConfig): Boolean {
        // 用 isNullOrBlank 而不是判空：畸形响应可能给空串，空串会骗过 ?: 判空
        val url = config.websocketUrl.takeUnless { it.isNullOrBlank() }
            ?: return fail("服务端未返回 WebSocket 地址")
        val token = config.websocketToken.takeUnless { it.isNullOrBlank() }
            ?: return fail("服务端未返回 token")

        if (config.isTestGroup) {
            // 重置身份重试后仍是测试组凭据：绑定确实没生效，带出设备标识便于排查
            return fail(
                "凭据仍为测试组，绑定未生效。请点击下方「重置设备身份」按钮重新开始绑定 " +
                    "（device_id=${identity?.deviceId}, client_id=${identity?.clientId}）"
            )
        }

        _phase.value = Phase.Connecting
        messageJob = scope.launch { collectMessages() }
        stateJob = scope.launch { collectConnectionState() }
        decoderJob = scope.launch(Dispatchers.IO) { collectOpusFrames() }
        // OkHttp 的 newWebSocket 对非法 URL 是【同步抛异常】而不是回调 onFailure，
        // 不接住的话异常会一路穿到 ViewModel 的 launch 里把 App 打崩。
        return try {
            transport.connect(url, token, identity?.deviceId.orEmpty(), identity?.clientId.orEmpty())
            true
        } catch (e: Exception) {
            fail("建立连接失败: ${e.message}")
        }
    }

    private suspend fun collectMessages() {
        transport.messages.collect { msg ->
            // 单条消息处理失败绝不能让协程挂掉：本协程跑在外部 scope（生产是
            // viewModelScope）上，异常逃逸会取消整个 scope，把连接状态收集、
            // Opus 解码收集一起带走——用户只会看到界面"卡死"却没有任何提示。
            try {
                handleMessage(msg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("处理消息失败: $msg", e)
            }
        }
    }

    private fun handleMessage(msg: XiaozhiMessage) {
        when (msg) {
            is XiaozhiMessage.Stt -> _subtitle.value = msg.text
            is XiaozhiMessage.Llm -> {
                _emotion.value = msg.emotion
                if (msg.text.isNotEmpty()) _subtitle.value = msg.text
            }
            is XiaozhiMessage.Tts -> when (msg.state) {
                TtsState.START -> {
                    _phase.value = Phase.Speaking(msg.text)
                    // AudioTrack 尚未初始化或被 release 时 flush 会抛异常，
                    // 由 collectMessages 统一兜住：丢一次 flush 不影响后续播放
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

    private suspend fun collectConnectionState() {
        transport.state.collect { st ->
            when (st) {
                is ConnectionState.Open -> {
                    // 下行采样率以服务端 hello 协商结果为准（可能 24k，也可能 16k）
                    downlinkSampleRate = st.audioParams?.sampleRate ?: DEFAULT_DOWNLINK_RATE
                    _phase.value = Phase.Ready(downlinkSampleRate)
                    // 设备不支持该采样率时 AudioTrack 初始化会抛异常；
                    // 这里必须接住，否则 stateJob 被取消，整个会话一起失效
                    try {
                        audio.startPlayback(downlinkSampleRate)
                    } catch (e: Exception) {
                        fail("无法启动音频播放（${downlinkSampleRate}Hz）: ${e.message}")
                    }
                }
                is ConnectionState.Failed ->
                    if (_phase.value !is Phase.Error) fail("连接失败: ${st.reason}")
                ConnectionState.Closed -> when (_phase.value) {
                    is Phase.Listening -> audio.stopCapture()
                    // 会话还没建立（没收到服务端 hello）就被关闭：典型原因是凭据无效，
                    // 服务端给完 101 就直接 close。不处理的话 phase 会永远停在 Connecting，
                    // 用户只看到"连接中..."却没有任何反馈。
                    // 注意 Idle 要排除：stop() 会先 close 再置 Idle，那是主动断开。
                    is Phase.Connecting ->
                        fail("连接被服务端关闭（未收到 hello），凭据可能已失效，请重新点击连接")
                    else -> Unit
                }
                else -> Unit
            }
        }
    }

    private suspend fun collectOpusFrames() {
        // 解码器按下行采样率懒创建（hello 协商前默认 24k，协商后如有变化则重建）
        var decoder: OpusDecoder? = null
        var decoderRate = 0
        transport.opusFrames.collect { opus ->
            // 与 collectMessages 同理：坏帧（解码失败）或 AudioTrack 已被 release 时
            // write 抛异常都要就地兜住，丢一帧音频用户无感，取消整个 scope 却是致命的
            try {
                if (decoderRate != downlinkSampleRate) {
                    decoder?.release()
                    decoder = codecProvider.createDecoder(downlinkSampleRate)
                    decoderRate = downlinkSampleRate
                }
                val pcm = decoder?.decode(opus)
                if (pcm != null) audio.enqueuePcm(pcm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW("解码/播放音频帧失败", e)
            }
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
        // 麦克风未授权 / 设备不支持 16k 采集时 startCapture 会抛异常，
        // 必须转成 Error 阶段：否则异常穿到 UI 线程直接崩 App
        try {
            audio.startCapture()
        } catch (e: Exception) {
            audio.onPcmFrame = null
            fail("无法启动录音: ${e.message}")
            return
        }
        transport.send(XiaozhiMessage.Listen(com.xiaozhi.protocol.ws.ListenState.START))
        _phase.value = Phase.Listening
    }

    /** 停止聆听，等服务端回复 */
    fun stopListening() {
        if (_phase.value !is Phase.Listening) return
        guard("停止录音") {
            transport.send(XiaozhiMessage.Listen(com.xiaozhi.protocol.ws.ListenState.STOP))
            audio.stopCapture()
        }
        _phase.value = Phase.Ready(downlinkSampleRate)
    }

    /** 用户打断 TTS 播放 */
    fun abort() {
        // abort() 由 UI 直接同步调用（不在协程里），这里的任何异常都是直接崩 App。
        // AudioTrack 未初始化或被系统回收时 pause()/flush() 会抛 IllegalStateException。
        guard("打断播放") {
            transport.send(XiaozhiMessage.Abort(REASON_USER_ABORT))
            audio.flushPlayback()
        }
        // 立刻回到 Ready，让用户能马上接下一句，不必等服务端补发 TTS stop。
        // 服务端随后若发来 TTS stop，collectMessages 里只处理 Speaking 态，不会重复切换。
        if (_phase.value is Phase.Speaking) _phase.value = Phase.Ready(downlinkSampleRate)
    }

    fun stop() {
        stopped = true
        // 先置 Idle 再 close：transport.close() 会同步 emit Closed，
        // 而 stateJob 的取消是异步生效的，晚一步就把主动断开误判成"被服务端关闭"了
        _phase.value = Phase.Idle
        decoderJob?.cancel(); messageJob?.cancel(); stateJob?.cancel()
        guard("释放音频资源") { audio.releaseAll() }
        guard("关闭连接") { transport.close() }
    }

    private fun fail(message: String): Boolean {
        _phase.value = Phase.Error(message)
        return false
    }

    /**
     * 非致命内部异常记录。core-protocol 是纯 JVM 模块，不能依赖 android.util.Log；
     * Android 上 System.err 同样会落到 logcat，排查时按 "XiaozhiSession:" 过滤即可。
     */
    private fun logW(message: String, e: Throwable) {
        System.err.println("XiaozhiSession: $message: ${e.message}")
    }

    /**
     * [stopListening] / [abort] / [stop] 都是 UI 线程同步调用的（不跑在协程里），
     * 里面任何异常都无处传播、直接崩 App。这里统一兜住并记日志。
     */
    private fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logW(what + "失败", e)
        }
    }

    companion object {
        const val DEFAULT_DOWNLINK_RATE = 24_000
        const val REASON_USER_ABORT = "user_interruption"

        /** activate 网络异常的伪状态码（区别于真实 HTTP 状态码，用于区分重试策略） */
        const val NETWORK_ERROR_CODE = -1
    }
}
