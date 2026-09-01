package com.xiaozhi.protocol.ws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XiaozhiMessageParserTest {

    // ---------------- 服务端 -> 设备 ----------------

    @Test
    fun `hello 消息解析出协商音频参数`() {
        val msg = XiaozhiMessageParser.parse(
            """{"type":"hello","session_id":"s1",
                "audio_params":{"format":"opus","sample_rate":24000,"channels":1,"frame_duration":60}}""".trimIndent(),
        )
        val ack = assertIs<XiaozhiMessage.HelloAck>(msg)
        assertEquals("s1", ack.sessionId)
        assertEquals(24_000, ack.audioParams?.sampleRate)
        assertEquals("opus", ack.audioParams?.format)
    }

    @Test
    fun `hello 无 audio_params 时为 null`() {
        val msg = XiaozhiMessageParser.parse("""{"type":"hello"}""")
        val ack = assertIs<XiaozhiMessage.HelloAck>(msg)
        assertNull(ack.audioParams)
        assertNull(ack.sessionId)
    }

    @Test
    fun `stt 消息解析`() {
        val msg = XiaozhiMessageParser.parse("""{"type":"stt","text":"你好小智","session_id":"s1"}""")
        val stt = assertIs<XiaozhiMessage.Stt>(msg)
        assertEquals("你好小智", stt.text)
        assertEquals("s1", stt.sessionId)
    }

    @Test
    fun `llm 消息解析，缺省 emotion 为 neutral`() {
        val msg = XiaozhiMessageParser.parse("""{"type":"llm","text":"在呢"}""")
        val llm = assertIs<XiaozhiMessage.Llm>(msg)
        assertEquals("neutral", llm.emotion)
        assertEquals("在呢", llm.text)
    }

    @Test
    fun `tts 三种状态映射`() {
        val start = assertIs<XiaozhiMessage.Tts>(
            XiaozhiMessageParser.parse("""{"type":"tts","state":"start","text":"开始说话"}"""),
        )
        assertEquals(TtsState.START, start.state)
        assertEquals("开始说话", start.text)

        val sentence = assertIs<XiaozhiMessage.Tts>(
            XiaozhiMessageParser.parse("""{"type":"tts","state":"sentence_start","text":"第一句"}"""),
        )
        assertEquals(TtsState.SENTENCE_START, sentence.state)

        val stop = assertIs<XiaozhiMessage.Tts>(
            XiaozhiMessageParser.parse("""{"type":"tts","state":"stop"}"""),
        )
        assertEquals(TtsState.STOP, stop.state)
        assertNull(stop.text)
    }

    @Test
    fun `mcp 消息解析 payload`() {
        val msg = XiaozhiMessageParser.parse(
            """{"type":"mcp","payload":{"jsonrpc":"2.0","id":1,"method":"tools/list"},"session_id":"s1"}""",
        )
        val mcp = assertIs<XiaozhiMessage.Mcp>(msg)
        assertEquals("2.0", mcp.payload.optString("jsonrpc"))
        assertEquals("s1", mcp.sessionId)
    }

    @Test
    fun `system 与 alert 消息解析`() {
        val sys = assertIs<XiaozhiMessage.System>(XiaozhiMessageParser.parse("""{"type":"system","command":"reboot"}"""))
        assertEquals("reboot", sys.command)

        val alert = assertIs<XiaozhiMessage.Alert>(
            XiaozhiMessageParser.parse("""{"type":"alert","status":"warning","message":"电量低"}"""),
        )
        assertEquals("warning", alert.status)
        assertEquals("电量低", alert.message)
    }

    @Test
    fun `未知类型保留原始 JSON`() {
        val msg = XiaozhiMessageParser.parse("""{"type":"future_thing","foo":"bar"}""")
        val unknown = assertIs<XiaozhiMessage.Unknown>(msg)
        assertEquals("future_thing", unknown.type)
        assertEquals("bar", unknown.raw.optString("foo"))
    }

    // ---------------- 设备 -> 服务端（toJson 往返） ----------------

    @Test
    fun `hello toJson 字段完整`() {
        val json = XiaozhiMessage.Hello().toJson(null)
        assertEquals("hello", json.optString("type"))
        assertEquals(1, json.optInt("version"))
        assertEquals("websocket", json.optString("transport"))
        assertTrue(json.optJSONObject("features")?.optBoolean("mcp") == true)
        assertEquals(16_000, json.optJSONObject("audio_params")?.optInt("sample_rate"))
    }

    @Test
    fun `hello toJson 带可选 sessionId`() {
        val json = XiaozhiMessage.Hello().toJson("sess-9")
        assertEquals("sess-9", json.optString("session_id"))
    }

    @Test
    fun `listen toJson 状态与模式`() {
        val json = XiaozhiMessage.Listen(ListenState.START, ListenMode.MANUAL, "小智").toJson(null)
        assertEquals("listen", json.optString("type"))
        assertEquals("start", json.optString("state"))
        assertEquals("manual", json.optString("mode"))
        assertEquals("小智", json.optString("text"))
    }

    @Test
    fun `abort toJson 携带 reason`() {
        val json = XiaozhiMessage.Abort("user_interruption").toJson("s1")
        assertEquals("abort", json.optString("type"))
        assertEquals("user_interruption", json.optString("reason"))
        assertEquals("s1", json.optString("session_id"))
    }

    @Test
    fun `helloAck 从服务端 json 到 AudioParams 往返一致`() {
        val server = XiaozhiMessage.HelloAck(
            sessionId = "rt",
            audioParams = AudioParams.downlink(24_000),
        )
        // 模拟服务端按 AudioParams.toJson() 编码后再解析回来
        val raw = org.json.JSONObject()
            .put("type", "hello")
            .put("session_id", server.sessionId)
            .put("audio_params", server.audioParams!!.toJson())
        val parsed = XiaozhiMessageParser.parse(raw.toString())
        val ack = assertIs<XiaozhiMessage.HelloAck>(parsed)
        assertEquals(24_000, ack.audioParams?.sampleRate)
        assertEquals(60, ack.audioParams?.frameDurationMs)
    }
}
