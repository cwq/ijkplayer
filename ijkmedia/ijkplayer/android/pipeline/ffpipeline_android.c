/*
 * ffpipeline_android.c
 *
 * Copyright (c) 2014 Zhang Rui <bbcallen@gmail.com>
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

#include "ffpipeline_android.h"
#include <jni.h>
#include "ffpipenode_android_mediacodec_vdec.h"
#include "ffpipenode_android_mediacodec_vout.h"
#include "../../ff_ffplay.h"
#include "ijksdl/android/ijksdl_android_jni.h"

 typedef struct IJKFF_Pipeline_Opaque {
    FFPlayer      *ffp;
    SDL_mutex     *surface_mutex;
    jobject        jsurface;
    volatile int   surface_need_reconfigure;
} IJKFF_Pipeline_Opaque;

static void func_destroy(IJKFF_Pipeline *pipeline)
{
    IJKFF_Pipeline_Opaque *opaque = pipeline->opaque;
    JNIEnv *env = NULL;

    SDL_DestroyMutexP(&opaque->surface_mutex);

    if (JNI_OK != SDL_JNI_SetupThreadEnv(&env)) {
        ALOGE("amediacodec-pipeline:destroy: SetupThreadEnv failed\n");
        goto fail;
    }

    SDL_JNI_DeleteGlobalRefP(env, &opaque->jsurface);
fail:
    return;
}

static IJKFF_Pipenode *func_open_video_decoder(IJKFF_Pipeline *pipeline, FFPlayer *ffp)
{
    return ffpipenode_create_video_decoder_from_android_mediacodec(ffp);
}

static IJKFF_Pipenode *func_open_video_output(IJKFF_Pipeline *pipeline, FFPlayer *ffp)
{
    return ffpipenode_create_video_output_from_android_mediacodec(ffp);
}

IJKFF_Pipeline *ffpipeline_create_from_android(FFPlayer *ffp)
{
    ALOGD("ffpipeline_create_from_android()\n");
    IJKFF_Pipeline *pipeline = ffpipeline_alloc(sizeof(IJKFF_Pipeline_Opaque));
    if (!pipeline)
        return pipeline;

    IJKFF_Pipeline_Opaque *opaque = pipeline->opaque;
    opaque->ffp                   = ffp;
    opaque->surface_mutex         = SDL_CreateMutex();
    if (!opaque->surface_mutex) {
        ALOGE("ffpipeline-android:create SDL_CreateMutex failed\n");
        goto fail;
    }

    pipeline->func_destroy            = func_destroy;
    pipeline->func_open_video_decoder = func_open_video_decoder;
    pipeline->func_open_video_output  = func_open_video_output;

    return pipeline;
fail:
    ffpipeline_free_p(&pipeline);
    return NULL;
}

jobject ffpipeline_get_surface_as_local_ref(JNIEnv *env, IJKFF_Pipeline* pipeline)
{
    IJKFF_Pipeline_Opaque *opaque = pipeline->opaque;
    if (!opaque->surface_mutex)
        return NULL;

    jobject local_ref = NULL;
    SDL_LockMutex(opaque->surface_mutex);
    {
        if (opaque->jsurface)
            local_ref = (*env)->NewLocalRef(env, opaque->jsurface);
    }
    SDL_UnlockMutex(opaque->surface_mutex);

    return local_ref;
}

int ffpipeline_set_surface(JNIEnv *env, IJKFF_Pipeline* pipeline, jobject surface)
{
    ALOGD("ffpipeline_set_surface()\n");
    IJKFF_Pipeline_Opaque *opaque = pipeline->opaque;
    if (!opaque->surface_mutex)
        return -1;

    SDL_LockMutex(opaque->surface_mutex);
    {
        jobject prev_surface = opaque->jsurface;

        opaque->jsurface = (*env)->NewGlobalRef(env, surface);
        opaque->surface_need_reconfigure = 1;

        SDL_JNI_DeleteGlobalRefP(env, &prev_surface);
    }
    SDL_UnlockMutex(opaque->surface_mutex);

    return 0;
}
