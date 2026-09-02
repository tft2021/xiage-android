package com.xiaozhi.protocol.ota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 小智 OTA / 激活客户端（[OtaApi] 的生产实现，HttpURLConnection 无第三方依赖）
 *
 * 协议来源：
 *  - 固件：78/xiaozhi-esp32  main/ota.cc
 *  - 第三方客户端参考：huangjunsen0406/py-xiaozhi  src/activation/
 *  - 以上链路均已对官方线上服务实测验证（2026-08-31），见 probe/ 官方服务器接入验证报告
 *
 * 关键约束（实测踩坑记录）：
 *  - User-Agent 必须是 "{boardType}/{appName}-{appVersion}"，多余后缀会 400
 *  - board.type 必须用服务端认识的板型，第三方客户端沿用 py-xiaozhi 的
 *    "bread-compact-wifi"（面包板 WiFi 版）即可
 *  - Activation-Version=2 走 HMAC 激活，Serial-Number 头 + body 的
 *    application.elf_sha256 字段把 hmacKey 上报给服务端注册
 *  - /activate 的载荷是 {"Payload": {...}} 嵌套结构，与固件的扁平结构不同
 *    （固件发扁平是因为它没有序列号、走 v1 路径发 "{}"）
 */
class OtaClient(
    /** 默认官方地址，自建服务端时替换 */
    private val otaUrl: String = "https://api.tenclass.net/xiaozhi/ota/",
    /** 服务端认识的板型名，沿用 py-xiaozhi 的值 */
    private val boardType: String = "bread-compact-wifi",
    private val appName: String = "xiaozhi-android",
    private val appVersion: String = "2.1.5",
    private val acceptLanguage: String = "zh-CN",
) : OtaApi {

    /** 最近一次成功拉取的配置，供诊断（如判断是否仍在测试组） */
    var lastConfig: OtaConfig? = null
        private set

    override suspend fun checkVersion(
        identity: DeviceIdentity,
        localIp: String,
    ): OtaConfig = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("application", JSONObject().apply {
                put("version", appVersion)
                // 服务端靠这个字段登记设备的 hmacKey
                put("elf_sha256", identity.credentials.hmacKey)
            })
            put("board", JSONObject().apply {
                put("type", boardType)
                put("name", appName)
                put("ip", localIp.ifEmpty { "127.0.0.1" })
                put("mac", identity.deviceId)
            })
        }

        val conn = open(identity, activationVersion = "2")
        try {
            conn.requestMethod = "POST"
            conn.setDoOutput(true)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw OtaException(code, "OTA checkVersion 失败: $err")
            }
            parseConfig(JSONObject(conn.inputStream.bufferedReader().readText()))
                .also { lastConfig = it }
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun activate(
        identity: DeviceIdentity,
        challenge: String,
    ): ActivateResult = withContext(Dispatchers.IO) {
        val url = if (otaUrl.endsWith("/")) otaUrl + "activate" else otaUrl + "/activate"
        val payload = JSONObject().apply {
            put("Payload", JSONObject().apply {
                put("algorithm", "hmac-sha256")
                put("serial_number", identity.credentials.serialNumber)
                put("challenge", challenge)
                put("hmac", identity.credentials.sign(challenge))
            })
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setHeaders(identity, "2")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CONNECT_TIMEOUT_MS
        }
        try {
            conn.requestMethod = "POST"
            conn.setDoOutput(true)
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            when (conn.responseCode) {
                200 -> ActivateResult.Success
                202 -> ActivateResult.Waiting
                else -> ActivateResult.Failed(
                    conn.responseCode,
                    conn.errorStream?.bufferedReader()?.readText().orEmpty(),
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ internal

    private fun open(identity: DeviceIdentity, activationVersion: String): HttpURLConnection =
        (URL(otaUrl).openConnection() as HttpURLConnection).apply {
            setHeaders(identity, activationVersion)
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = CONNECT_TIMEOUT_MS
        }

    private fun HttpURLConnection.setHeaders(identity: DeviceIdentity, v: String) {
        setRequestProperty("Activation-Version", v)
        setRequestProperty("Device-Id", identity.deviceId)
        setRequestProperty("Client-Id", identity.clientId)
        setRequestProperty("Serial-Number", identity.credentials.serialNumber)
        setRequestProperty("User-Agent", "$boardType/$appName-$appVersion")
        setRequestProperty("Accept-Language", acceptLanguage)
        setRequestProperty("Content-Type", "application/json")
    }

    /**
     * 解析 OTA 响应。
     *
     * 必须用 [optNonEmptyString] 而不是 [JSONObject.optString]：org.json 的 optString
     * 在字段缺失时返回 **空串**（不是 null），空串会骗过上层所有 `?:` 判空：
     *  - `websocketUrl = ""`  -> 拿空 URL 去建立 WebSocket，OkHttp 直接抛 IllegalArgumentException
     *  - `activationCode = ""` -> `needsActivation`（判 `!= null`）被误判为 true，
     *    用户看到一个空白激活码；轮询刷新时还会把 challenge 覆盖成空串
     *  - `websocketToken = ""` -> 绕开 `isTestGroup` 的 `token == "test-token"` 判据
     */
    private fun parseConfig(root: JSONObject): OtaConfig {
        val ws = root.optJSONObject("websocket")
        val mqtt = root.optJSONObject("mqtt")
        val act = root.optJSONObject("activation")
        val fw = root.optJSONObject("firmware")
        return OtaConfig(
            websocketUrl = ws?.optNonEmptyString("url"),
            websocketToken = ws?.optNonEmptyString("token"),
            mqttEndpoint = mqtt?.optNonEmptyString("endpoint"),
            mqttClientId = mqtt?.optNonEmptyString("client_id"),
            mqttUsername = mqtt?.optNonEmptyString("username"),
            mqttPassword = mqtt?.optNonEmptyString("password"),
            mqttPublishTopic = mqtt?.optNonEmptyString("publish_topic"),
            mqttSubscribeTopic = mqtt?.optNonEmptyString("subscribe_topic"),
            activationCode = act?.optNonEmptyString("code"),
            activationChallenge = act?.optNonEmptyString("challenge"),
            firmwareVersion = fw?.optNonEmptyString("version"),
            firmwareUrl = fw?.optNonEmptyString("url"),
        )
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000
    }
}
