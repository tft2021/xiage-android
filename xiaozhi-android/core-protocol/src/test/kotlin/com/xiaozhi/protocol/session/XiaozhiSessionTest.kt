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

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state

    private val _messages = MutableSharedFlow<XiaozhiMessage>(replay = 32)
    override val messages: SharedFlow<XiaozhiMessage> = _messages

    private val _opusFrames = MutableSharedFlow<ByteArray>(replay = 32)
    override val opusFrames: SharedFlow<ByteArray> = _opusFrames

    override fun connect(url: String, token: String, deviceId: String, clientId: String) {
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
) : OtaApi {
    val checkVersionCalls = mutableListOf<DeviceIdentity>()
    val activateCalls = mutableListOf<Pair<DeviceIdentity, String>>()

    override suspend fun checkVersion(identity: DeviceIdentity, localIp: String): OtaConfig {
        checkVersionCalls.add(identity)
        return configs.removeFirstOrNull() ?: error("FakeOtaApi 没有更多预置配置")
    }

    override suspend fun activate(identity: DeviceIdentity, challenge: String): ActivateResult {
        activateCalls.add(identity to challenge)
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
    fun `激活超时返回 false 且停在 NeedActivation`() = runBlocking {
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
        )

        val ok = session.start(identity, { })
        assertFalse(ok, "激活超时应返回 false")
        assertIs<XiaozhiSession.Phase.NeedActivation>(session.phase.value)
        assertTrue(transport.connectedUrl == null, "不应发起连接")
        session.stop()
    }

    // ---------------- 测试组凭据诊断 ----------------

    @Test
    fun `服务端仍下发测试组凭据时报错不连接`() = runBlocking {
        // 重置身份重试后仍是测试组 -> 报错（两份配置都是测试组且无激活码）
        val bad = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
        )
        val ota = FakeOtaApi(mutableListOf(bad, bad), mutableListOf())
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
        assertTrue(err.message.contains("绑定未生效"))
        assertTrue(transport.connectedUrl == null)
        // 重试确实发生过：两次 OTA、一次身份重置
        assertEquals(2, ota.checkVersionCalls.size)
        assertEquals(1, resets.size)
        session.stop()
    }

    @Test
    fun `身份异常时自动重置身份重试后正常连接`() = runBlocking {
        // 第一次：测试组凭据且无激活码（服务端不下发 activation 的异常身份）
        val broken = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = OtaConfig.TEST_TOKEN,
        )
        // 重置身份后：真实凭据
        val healthy = OtaConfig.minimal(
            websocketUrl = "wss://fake/v1/",
            websocketToken = "real-token",
        )
        val ota = FakeOtaApi(mutableListOf(broken, healthy), mutableListOf())
        val transport = FakeTransport()
        val audio = FakeAudioIO()
        val session = XiaozhiSession(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            transport = transport, audio = audio, otaApi = ota,
        )

        val resets = mutableListOf<DeviceIdentity>()
        val ok = session.start(identity, { }, onIdentityReset = { resets.add(it) })
        assertTrue(ok, "重置身份后应成功建立会话")

        // 第二次 OTA 用的是全新身份，且新身份被回调通知持久化
        assertEquals(2, ota.checkVersionCalls.size)
        val secondIdentity = ota.checkVersionCalls[1]
        assertFalse(
            secondIdentity.deviceId == identity.deviceId &&
                secondIdentity.clientId == identity.clientId,
            "重试必须使用全新身份",
        )
        assertEquals(listOf(secondIdentity), resets)

        assertEquals("real-token", transport.connectedToken)
        transport.open()
        awaitPhase(session) { it is XiaozhiSession.Phase.Ready }
        session.stop()
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
