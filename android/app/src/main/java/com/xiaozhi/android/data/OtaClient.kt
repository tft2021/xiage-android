package com.xiaozhi.android.data

import com.xiaozhi.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** OTA 响应解析结果 */
data class OtaConfig(
    val websocketUrl: String,
    val websocketToken: String,
    val mqtt: JSONObject?,
    val activationCode: String?,
    val activationMessage: String?,
    val activationChallenge: String?,
    val raw: JSONObject,
) {
    /** 存在 activation 段即表示设备尚未绑定账号 */
    val needsActivation: Boolean get() = activationCode != null

    /** 未绑定账号的三个特征，用于自检 */
    val isTestTier: Boolean
        get() = websocketToken == OtaClient.PLACEHOLDER_TOKEN ||
            mqtt?.optString("client_id", "")?.startsWith("GID_test") == true ||
            mqtt?.optString("subscribe_topic", "") == "null"
}

sealed class ActivateResult {
    /** 200：激活成功 */
    data class Activated(val deviceId: Long?) : ActivateResult()

    /** 202：等待用户在 xiaozhi.me 输入激活码 */
    data object Pending : ActivateResult()

    data class Failed(val code: Int, val body: String) : ActivateResult()
}

/**
 * OTA 配置拉取与激活。
 *
 * 实测要点：
 * - User-Agent 必须形如 {BOARD_TYPE}/{APP_NAME}-{VERSION}，否则 400
 * - Activation-Version 头不需要传，缺省即 v1（无 eFuse 序列号路径）
 * - 无序列号时激活载荷固定为 {}，不走 HMAC
 */
class OtaClient(
    private val identity: DeviceIdentity,
    otaUrl: String = DEFAULT_OTA_URL,
) {
    private val baseUrl = if (otaUrl.endsWith("/")) otaUrl else "$otaUrl/"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val userAgent =
        "${BuildConfig.BOARD_TYPE}/${BuildConfig.APP_NAME}-${BuildConfig.VERSION_NAME}"

    /** 拉取 OTA 配置 */
    suspend fun fetchConfig(localIp: String = "192.168.1.100"): OtaConfig =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("application", JSONObject().apply {
                    put("version", BuildConfig.VERSION_NAME)
                    put("elf_sha256", "unknown")
                })
                put("board", JSONObject().apply {
                    put("type", BuildConfig.BOARD_TYPE)
                    put("name", BuildConfig.APP_NAME)
                    put("ip", localIp)
                    put("mac", identity.deviceId)
                })
            }

            val request = Request.Builder()
                .url(baseUrl)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Device-Id", identity.deviceId)
                .addHeader("Client-Id", identity.clientId)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", userAgent)
                .addHeader("Accept-Language", "zh-CN")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw OtaException(response.code, body)
                }
                JSONObject(body).parseOtaConfig()
            }
        }

    /** 确认激活。无 eFuse 序列号时载荷为 {} */
    suspend fun activate(): ActivateResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl}activate")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Device-Id", identity.deviceId)
            .addHeader("Client-Id", identity.clientId)
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", userAgent)
            .addHeader("Accept-Language", "zh-CN")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when (response.code) {
                200 -> {
                    val id = runCatching { JSONObject(body).optLong("device_id", -1L) }
                        .getOrDefault(-1L)
                        .takeIf { it > 0 }
                    ActivateResult.Activated(id)
                }
                202 -> ActivateResult.Pending
                else -> ActivateResult.Failed(response.code, body)
            }
        }
    }

    private fun JSONObject.parseOtaConfig(): OtaConfig {
        val ws = optJSONObject("websocket")
        // token 缺失或空一律兜底为占位符，与固件行为一致
        val token = ws?.optString("token")?.takeIf { it.isNotBlank() } ?: PLACEHOLDER_TOKEN
        val activation = optJSONObject("activation")

        return OtaConfig(
            websocketUrl = ws?.optString("url").orEmpty(),
            websocketToken = token,
            mqtt = optJSONObject("mqtt"),
            activationCode = activation?.optString("code")?.takeIf { it.isNotBlank() },
            activationMessage = activation?.optString("message"),
            activationChallenge = activation?.optString("challenge"),
            raw = this,
        )
    }

    companion object {
        const val DEFAULT_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
        const val PLACEHOLDER_TOKEN = "test-token"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class OtaException(val code: Int, val body: String) :
    RuntimeException("OTA 请求失败 HTTP $code: $body")
