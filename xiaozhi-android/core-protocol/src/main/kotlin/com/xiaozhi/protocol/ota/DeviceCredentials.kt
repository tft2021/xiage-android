package com.xiaozhi.protocol.ota

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 设备凭据 —— 对应 py-xiaozhi 的 efuse.json
 *
 * 服务端信任模型：设备首次 OTA 时通过 Serial-Number 头 + body 里的
 * application.elf_sha256 字段上报自己的序列号与 HMAC 密钥，服务端据此注册；
 * 之后激活时用同一密钥对 challenge 做 HMAC-SHA256 签名证明身份。
 *
 * 因此密钥可以完全在本地生成，不需要真实硬件。生成后必须持久化，
 * 卸载前保持不变，否则绑定关系会丢失。
 */
data class DeviceCredentials(
    val serialNumber: String,
    val hmacKey: String,
) {
    /** 对 challenge 做 HMAC-SHA256，输出 64 位十六进制小写 */
    fun sign(challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(challenge.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * 首次启动时生成。
         *
         * serial_number 格式沿用 py-xiaozhi：SN-<md5(mac)前8位大写>-<mac去冒号>
         * hmac_key 为 SHA-256 十六进制串（64 字符）。
         */
        fun generate(deviceId: String): DeviceCredentials {
            val macClean = deviceId.lowercase().replace(":", "")
            val sn = "SN-" +
                md5Hex(macClean).take(8).uppercase() + "-" + macClean
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            return DeviceCredentials(serialNumber = sn, hmacKey = key)
        }
    }
}

/**
 * 设备身份。三项都必须持久化（SharedPreferences / DataStore），
 * 卸载重装前保持不变。
 */
data class DeviceIdentity(
    val deviceId: String,
    val clientId: String,
    val credentials: DeviceCredentials,
) {
    companion object {
        fun create(persisted: PersistedIdentity?): DeviceIdentity {
            val id = persisted?.deviceId ?: randomMacLike()
            return DeviceIdentity(
                deviceId = id,
                clientId = persisted?.clientId ?: UUID.randomUUID().toString(),
                credentials = persisted?.credentials
                    ?: DeviceCredentials.generate(id),
            )
        }
    }
}

/** 持久化载体，实现层映射到 SharedPreferences / DataStore */
data class PersistedIdentity(
    val deviceId: String,
    val clientId: String,
    val credentials: DeviceCredentials,
)

/**
 * 生成 MAC 格式的随机 Device-Id。
 * 首字节取 0x02（locally administered unicast），避开广播与组播段。
 */
fun randomMacLike(): String {
    val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
    bytes[0] = ((bytes[0].toInt() or 0x02) and 0xFE).toByte()
    return bytes.joinToString(":") { "%02X".format(it) }
}

private fun md5Hex(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
