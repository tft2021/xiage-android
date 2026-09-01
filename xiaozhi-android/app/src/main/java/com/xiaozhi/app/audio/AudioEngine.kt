package com.xiaozhi.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.xiaozhi.protocol.audio.AudioIO
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
    }

    override var onPcmFrame: ((ShortArray) -> Unit)? = null

    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    override var isCapturing: Boolean = false
        private set

    /** 启动麦克风采集，持续产出固定帧长的 PCM */
    @SuppressLint("MissingPermission")
    override fun startCapture() {
        if (isCapturing) return
        val minBuf = AudioRecord.getMinBufferSize(
            UPLINK_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // VOICE_COMMUNICATION 让系统 AEC / AGC 生效
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            UPLINK_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_SIZE * 2 * 4),
        ).also { it.startRecording() }

        isCapturing = true
        recordJob = scope.launch(Dispatchers.IO) {
            val rec = audioRecord ?: return@launch
            val buf = ShortArray(FRAME_SIZE)
            while (isActive && isCapturing) {
                var read = 0
                while (read < FRAME_SIZE && isActive) {
                    val n = rec.read(buf, read, FRAME_SIZE - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == FRAME_SIZE) onPcmFrame?.invoke(buf.copyOf())
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
        audioTrack = AudioTrack.Builder()
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
            .setAudioSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
            .also { it.play() }
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
