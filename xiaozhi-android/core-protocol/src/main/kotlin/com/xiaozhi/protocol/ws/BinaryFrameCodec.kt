package com.xiaozhi.protocol.ws

import java.nio.ByteBuffer

/**
 * 小智二进制帧编解码
 *
 * 协议来源：78/xiaozhi-esp32 docs/websocket.md
 *
 *  v1：裸 Opus，无封装
 *  v2：u16 version | u16 type | u32 reserved | u32 timestamp | u32 payload_size | payload[]
 *      （timestamp 供服务端 AEC 使用）
 *  v3：u8 type | u8 reserved | u16 payload_size | payload[]（轻量头）
 *
 * type：0 = OPUS，1 = JSON
 */
object BinaryFrameCodec {

    const val TYPE_OPUS = 0
    const val TYPE_JSON = 1

    const val V1 = 1
    const val V2 = 2
    const val V3 = 3

    private const val V2_HEADER = 16
    private const val V3_HEADER = 4

    /**
     * 封装上行帧。
     * v1 直接返回原始载荷；v3 追加 4 字节轻量头。
     */
    fun encode(version: Int, type: Int, payload: ByteArray): ByteArray = when (version) {
        V1 -> payload
        V2 -> {
            val buf = ByteBuffer.allocate(V2_HEADER + payload.size)
            buf.putShort(version.toShort())
            buf.putShort(type.toShort())
            buf.putInt(0)                 // reserved
            buf.putInt(0)                 // timestamp，客户端默认不填
            buf.putInt(payload.size)
            buf.put(payload)
            buf.array()
        }
        V3 -> {
            require(payload.size <= 0xFFFF) { "v3 payload 最大 65535 字节" }
            val buf = ByteBuffer.allocate(V3_HEADER + payload.size)
            buf.put(type.toByte())
            buf.put(0)                    // reserved
            buf.putShort(payload.size.toShort())
            buf.put(payload)
            buf.array()
        }
        else -> throw IllegalArgumentException("不支持的二进制协议版本: $version")
    }

    /**
     * 解析服务端下行帧，返回 (type, payload)。
     *
     * 对 v1 无法区分 type（WebSocket 帧类型层已区分 JSON/二进制），
     * 一律按 OPUS 处理，与固件行为一致。
     *
     * @return null 表示帧非法或载荷不完整，调用方应丢弃
     */
    fun decode(version: Int, raw: ByteArray): Pair<Int, ByteArray>? {
        when (version) {
            V1 -> return Pair(TYPE_OPUS, raw)
            V2 -> {
                if (raw.size < V2_HEADER) return null
                val buf = ByteBuffer.wrap(raw)
                val v = buf.short.toInt() and 0xFFFF
                val type = buf.short.toInt() and 0xFFFF
                buf.int                   // reserved
                buf.int                   // timestamp
                val size = buf.int
                if (v != V2 || size < 0 || raw.size < V2_HEADER + size) return null
                return Pair(type, raw.copyOfRange(V2_HEADER, V2_HEADER + size))
            }
            V3 -> {
                if (raw.size < V3_HEADER) return null
                val type = raw[0].toInt() and 0xFF
                val size = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
                if (size < 0 || raw.size < V3_HEADER + size) return null
                return Pair(type, raw.copyOfRange(V3_HEADER, V3_HEADER + size))
            }
            else -> return null
        }
    }
}
