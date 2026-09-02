package com.xiaozhi.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.xiaozhi.protocol.audio.AudioIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android 音频引擎，实现 core-protocol 的 AudioIO 接口
 *
 * 采集要点：
 *  - 用 VOICE_COMMUNICATION 音源可让系统 AEC 生效，是免 NDK 做回声消除的关键
 *  - 上行固定 16000 Hz / 单声道 / 16-bit PCM，按帧送 Opus 编码
 *  - 下行 AudioTrack 直接按服务端采样率打开（AudioFlinger 内部会重采样到设备输出）
 */
class AudioEngine(private val scope: CoroutineScope) : AudioIO {

    companion object {
        const val UPLINK_SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val FRAME_DURATION_MS = 60

        /** 每帧采样数：16000 * 60 / 1000 = 960 */
        val FRAME_SIZE = UPLINK_SAMPLE_RATE * FRAME_DURATION_MS / 1000

        private const val TAG = "AudioEngine"
    }

    override var onPcmFrame: ((ShortArray) -> Unit)? = null

    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    override var isCapturing: Boolean = false
        private set

    /** 启动麦克风采集，持续产出固定帧长的 PCM
     *
     * 失败时抛异常（由上层转成 Error 阶段），不静默降级——否则用户会以为在录音其实没有。
     * 循环体内的异常则就地兜住：录音跑在传入的 scope（生产是 viewModelScope）上，
     * 一旦异常逃逸会取消整个 scope，把消息收集、连接状态收集一起带走。
     */
    @SuppressLint("MissingPermission")
    override fun startCapture() {
        if (isCapturing) return
        val minBuf = AudioRecord.getMinBufferSize(
            UPLINK_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 返回 ERROR(-1) / ERROR_BAD_VALUE(-2) 说明设备不支持这组参数
        require(minBuf > 0) { "设备不支持 ${UPLINK_SAMPLE_RATE}Hz 单声道 16bit 采集" }

        // VOICE_COMMUNICATION 让系统 AEC / AGC 生效
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            UPLINK_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_SIZE * 2 * 4),
        )
        // 未授予麦克风权限、或音频通道被占用时，构造不抛异常但状态是 UNINITIALIZED
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("AudioRecord 初始化失败（麦克风权限未授予或被其他应用占用）")
        }
        rec.startRecording()
        audioRecord = rec

        isCapturing = true
        recordJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(FRAME_SIZE)
            try {
                while (isActive && isCapturing) {
                    var read = 0
                    while (read < FRAME_SIZE && isActive) {
                        val n = rec.read(buf, read, FRAME_SIZE - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read == FRAME_SIZE) onPcmFrame?.invoke(buf.copyOf())
                }
            } catch (e: CancellationException) {
                throw e // 必须重抛，否则协程无法正常取消
            } catch (e: Exception) {
                // stopCapture() 会 release 掉 rec，此时阻塞中的 read() 抛异常属正常，
                // 兜住即可，绝不让异常逃到外部 scope
                Log.w(TAG, "录音循环异常退出", e)
            }
        }
    }

    override fun stopCapture() {
        isCapturing = false
        recordJob?.cancel()
        recordJob = null
        audioRecord?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        audioRecord = null
    }

    /** 播放服务端下发的 PCM（已解码），采样率与服务端下行一致 */
    override fun startPlayback(sampleRate: Int) {
        stopPlayback()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 返回 ERROR(-1) / ERROR_BAD_VALUE(-2) 说明设备不支持这组参数，
        // 不校验的话 AudioTrack 会以 UNINITIALIZED 状态建成，play() 时才抛异常
        require(minBuf > 0) { "设备不支持 ${sampleRate}Hz 单声道 16bit 播放" }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate / 5 * 2))
            // AudioTrack.Builder 的方法名是 setSessionId（不是 setAudioSessionId）
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
        // 音频输出通道被占用（或策略拒绝）时不抛异常，只在 state 上体现
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("AudioTrack 初始化失败（音频输出被占用或设备不支持 ${sampleRate}Hz 播放）")
        }
        audioTrack = track
        track.play()
    }

    override fun enqueuePcm(pcm: ShortArray) {
        audioTrack?.write(pcm, 0, pcm.size)
    }

    /** 打断播放（用户说话时清空缓冲） */
    override fun flushPlayback() {
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
    }

    override fun stopPlayback() {
        audioTrack?.run {
            try { stop() } catch (_: Exception) {}
            release()
        }
        audioTrack = null
    }

    override fun releaseAll() {
        stopCapture()
        stopPlayback()
    }
}
