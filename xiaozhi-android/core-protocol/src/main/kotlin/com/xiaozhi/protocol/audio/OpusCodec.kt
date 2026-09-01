package com.xiaozhi.protocol.audio

/**
 * Opus 编解码抽象
 *
 * 参数约束（协议固定值）：
 *  - 上行：16000 Hz / 单声道 / 60ms 每帧，即每帧 960 个 PCM 采样（short）
 *  - 下行：服务端可能用 24000 Hz，AudioTrack 直接按该采样率打开，
 *    AudioFlinger 内部会重采样到设备输出，无需手动转换
 *
 * 实现选型（三选一，见 README）：
 *  1. Concentus —— 纯 Java 移植，无 NDK 依赖，性能可接受，推荐起步用
 *  2. libopus + 自写 JNI —— 性能最佳，需要 NDK
 *  3. opus-android 预编译 so —— 省事但需要自己维护 so 分发
 *
 * 实现就绪后，包装成 [AudioCodecProvider] 注入 XiaozhiSession；
 * 接入前用 NoOpCodecProvider 优雅降级（协议与 UI 正常，只是没有声音）。
 */
interface OpusEncoder {
    /**
     * @param pcm 16-bit PCM，长度必须等于 frameSize * channels
     * @return 编码后的 Opus 帧
     */
    fun encode(pcm: ShortArray): ByteArray

    /** 释放 native / 内部资源 */
    fun release()
}

interface OpusDecoder {
    /**
     * @param opus 服务端下发的 Opus 帧
     * @return 16-bit PCM
     */
    fun decode(opus: ByteArray): ShortArray

    fun release()
}

/**
 * 简单线性插值重采样器。
 *
 * 当前链路中 AudioTrack 按服务端采样率打开，无需手动重采样；
 * 保留此工具用于：固定采样率的播放设备、离线音频处理、单元测试。
 * 生产若需更高质量可替换为 SoundTouch / soxr。
 */
object Resampler {
    fun resample(
        input: ShortArray,
        fromRate: Int,
        toRate: Int,
        channels: Int = 1,
    ): ShortArray {
        require(channels >= 1) { "channels 必须 >= 1" }
        if (fromRate == toRate) return input.copyOf()
        require(input.size % channels == 0) { "input 长度必须是 channels 的整数倍" }
        val inFrames = input.size / channels
        val outFrames = (inFrames.toLong() * toRate / fromRate).toInt()
        if (outFrames <= 0) return ShortArray(0)
        val out = ShortArray(outFrames * channels)
        val ratio = inFrames.toDouble() / outFrames
        for (i in 0 until outFrames) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt().coerceIn(0, inFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(inFrames - 1)
            val frac = srcPos - i0
            for (c in 0 until channels) {
                val a = input[i0 * channels + c].toDouble()
                val b = input[i1 * channels + c].toDouble()
                out[i * channels + c] = (a + (b - a) * frac).toInt().toShort()
            }
        }
        return out
    }
}
