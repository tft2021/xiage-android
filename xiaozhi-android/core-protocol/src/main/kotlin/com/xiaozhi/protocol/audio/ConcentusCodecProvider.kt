package com.xiaozhi.protocol.audio

import org.concentus.OpusApplication
import org.concentus.OpusException

/**
 * 基于 Concentus（纯 Java Opus 移植，源码内嵌于 src/main/java/org/concentus/）
 * 的编解码提供者。
 *
 * 参数约定（与小智协议一致，见 [OpusEncoder] 抽象注释）：
 *  - 上行：16000 Hz / 单声道 / 60ms（960 采样/帧），VOIP 模式
 *  - 下行：按服务端 hello 协商采样率创建解码器（16k / 24k）
 *
 * Concentus 是逐文件移植的参考实现，正确性优先、性能次之；
 * 单帧 60ms 编解码在低端机上也远低于实时预算，可放心使用。
 */
class ConcentusCodecProvider : AudioCodecProvider {

    override fun createEncoder(): com.xiaozhi.protocol.audio.OpusEncoder? = try {
        val enc = org.concentus.OpusEncoder(
            UPLINK_SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP
        )
        enc.bitrate = UPLINK_BITRATE
        ConcentusEncoder(enc)
    } catch (_: OpusException) {
        null
    }

    override fun createDecoder(sampleRate: Int): com.xiaozhi.protocol.audio.OpusDecoder? = try {
        ConcentusDecoder(org.concentus.OpusDecoder(sampleRate, CHANNELS))
    } catch (_: OpusException) {
        null
    }

    // ------------------------------------------------------------------ 包装

    private class ConcentusEncoder(
        private val enc: org.concentus.OpusEncoder,
    ) : com.xiaozhi.protocol.audio.OpusEncoder {
        private val outBuf = ByteArray(MAX_PACKET_BYTES)

        override fun encode(pcm: ShortArray): ByteArray {
            val n = enc.encode(pcm, 0, pcm.size, outBuf, 0, outBuf.size)
            return outBuf.copyOf(n)
        }

        /** 纯 Java 实现无 native 资源，重置内部状态即可 */
        override fun release() = enc.resetState()
    }

    private class ConcentusDecoder(
        private val dec: org.concentus.OpusDecoder,
    ) : com.xiaozhi.protocol.audio.OpusDecoder {
        private val pcmBuf = ShortArray(MAX_FRAME_SAMPLES)

        override fun decode(opus: ByteArray): ShortArray {
            val samples = dec.decode(opus, 0, opus.size, pcmBuf, 0, MAX_FRAME_SAMPLES, false)
            return pcmBuf.copyOf(samples)
        }

        override fun release() = dec.resetState()
    }

    companion object {
        /** 上行采样率（协议固定值） */
        const val UPLINK_SAMPLE_RATE = 16_000

        /** 上行帧长 60ms @16k = 960 采样 */
        const val UPLINK_FRAME_SIZE = 960

        /** 上行比特率：24kbps 在 16k 单声道语音下质量与带宽均衡 */
        const val UPLINK_BITRATE = 24_000

        const val CHANNELS = 1

        /** Opus 单帧最大字节数（官方推荐 1275 上限，留余量） */
        const val MAX_PACKET_BYTES = 1500

        /** 解码缓冲上限：48k * 120ms（覆盖所有采样率与帧长组合） */
        const val MAX_FRAME_SAMPLES = 5760
    }
}
