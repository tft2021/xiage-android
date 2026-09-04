package com.xiaozhi.protocol.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量诊断日志（调试版专用）：进程内全局环形缓冲，线程安全，无第三方依赖。
 *
 * 定位「绑定已确认但服务端永不下发真实凭据」这类只在真机上发生的问题：
 *  - [com.xiaozhi.protocol.ota.OtaClient] 记录每次 HTTP 的完整请求/响应
 *  - [com.xiaozhi.protocol.session.XiaozhiSession] 记录 phase 转换与关键决策
 *  - UI 层把身份四元组 + 本缓冲快照拼成文本，一键复制给开发者
 *
 * core-protocol 是纯 JVM 模块，不能依赖 android.util.Log；
 * 每条日志同步打到 System.err（Android 上落 logcat，可用 `XZDBG` 过滤）。
 */
object DebugLog {

    /** 缓冲上限：够覆盖一次完整的「连接→输码→等待凭据」全流程（约几百次轮询） */
    const val MAX_ENTRIES = 500

    /** 单条消息截断长度：OTA 响应体正常只有几百字节，防御异常超大响应 */
    private const val MAX_MSG_LEN = 2_000

    data class Entry(val time: String, val category: String, val message: String)

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES)

    /** HH:mm:ss.SSS，与服务端日志、xiaozhi.me 操作时间可直接对照 */
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(category: String, message: String) {
        // 压成单行：响应体里的换行会破坏 dump 的逐行格式
        val single = message.replace('\n', ' ').replace('\r', ' ')
        val clipped = if (single.length > MAX_MSG_LEN) single.take(MAX_MSG_LEN) + "…(截断)" else single
        val entry = Entry(fmt.format(Date()), category, clipped)
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        System.err.println("XZDBG/$category: ${entry.time} $clipped")
    }

    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }

    fun clear() = synchronized(lock) { buffer.clear() }

    /** 导出为可读文本，追加在诊断信息的事件日志段 */
    fun dump(): String = buildString {
        val entries = snapshot()
        appendLine("--- 事件日志（最近 ${entries.size} 条，上限 $MAX_ENTRIES） ---")
        entries.forEach { appendLine("${it.time} [${it.category}] ${it.message}") }
    }
}
