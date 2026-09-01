package com.xiaozhi.protocol.ws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinaryFrameCodecTest {

    private val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)

    // ---------------- v1：裸 Opus ----------------

    @Test
    fun `v1 encode 返回原始载荷`() {
        val out = BinaryFrameCodec.encode(BinaryFrameCodec.V1, BinaryFrameCodec.TYPE_OPUS, payload)
        assertTrue(payload.contentEquals(out))
    }

    @Test
    fun `v1 decode 按 OPUS 处理`() {
        val (type, data) = BinaryFrameCodec.decode(BinaryFrameCodec.V1, payload)!!
        assertEquals(BinaryFrameCodec.TYPE_OPUS, type)
        assertTrue(payload.contentEquals(data))
    }

    // ---------------- v2 ----------------

    @Test
    fun `v2 编解码往返`() {
        val out = BinaryFrameCodec.encode(BinaryFrameCodec.V2, BinaryFrameCodec.TYPE_OPUS, payload)
        assertEquals(16 + payload.size, out.size)
        val (type, data) = BinaryFrameCodec.decode(BinaryFrameCodec.V2, out)!!
        assertEquals(BinaryFrameCodec.TYPE_OPUS, type)
        assertTrue(payload.contentEquals(data))
    }

    @Test
    fun `v2 头部布局正确`() {
        val out = BinaryFrameCodec.encode(BinaryFrameCodec.V2, BinaryFrameCodec.TYPE_JSON, payload)
        // u16 version=2 | u16 type=1 | u32 reserved | u32 timestamp | u32 payload_size
        assertEquals(2, ((out[0].toInt() and 0xFF) shl 8) or (out[1].toInt() and 0xFF))
        assertEquals(1, ((out[2].toInt() and 0xFF) shl 8) or (out[3].toInt() and 0xFF))
        assertEquals(payload.size, ((out[12].toInt() and 0xFF) shl 24) or ((out[13].toInt() and 0xFF) shl 16) or
            ((out[14].toInt() and 0xFF) shl 8) or (out[15].toInt() and 0xFF))
    }

    @Test
    fun `v2 截断帧返回 null`() {
        assertNull(BinaryFrameCodec.decode(BinaryFrameCodec.V2, ByteArray(15)))
    }

    @Test
    fun `v2 payload_size 超过实际长度返回 null`() {
        val out = BinaryFrameCodec.encode(BinaryFrameCodec.V2, BinaryFrameCodec.TYPE_OPUS, payload)
        val corrupt = out.copyOf().also {
            it[12] = 0x00; it[13] = 0x00; it[14] = 0x10; it[15] = 0x00 // size=4096
        }
        assertNull(BinaryFrameCodec.decode(BinaryFrameCodec.V2, corrupt))
    }

    // ---------------- v3 ----------------

    @Test
    fun `v3 编解码往返`() {
        val out = BinaryFrameCodec.encode(BinaryFrameCodec.V3, BinaryFrameCodec.TYPE_OPUS, payload)
        assertEquals(4 + payload.size, out.size)
        val (type, data) = BinaryFrameCodec.decode(BinaryFrameCodec.V3, out)!!
        assertEquals(BinaryFrameCodec.TYPE_OPUS, type)
        assertTrue(payload.contentEquals(data))
    }

    @Test
    fun `v3 头部布局正确`() {
        val out = BinaryFrameCodec.encode(BinaryFrameCodec.V3, BinaryFrameCodec.TYPE_JSON, payload)
        assertEquals(BinaryFrameCodec.TYPE_JSON, out[0].toInt() and 0xFF)
        assertEquals(0, out[1].toInt()) // reserved
        assertEquals(payload.size, ((out[2].toInt() and 0xFF) shl 8) or (out[3].toInt() and 0xFF))
    }

    @Test
    fun `v3 截断帧返回 null`() {
        assertNull(BinaryFrameCodec.decode(BinaryFrameCodec.V3, ByteArray(3)))
    }

    @Test
    fun `v3 不完整载荷返回 null`() {
        // 头声明 8 字节载荷，实际只有 2 字节
        val bad = byteArrayOf(BinaryFrameCodec.TYPE_OPUS.toByte(), 0x00, 0x00, 0x08, 0x01, 0x02)
        assertNull(BinaryFrameCodec.decode(BinaryFrameCodec.V3, bad))
    }

    @Test
    fun `v3 空载荷往返`() {
        val out = BinaryFrameCodec.encode(BinaryFrameCodec.V3, BinaryFrameCodec.TYPE_OPUS, ByteArray(0))
        assertEquals(4, out.size)
        val (type, data) = BinaryFrameCodec.decode(BinaryFrameCodec.V3, out)!!
        assertEquals(BinaryFrameCodec.TYPE_OPUS, type)
        assertEquals(0, data.size)
    }

    // ---------------- 非法输入 ----------------

    @Test
    fun `未知版本 decode 返回 null`() {
        assertNull(BinaryFrameCodec.decode(9, payload))
    }

    @Test
    fun `未知版本 encode 抛出异常`() {
        assertFailsWith<IllegalArgumentException> {
            BinaryFrameCodec.encode(9, BinaryFrameCodec.TYPE_OPUS, payload)
        }
    }

    @Test
    fun `v3 载荷超限抛出异常`() {
        assertFailsWith<IllegalArgumentException> {
            BinaryFrameCodec.encode(BinaryFrameCodec.V3, BinaryFrameCodec.TYPE_OPUS, ByteArray(0x10000))
        }
    }
}
