package com.xiaozhi.protocol.audio

/**
 * 音频 IO 抽象 —— 隔离 Android 的 AudioRecord / AudioTrack。
 *
 * 会话状态机依赖此接口而非具体实现，从而可以在 JVM 上用
 * FakeAudioIO 做单元测试。生产实现见 app 模块的 AudioEngine。
 */
interface AudioIO {
    /** 采集到的上行 PCM 帧回调（固定帧长，见 FRAME_SIZE 约定） */
    var onPcmFrame: ((ShortArray) -> Unit)?

    fun startCapture()
    fun stopCapture()
    val isCapturing: Boolean

    /** 打开播放通道；sampleRate 用服务端下行采样率 */
    fun startPlayback(sampleRate: Int)
    fun enqueuePcm(pcm: ShortArray)
    fun flushPlayback()
    fun stopPlayback()

    fun releaseAll()
}

/**
 * Opus 编解码提供者。
 *
 * 返回 null 表示编解码未接入（如尚未集成 Opus 实现），
 * 会话会优雅降级：协议与 UI 正常工作，只是没有声音。
 * 这样可以在接入编解码之前先把整条链路跑通。
 */
interface AudioCodecProvider {
    fun createEncoder(): OpusEncoder?
    fun createDecoder(): OpusDecoder?
}

/** 未接入 Opus 实现时的默认提供者，始终返回 null */
class NoOpCodecProvider : AudioCodecProvider {
    override fun createEncoder(): OpusEncoder? = null
    override fun createDecoder(): OpusDecoder? = null
}
