package com.xiaozhi.android.protocol

import org.json.JSONObject

/** 服务端下发消息的类型 */
object ServerMessageType {
    const val HELLO = "hello"
    const val STT = "stt"
    const val LLM = "llm"
    const val TTS = "tts"
    const val MCP = "mcp"
    const val SYSTEM = "system"
    const val ALERT = "alert"
    const val CUSTOM = "custom"
}

/** tts 消息的状态 */
object TtsState {
    const val START = "start"
    const val STOP = "stop"
    const val SENTENCE_START = "sentence_start"
}

/**
 * 客户端 → 服务端的消息构造。
 *
 * 除首条 hello 外，所有消息都要带 session_id。
 */
object Messages {
    fun hello(
        version: Int = 1,
        supportsMcp: Boolean = true,
        sampleRate: Int = AudioConfig.INPUT_SAMPLE_RATE,
        channels: Int = AudioConfig.CHANNELS,
        frameDuration: Int = AudioConfig.frameDurationMs,
    ): String = JSONObject().apply {
        put("type", "hello")
        put("version", version)
        put("features", JSONObject().apply { put("mcp", supportsMcp) })
        put("transport", "websocket")
        put("audio_params", JSONObject().apply {
            put("format", "opus")
            put("sample_rate", sampleRate)
            put("channels", channels)
            put("frame_duration", frameDuration)
        })
    }.toString()

    fun listenStart(sessionId: String, mode: ListeningMode): String = JSONObject().apply {
        put("session_id", sessionId)
        put("type", "listen")
        put("state", "start")
        put("mode", mode.wireValue)
    }.toString()

    fun listenStop(sessionId: String): String = JSONObject().apply {
        put("session_id", sessionId)
        put("type", "listen")
        put("state", "stop")
    }.toString()

    /** 上报唤醒词 */
    fun listenDetect(sessionId: String, wakeWord: String): String = JSONObject().apply {
        put("session_id", sessionId)
        put("type", "listen")
        put("state", "detect")
        put("text", wakeWord)
    }.toString()

    /**
     * 中止当前播放或采集。
     * 只有唤醒词中止才带 reason，用户打断不带。
     */
    fun abort(sessionId: String, reason: AbortReason): String = JSONObject().apply {
        put("session_id", sessionId)
        put("type", "abort")
        if (reason == AbortReason.WAKE_WORD_DETECTED) {
            put("reason", "wake_word_detected")
        }
    }.toString()

    /** MCP 消息，payload 为 JSON-RPC 2.0 对象 */
    fun mcp(sessionId: String, payload: JSONObject): String = JSONObject().apply {
        put("session_id", sessionId)
        put("type", "mcp")
        put("payload", payload)
    }.toString()

    /**
     * MCP 工具调用的回执。
     * [id] 必须与服务端下发请求的 id 一致，否则服务端对不上账。
     */
    fun mcpResult(sessionId: String, id: Any?, text: String, isError: Boolean = false): String =
        mcp(
            sessionId,
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "result",
                    JSONObject().apply {
                        put(
                            "content",
                            org.json.JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", text)
                                })
                            }
                        )
                        put("isError", isError)
                    }
                )
            }
        )
}
