/*****************************************************************************
 * ijksdl_codec_android_mediacodec_java.c
 *****************************************************************************
 *
 * copyright (c) 2014 Zhang Rui <bbcallen@gmail.com>
 *
 * This file is part of ijkPlayer.
 *
 * ijkPlayer is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * ijkPlayer is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with ijkPlayer; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

#include "ijksdl_codec_android_mediacodec_java.h"
#include "ijksdl_android_jni.h"
#include "ijksdl_codec_android_mediacodec_internal.h"
#include "ijksdl_codec_android_mediaformat_java.h"
#include "ijksdl_inc_internal_android.h"

typedef struct SDL_AMediaCodec_Opaque {
    jobject android_media_codec;

    jobjectArray    input_buffer_array;
    jobjectArray    output_buffer_array;
    jobject         output_buffer_info;
} SDL_AMediaCodec_Opaque;

typedef struct SDL_AMediaCodec_fields_t {
    jclass clazz;

    jmethodID jmid_createDecoderByType;
    jmethodID jmid_configure;
    jmethodID jmid_dequeueInputBuffer;
    jmethodID jmid_dequeueOutputBuffer;
    jmethodID jmid_flush;
    jmethodID jmid_getInputBuffers;
    jmethodID jmid_getOutputBuffers;
    jmethodID jmid_queueInputBuffer;
    jmethodID jmid_release;
    jmethodID jmid_releaseOutputBuffer;
    jmethodID jmid_reset;
    jmethodID jmid_start;
    jmethodID jmid_stop;

    // >= API-18
    jmethodID jmid_getCodecInfo;
    jmethodID jmid_getName;
} SDL_AMediaCodec_fields_t;
static SDL_AMediaCodec_fields_t g_clazz;

typedef struct SDL_AMediaCodec_BufferInfo_fields_t {
    jclass clazz;

    jmethodID jmid__ctor;

    jfieldID jfid_flags;
    jfieldID jfid_offset;
    jfieldID jfid_presentationTimeUs;
    jfieldID jfid_size;
} SDL_AMediaCodec_BufferInfo_fields_t;
static SDL_AMediaCodec_BufferInfo_fields_t g_clazz_BufferInfo;

int SDL_AMediaCodecJava__loadClass(JNIEnv *env)
{
    jint sdk_int = SDL_Android_GetApiLevel();
    if (sdk_int < IJK_API_16_JELLY_BEAN) {
        return 0;
    }

    //--------------------
    IJK_FIND_JAVA_CLASS( env, g_clazz.clazz, "android/media/MediaCodec");

    IJK_FIND_JAVA_STATIC_METHOD(env, g_clazz.jmid_createDecoderByType,  g_clazz.clazz,
        "createDecoderByType",  "(Ljava/lang/String;)Landroid/media/MediaCodec;");

    IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_configure,            g_clazz.clazz,
        "configure",            "(Landroid/media/MediaFormat;Landroid/view/Surface;Landroid/media/MediaCrypto;I)V");
    IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_dequeueInputBuffer,   g_clazz.clazz,
        "dequeueInputBuffer",   "(J)I");
    IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_dequeueOutputBuffer,  g_clazz.clazz,
        "dequeueOutputBuffer",  "(Landroid/media/MediaCodec$BufferInfo;J)I");
    IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_flush,                g_clazz.clazz,
        "flush",                "()V");
    IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_getInputBuffers,      g_clazz.clazz,
        "getInputBuffers",      "()[Ljava/nio/ByteBuffer;");
    IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_getOutputBuffers,     g_clazz.clazz,
        "getOutputBuffers",     "()[Ljava/nio/ByteBuffer;");
    IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_queueInputBuffer,     g_clazz.clazz,
        "queueInputBuffer",     "(IIIJI)V");
    IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_release,              g_clazz.clazz,
        "release",              "()V");
    IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_releaseOutputBuffer,  g_clazz.clazz,
        "releaseOutputBuffer",  "(IZ)V");
    IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_start,                g_clazz.clazz,
        "start",                "()V");
    IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_stop,                 g_clazz.clazz,
        "stop",                 "()V");

    if (sdk_int >= IJK_API_18_JELLY_BEAN_MR2) {
        IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_getCodecInfo,         g_clazz.clazz,
            "getCodecInfo",         "(I)Landroid/media/MediaCodecInfo;");
        IJK_FIND_JAVA_METHOD(env, g_clazz.jmid_getName,              g_clazz.clazz,
            "getName",              "()Ljava/lang/String;");
    }


    //--------------------
    IJK_FIND_JAVA_CLASS( env, g_clazz_BufferInfo.clazz, "android/media/MediaCodec$BufferInfo");

    IJK_FIND_JAVA_METHOD(env, g_clazz_BufferInfo.jmid__ctor,                g_clazz_BufferInfo.clazz,
        "<init>"    ,           "()V");

    IJK_FIND_JAVA_FIELD(env, g_clazz_BufferInfo.jfid_flags,                 g_clazz_BufferInfo.clazz,
        "flags",                "I");
    IJK_FIND_JAVA_FIELD(env, g_clazz_BufferInfo.jfid_offset,                g_clazz_BufferInfo.clazz,
        "offset",               "I");
    IJK_FIND_JAVA_FIELD(env, g_clazz_BufferInfo.jfid_presentationTimeUs,    g_clazz_BufferInfo.clazz,
        "presentationTimeUs",   "J");
    IJK_FIND_JAVA_FIELD(env, g_clazz_BufferInfo.jfid_size,                  g_clazz_BufferInfo.clazz,
        "size",                 "I");
    SDLTRACE("android.media.MediaCodec$BufferInfo class loaded");


    SDLTRACE("android.media.MediaCodec class loaded");
    return 0;
}

jobject SDL_AMediaCodecJava_getObject(JNIEnv *env, const SDL_AMediaCodec *thiz)
{
    if (!thiz || !thiz->opaque)
        return NULL;

    SDL_AMediaCodec_Opaque *opaque = (SDL_AMediaCodec_Opaque *)thiz->opaque;
    return opaque->android_media_codec;
}

static sdl_amedia_status_t SDL_AMediaCodecJava_delete(SDL_AMediaCodec* acodec)
{
    if (!acodec)
        return SDL_AMEDIA_OK;

    JNIEnv *env = NULL;
    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("SDL_AMediaCodecJava_delete: SetupThreadEnv failed");
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    SDL_AMediaCodec_Opaque *opaque = (SDL_AMediaCodec_Opaque *)acodec->opaque;
    if (opaque) {
        SDL_JNI_DeleteGlobalRefP(env, &opaque->output_buffer_info);
        SDL_JNI_DeleteGlobalRefP(env, &opaque->output_buffer_array);
        SDL_JNI_DeleteGlobalRefP(env, &opaque->input_buffer_array);
        SDL_JNI_DeleteGlobalRefP(env, &opaque->android_media_codec);
    }

    SDL_AMediaCodec_FreeInternal(acodec);
    return SDL_AMEDIA_OK;
}

static sdl_amedia_status_t SDL_AMediaCodecJava_configure_surface(
    JNIEnv*env,
    SDL_AMediaCodec* acodec,
    const SDL_AMediaFormat* aformat,
    jobject android_surface,
    SDL_AMediaCrypto *crypto,
    uint32_t flags)
{
    SDLTRACE("SDL_AMediaCodecJava_configure");

    // TODO: implement SDL_AMediaCrypto
    jobject android_media_format = SDL_AMediaFormatJava_getObject(env, aformat);
    jobject android_media_codec  = SDL_AMediaCodecJava_getObject(env, acodec);
    (*env)->CallVoidMethod(env, android_media_codec, g_clazz.jmid_configure, android_media_format, android_surface, crypto, flags);
    if (SDL_JNI_CatchException(env)) {
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    return SDL_AMEDIA_OK;
}

static sdl_amedia_status_t SDL_AMediaCodecJava_start(SDL_AMediaCodec* acodec)
{
    SDLTRACE("SDL_AMediaCodecJava_start");

    JNIEnv *env = NULL;
    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("SDL_AMediaCodecJava_start: SetupThreadEnv failed");
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    jobject android_media_codec = SDL_AMediaCodecJava_getObject(env, acodec);
    (*env)->CallVoidMethod(env, android_media_codec, g_clazz.jmid_start, android_media_codec);
    if (SDL_JNI_CatchException(env)) {
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    return SDL_AMEDIA_OK;
}

static sdl_amedia_status_t SDL_AMediaCodecJava_stop(SDL_AMediaCodec* acodec)
{
    SDLTRACE("SDL_AMediaCodecJava_stop");

    JNIEnv *env = NULL;
    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("SDL_AMediaCodecJava_stop: SetupThreadEnv failed");
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    jobject android_media_codec = SDL_AMediaCodecJava_getObject(env, acodec);
    (*env)->CallVoidMethod(env, android_media_codec, g_clazz.jmid_stop, android_media_codec);
    if (SDL_JNI_CatchException(env)) {
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    return SDL_AMEDIA_OK;
}

static sdl_amedia_status_t SDL_AMediaCodecJava_flush(SDL_AMediaCodec* acodec)
{
    SDLTRACE("SDL_AMediaCodecJava_flush");

    JNIEnv *env = NULL;
    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("SDL_AMediaCodecJava_flush: SetupThreadEnv failed");
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    jobject android_media_codec = SDL_AMediaCodecJava_getObject(env, acodec);
    (*env)->CallVoidMethod(env, android_media_codec, g_clazz.jmid_flush, android_media_codec);
    if (SDL_JNI_CatchException(env)) {
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    return SDL_AMEDIA_OK;
}

static uint8_t* SDL_AMediaCodecJava_getInputBuffer(SDL_AMediaCodec* acodec, size_t idx, size_t *out_size)
{
    SDLTRACE("SDL_AMediaCodecJava_getInputBuffer");

    JNIEnv *env = NULL;
    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("SDL_AMediaCodecJava_getInputBuffer: SetupThreadEnv failed");
        return NULL;
    }

    SDL_AMediaCodec_Opaque *opaque = (SDL_AMediaCodec_Opaque *)acodec->opaque;
    jobject android_media_codec = opaque->android_media_codec;
    jobjectArray local_input_buffer_array = (*env)->CallObjectMethod(env, android_media_codec, g_clazz.jmid_getInputBuffers);
    if (SDL_JNI_CatchException(env) || !local_input_buffer_array) {
        return NULL;
    }

    jobject global_input_buffer_array = (*env)->NewGlobalRef(env, local_input_buffer_array);
    SDL_JNI_DeleteLocalRefP(env, &local_input_buffer_array);
    if (SDL_JNI_CatchException(env) || !global_input_buffer_array) {
        return NULL;
    }
    opaque->input_buffer_array = global_input_buffer_array;

    int buffer_count = (*env)->GetArrayLength(env, global_input_buffer_array);
    if (SDL_JNI_CatchException(env) || idx < 0 || idx >= buffer_count) {
        return NULL;
    }

    jobject bytebuf = (*env)->GetObjectArrayElement(env, global_input_buffer_array, idx);
    if (SDL_JNI_CatchException(env) || !bytebuf) {
        return NULL;
    }

    jlong size = (*env)->GetDirectBufferCapacity(env, bytebuf);
    void *ptr  = (*env)->GetDirectBufferAddress(env, bytebuf);

    // bytebuf is also referenced by opaque->input_buffer_array
    SDL_JNI_DeleteLocalRefP(env, &bytebuf);

    if (out_size)
        *out_size = size;
    return ptr;
}

static uint8_t* SDL_AMediaCodecJava_getOutputBuffer(SDL_AMediaCodec* acodec, size_t idx, size_t *out_size)
{
    SDLTRACE("SDL_AMediaCodecJava_getOutputBuffer");

    JNIEnv *env = NULL;
    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("SDL_AMediaCodecJava_getOutputBuffer: SetupThreadEnv failed");
        return NULL;
    }

    SDL_AMediaCodec_Opaque *opaque = (SDL_AMediaCodec_Opaque *)acodec->opaque;
    jobject android_media_codec = opaque->android_media_codec;
    jobjectArray local_output_buffer_array = (*env)->CallObjectMethod(env, android_media_codec, g_clazz.jmid_getOutputBuffers);
    if (SDL_JNI_CatchException(env) || !local_output_buffer_array) {
        return NULL;
    }

    jobject global_output_buffer_array = (*env)->NewGlobalRef(env, local_output_buffer_array);
    SDL_JNI_DeleteLocalRefP(env, &local_output_buffer_array);
    if (SDL_JNI_CatchException(env) || !global_output_buffer_array) {
        return NULL;
    }
    opaque->output_buffer_array = global_output_buffer_array;

    int buffer_count = (*env)->GetArrayLength(env, global_output_buffer_array);
    if (SDL_JNI_CatchException(env) || idx < 0 || idx >= buffer_count) {
        return NULL;
    }

    jobject bytebuf = (*env)->GetObjectArrayElement(env, global_output_buffer_array, idx);
    if (SDL_JNI_CatchException(env) || !bytebuf) {
        return NULL;
    }

    jlong size = (*env)->GetDirectBufferCapacity(env, bytebuf);
    void *ptr  = (*env)->GetDirectBufferAddress(env, bytebuf);

    // bytebuf is also referenced by opaque->input_buffer_array
    SDL_JNI_DeleteLocalRefP(env, &bytebuf);

    if (out_size)
        *out_size = size;
    return ptr;
}

ssize_t SDL_AMediaCodecJava_dequeueInputBuffer(SDL_AMediaCodec* acodec, int64_t timeoutUs)
{
    SDLTRACE("SDL_AMediaCodecJava_dequeueInputBuffer");

    JNIEnv *env = NULL;
    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("SDL_AMediaCodecJava_dequeueInputBuffer: SetupThreadEnv failed");
        return -1;
    }

    SDL_AMediaCodec_Opaque *opaque = (SDL_AMediaCodec_Opaque *)acodec->opaque;
    jobject android_media_codec = opaque->android_media_codec;
    jint idx = (*env)->CallIntMethod(env, android_media_codec, g_clazz.jmid_dequeueInputBuffer, (jlong)timeoutUs);
    if (SDL_JNI_CatchException(env)) {
        return -1;
    }

    return idx;
}

sdl_amedia_status_t SDL_AMediaCodecJava_queueInputBuffer(SDL_AMediaCodec* acodec, size_t idx, off_t offset, size_t size, uint64_t time, uint32_t flags)
{
    SDLTRACE("SDL_AMediaCodecJava_queueInputBuffer");

    JNIEnv *env = NULL;
    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("SDL_AMediaCodecJava_queueInputBuffer: SetupThreadEnv failed");
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    SDL_AMediaCodec_Opaque *opaque = (SDL_AMediaCodec_Opaque *)acodec->opaque;
    jobject android_media_codec = opaque->android_media_codec;
    (*env)->CallVoidMethod(env, android_media_codec, g_clazz.jmid_queueInputBuffer, (jint)idx, (jint)offset, (jint)size, (jlong)time, (jint)flags);
    if (SDL_JNI_CatchException(env)) {
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    return SDL_AMEDIA_OK;
}

ssize_t SDL_AMediaCodecJava_dequeueOutputBuffer(SDL_AMediaCodec* acodec, SDL_AMediaCodecBufferInfo *info, int64_t timeoutUs)
{
    SDLTRACE("SDL_AMediaCodec_dequeueOutputBuffer");

    JNIEnv *env = NULL;
    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("SDL_AMediaCodec_dequeueOutputBuffer: SetupThreadEnv failed");
        return -1;
    }

    SDL_AMediaCodec_Opaque *opaque = (SDL_AMediaCodec_Opaque *)acodec->opaque;
    jobject android_media_codec = opaque->android_media_codec;
    jobject output_buffer_info  = opaque->output_buffer_info;
    if (!output_buffer_info) {
        output_buffer_info = SDL_JNI_NewObjectAsGlobalRef(env, g_clazz_BufferInfo.clazz, g_clazz_BufferInfo.jmid__ctor);
        if (SDL_JNI_CatchException(env) || !output_buffer_info) {
            return -1;
        }
        opaque->output_buffer_info = output_buffer_info;
    }

    jint idx = (*env)->CallIntMethod(env, android_media_codec, g_clazz.jmid_dequeueOutputBuffer, output_buffer_info, (jlong)timeoutUs);
    if (SDL_JNI_CatchException(env)) {
        return -1;
    }

    if (info) {
        info->offset              = (*env)->GetIntField(env, output_buffer_info, g_clazz_BufferInfo.jfid_offset);
        info->size                = (*env)->GetIntField(env, output_buffer_info, g_clazz_BufferInfo.jfid_size);
        info->presentationTimeUs  = (*env)->GetLongField(env, output_buffer_info, g_clazz_BufferInfo.jfid_presentationTimeUs);
        info->flags               = (*env)->GetIntField(env, output_buffer_info, g_clazz_BufferInfo.jfid_flags);
    }

    return idx;
}

sdl_amedia_status_t SDL_AMediaCodecJava_releaseOutputBuffer(SDL_AMediaCodec* acodec, size_t idx, bool render)
{
    SDLTRACE("SDL_AMediaCodecJava_releaseOutputBuffer");

    JNIEnv *env = NULL;
    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("SDL_AMediaCodecJava_releaseOutputBuffer: SetupThreadEnv failed");
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    SDL_AMediaCodec_Opaque *opaque = (SDL_AMediaCodec_Opaque *)acodec->opaque;
    jobject android_media_codec = opaque->android_media_codec;
    (*env)->CallVoidMethod(env, android_media_codec, g_clazz.jmid_releaseOutputBuffer, (jint)idx, (jboolean)render);
    if (SDL_JNI_CatchException(env)) {
        return SDL_AMEDIA_ERROR_UNKNOWN;
    }

    return SDL_AMEDIA_OK;
}

SDL_AMediaCodec* SDL_AMediaCodecJava_createDecoderByType(JNIEnv *env, const char *mime_type)
{
    SDLTRACE("SDL_AMediaCodecJava_createDecoderByType");

    jstring jmime_type = (*env)->NewStringUTF(env, mime_type);
    if (SDL_JNI_CatchException(env) || !jmime_type) {
        return NULL;
    }

    jobject local_android_media_codec = (*env)->CallStaticObjectMethod(env, g_clazz.clazz, g_clazz.jmid_createDecoderByType, jmime_type);
    SDL_JNI_DeleteLocalRefP(env, &jmime_type);
    if (SDL_JNI_CatchException(env) || !local_android_media_codec) {
        return NULL;
    }

    jobject global_android_media_codec = (*env)->NewGlobalRef(env, local_android_media_codec);
    SDL_JNI_DeleteLocalRefP(env, &local_android_media_codec);
    if (SDL_JNI_CatchException(env) || !global_android_media_codec) {
        return NULL;
    }

    SDL_AMediaCodec *acodec = SDL_AMediaCodec_CreateInternal(sizeof(SDL_AMediaCodec_Opaque));
    if (!acodec) {
        SDL_JNI_DeleteGlobalRefP(env, &global_android_media_codec);
        return NULL;
    }

    SDL_AMediaCodec_Opaque *opaque = acodec->opaque;
    opaque->android_media_codec         = global_android_media_codec;

    acodec->func_delete                 = SDL_AMediaCodecJava_delete;
    acodec->func_configure              = NULL;
    acodec->func_configure_surface      = SDL_AMediaCodecJava_configure_surface;

    acodec->func_start                  = SDL_AMediaCodecJava_start;
    acodec->func_stop                   = SDL_AMediaCodecJava_stop;
    acodec->func_flush                  = SDL_AMediaCodecJava_flush;

    acodec->func_getInputBuffer         = SDL_AMediaCodecJava_getInputBuffer;
    acodec->func_getOutputBuffer        = SDL_AMediaCodecJava_getOutputBuffer;

    acodec->func_dequeueInputBuffer     = SDL_AMediaCodecJava_dequeueInputBuffer;
    acodec->func_queueInputBuffer       = SDL_AMediaCodecJava_queueInputBuffer;

    acodec->func_dequeueOutputBuffer    = SDL_AMediaCodecJava_dequeueOutputBuffer;
    acodec->func_getOutputFormat        = NULL;

    acodec->func_releaseOutputBuffer    = SDL_AMediaCodecJava_releaseOutputBuffer;

    return acodec;
}
