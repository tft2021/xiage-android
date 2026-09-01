package com.xiaozhi.protocol.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Concentus 编解码往返测试（JVM，无需 Android）。
 *
 * 验证真实语音链路参数：
 *  - 上行编码：16k / 单声道 / 960 采样（60ms）
 *  - 下行解码：16k 与 24k 两种协商采样率
 */
class ConcentusCodecTest {

    /** 生成 440Hz 正弦波帧 */
    private fun sine16k(phaseOffset: Int = 0): ShortArray =
        ShortArray(960) { i ->
            (12000.0 * sin(2.0 * PI * 440.0 * (i + phaseOffset) / 16000.0)).toInt().toShort()
        }

    @Test
    fun `上行编码 16k 960采样输出合法 Opus 帧`() {
        val enc = ConcentusCodecProvider().createEncoder() ?: error("编码器创建失败")
        try {
            val frame = enc.encode(sine16k())
            assertTrue(frame.isNotEmpty(), "编码输出不能为空")
            assertTrue(frame.size <= ConcentusCodecProvider.MAX_PACKET_BYTES, "编码输出超长")
        } finally {
            enc.release()
        }
    }

    @Test
    fun `编码到解码往返 16k 下行`() {
        val provider = ConcentusCodecProvider()
        val enc = provider.createEncoder() ?: error("编码器创建失败")
        val dec = provider.createDecoder(16_000) ?: error("解码器创建失败")
        try {
            val pcm = sine16k()
            val opus = enc.encode(pcm)
            val out = dec.decode(opus)
            // 帧时长一致：60ms @16k = 960 采样
            assertEquals(960, out.size, "解码采样数应与帧长一致")
        } finally {
            enc.release(); dec.release()
        }
    }

    @Test
    fun `编码到解码往返 24k 下行`() {
        val provider = ConcentusCodecProvider()
        val enc = provider.createEncoder() ?: error("编码器创建失败")
        val dec = provider.createDecoder(24_000) ?: error("解码器创建失败")
        try {
            val opus = enc.encode(sine16k())
            val out = dec.decode(opus)
            // 24k 下行同样 60ms = 1440 采样（Opus 全频带内部处理，解码率可异于编码率）
            assertEquals(1440, out.size, "24k 解码采样数应与帧长一致")
        } finally {
            enc.release(); dec.release()
        }
    }

    @Test
    fun `连续多帧编码解码保持流式状态一致`() {
        val provider = ConcentusCodecProvider()
        val enc = provider.createEncoder() ?: error("编码器创建失败")
        val dec = provider.createDecoder(24_000) ?: error("解码器创建失败")
        try {
            for (i in 0 until 10) {
                val opus = enc.encode(sine16k(phaseOffset = i * 960))
                val out = dec.decode(opus)
                assertEquals(1440, out.size, "第 $i 帧解码采样数异常")
            }
        } finally {
            enc.release(); dec.release()
        }
    }

    @Test
    fun `能量量级合理（非静音非削波）`() {
        val provider = ConcentusCodecProvider()
        val enc = provider.createEncoder() ?: error("编码器创建失败")
        val dec = provider.createDecoder(16_000) ?: error("解码器创建失败")
        try {
            val out = dec.decode(enc.encode(sine16k()))
            val peak = out.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue(peak > 1000, "解码峰值 $peak 过低，疑似静音")
            assertTrue(peak < 32000, "解码峰值 $peak 过高，疑似削波")
        } finally {
            enc.release(); dec.release()
        }
    }
}
