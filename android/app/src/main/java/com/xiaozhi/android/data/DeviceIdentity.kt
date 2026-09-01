package com.xiaozhi.android.data

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

/**
 * 设备身份。
 *
 * Android 6+ 拿不到真实 Wi-Fi MAC（已随机化），因此伪造一个并持久化。
 * 实测服务端不校验 MAC 的 OUI，伪造值可用。
 *
 * 两个 ID 必须在卸载前保持不变，否则每次启动都是新设备、都要重新激活。
 */
class DeviceIdentity(context: Context) {

    val deviceId: String
    val clientId: String

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        var storedDevice = prefs.getString(KEY_DEVICE_ID, null)
        if (storedDevice == null) {
            storedDevice = generateFakeMac()
            prefs.edit().putString(KEY_DEVICE_ID, storedDevice).apply()
        }

        var storedClient = prefs.getString(KEY_CLIENT_ID, null)
        if (storedClient == null) {
            storedClient = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_CLIENT_ID, storedClient).apply()
        }

        deviceId = storedDevice
        clientId = storedClient
    }

    /**
     * 生成本地管理（locally administered）、单播的伪 MAC。
     * 第二低位十六进制置 2/6/A/E，避免被误判为全局唯一地址。
     */
    private fun generateFakeMac(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        bytes[0] = (bytes[0].toInt() and 0xFE or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it) }
    }

    companion object {
        private const val PREFS_NAME = "xiaozhi_device"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CLIENT_ID = "client_id"
    }
}
