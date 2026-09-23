/*
 * LAME 3.100 JNI bridge for VoiceForge.
 *
 * Exports:
 *   Java_com_voiceforge_app_engine_xtts_Mp3Encoder_encodeNative(
 *       JNIEnv*, jclass, jshortArray pcm, jint sampleRate, jint channels, jint kbps)
 *
 * One-shot buffer encode: lame_init → params → lame_encode_buffer[_interleaved]
 * → lame_encode_flush → complete MP3 byte stream (headers included).
 */
#include <jni.h>
#include <stdlib.h>
#include "lame.h"

JNIEXPORT jbyteArray JNICALL
Java_com_voiceforge_app_engine_xtts_Mp3Encoder_encodeNative(
    JNIEnv *env,
    jclass clazz,
    jshortArray pcm,
    jint sampleRate,
    jint channels,
    jint kbps) {
    (void)clazz;

    if (pcm == NULL || channels < 1 || sampleRate <= 0) {
        return (*env)->NewByteArray(env, 0);
    }
    jsize n = (*env)->GetArrayLength(env, pcm);
    if (n <= 0) {
        return (*env)->NewByteArray(env, 0);
    }
    if (kbps <= 0) {
        kbps = 192;
    }

    jshort *samples = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (samples == NULL) {
        return (*env)->NewByteArray(env, 0);
    }

    lame_t gf = lame_init();
    if (gf == NULL) {
        (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
        return (*env)->NewByteArray(env, 0);
    }
    lame_set_in_samplerate(gf, (int)sampleRate);
    lame_set_num_channels(gf, (int)channels);
    lame_set_brate(gf, (int)kbps);
    lame_set_mode(gf, channels == 1 ? MONO : JOINT_STEREO);
    lame_set_quality(gf, 2);
    if (lame_init_params(gf) < 0) {
        lame_close(gf);
        (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
        return (*env)->NewByteArray(env, 0);
    }

    int nsamples = (int)(n / channels);
    /* LAME docs: 1.25 * num_samples + 7200 is always enough. */
    int bufSize = (int)(1.25 * (double)nsamples) + 7200;
    unsigned char *buf = (unsigned char *)malloc((size_t)bufSize);
    if (buf == NULL) {
        lame_close(gf);
        (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
        return (*env)->NewByteArray(env, 0);
    }

    int written;
    if (channels == 1) {
        written = lame_encode_buffer(gf, samples, samples, nsamples, buf, bufSize);
    } else {
        written = lame_encode_buffer_interleaved(gf, samples, nsamples, buf, bufSize);
    }
    int flushed = 0;
    if (written >= 0) {
        flushed = lame_encode_flush(gf, buf + written, bufSize - written);
        if (flushed < 0) {
            flushed = 0;
            written = written >= 0 ? written : 0;
        }
    } else {
        written = 0;
    }

    int total = written + flushed;
    jbyteArray out = (*env)->NewByteArray(env, total);
    if (out != NULL && total > 0) {
        (*env)->SetByteArrayRegion(env, out, 0, total, (const jbyte *)buf);
    }

    free(buf);
    lame_close(gf);
    (*env)->ReleaseShortArrayElements(env, pcm, samples, JNI_ABORT);
    return out;
}
