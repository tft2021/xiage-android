package com.xiaozhi.protocol.ota

import org.json.JSONObject

/** OTA 响应解析结果 */
data class OtaConfig(
    val websocketUrl: String?,
    val websocketToken: String?,
    val mqttEndpoint: String?,
    val mqttClientId: String?,
    val mqttUsername: String?,
    val mqttPassword: String?,
    val mqttPublishTopic: String?,
    val mqttSubscribeTopic: String?,
    /** 6 位激活码，未绑定时返回，需引导用户到 xiaozhi.me 输入 */
    val activationCode: String?,
    val activationChallenge: String?,
    val firmwareVersion: String?,
    val firmwareUrl: String?,
) {
    /** 服务端下发激活码说明设备未绑定，需先走激活流程 */
    val needsActivation: Boolean get() = activationCode != null

    /** 三处占位特征，用于诊断绑定是否生效（实测判据，见 probe/验证报告） */
    val isTestGroup: Boolean
        get() = websocketToken == TEST_TOKEN ||
            mqttSubscribeTopic == "null" ||
            mqttClientId?.startsWith(TEST_GID_PREFIX) == true

    companion object {
        const val TEST_TOKEN = "test-token"
        const val TEST_GID_PREFIX = "GID_test"

        /** 测试用：构造最小配置 */
        fun minimal(
            websocketUrl: String? = null,
            websocketToken: String? = null,
            activationCode: String? = null,
            activationChallenge: String? = null,
        ): OtaConfig = OtaConfig(
            websocketUrl = websocketUrl,
            websocketToken = websocketToken,
            mqttEndpoint = null,
            mqttClientId = null,
            mqttUsername = null,
            mqttPassword = null,
            mqttPublishTopic = null,
            mqttSubscribeTopic = null,
            activationCode = activationCode,
            activationChallenge = activationChallenge,
            firmwareVersion = null,
            firmwareUrl = null,
        )
    }
}

/** 激活结果，状态码语义与固件 ota.cc Activate() 一致 */
sealed class ActivateResult {
    /** 200：用户已在网页完成绑定 */
    data object Success : ActivateResult()

    /** 202：等待用户在 xiaozhi.me 输入 6 位激活码 */
    data object Waiting : ActivateResult()

    data class Failed(val code: Int, val body: String) : ActivateResult()
}

class OtaException(val code: Int, message: String) : Exception(message)

/**
 * OTA / 激活 API 抽象。
 *
 * 抽出接口让会话状态机可以注入 Fake 实现做单元测试；
 * 生产实现为 [OtaClient]（HttpURLConnection，无第三方依赖）。
 */
interface OtaApi {
    /**
     * 拉取配置。必须用固定的身份调用，换身份会导致服务端重新注册、丢失绑定。
     */
    suspend fun checkVersion(identity: DeviceIdentity, localIp: String = ""): OtaConfig

    /**
     * 提交激活请求。轮询直到 Success 或调用方放弃。
     */
    suspend fun activate(identity: DeviceIdentity, challenge: String): ActivateResult
}

/** 便捷扩展：判断 mqtt.username（Base64）里的绑定标志（实测 c=0 未注册 / c=1 已注册） */
fun OtaConfig.decodedMqttUsernameHint(): String? = try {
    val raw = java.util.Base64.getDecoder().decode(mqttUsername ?: return null)
    String(raw, Charsets.UTF_8)
} catch (_: Exception) {
    null
}

fun JSONObject.optNonEmptyString(name: String): String? =
    optString(name).takeIf { it.isNotEmpty() }
