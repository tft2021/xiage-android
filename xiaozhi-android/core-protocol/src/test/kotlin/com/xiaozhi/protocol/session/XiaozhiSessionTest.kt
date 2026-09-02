package com.xiaozhi.protocol.session

import com.xiaozhi.protocol.audio.AudioIO
import com.xiaozhi.protocol.ota.ActivateResult
import com.xiaozhi.protocol.ota.DeviceIdentity
import com.xiaozhi.protocol.ota.OtaApi
import com.xiaozhi.protocol.ota.OtaConfig
import com.xiaozhi.protocol.ws.AudioParams
import com.xiaozhi.protocol.ws.ConnectionState
import com.xiaozhi.protocol.ws.ListenState
import com.xiaozhi.protocol.ws.TtsState
import com.xiaozhi.protocol.ws.XiaozhiMessage
import com.xiaozhi.protocol.ws.XiaozhiTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// ---------------------------------------------------------------- Fakes

private class FakeTransport : XiaozhiTransport {
    val sentMessages = mutableListOf<XiaozhiMessage>()
    val sentOpus = mutableListOf<ByteArray>()
    var connectedUrl: String? = null
    var connectedToken: String? = null
    var closed = false
    /** 故障注入：模拟 OkHttp 对非法 URL 同步抛异常（不是回调 onFailure） */
    var connectThrows: Throwable? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state

    private val _messages = MutableSharedFlow<XiaozhiMessage>(replay = 32)
    override val messages: SharedFlow<XiaozhiMessage> = _messages

    private val _opusFrames = MutableSharedFlow<ByteArray>(replay = 32)
    override val opusFrames: SharedFlow<ByteArray> = _opusFrames

    override fun connect(url: String, token: String, deviceId: String, clientId: String) {
        connectThrows?.let { throw it }
        connectedUrl = url
        connectedToken = token
        _state.value = ConnectionState.Connecting
    }

    override fun send(message: XiaozhiMessage) {
        sentMessages.add(message)
    }

    override fun sendMcpResult(payload: JSONObject) = Unit

    override fun sendOpus(frame: ByteArray) {
        sentOpus.add(frame)
    }

    override fun close(reason: String) {
        closed = true
        _state.value = ConnectionState.Closed
    }

    /** 测试辅助：模拟服务端 hello，会话进入 Open */
    suspend fun open(sessionId: String? = "sess-1", params: AudioParams? = AudioParams.downlink(24_000)) {
        _state.value = ConnectionState.Open(sessionId, params)
    }

    suspend fun emitMessage(msg: XiaozhiMessage) = _messages.emit(msg)
}

private class FakeAudioIO : AudioIO {
    override var onPcmFrame: ((ShortArray) -> Unit)? = null
    var captureStarted = false
    var playbackRate: Int? = null
    val pcmReceived = mutableListOf<ShortArray>()
    var released = false

    override fun startCapture() { captureStarted = true }
    override fun stopCapture() { captureStarted = false }
    override val isCapturing: Boolean get() = captureStarted
    override fun startPlayback(sampleRate: Int) { playbackRate = sampleRate }
    override fun enqueuePcm(pcm: ShortArray) { pcmReceived.add(pcm) }
    override fun flushPlayback() = Unit
    override fun stopPlayback() = Unit
    override fun releaseAll() { released = true }
}

private class FakeOtaApi(
    private val configs: MutableList<OtaConfig>,
    private val activateResults: MutableList<ActivateResult>,
    /** 前 N 次 activate 调用抛出网络异常，用于验证重试与防崩溃 */
    var activateFailureCount: Int = 0,
) : OtaApi {
    val checkVersionCalls = mutableListOf<DeviceIdentity>()
    val activateCalls = mutableListOf<Pair<DeviceIdentity, String>>()

    override suspend fun checkVersion(identity: DeviceIdentity, localIp: String): OtaConfig {
        checkVersionCalls.add(identity)
        return configs.removeFirstOrNull() ?: error("FakeOtaApi 没有更多预置配置")
    }

    override suspend fun activate(identity: DeviceIdentity, challenge: String): ActivateResult {
        activateCalls.add(identity to challenge)
        if (activateFailureCount > 0) {
            activateFailureCount--
            throw java.io.IOException("模拟网络抖动")
        }
        return activateResults.removeFirstOrNull() ?: ActivateResult.Waiting
    }
}

// ---------------------------------------------------------------- 测试

class XiaozhiSessionTest {

    private val identity = DeviceIdentity.create(null)

    private suspend fun awaitPhase(
        session: XiaozhiSession,
        timeoutMs: Long = 5_000,
        pred: (XiaozhiSession.Phase) -> Boolean,
    ): XiaozhiSession.Phase = withTimeout(timeoutMs) {
        var last = session.phase.value
        while (!pred(last)) {
            delay(10)
            last = session.phase.value
        }
        last
    }

    // ---------------- 已绑定设备：直接进会话 ----------------

    @Test
    fun `已绑定设备 OTA 后直接连接进入 Ready`() = runBlocking {
        val config = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = "real-token",
        )
        val ota = FakeOtaApi(mutableListOf(config), mutableListOf())
        val transport = FakeTransport()
        val audio = FakeAudioIO()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = audio, otaApi = ota,
        )

        val ok = session.start(identity, { })
        assertTrue(ok, "已绑定设备应直接建立会话")

        // 用真实凭据连接
        assertEquals("wss://fake/v1/", transport.connectedUrl)
        assertEquals("real-token", transport.connectedToken)
        assertEquals(1, ota.checkVersionCalls.size) // 未走激活，只拉了一次配置

        // 服务端 hello 到达 -> Ready，采样率取协商值
        transport.open()
        val phase = awaitPhase(session) { it is XiaozhiSession.Phase.Ready }
        assertEquals(24_000, (phase as XiaozhiSession.Phase.Ready).sampleRate)
        assertEquals(24_000, audio.playbackRate)
        session.stop()
    }

    // ---------------- 未绑定设备：激活轮询 ----------------

    @Test
    fun `未绑定设备激活轮询后重新拉配置进入 Ready`() = runBlocking {
        val first = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
            activationCode = "123456",
            activationChallenge = "challenge-x",
        )
        val refreshed = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = "bound-token",
        )
        val ota = FakeOtaApi(
            mutableListOf(first, refreshed),
            mutableListOf(ActivateResult.Waiting, ActivateResult.Waiting, ActivateResult.Success),
        )
        val transport = FakeTransport()
        val audio = FakeAudioIO()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = audio, otaApi = ota,
            activationPollIntervalMs = 1,
        )

        val codes = mutableListOf<String>()
        val ok = session.start(identity, { codes.add(it) })
        assertTrue(ok)

        // 展示过激活码，并按挑战值轮询激活
        assertEquals(listOf("123456"), codes)
        assertTrue(ota.activateCalls.size >= 3)
        assertEquals("challenge-x", ota.activateCalls[0].second)
        // 激活成功后重新拉取配置（拿到真实 token）
        assertEquals(2, ota.checkVersionCalls.size)
        assertEquals("bound-token", transport.connectedToken)

        transport.open()
        awaitPhase(session) { it is XiaozhiSession.Phase.Ready }
        session.stop()
    }

    @Test
    fun `激活超时显式报错而不是静默停在 NeedActivation`() = runBlocking {
        val first = OtaConfig.minimal(
            activationCode = "999999",
            activationChallenge = "challenge-y",
        )
        val ota = FakeOtaApi(mutableListOf(first), mutableListOf()) // activate 永远 Waiting
        val transport = FakeTransport()
        val audio = FakeAudioIO()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = audio, otaApi = ota,
            activationPollIntervalMs = 10,
            activationTimeoutMs = 120,
            challengeRefreshIntervalMs = 10_000, // 本例不测刷新，避免消耗预置配置
        )

        val ok = session.start(identity, { })
        assertFalse(ok, "激活超时应返回 false")
        val err = assertIs<XiaozhiSession.Phase.Error>(session.phase.value)
        assertTrue(err.message.contains("等待绑定超时"), "实际: ${err.message}")
        assertTrue(transport.connectedUrl == null, "不应发起连接")
        session.stop()
    }

    // ---------------- 测试组凭据诊断 ----------------

    @Test
    fun `服务端仍下发测试组凭据时报错不连接且回滚身份`() = runBlocking {
        // 自愈三级都用尽仍是测试组（无激活码）-> 报错并回滚原身份，绝不丢弃已绑定的身份
        val bad = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
        )
        // first / 同身份 retry / 新身份 fresh 各一份
        val ota = FakeOtaApi(mutableListOf(bad, bad, bad), mutableListOf())
        val transport = FakeTransport()
        val audio = FakeAudioIO()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = audio, otaApi = ota,
        )

        val resets = mutableListOf<DeviceIdentity>()
        val ok = session.start(identity, { }, onIdentityReset = { resets.add(it) })
        assertFalse(ok, "重试后仍是测试组凭据应报错")
        val err = assertIs<XiaozhiSession.Phase.Error>(session.phase.value)
        assertTrue(err.message.contains("绑定未生效"), "实际: ${err.message}")
        assertTrue(transport.connectedUrl == null)
        // 三级自愈确实发生过：first + 同身份 retry + 新身份 fresh
        assertEquals(3, ota.checkVersionCalls.size)
        assertEquals(identity.deviceId, ota.checkVersionCalls[0].deviceId)
        assertEquals(identity.deviceId, ota.checkVersionCalls[1].deviceId, "第二级应复用原身份")
        assertFalse(
            ota.checkVersionCalls[2].deviceId == identity.deviceId,
            "第三级应换新身份试探",
        )
        // 新身份同样异常 -> 不回调持久化，原身份保留（连接用的是原身份）
        assertTrue(resets.isEmpty(), "新身份不可用时不得持久化")
        session.stop()
    }

    @Test
    fun `新身份同样异常时绝不替换已绑定身份`() = runBlocking {
        // 已绑定设备遇到服务端偶发脏响应：不能因为一次异常就把已绑定身份换掉
        val bad = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
        )
        val ota = FakeOtaApi(mutableListOf(bad, bad, bad), mutableListOf())
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = FakeTransport(), audio = FakeAudioIO(), otaApi = ota,
        )
        val resets = mutableListOf<DeviceIdentity>()
        session.start(identity, { }, onIdentityReset = { resets.add(it) })
        assertTrue(resets.isEmpty())
        // 会话内部记录的身份仍是原身份（错误信息里带出）
        val err = session.phase.value as XiaozhiSession.Phase.Error
        assertTrue(err.message.contains(identity.deviceId), "实际: ${err.message}")
        session.stop()
    }

    @Test
    fun `身份异常时自动重置身份重试后正常连接`() = runBlocking {
        // first：测试组凭据且无激活码（服务端不下发 activation 的异常身份）
        val broken = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
        )
        // retry（同身份）：仍然异常
        val brokenAgain = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
        )
        // fresh（新身份）：真实凭据
        val healthy = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = "real-token",
        )
        val ota = FakeOtaApi(mutableListOf(broken, brokenAgain, healthy), mutableListOf())
        val transport = FakeTransport()
        val audio = FakeAudioIO()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = audio, otaApi = ota,
        )

        val resets = mutableListOf<DeviceIdentity>()
        val ok = session.start(identity, { }, onIdentityReset = { resets.add(it) })
        assertTrue(ok, "重置身份后应成功建立会话")

        // 三次 OTA：first -> 同身份 retry -> 新身份 fresh
        assertEquals(3, ota.checkVersionCalls.size)
        val secondIdentity = ota.checkVersionCalls[2]
        assertFalse(
            secondIdentity.deviceId == identity.deviceId ||
                secondIdentity.clientId == identity.clientId,
            "第三级必须使用全新身份",
        )
        assertEquals(listOf(secondIdentity), resets)

        assertEquals("real-token", transport.connectedToken)
        transport.open()
        awaitPhase(session) { it is XiaozhiSession.Phase.Ready }
        session.stop()
    }

    // ---------------- 激活链路健壮性 ----------------

    @Test
    fun `activate 网络异常不崩溃且自动重试`() = runBlocking {
        val pending = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
            activationCode = "555555",
            activationChallenge = "challenge-n",
        )
        val bound = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = "bound-token")
        val ota = FakeOtaApi(
            mutableListOf(pending, bound),
            mutableListOf(ActivateResult.Success),
            activateFailureCount = 2, // 前两次抛 IOException
        )
        val transport = FakeTransport()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = FakeAudioIO(), otaApi = ota,
            activationPollIntervalMs = 1,
            challengeRefreshIntervalMs = 10_000,
        )

        val ok = session.start(identity, { })
        assertTrue(ok, "网络抖动应被吞掉并重试，不能崩溃")
        assertEquals(3, ota.activateCalls.size, "两次异常 + 一次成功")
        assertEquals("bound-token", transport.connectedToken)
        session.stop()
    }

    @Test
    fun `activate 被服务端拒绝时立即报错不重试`() = runBlocking {
        val pending = OtaConfig.minimal(
            activationCode = "555555",
            activationChallenge = "challenge-n",
        )
        val ota = FakeOtaApi(mutableListOf(pending), mutableListOf(ActivateResult.Failed(403, "forbidden")))
        val transport = FakeTransport()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = FakeAudioIO(), otaApi = ota,
            activationPollIntervalMs = 1,
            challengeRefreshIntervalMs = 10_000,
        )

        assertFalse(session.start(identity, { }))
        val err = assertIs<XiaozhiSession.Phase.Error>(session.phase.value)
        assertTrue(err.message.contains("403"), "实际: ${err.message}")
        assertEquals(1, ota.activateCalls.size, "非网络错误不应重试")
        session.stop()
    }

    @Test
    fun `轮询期间刷新 challenge 并在检测到已绑定后直接进会话`() = runBlocking {
        val pending1 = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
            activationCode = "111111",
            activationChallenge = "challenge-1",
        )
        val pending2 = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
            activationCode = "111111",
            activationChallenge = "challenge-2",
        )
        val bound = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = "bound-token")
        val ota = FakeOtaApi(mutableListOf(pending1, pending2, bound), mutableListOf(ActivateResult.Waiting))
        val transport = FakeTransport()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = FakeAudioIO(), otaApi = ota,
            activationPollIntervalMs = 1,
            challengeRefreshIntervalMs = 0, // 每轮都刷新，确定性触发
        )

        val ok = session.start(identity, { })
        assertTrue(ok, "刷新到已绑定配置后应直接进入会话")
        assertEquals(3, ota.checkVersionCalls.size)
        assertTrue(
            ota.activateCalls.any { it.second == "challenge-2" },
            "应使用刷新后的 challenge 轮询，实际: ${ota.activateCalls.map { it.second }}",
        )
        assertEquals("bound-token", transport.connectedToken)
        session.stop()
    }

    @Test
    fun `刷新后激活码变化时更新 UI`() = runBlocking {
        val pending1 = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
            activationCode = "111111",
            activationChallenge = "challenge-1",
        )
        val pending2 = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
            activationCode = "222222",
            activationChallenge = "challenge-2",
        )
        val bound = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = "bound-token")
        val ota = FakeOtaApi(mutableListOf(pending1, pending2, bound), mutableListOf(ActivateResult.Success))
        val transport = FakeTransport()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = FakeAudioIO(), otaApi = ota,
            activationPollIntervalMs = 1,
            challengeRefreshIntervalMs = 0,
        )

        val codes = mutableListOf<String>()
        val ok = session.start(identity, { codes.add(it) })
        assertTrue(ok)
        assertEquals(listOf("111111", "222222"), codes, "激活码变化时应再次回调 UI")
        assertEquals("bound-token", transport.connectedToken)
        session.stop()
    }

    @Test
    fun `激活后服务端延迟下发凭据时重试拉取`() = runBlocking {
        val pending = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
            activationCode = "777777",
            activationChallenge = "challenge-z",
        )
        val stale1 = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = OtaConfig.TEST_TOKEN)
        val stale2 = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = OtaConfig.TEST_TOKEN)
        val bound = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = "bound-token")
        val ota = FakeOtaApi(
            mutableListOf(pending, stale1, stale2, bound),
            mutableListOf(ActivateResult.Success),
        )
        val transport = FakeTransport()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = FakeAudioIO(), otaApi = ota,
            challengeRefreshIntervalMs = 10_000,
            postActivateOtaRetries = 3,
            postActivateRetryDelayMs = 1,
        )

        val ok = session.start(identity, { })
        assertTrue(ok, "重试后拿到真实凭据应成功")
        assertEquals(4, ota.checkVersionCalls.size) // OTA + 3 次激活后重拉
        assertEquals("bound-token", transport.connectedToken)
        session.stop()
    }

    @Test
    fun `激活后始终拿不到真实凭据时显式报错`() = runBlocking {
        val pending = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
            activationCode = "888888",
            activationChallenge = "challenge-w",
        )
        val stale = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = OtaConfig.TEST_TOKEN)
        val ota = FakeOtaApi(
            mutableListOf(pending, stale, stale, stale),
            mutableListOf(ActivateResult.Success),
        )
        val transport = FakeTransport()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = FakeAudioIO(), otaApi = ota,
            challengeRefreshIntervalMs = 10_000,
            postActivateOtaRetries = 3,
            postActivateRetryDelayMs = 1,
        )

        assertFalse(session.start(identity, { }))
        val err = assertIs<XiaozhiSession.Phase.Error>(session.phase.value)
        assertTrue(err.message.contains("尚未下发真实凭据"), "实际: ${err.message}")
        assertTrue(transport.connectedUrl == null, "拿不到真实凭据时不应带测试组凭据去连")
        session.stop()
    }

    // ---------------- 传输层健壮性 ----------------

    @Test
    fun `connect 抛异常时报错而不是让协程崩掉`() = runBlocking {
        // OkHttp 的 newWebSocket 对非法 URL 是【同步抛异常】，不回调 onFailure。
        // 异常若穿出去会打死 ViewModel 的 launch 协程（App 崩溃）。
        val config = OtaConfig.minimal(websocketUrl = "not a url", websocketToken = "t")
        val transport = FakeTransport().apply {
            connectThrows = IllegalArgumentException("expected URL scheme http or https")
        }
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = FakeAudioIO(),
            otaApi = FakeOtaApi(mutableListOf(config), mutableListOf()),
        )

        assertFalse(session.start(identity, { }), "connect 异常应转成失败而不是抛出")
        val err = assertIs<XiaozhiSession.Phase.Error>(session.phase.value)
        assertTrue(err.message.contains("建立连接失败"), "实际: ${err.message}")
        session.stop()
    }

    @Test
    fun `连接中被服务端关闭时显式报错而不是永远卡在连接中`() = runBlocking {
        // 实测：拿未绑定凭据去连，nginx 给 101 后应用层直接 close，收不到 hello。
        // 不处理的话 phase 会永远停在 Connecting，用户只看到"连接中..."。
        val config = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = "t")
        val transport = FakeTransport()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = FakeAudioIO(),
            otaApi = FakeOtaApi(mutableListOf(config), mutableListOf()),
        )

        assertTrue(session.start(identity, { }))
        assertEquals(XiaozhiSession.Phase.Connecting, session.phase.value)

        transport.close("server rejected")
        val err = awaitPhase(session) { it is XiaozhiSession.Phase.Error }
        assertTrue(
            (err as XiaozhiSession.Phase.Error).message.contains("未收到 hello"),
            "实际: ${err.message}",
        )
        session.stop()
    }

    @Test
    fun `畸形配置空串字段时报错不连接`() = runBlocking {
        // org.json 的 optString 在字段缺失时返回空串（不是 null），
        // 空串会骗过上层的 ?: 判空，导致拿空 URL 去建立 WebSocket。
        val blank = OtaConfig.minimal(websocketUrl = "", websocketToken = "")
        val transport = FakeTransport()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = FakeAudioIO(),
            otaApi = FakeOtaApi(mutableListOf(blank), mutableListOf()),
        )

        assertFalse(session.start(identity, { }))
        val err = assertIs<XiaozhiSession.Phase.Error>(session.phase.value)
        assertTrue(err.message.contains("WebSocket 地址"), "实际: ${err.message}")
        assertTrue(transport.connectedUrl == null, "不能拿空 URL 去连")
        session.stop()
    }

    @Test
    fun `主动 stop 不会被误判成被服务端关闭`() = runBlocking {
        // 回归保护：stop() 也会触发 Closed，必须区分主动断开与异常关闭
        val config = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = "t")
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = FakeTransport(), audio = FakeAudioIO(),
            otaApi = FakeOtaApi(mutableListOf(config), mutableListOf()),
        )
        session.start(identity, { })
        session.stop()
        assertEquals(XiaozhiSession.Phase.Idle, session.phase.value)
        delay(50) // 给 collector 时间，确认不会翻成 Error
        assertEquals(XiaozhiSession.Phase.Idle, session.phase.value)
    }

    // ---------------- 会话内交互 ----------------

    @Test
    fun `startListening 与 stopListening 发送 listen 消息`() = runBlocking {
        val config = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = "t")
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = FakeTransport(), audio = FakeAudioIO(),
            otaApi = FakeOtaApi(mutableListOf(config), mutableListOf()),
        )
        val transport = session.transportFieldForTest()
        val audio = session.audioFieldForTest()

        session.start(identity, { })
        transport.open()
        awaitPhase(session) { it is XiaozhiSession.Phase.Ready }

        session.startListening()
        assertEquals(XiaozhiSession.Phase.Listening, session.phase.value)
        assertTrue(audio.captureStarted)
        val startMsg = transport.sentMessages.filterIsInstance<XiaozhiMessage.Listen>().last()
        assertEquals(ListenState.START, startMsg.state)

        session.stopListening()
        assertEquals(XiaozhiSession.Phase.Ready::class, session.phase.value::class)
        assertFalse(audio.captureStarted)
        val stopMsg = transport.sentMessages.filterIsInstance<XiaozhiMessage.Listen>().last()
        assertEquals(ListenState.STOP, stopMsg.state)
        session.stop()
    }

    @Test
    fun `TTS 消息驱动 Speaking 与字幕`() = runBlocking {
        val config = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = "t")
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = FakeTransport(), audio = FakeAudioIO(),
            otaApi = FakeOtaApi(mutableListOf(config), mutableListOf()),
        )
        val transport = session.transportFieldForTest()

        session.start(identity, { })
        transport.open()
        awaitPhase(session) { it is XiaozhiSession.Phase.Ready }

        transport.emitMessage(XiaozhiMessage.Stt("你好小智", "sess-1"))
        transport.emitMessage(XiaozhiMessage.Tts(TtsState.START, "我是小智", "sess-1"))
        awaitPhase(session) { it is XiaozhiSession.Phase.Speaking }
        assertEquals("你好小智", session.subtitle.value)

        transport.emitMessage(XiaozhiMessage.Tts(TtsState.STOP, null, "sess-1"))
        awaitPhase(session) { it is XiaozhiSession.Phase.Ready }
        session.stop()
    }

    @Test
    fun `打断后立即回到 Ready 可以接着开始下一轮对话`() = runBlocking {
        val config = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = "t")
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = FakeTransport(), audio = FakeAudioIO(),
            otaApi = FakeOtaApi(mutableListOf(config), mutableListOf()),
        )
        val transport = session.transportFieldForTest()
        session.start(identity, { })
        transport.open()
        awaitPhase(session) { it is XiaozhiSession.Phase.Ready }

        transport.emitMessage(XiaozhiMessage.Tts(TtsState.START, "我是小智", "sess-1"))
        awaitPhase(session) { it is XiaozhiSession.Phase.Speaking }

        // 不等服务端补发 TTS stop，用户打断后应能立刻再说一句
        session.abort()
        assertEquals(XiaozhiSession.Phase.Ready::class, session.phase.value::class)
        session.startListening()
        assertEquals(XiaozhiSession.Phase.Listening, session.phase.value)
        session.stop()
    }

    @Test
    fun `stop 释放资源并回到 Idle`() = runBlocking {
        val config = OtaConfig.minimal(websocketUrl = "wss://fake/v1/", websocketToken = "t")
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = FakeTransport(), audio = FakeAudioIO(),
            otaApi = FakeOtaApi(mutableListOf(config), mutableListOf()),
        )
        val transport = session.transportFieldForTest()
        val audio = session.audioFieldForTest()
        session.start(identity, { })
        session.stop()
        assertEquals(XiaozhiSession.Phase.Idle, session.phase.value)
        assertTrue(transport.closed)
        assertTrue(audio.released)
    }
}

// ---------------------------------------------------------------- 测试取值辅助
// 会话的 transport / audio 是 private 字段，这里通过类型化桥接读取，
// 避免为测试开放公共 API。

private fun XiaozhiSession.transportFieldForTest(): FakeTransport {
    val f = XiaozhiSession::class.java.getDeclaredField("transport")
    f.isAccessible = true
    return f.get(this) as FakeTransport
}

private fun XiaozhiSession.audioFieldForTest(): FakeAudioIO {
    val f = XiaozhiSession::class.java.getDeclaredField("audio")
    f.isAccessible = true
    return f.get(this) as FakeAudioIO
}
