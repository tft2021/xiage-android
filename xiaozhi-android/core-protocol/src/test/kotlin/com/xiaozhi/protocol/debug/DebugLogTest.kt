package com.xiaozhi.protocol.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebugLogTest {

    @Test
    fun `环形缓冲超过上限时丢弃最旧条目`() {
        DebugLog.clear()
        repeat(DebugLog.MAX_ENTRIES + 100) { DebugLog.log("t", "msg$it") }
        val snapshot = DebugLog.snapshot()
        assertEquals(DebugLog.MAX_ENTRIES, snapshot.size)
        assertEquals("msg${DebugLog.MAX_ENTRIES + 99}", snapshot.last().message)
    }

    @Test
    fun `dump 包含类别与消息且逐条成行`() {
        DebugLog.clear()
        DebugLog.log("ota", "checkVersion RESP <- HTTP 200 body={}")
        DebugLog.log("session", "激活成功 round=1")
        val dump = DebugLog.dump()
        assertTrue("[ota] checkVersion RESP" in dump, dump)
        assertTrue("[session] 激活成功 round=1" in dump, dump)
        assertTrue(dump.lineSequence().none { it.count { c -> c == '\n' } > 0 })
    }

    @Test
    fun `换行被压平避免破坏逐行格式`() {
        DebugLog.clear()
        DebugLog.log("t", "a\nb\rc")
        val dump = DebugLog.dump()
        // 消息本体不能跨行：dump 出来的行数 = 头部 1 行 + 条目 1 行
        assertEquals(2, dump.trim().lineSequence().count())
    }

    @Test
    fun `超长消息被截断到上限`() {
        DebugLog.clear()
        DebugLog.log("t", "x".repeat(10_000))
        assertTrue(DebugLog.snapshot().single().message.length < 2_500)
    }
}
