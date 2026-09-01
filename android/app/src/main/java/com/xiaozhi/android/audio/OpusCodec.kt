package com.xiaozhi.android.audio

/**
 * Opus 编解码器，JNI 封装 libopus。
 *
 * 上下行采样率不同，编码器与解码器是两个独立实例：
 *   编码：16000 Hz 单声道（协议固定）
 *   解码：24000 Hz 单声道（服务端协商，见服务端 hello 的 audio_params）
 */
class OpusCodec(
    private val inputSampleRate: Int = 16000,
    private val outputSampleRate: Int = 24000,
    private val channels: Int = 1,
) {
    companion object {
        /** RFC 6716 规定的单帧最大包长 */
        private const val MAX_PACKET = 4000

        // 与 opus_jni.cpp 中的值保持一致
        const val APP_VOIP = 2048
        const val APP_AUDIO = 2049
        const val APP_LOWDELAY = 2051

        init {
            System.loadLibrary("xiaozhi-opus")
        }
    }

    private var encoderHandle = 0L
    private var decoderHandle = 0L
    private var closed = false

    /** 复用的编码输出缓冲，因此 encode() 加锁 */
    private val encodeBuffer = ByteArray(MAX_PACKET)
    private val encodeLock = Any()

    fun initialize() {
        encoderHandle = nativeCreateEncoder(inputSampleRate, channels, APP_VOIP)
        check(encoderHandle != 0L) { "Opus 编码器创建失败" }

        decoderHandle = nativeCreateDecoder(outputSampleRate, channels)
        check(decoderHandle != 0L) { "Opus 解码器创建失败" }
    }

    /**
     * float32 PCM → Opus。
     *
     * @param pcm 采样值范围 [-1.0, 1.0]
     * @param frameSize 本帧样本数，非字节数
     */
    fun encode(pcm: FloatArray, frameSize: Int): ByteArray = synchronized(encodeLock) {
        check(!closed) { "OpusCodec 已关闭" }
        val n = nativeEncode(encoderHandle, pcm, frameSize, encodeBuffer)
        check(n >= 0) { "Opus 编码失败，错误码 $n" }
        return encodeBuffer.copyOf(n)
    }

    /**
     * Opus → float32 PCM。
     *
     * @param frameSize 期望解码出的**每声道**样本数
     */
    fun decode(data: ByteArray, frameSize: Int, decodeFec: Boolean = false): FloatArray {
        check(!closed) { "OpusCodec 已关闭" }
        val out = FloatArray(frameSize * channels)
        val n = nativeDecode(decoderHandle, data, data.size, out, frameSize, decodeFec)
        check(n >= 0) { "Opus 解码失败，错误码 $n" }
        return if (n == out.size) out else out.copyOf(n)
    }

    /** 幂等释放，可重复调用 */
    fun close() {
        if (closed) return
        closed = true
        if (encoderHandle != 0L) {
            nativeDestroyEncoder(encoderHandle)
            encoderHandle = 0L
        }
        if (decoderHandle != 0L) {
            nativeDestroyDecoder(decoderHandle)
            decoderHandle = 0L
        }
    }

    private external fun nativeCreateEncoder(sampleRate: Int, channels: Int, application: Int): Long
    private external fun nativeDestroyEncoder(handle: Long)
    private external fun nativeEncode(handle: Long, pcm: FloatArray, frameSize: Int, out: ByteArray): Int

    private external fun nativeCreateDecoder(sampleRate: Int, channels: Int): Long
    private external fun nativeDestroyDecoder(handle: Long)
    private external fun nativeDecode(
        handle: Long,
        data: ByteArray,
        dataLen: Int,
        out: FloatArray,
        frameSize: Int,
        decodeFec: Boolean,
    ): Int
}
