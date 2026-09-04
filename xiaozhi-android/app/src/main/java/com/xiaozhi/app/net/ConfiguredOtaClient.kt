package com.xiaozhi.app.net

import android.content.Context
import com.xiaozhi.protocol.ota.DeviceIdentity
import com.xiaozhi.protocol.ota.OtaApi
import com.xiaozhi.protocol.ota.OtaClient
import com.xiaozhi.protocol.ota.OtaConfig
import com.xiaozhi.protocol.ota.ActivateResult

/**
 * 可配置服务端地址的 OTA 客户端（调试用）。
 *
 * 每次**调用时**从 SharedPreferences 读取 `ota_url`，因此界面上改完地址，
 * 下一次点「连接」立即生效，无需重启 App 或重建会话。
 *
 * 地址归一化规则（[normalizeOtaUrl]）：
 *  - 空 -> 官方默认 https://api.tenclass.net/xiaozhi/ota/
 *  - 只给主机（如 http://192.168.1.5:8000）-> 自动补 /xiaozhi/ota/
 *  - 已含 xiaozhi 路径（如官方完整地址）-> 原样使用
 */
class ConfiguredOtaClient(context: Context) : OtaApi {

    private val appContext = context.applicationContext
    private val sp get() = appContext.getSharedPreferences("xiaozhi", Context.MODE_PRIVATE)

    private fun currentUrl(): String =
        normalizeOtaUrl(sp.getString(PREF_KEY, "").orEmpty())

    /** 当前生效的 OTA 地址，诊断导出用 */
    fun effectiveUrl(): String = currentUrl()

    /** 是否指向非官方服务端（UI 徽标用） */
    fun isCustom(): Boolean = currentUrl() != DEFAULT_OTA_URL

    override suspend fun checkVersion(identity: DeviceIdentity, localIp: String): OtaConfig =
        OtaClient(otaUrl = currentUrl()).checkVersion(identity, localIp)

    override suspend fun activate(identity: DeviceIdentity, challenge: String): ActivateResult =
        OtaClient(otaUrl = currentUrl()).activate(identity, challenge)

    companion object {
        const val PREF_KEY = "ota_url"
        const val DEFAULT_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"

        /** 保存地址（未归一化前也允许存原始输入，读取时归一化） */
        fun save(context: Context, raw: String) {
            context.getSharedPreferences("xiaozhi", Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, raw.trim()).apply()
        }

        fun normalizeOtaUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return DEFAULT_OTA_URL
            // 已带 xiaozhi 路径视为完整 OTA 地址；只给主机则补标准路径
            return if (trimmed.substringAfter("://", "").contains("xiaozhi")) {
                "$trimmed/"
            } else {
                "$trimmed/xiaozhi/ota/"
            }
        }
    }
}
