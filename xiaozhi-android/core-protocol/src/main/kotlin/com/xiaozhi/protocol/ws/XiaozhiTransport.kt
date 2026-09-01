package com.xiaozhi.protocol.ws

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** 连接状态，传输层与会话层共用 */
sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Connecting : ConnectionState()

    /**
     * 会话已建立（收到服务端 hello）。
     * audioParams 为服务端协商的下行音频参数，采样率可能与上行不同。
     */
    data class Open(
        val sessionId: String?,
        val audioParams: com.xiaozhi.protocol.ws.AudioParams? = null,
    ) : ConnectionState()

    data class Failed(val reason: String) : ConnectionState()
    data object Closed : ConnectionState()
}

/**
 * 小智传输层抽象。
 *
 * 抽出接口的目的：会话状态机（XiaozhiSession）可以注入 FakeTransport
 * 在 JVM 上做单元测试，不必依赖真实网络。
 *
 * 生产实现：[XiaozhiWsClient]（WebSocket）
 */
interface XiaozhiTransport {
    val state: StateFlow<ConnectionState>

    /** 服务端 JSON 消息（hello 之外的） */
    val messages: SharedFlow<XiaozhiMessage>

    /** 服务端下发的 Opus 帧（已剥掉二进制协议头） */
    val opusFrames: SharedFlow<ByteArray>

    fun connect(url: String, token: String, deviceId: String, clientId: String)
    fun send(message: XiaozhiMessage)
    fun sendMcpResult(payload: JSONObject)
    fun sendOpus(frame: ByteArray)
    fun close(reason: String = "client closed")
}
