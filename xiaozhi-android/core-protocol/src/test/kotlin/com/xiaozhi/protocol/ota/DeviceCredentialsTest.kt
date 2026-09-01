package com.xiaozhi.protocol.ota

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceCredentialsTest {

    // ---------------- HMAC-SHA256 签名 ----------------

    /**
     * 已知测试向量（RFC 4231 派生 / 常用验证组）：
     *   key = "key"
     *   message = "The quick brown fox jumps over the lazy dog"
     *   HMAC-SHA256 = f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8
     */
    @Test
    fun `HMAC 签名与已知测试向量一致`() {
        val creds = DeviceCredentials(serialNumber = "SN-TEST", hmacKey = "key")
        val sig = creds.sign("The quick brown fox jumps over the lazy dog")
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", sig)
    }

    @Test
    fun `签名输出为 64 位十六进制小写`() {
        val creds = DeviceCredentials.generate("AA:BB:CC:DD:EE:FF")
        val sig = creds.sign("challenge-123")
        assertEquals(64, sig.length)
        assertTrue(sig.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `不同 challenge 产生不同签名`() {
        val creds = DeviceCredentials.generate("AA:BB:CC:DD:EE:FF")
        assertTrue(creds.sign("a") != creds.sign("b"))
    }

    // ---------------- 身份生成 ----------------

    @Test
    fun `generate 序列号格式 SN-md5前8位大写-mac去冒号`() {
        val deviceId = "aa:bb:cc:dd:ee:ff"
        val creds = DeviceCredentials.generate(deviceId)

        // 独立实现一遍 md5 以交叉验证
        val macClean = "aabbccddeeff"
        val md5 = MessageDigest.getInstance("MD5").digest(macClean.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val expectSn = "SN-${md5.take(8).uppercase()}-$macClean"
        assertEquals(expectSn, creds.serialNumber)
        assertTrue(creds.serialNumber.startsWith("SN-"))
    }

    @Test
    fun `generate 的 hmacKey 为 64 位 hex`() {
        val creds = DeviceCredentials.generate("AA:BB:CC:DD:EE:FF")
        assertEquals(64, creds.hmacKey.length)
        assertTrue(creds.hmacKey.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `同一 deviceId 生成相同序列号`() {
        val a = DeviceCredentials.generate("11:22:33:44:55:66")
        val b = DeviceCredentials.generate("11:22:33:44:55:66")
        assertEquals(a.serialNumber, b.serialNumber)
        // 密钥是随机的，不应相同
        assertTrue(a.hmacKey != b.hmacKey)
    }

    @Test
    fun `randomMacLike 格式正确且首字节为本地管理单播`() {
        repeat(20) {
            val mac = randomMacLike()
            assertTrue(mac.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")))
            val first = mac.substringBefore(':').toInt(16)
            assertEquals(0, first and 0x01) // 单播
            assertEquals(0x02, first and 0x02) // locally administered
        }
    }

    // ---------------- DeviceIdentity ----------------

    @Test
    fun `DeviceIdentity 从持久化恢复时各项保持不变`() {
        val creds = DeviceCredentials.generate("00:11:22:33:44:55")
        val persisted = PersistedIdentity(
            deviceId = "AA:BB:CC:DD:EE:01",
            clientId = "client-fixed",
            credentials = creds,
        )
        val id = DeviceIdentity.create(persisted)
        assertEquals("AA:BB:CC:DD:EE:01", id.deviceId)
        assertEquals("client-fixed", id.clientId)
        assertEquals(creds, id.credentials)
    }

    @Test
    fun `DeviceIdentity 无持久化时全新生成`() {
        val id = DeviceIdentity.create(null)
        assertTrue(id.deviceId.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")))
        assertTrue(id.clientId.isNotEmpty())
        assertEquals(64, id.credentials.hmacKey.length)
    }
}
