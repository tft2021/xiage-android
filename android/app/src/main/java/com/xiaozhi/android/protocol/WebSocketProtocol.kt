package com.xiaozhi.android.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * 小智 WebSocket 传输层。
 *
 * 关键判据：HTTP 层的 101 由 nginx 返回，**不代表应用层接受了会话**。
 * 未绑定账号的设备会在发送 hello 后被立即 Close。
 * 连接成功的唯一标志是收到服务端的 hello JSON。
 */
class WebSocketProtocol(
    private val url: String,
    private val token: String,
    private val deviceId: String,
    private val clientId: String,
    private val listener: Listener,
) {
    interface Listener {
        /** 收到服务端 hello，会话真正建立 */
        fun onConnected(sessionId: String, audioParams: JSONObject?)

        fun onJson(json: JSONObject)

        /** 服务端下发的 Opus 音频帧 */
        fun onAudio(data: ByteArray)

        fun onDisconnected(reason: String)

        fun onError(message: String)
    }

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接不设读超时
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var sessionId: String = ""

    @Volatile
    private var connected = false

    private var helloSignal: CompletableDeferred<JSONObject>? = null

    val isConnected: Boolean get() = connected
    val currentSessionId: String get() = sessionId

    /** 建立连接并等待服务端 hello（10 秒超时） */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Protocol-Version", "1")
            .addHeader("Device-Id", deviceId)
            .addHeader("Client-Id", clientId)
            .build()

        val signal = CompletableDeferred<JSONObject>()
        helloSignal = signal

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 101 已返回，但要等 hello 才算真正连上
                webSocket.send(Messages.hello())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (json.optString("type") == ServerMessageType.HELLO) {
                    signal.complete(json)
                    return
                }
                listener.onJson(json)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onAudio(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                listener.onDisconnected("code=$code reason=$reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                signal.completeExceptionally(t)
                listener.onError(t.message ?: "WebSocket 失败")
            }
        })

        return@withContext try {
            val hello = withTimeout(10_000) { signal.await() }
            val transport = hello.optString("transport")
            if (transport != "websocket") {
                listener.onError("不支持的传输方式: $transport")
                close()
                return@withContext false
            }
            sessionId = hello.optString("session_id", "")
            connected = true
            listener.onConnected(sessionId, hello.optJSONObject("audio_params"))
            true
        } catch (e: TimeoutCancellationException) {
            listener.onError("等待服务端 hello 超时（10 秒）")
            close()
            false
        } catch (e: Exception) {
            listener.onError("连接失败: ${e.message}")
            close()
            false
        }
    }

    fun sendText(message: String): Boolean =
        webSocket?.takeIf { connected }?.send(message) ?: false

    fun sendAudio(opus: ByteArray): Boolean =
        webSocket?.takeIf { connected }?.send(opus.toByteString()) ?: false

    fun close() {
        connected = false
        webSocket?.close(1000, "client close")
        webSocket = null
    }
}
