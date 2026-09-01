package com.xiaozhi.android.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.xiaozhi.android.protocol.AudioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 音频管线：采集 → Opus 编码 → 上行；下行 → Opus 解码 → 播放。
 *
 * 上下行采样率不同（16 kHz / 24 kHz），两端各自独立，不要共用缓冲。
 */
class AudioPipeline(
    private val scope: CoroutineScope,
    private val onOpusFrame: (ByteArray) -> Unit,
    private val onLevel: ((rmsDb: Float) -> Unit)? = null,
) {
    private val codec = OpusCodec(
        inputSampleRate = AudioConfig.INPUT_SAMPLE_RATE,
        outputSampleRate = AudioConfig.outputSampleRate,
        channels = AudioConfig.CHANNELS,
    )

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var captureJob: Job? = null

    @Volatile
    private var capturing = false

    val isCapturing: Boolean get() = capturing

    /** 是否启用回声消除。关掉时打断能力会退化 */
    var aecEnabled: Boolean = true

    fun prepare() {
        codec.initialize()
    }

    // ---------------------------------------------------------------- 采集

    fun startCapture() {
        if (capturing) return
        val frameSize = AudioConfig.inputFrameSize
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioConfig.INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferSize = maxOf(minBuffer * 2, frameSize * BYTES_PER_FLOAT * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // 该音源会自动挂系统 AEC/NS
            AudioConfig.INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferSize,
        )
        recorder = record

        if (aecEnabled && AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
        }

        record.startRecording()
        capturing = true

        captureJob = scope.launch(Dispatchers.IO) {
            val frame = FloatArray(frameSize)
            while (isActive && capturing) {
                val read = record.read(frame, 0, frameSize, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                onLevel?.let { it(rmsDb(frame, read)) }
                onOpusFrame(codec.encode(frame, read))
            }
        }
    }

    fun stopCapture() {
        capturing = false
        captureJob?.cancel()
        captureJob = null
        try {
            recorder?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        } catch (e: IllegalStateException) {
            // 已停止或已释放，忽略
        }
        recorder = null
        runCatching { aec?.release() }
        runCatching { ns?.release() }
        aec = null
        ns = null
    }

    // ---------------------------------------------------------------- 播放

    fun preparePlayback() {
        releaseTrack()
        val bufferSize = AudioTrack.getMinBufferSize(
            AudioConfig.outputSampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(AudioConfig.outputSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize * 4, AudioConfig.outputFrameSize * BYTES_PER_FLOAT * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    /** 解码一帧 Opus 并写入播放队列 */
    fun play(opus: ByteArray) {
        val t = track ?: return
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return
        val pcm = runCatching { codec.decode(opus, AudioConfig.outputFrameSize) }.getOrNull() ?: return
        t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    }

    /** 打断：丢弃尚未播完的音频 */
    fun flushPlayback() {
        track?.apply {
            if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                pause()
                flush()
                play()
            }
        }
    }

    fun pausePlayback() {
        runCatching { track?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING }?.pause() }
    }

    fun resumePlayback() {
        runCatching { track?.takeIf { it.playState != AudioTrack.PLAYSTATE_PLAYING }?.play() }
    }

    private fun releaseTrack() {
        runCatching {
            track?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                release()
            }
        }
        track = null
    }

    // ---------------------------------------------------------------- 生命周期

    fun release() {
        stopCapture()
        releaseTrack()
        codec.close()
    }

    /** 请求音频焦点，避免和音乐播放器打架 */
    fun requestAudioFocus(manager: AudioManager, listener: AudioManager.OnAudioFocusChangeListener): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.requestAudioFocus(
                android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(listener)
                    .build()
            )
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    companion object {
        private const val BYTES_PER_FLOAT = 4

        private fun rmsDb(frame: FloatArray, length: Int): Float {
            var sum = 0.0
            for (i in 0 until length) sum += frame[i].toDouble() * frame[i]
            val rms = sqrt(sum / length)
            return if (rms < 1e-6f) -120f else (20.0 * kotlin.math.log10(rms)).toFloat().coerceIn(-120f, 0f)
        }

        /** 简易能量检测，用于打断判断。阈值需实测调整 */
        fun isSpeech(frame: FloatArray, threshold: Float = 0.02f): Boolean {
            var peak = 0f
            for (v in frame) {
                val a = abs(v)
                if (a > peak) peak = a
            }
            return peak > threshold
        }
    }
}
