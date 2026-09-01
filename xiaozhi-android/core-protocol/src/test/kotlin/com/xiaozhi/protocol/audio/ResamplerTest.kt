package com.xiaozhi.protocol.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResamplerTest {

    @Test
    fun `同采样率返回内容相同的拷贝`() {
        val input = shortArrayOf(1, -2, 3, -4)
        val out = Resampler.resample(input, 16_000, 16_000)
        assertTrue(out.contentEquals(input))
        assertTrue(!out.contentEquals(input) || out !== input) // 是拷贝而非原引用
    }

    @Test
    fun `16k 到 32k 线性插值数值正确`() {
        // 输入 [0, 100] 升采样一倍：[0, 50, 100, 100]
        val out = Resampler.resample(shortArrayOf(0, 100), 16_000, 32_000)
        assertEquals(4, out.size)
        assertEquals(0, out[0].toInt())
        assertEquals(50, out[1].toInt())
        assertEquals(100, out[2].toInt())
        assertEquals(100, out[3].toInt())
    }

    @Test
    fun `16k 到 24k 输出长度为 3 比 2 倍`() {
        // 10ms @16k = 160 采样 -> 240 采样
        val input = ShortArray(160) { (it % 100).toShort() }
        val out = Resampler.resample(input, 16_000, 24_000)
        assertEquals(240, out.size)
    }

    @Test
    fun `24k 到 16k 输出长度为 2 比 3 倍`() {
        val input = ShortArray(240) { 1000 }
        val out = Resampler.resample(input, 24_000, 16_000)
        assertEquals(160, out.size)
    }

    @Test
    fun `直流信号重采样幅值不变`() {
        val input = ShortArray(480) { 1234 }
        val out = Resampler.resample(input, 24_000, 16_000)
        assertTrue(out.all { it.toInt() == 1234 })
    }

    @Test
    fun `立体声长度按帧计算`() {
        // 2 帧 x 2 声道，16k -> 32k 应输出 4 帧 x 2 = 8 个采样
        val input = shortArrayOf(0, 100, 10, 110)
        val out = Resampler.resample(input, 16_000, 32_000, channels = 2)
        assertEquals(8, out.size)
        // 第一声道插值 [0, 10] -> [0, 5, 10, 10]
        assertEquals(0, out[0].toInt())
        assertEquals(5, out[2].toInt())
        // 第二声道插值 [100, 110] -> [100, 105, 110, 110]
        assertEquals(100, out[1].toInt())
        assertEquals(105, out[3].toInt())
    }

    @Test
    fun `空输入返回空输出`() {
        assertEquals(0, Resampler.resample(ShortArray(0), 16_000, 24_000).size)
        assertEquals(0, Resampler.resample(ShortArray(0), 16_000, 16_000).size)
    }

    @Test
    fun `非法参数抛出异常`() {
        assertFailsWith<IllegalArgumentException> {
            Resampler.resample(ShortArray(4), 16_000, 24_000, channels = 0)
        }
        // 长度不是声道数的整数倍
        assertFailsWith<IllegalArgumentException> {
            Resampler.resample(ShortArray(3), 16_000, 24_000, channels = 2)
        }
    }
}
