@file:Suppress("unused")

import com.xiaozhi.protocol.audio.AudioCodecProvider
import com.xiaozhi.protocol.audio.AudioIO
import com.xiaozhi.protocol.audio.OpusDecoder
import com.xiaozhi.protocol.audio.OpusEncoder
import com.xiaozhi.protocol.debug.DebugLog
import com.xiaozhi.protocol.ota.DeviceIdentity
import com.xiaozhi.protocol.ota.OtaClient
import com.xiaozhi.protocol.session.XiaozhiSession
import com.xiaozhi.protocol.ws.XiaozhiWsClient
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/**
 * 真实客户端代码 x 模拟服务端 集成运行器（不进 CI，手动跑）。
 *
 * 与 App 生产装配的唯一差异：AudioIO 用哑实现（JVM 无麦克风）、
 * 激活等待里的「用户去 xiaozhi.me 输码」替换为直接 POST 模拟服务端的 /bind。
 * OtaClient / XiaozhiWsClient / XiaozhiSession 状态机全部是生产原版。
 *
 * 用法（先启动 server/mock_server.py）：
 *   java -cp <classpath> ItSessionMockKt [http://127.0.0.1:8000]
 */

/** 哑编解码器：透传长度，用于验证上行 PCM->Opus->WS 与下行 Opus->PCM->播放 两条链路 */
class ItCodecProvider : AudioCodecProvider {
    var upFrames = 0
        private set
    var downFrames = 0
        private set

    override fun createEncoder(): OpusEncoder = object : OpusEncoder {
        override fun encode(pcm: ShortArray): ByteArray {
            upFrames++
            return ByteArray(pcm.size / 80 + 1)
        }
        override fun release() = Unit
    }

    override fun createDecoder(sampleRate: Int): OpusDecoder = object : OpusDecoder {
        override fun decode(opus: ByteArray): ShortArray {
            downFrames++
            return ShortArray(opus.size * 80)
        }
        override fun release() = Unit
    }
}

/** JVM 哑音频：startCapture 时模拟「按住说话」发 3 帧 PCM，播放侧只计数 */
class ItAudioIO : AudioIO {
    override var onPcmFrame: ((ShortArray) -> Unit)? = null
    var playbackRate: Int? = null
    var captureCount = 0
        private set
    var enqueueCount = 0
        private set

    override fun startCapture() {
        // 模拟用户说话 3 帧（每帧 960 samples = 60ms @16k）
        repeat(3) { onPcmFrame?.invoke(ShortArray(960)) }
        captureCount++
    }
    override fun stopCapture() = Unit
    override val isCapturing: Boolean get() = false
    override fun startPlayback(sampleRate: Int) { playbackRate = sampleRate }
    override fun enqueuePcm(pcm: ShortArray) { enqueueCount++ }
    override fun flushPlayback() = Unit
    override fun stopPlayback() = Unit
    override fun releaseAll() = Unit
}

/** 模拟「用户在网页输码」：POST /bind */
fun bindOnWeb(base: String, code: String): Int {
    val conn = URL("$base/bind").openConnection() as HttpURLConnection
    return try {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write("code=$code".toByteArray()) }
        conn.responseCode
    } finally {
        conn.disconnect()
    }
}

fun main(args: Array<String>): Unit = runBlocking {
    val base = args.getOrElse(0) { "http://127.0.0.1:8000" }
    val identity = DeviceIdentity.create(null)
    println("=== 真实客户端(OtaClient+WsClient+Session) 连接模拟服务端 $base ===")
    println("device=${identity.deviceId} serial=${identity.credentials.serialNumber}")

    val audio = ItAudioIO()
    val codec = ItCodecProvider()
    val session = XiaozhiSession(
        scope = this,
        transport = XiaozhiWsClient(this),
        audio = audio,
        otaApi = OtaClient(otaUrl = "$base/xiaozhi/ota/"),
        codecProvider = codec,
        activationPollIntervalMs = 2_000,
        activationTimeoutMs = 60_000,
        postActivateOtaRetries = 6,
        postActivateRetryDelayMs = 2_000,
    )

    val job = launch {
        val ok = session.start(
            identity,
            onNeedActivation = { code ->
                println("\n>>> [模拟用户] 拿到激活码 $code，去网页输码…")
                val rc = bindOnWeb(base, code)
                println(">>> [模拟用户] /bind HTTP $rc")
                session.nudgeActivation()
            },
        )
        println(">>> session.start 返回 ok=$ok")
    }

    val finalPhase = withTimeoutOrNull(90_000) {
        session.phase.first {
            it is XiaozhiSession.Phase.Ready || it is XiaozhiSession.Phase.Error
        }
    }

    var sawSpeaking = false
    if (finalPhase is XiaozhiSession.Phase.Ready) {
        println("\n>>> Ready！模拟「按住说话」（3 帧 PCM）…")
        // 先订阅相位流再触发对话：Speaking 是瞬态（tts start->stop 仅几毫秒），
        // 订阅晚了会被 StateFlow 合并跳过（第一次踩坑：日志明明有 Speaking 但收集不到）
        val conversation = async {
            withTimeoutOrNull(20_000) {
                session.phase.first { p ->
                    if (p is XiaozhiSession.Phase.Speaking) sawSpeaking = true
                    sawSpeaking && p is XiaozhiSession.Phase.Ready
                }
            }
        }
        session.startListening()
        // 模拟松开：停采集 + 发 listen stop（服务端据此回 stt/llm/tts）
        delay(500)
        session.stopListening()
        if (conversation.await() == null) println(">>> 等待对话完成超时")
        // 给 tts stop 后的收尾留时间
        Thread.sleep(500)
    }

    session.stop()
    job.cancel()

    println("\n=== 最终 phase: $finalPhase ===")
    println("下行采样率(服务端 hello 协商): ${audio.playbackRate}")
    println("上行编码帧数: ${codec.upFrames}  下行解码帧数(回声): ${codec.downFrames}")
    println("经历 Speaking 阶段: $sawSpeaking")

    println("\n--- 客户端视角事件日志 ---")
    print(DebugLog.dump())

    val ready = finalPhase is XiaozhiSession.Phase.Ready
    val success = ready && sawSpeaking && codec.downFrames > 0
    println(
        when {
            success -> "\n✅ 真实客户端在模拟服务端上全链路走通：激活→绑定→真实凭据→WS 会话→语音上下行"
            ready -> "\n⚠️ 达到 Ready 但对话链路未验证（Speaking=$sawSpeaking 下行=${codec.downFrames}）"
            else -> "\n❌ 未达到 Ready"
        }
    )
    kotlin.system.exitProcess(if (success) 0 else 1)
}
