#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>

#include <opus.h>

#define LOG_TAG "xiaozhi-opus"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Opus 单帧最大包长，RFC 6716 规定的上限
#define MAX_OPUS_PACKET 4000

// 与 Kotlin 侧 com.xiaozhi.android.audio.OpusCodec 中的常量保持一致
#define APP_VOIP 2048
#define APP_AUDIO 2049
#define APP_LOWDELAY 2051

static int to_opus_application(jint application) {
    switch (application) {
        case APP_AUDIO: return OPUS_APPLICATION_AUDIO;
        case APP_LOWDELAY: return OPUS_APPLICATION_RESTRICTED_LOWDELAY;
        case APP_VOIP:
        default: return OPUS_APPLICATION_VOIP;
    }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_xiaozhi_android_audio_OpusCodec_nativeCreateEncoder(
        JNIEnv *env, jobject /*thiz*/, jint sampleRate, jint channels, jint application) {
    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(sampleRate, channels,
                                               to_opus_application(application), &error);
    if (error != OPUS_OK || encoder == nullptr) {
        LOGE("opus_encoder_create failed: %s", opus_strerror(error));
        return 0;
    }
    // 语音场景：启用 DTX 可显著降低静音期码率，服务端侧无感知
    opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(OPUS_AUTO));
    return reinterpret_cast<jlong>(encoder);
}

JNIEXPORT void JNICALL
Java_com_xiaozhi_android_audio_OpusCodec_nativeDestroyEncoder(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    OpusEncoder *encoder = reinterpret_cast<OpusEncoder *>(handle);
    opus_encoder_destroy(encoder);
}

JNIEXPORT jint JNICALL
Java_com_xiaozhi_android_audio_OpusCodec_nativeEncode(
        JNIEnv *env, jobject /*thiz*/, jlong handle,
        jfloatArray pcm, jint frameSize, jbyteArray out) {
    if (handle == 0) return -1;

    OpusEncoder *encoder = reinterpret_cast<OpusEncoder *>(handle);

    jsize pcmLen = env->GetArrayLength(pcm);
    if (pcmLen < frameSize) return -2;

    jsize outCap = env->GetArrayLength(out);
    if (outCap < MAX_OPUS_PACKET) return -3;

    jfloat *pcmBuf = env->GetFloatArrayElements(pcm, nullptr);
    if (pcmBuf == nullptr) return -4;

    unsigned char packet[MAX_OPUS_PACKET];
    opus_int32 encoded = opus_encode_float(encoder, pcmBuf, frameSize,
                                           packet, MAX_OPUS_PACKET);

    env->ReleaseFloatArrayElements(pcm, pcmBuf, JNI_ABORT);

    if (encoded < 0) {
        LOGE("opus_encode_float failed: %s", opus_strerror(encoded));
        return encoded;
    }

    env->SetByteArrayRegion(out, 0, encoded, reinterpret_cast<const jbyte *>(packet));
    return encoded;
}

JNIEXPORT jlong JNICALL
Java_com_xiaozhi_android_audio_OpusCodec_nativeCreateDecoder(
        JNIEnv *env, jobject /*thiz*/, jint sampleRate, jint channels) {
    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(sampleRate, channels, &error);
    if (error != OPUS_OK || decoder == nullptr) {
        LOGE("opus_decoder_create failed: %s", opus_strerror(error));
        return 0;
    }
    return reinterpret_cast<jlong>(decoder);
}

JNIEXPORT void JNICALL
Java_com_xiaozhi_android_audio_OpusCodec_nativeDestroyDecoder(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    OpusDecoder *decoder = reinterpret_cast<OpusDecoder *>(handle);
    opus_decoder_destroy(decoder);
}

JNIEXPORT jint JNICALL
Java_com_xiaozhi_android_audio_OpusCodec_nativeDecode(
        JNIEnv *env, jobject /*thiz*/, jlong handle,
        jbyteArray data, jint dataLen, jfloatArray out, jint frameSize, jboolean decodeFec) {
    if (handle == 0) return -1;

    OpusDecoder *decoder = reinterpret_cast<OpusDecoder *>(handle);

    jsize outCap = env->GetArrayLength(out);
    jint channels = 1;
    opus_decoder_ctl(decoder, OPUS_GET_NB_CHANNELS(&channels));
    if (outCap < frameSize * channels) return -2;

    jbyte *dataBuf = env->GetByteArrayElements(data, nullptr);
    if (dataBuf == nullptr) return -3;
    jfloat *outBuf = env->GetFloatArrayElements(out, nullptr);
    if (outBuf == nullptr) {
        env->ReleaseByteArrayElements(data, dataBuf, JNI_ABORT);
        return -4;
    }

    int decoded = opus_decode_float(decoder,
                                    reinterpret_cast<const unsigned char *>(dataBuf),
                                    dataLen, outBuf, frameSize,
                                    decodeFec == JNI_TRUE ? 1 : 0);

    env->ReleaseFloatArrayElements(out, outBuf, 0);
    env->ReleaseByteArrayElements(data, dataBuf, JNI_ABORT);

    if (decoded < 0) {
        LOGE("opus_decode_float failed: %s", opus_strerror(decoded));
        return decoded;
    }
    return decoded;
}

} // extern "C"
