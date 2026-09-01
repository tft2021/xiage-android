package com.xiaozhi.protocol.ws

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 小智 WebSocket 会话客户端（XiaozhiTransport 的生产实现）
 *
 * 握手要点（实测验证）：
 *  - 必须带四个头：Authorization / Protocol-Version / Device-Id / Client-Id
 *  - 连接后先发 hello，10 秒内收不到服务端 hello 视为失败
 *  - nginx 返回 101 不代表应用层接受，以是否收到服务端 hello 为准
 */
class XiaozhiWsClient(
    private val scope: CoroutineScope,
    /** 二进制封装版本，默认 v1 裸 Opus */
    private val binaryProtocolVersion: Int = BinaryFrameCodec.V1,
) : XiaozhiTransport {

    override val state: MutableStateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.Idle)

    private val _messages = MutableSharedFlow<XiaozhiMessage>(extraBufferCapacity = 64)
    override val messages: SharedFlow<XiaozhiMessage> = _messages

    private val _opusFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val opusFrames: SharedFlow<ByteArray> = _opusFrames

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var session: String? = null
    private var helloTimeoutJob: Job? = null

    override fun connect(url: String, token: String, deviceId: String, clientId: String) {
        state.value = ConnectionState.Connecting
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Protocol-Version", "1")
            .header("Device-Id", deviceId)
            .header("Client-Id", clientId)
            .build()

        webSocket = client.newWebSocket(request, Listener())
    }

    override fun send(message: XiaozhiMessage) {
        val json: JSONObject = when (message) {
            is XiaozhiMessage.Hello -> message.toJson(null)
            is XiaozhiMessage.Listen -> message.toJson(session)
            is XiaozhiMessage.Abort -> message.toJson(session)
            else -> return
        }
        webSocket?.send(json.toString())
    }

    /** 发送 MCP 结果（JSON-RPC 2.0 响应） */
    override fun sendMcpResult(payload: JSONObject) {
        val obj = JSONObject().apply {
            put("type", "mcp")
            session?.let { put("session_id", it) }
            put("payload", payload)
        }
        webSocket?.send(obj.toString())
    }

    /** 上行 Opus 音频帧 */
    override fun sendOpus(frame: ByteArray) {
        val wrapped = BinaryFrameCodec.encode(binaryProtocolVersion, BinaryFrameCodec.TYPE_OPUS, frame)
        webSocket?.send(wrapped.toByteString())
    }

    override fun close(reason: String) {
        helloTimeoutJob?.cancel()
        webSocket?.close(NORMAL_CLOSE, reason)
        webSocket = null
        state.value = ConnectionState.Closed
    }

    // ------------------------------------------------------------------ internal

    private inner class Listener : WebSocketListener() {
        override fun onOpen(ws: WebSocket, response: Response) {
            send(XiaozhiMessage.Hello())
            // 10 秒收不到服务端 hello 判失败，与固件行为一致
            helloTimeoutJob = scope.launch {
                delay(HELLO_TIMEOUT_MS)
                if (state.value !is ConnectionState.Open) {
                    close("hello timeout: ${HELLO_TIMEOUT_MS / 1000}s 内未收到服务端 hello")
                }
            }
        }

        override fun onMessage(ws: WebSocket, text: String) = handleText(text)

        override fun onMessage(ws: WebSocket, bytes: ByteString) {
            // 二进制帧按配置版本解析，仅接受 OPUS 类型
            val (type, payload) = BinaryFrameCodec.decode(binaryProtocolVersion, bytes.toByteArray())
                ?: return
            if (type == BinaryFrameCodec.TYPE_OPUS) _opusFrames.tryEmit(payload)
        }

        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
            helloTimeoutJob?.cancel()
            state.value = ConnectionState.Closed
        }

        override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
            helloTimeoutJob?.cancel()
            state.value = ConnectionState.Failed(t.message ?: "unknown")
        }
    }

    private fun handleText(text: String) {
        when (val msg = XiaozhiMessageParser.parse(text)) {
            is XiaozhiMessage.HelloAck -> {
                helloTimeoutJob?.cancel()
                session = msg.sessionId
                state.value = ConnectionState.Open(msg.sessionId, msg.audioParams)
            }
            else -> _messages.tryEmit(msg)
        }
    }

    companion object {
        private const val HELLO_TIMEOUT_MS = 10_000L
        private const val NORMAL_CLOSE = 1000
    }
}
