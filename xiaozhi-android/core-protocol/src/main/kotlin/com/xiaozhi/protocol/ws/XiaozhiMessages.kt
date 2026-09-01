package com.xiaozhi.protocol.ws

import org.json.JSONObject

/**
 * 小智 WebSocket 消息模型
 * 协议来源：78/xiaozhi-esp32  docs/websocket.md
 */
sealed class XiaozhiMessage {

    /** 设备 -> 服务端：握手宣告音频参数与能力 */
    data class Hello(
        val version: Int = 1,
        val features: Map<String, Boolean> = mapOf("mcp" to true),
        val audioParams: AudioParams = AudioParams.uplink(),
    ) : XiaozhiMessage() {
        fun toJson(sessionId: String?): JSONObject = JSONObject().apply {
            put("type", "hello")
            put("version", version)
            put("features", JSONObject(features.filterValues { it }))
            put("transport", "websocket")
            sessionId?.let { put("session_id", it) }
            put("audio_params", audioParams.toJson())
        }
    }

    /** 设备 -> 服务端：开始 / 停止录音，或上报唤醒词命中 */
    data class Listen(
        val state: ListenState,
        val mode: ListenMode = ListenMode.MANUAL,
        val text: String? = null,
    ) : XiaozhiMessage() {
        fun toJson(sessionId: String?): JSONObject = JSONObject().apply {
            put("type", "listen")
            sessionId?.let { put("session_id", it) }
            put("state", state.wire)
            put("mode", mode.wire)
            text?.let { put("text", it) }
        }
    }

    /** 设备 -> 服务端：打断当前 TTS 播放 */
    data class Abort(val reason: String = "wake_word_detected") : XiaozhiMessage() {
        fun toJson(sessionId: String?): JSONObject = JSONObject().apply {
            put("type", "abort")
            sessionId?.let { put("session_id", it) }
            put("reason", reason)
        }
    }

    // ------------------------------------------------- 服务端 -> 设备

    /** 语音识别结果 */
    data class Stt(val text: String, val sessionId: String?) : XiaozhiMessage()

    /** 大模型状态 / 表情 */
    data class Llm(val emotion: String, val text: String, val sessionId: String?) : XiaozhiMessage()

    /** TTS 流控制 */
    data class Tts(
        val state: TtsState,
        val text: String?,
        val sessionId: String?,
    ) : XiaozhiMessage()

    /** MCP 工具调用（IoT 控制统一走这里，旧 type:"iot" 已废弃） */
    data class Mcp(val payload: JSONObject, val sessionId: String?) : XiaozhiMessage()

    /** 系统级指令，如 reboot */
    data class System(val command: String, val sessionId: String?) : XiaozhiMessage()

    /** 告警提示 */
    data class Alert(val status: String, val message: String, val emotion: String, val sessionId: String?) : XiaozhiMessage()

    /** 服务端 hello 确认，含协商后的音频参数 */
    data class HelloAck(
        val sessionId: String?,
        val audioParams: AudioParams?,
    ) : XiaozhiMessage()

    /** 未识别的消息类型，保留原始 JSON 便于调试 */
    data class Unknown(val type: String, val raw: JSONObject) : XiaozhiMessage()
}

enum class ListenState(val wire: String) { START("start"), STOP("stop"), DETECT("detect") }
enum class ListenMode(val wire: String) { AUTO("auto"), MANUAL("manual"), REALTIME("realtime") }
enum class TtsState(val wire: String) { START("start"), STOP("stop"), SENTENCE_START("sentence_start") }

/**
 * 音频参数。上行固定 16k / mono / 60ms；服务端下行可能用 24k 以提升音质，
 * 解码后需重采样到输出设备采样率。
 */
data class AudioParams(
    val format: String = "opus",
    val sampleRate: Int = 16_000,
    val channels: Int = 1,
    val frameDurationMs: Int = 60,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", format)
        put("sample_rate", sampleRate)
        put("channels", channels)
        put("frame_duration", frameDurationMs)
    }

    companion object {
        fun uplink() = AudioParams(sampleRate = 16_000)
        fun downlink(sampleRate: Int) = AudioParams(sampleRate = sampleRate)
    }
}

/** 服务端 JSON -> 消息对象 */
object XiaozhiMessageParser {
    fun parse(text: String): XiaozhiMessage {
        val obj = JSONObject(text)
        val sid = obj.optString("session_id").takeIf { it.isNotEmpty() }
        return when (obj.optString("type")) {
            "hello" -> XiaozhiMessage.HelloAck(
                sessionId = sid,
                audioParams = obj.optJSONObject("audio_params")?.let {
                    AudioParams(
                        format = it.optString("format", "opus"),
                        sampleRate = it.optInt("sample_rate", 24_000),
                        channels = it.optInt("channels", 1),
                        frameDurationMs = it.optInt("frame_duration", 60),
                    )
                },
            )
            "stt" -> XiaozhiMessage.Stt(obj.optString("text"), sid)
            "llm" -> XiaozhiMessage.Llm(
                obj.optString("emotion", "neutral"),
                obj.optString("text"), sid,
            )
            "tts" -> XiaozhiMessage.Tts(
                TtsState.entries.firstOrNull { it.wire == obj.optString("state") } ?: TtsState.STOP,
                obj.optString("text").takeIf { obj.has("text") },
                sid,
            )
            "mcp" -> XiaozhiMessage.Mcp(obj.optJSONObject("payload") ?: JSONObject(), sid)
            "system" -> XiaozhiMessage.System(obj.optString("command"), sid)
            "alert" -> XiaozhiMessage.Alert(
                obj.optString("status"), obj.optString("message"),
                obj.optString("emotion", "neutral"), sid,
            )
            else -> XiaozhiMessage.Unknown(obj.optString("type"), obj)
        }
    }
}
