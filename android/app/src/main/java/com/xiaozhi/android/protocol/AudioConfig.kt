package com.xiaozhi.android.protocol

/**
 * 协议层音频参数。
 *
 * 上行采样率与声道数是协议固定值，不可协商；
 * 下行采样率由服务端 hello 的 audio_params 决定，典型值 24000。
 */
object AudioConfig {
    /** 协议固定：上行 16 kHz */
    const val INPUT_SAMPLE_RATE = 16000

    /** 协议固定：单声道 */
    const val CHANNELS = 1

    /** 可选 20 / 40 / 60。60 与 ESP32 固件默认值一致，CPU 占用最低 */
    var frameDurationMs: Int = 60
        private set

    /** 服务端协商值，收到 hello 后更新 */
    var outputSampleRate: Int = 24000
        private set

    /** 每帧上行样本数：60 ms → 960 */
    val inputFrameSize: Int
        get() = INPUT_SAMPLE_RATE * frameDurationMs / 1000

    /** 每帧下行样本数：24 kHz / 60 ms → 1440 */
    val outputFrameSize: Int
        get() = outputSampleRate * frameDurationMs / 1000

    /**
     * 应用服务端 hello 中协商的参数。
     * 未知或缺失时保留当前值。
     */
    fun applyServerParams(sampleRate: Int?, frameDuration: Int?) {
        frameDuration?.let { if (it in intArrayOf(20, 40, 60)) frameDurationMs = it }
        sampleRate?.let { if (it > 0) outputSampleRate = it }
    }
}

/** 设备三态 */
enum class DeviceState { IDLE, LISTENING, SPEAKING }

/**
 * 监听模式。
 *
 * 注意枚举名与线上取值不一致，[wireValue] 才是实际发送的字符串。
 */
enum class ListeningMode(val wireValue: String) {
    REALTIME("realtime"),
    AUTO_STOP("auto"),
    MANUAL("manual"),
}

/** 中止原因。NONE 与 USER_INTERRUPTION 不发送 reason 字段 */
enum class AbortReason {
    NONE,
    WAKE_WORD_DETECTED,
    USER_INTERRUPTION,
}
